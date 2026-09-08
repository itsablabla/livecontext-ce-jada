package com.apimarketplace.common.credit;

import com.apimarketplace.common.web.GatewayAuthenticationFilter;
import com.apimarketplace.common.web.GatewayFilterProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gateway HMAC must be signed over the identity the request actually
 * carries.
 *
 * <p>Regression cover for the production incident of 2026-08-14: a
 * platform-credential generation reserved its markup and then could not commit
 * it. {@code scope-reserve} passed a user id and was accepted;
 * {@code scope-commit} passed {@code null} and was rejected 401 "Invalid gateway
 * secret", because {@code OrgContextHeaderForwarder} had meanwhile copied
 * {@code X-User-ID} off the inbound request while the signature was computed
 * over an empty user. The reserve stayed open until the sweeper refunded it, so
 * a delivered, provider-billed asset cost the customer nothing.
 *
 * <p>The assertion is deliberately made against the REAL
 * {@link GatewayAuthenticationFilter} rather than against a re-implementation of
 * the HMAC. A test that recomputes the signature the same way the client does
 * agrees with the client by construction and would have passed all through the
 * incident; only the verifier can say whether the request is accepted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditConsumptionClient gateway signature binds the identity it sends")
class CreditConsumptionClientSignedIdentityTest {

    private static final String GATEWAY_SECRET = "test-gateway-secret";
    private static final String INBOUND_USER_ID = "1";
    private static final String INBOUND_ORG_ID = "00000000-0000-0000-0000-000000000000";

    @Mock
    private RestTemplate restTemplate;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Nested
    @DisplayName("called from inside a request that carries a user identity")
    class InsideAnInboundRequest {

        @Test
        @DisplayName("scopeCommit is accepted by the gateway filter")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void scopeCommitIsAcceptedByTheGatewayFilter() {
            CreditConsumptionClient client = signingClient();
            bindInboundRequest();
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(new ResponseEntity(Map.of("outcome", "COMMITTED"), HttpStatus.OK));

            client.scopeCommit("platform-markup:STREAM:ui-1:openai/openai-create-image:c1",
                    new BigDecimal("200"), "OpenAI", "create_image");

            HttpHeaders sent = capturePostHeaders();
            assertThat(sent.getFirst("X-User-ID"))
                    .as("the forwarder carries the inbound user onto the outbound call")
                    .isEqualTo(INBOUND_USER_ID);
            assertThat(verifyThroughGatewayFilter(sent, "/api/credits/markup/scope-commit"))
                    .as("scope-commit must not 401 - an unaccepted commit leaves the reserve to be swept and refunded")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("scopeRelease is accepted by the gateway filter")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void scopeReleaseIsAcceptedByTheGatewayFilter() {
            CreditConsumptionClient client = signingClient();
            bindInboundRequest();
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(new ResponseEntity(Map.of("outcome", "RELEASED"), HttpStatus.OK));

            client.scopeRelease("platform-markup:STREAM:ui-1:openai/openai-create-image:c1", "upstream failed");

            assertThat(verifyThroughGatewayFilter(capturePostHeaders(), "/api/credits/markup/scope-release"))
                    .as("a release that 401s leaves the customer debited until the reserve expires")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("scopeReserve stays accepted - the caller-supplied user matches the header")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void scopeReserveStaysAccepted() {
            CreditConsumptionClient client = signingClient();
            bindInboundRequest();
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(new ResponseEntity(Map.of("success", true), HttpStatus.OK));

            client.scopeReserve(1L, "platform-markup:STREAM:ui-1:openai/openai-create-image:c1",
                    "OpenAI", "create_image", new BigDecimal("200"), 12L, 30,
                    "STREAM", "ui-1", false);

            assertThat(verifyThroughGatewayFilter(capturePostHeaders(), "/api/credits/markup/scope-reserve"))
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("outside any request context, an unauthenticated commit is still accepted")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void scopeCommitOutsideARequestContextIsStillAccepted() {
        CreditConsumptionClient client = signingClient();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity(Map.of("outcome", "COMMITTED"), HttpStatus.OK));

        client.scopeCommit("src-1", BigDecimal.ONE, "openai", "gpt-4.1");

        HttpHeaders sent = capturePostHeaders();
        assertThat(sent.getFirst("X-User-ID"))
                .as("nothing to inherit from - the call genuinely carries no user")
                .isNull();
        assertThat(verifyThroughGatewayFilter(sent, "/api/credits/markup/scope-commit"))
                .as("the empty-identity signature must keep working for daemon and scheduled callers")
                .isEqualTo(200);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private CreditConsumptionClient signingClient() {
        CreditConsumptionClient client =
                new CreditConsumptionClient("http://auth:8083", true, GATEWAY_SECRET);
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
        return client;
    }

    /**
     * Stand in for the servlet request the caller is already serving. Both
     * headers matter: the org id was always signed from the outbound headers,
     * the user id is the one the incident proved was not.
     */
    private void bindInboundRequest() {
        MockHttpServletRequest inbound = new MockHttpServletRequest();
        inbound.addHeader("X-User-ID", INBOUND_USER_ID);
        inbound.addHeader("X-Organization-ID", INBOUND_ORG_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private HttpHeaders capturePostHeaders() {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
        return captor.getValue().getHeaders();
    }

    /**
     * Replay the outbound headers against the real verifier and report the
     * status the receiving service would answer with. 200 means the chain ran.
     */
    private int verifyThroughGatewayFilter(HttpHeaders outbound, String path) {
        GatewayFilterProperties properties = new GatewayFilterProperties();
        properties.setVerificationEnabled(true);
        properties.setSecretKey(GATEWAY_SECRET);
        properties.setPublicPaths(List.of());
        properties.setHmacRequiredPaths(List.of());

        MockHttpServletRequest received = new MockHttpServletRequest("POST", path);
        outbound.forEach((name, values) -> values.forEach(value -> received.addHeader(name, value)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            new GatewayAuthenticationFilter(properties)
                    .doFilter(received, response, mock(FilterChain.class));
        } catch (Exception e) {
            throw new AssertionError("gateway filter threw", e);
        }
        return response.getStatus();
    }
}
