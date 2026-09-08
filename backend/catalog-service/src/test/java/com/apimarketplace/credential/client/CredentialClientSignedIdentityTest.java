package com.apimarketplace.credential.client;

import com.apimarketplace.common.web.GatewayAuthenticationFilter;
import com.apimarketplace.common.web.GatewayFilterProperties;
import com.apimarketplace.credential.client.dto.AccessTokenResult;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code CredentialClient} signs the same way {@code CreditConsumptionClient} does, and carried the
 * same latent defect: the HMAC was computed over the {@code tenantId} ARGUMENT while
 * {@code OrgContextHeaderForwarder} can put a DIFFERENT {@code X-User-ID} on the wire, copied off
 * the inbound request whenever the caller passed none.
 *
 * <p>It has never fired in production - every live caller passes a real id or one of the
 * {@code "SYSTEM"} / {@code "PLATFORM"} sentinels - and that is exactly why it needs a test. A
 * defect that is currently unreachable is one nothing would notice becoming reachable: the first
 * caller to pass a null tenant would 401 every credential lookup it makes, under a message
 * ("Invalid gateway secret") that points at configuration rather than at this line.
 *
 * <p><b>Why this test does not live in credential-client.</b> Reproducing the defect needs a bound
 * servlet request - {@code X-User-ID} is deliberately request-bound with no ThreadLocal fallback,
 * so there is no other way to make the forwarder inject one - and asserting acceptance needs the
 * receiving filter. credential-client has neither: it is a lightweight JAR whose production
 * classpath is servlet-free ON PURPOSE, which is the whole reason the forwarder works by
 * reflection. Adding the servlet API there in test scope would be a dependency taken on to run a
 * mock. catalog-service already has both, and consumes this client, so the test sits with the
 * classpath it needs rather than dragging that classpath to the code.
 *
 * <p>Asserted through the REAL verifier: a re-derived HMAC agrees with the signer by construction
 * and would have stayed green through the incident this guards.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CredentialClient gateway signature binds the identity it sends")
class CredentialClientSignedIdentityTest {

    private static final String GATEWAY_SECRET = "test-gateway-secret";
    private static final String ACCESS_TOKEN_PATH = "/api/internal/credentials/access-token";

    @Mock private RestTemplate restTemplate;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private CredentialClient signingClient() {
        CredentialClient client = new CredentialClient("http://auth:8083", GATEWAY_SECRET);
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
        return client;
    }

    private void bindInboundRequest(String userId, String orgId) {
        MockHttpServletRequest inbound = new MockHttpServletRequest();
        if (userId != null) inbound.addHeader("X-User-ID", userId);
        if (orgId != null) inbound.addHeader("X-Organization-ID", orgId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void serverAnswers() {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(new ResponseEntity(new AccessTokenResult(), HttpStatus.OK));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private HttpHeaders captureHeaders() {
        ArgumentCaptor<HttpEntity<Object>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), any(HttpMethod.class), captor.capture(), any(Class.class));
        return captor.getValue().getHeaders();
    }

    /** Replay the outbound headers against the real verifier; 200 means the chain ran. */
    private int verifyThroughGatewayFilter(HttpHeaders outbound) {
        GatewayFilterProperties properties = new GatewayFilterProperties();
        properties.setVerificationEnabled(true);
        properties.setSecretKey(GATEWAY_SECRET);
        properties.setPublicPaths(List.of());
        properties.setHmacRequiredPaths(List.of());

        MockHttpServletRequest received = new MockHttpServletRequest("GET", ACCESS_TOKEN_PATH);
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

    @Test
    @DisplayName("a call with no tenant of its own, made inside a user's request, is accepted")
    void nullTenantInsideAUserRequestIsAccepted() {
        CredentialClient client = signingClient();
        bindInboundRequest("1", "org-acme");
        serverAnswers();

        client.getAccessTokenInfo(null, "openai");

        HttpHeaders sent = captureHeaders();
        assertThat(sent.getFirst("X-User-ID"))
                .as("the forwarder carried the inbound user onto the outbound call")
                .isEqualTo("1");
        assertThat(verifyThroughGatewayFilter(sent))
                .as("signing the null argument instead would 401 every lookup this caller makes")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("an explicit tenant keeps working - the common case must not regress")
    void explicitTenantStillAccepted() {
        CredentialClient client = signingClient();
        bindInboundRequest("1", "org-acme");
        serverAnswers();

        client.getAccessTokenInfo("42", "openai");

        HttpHeaders sent = captureHeaders();
        assertThat(sent.getFirst("X-User-ID"))
                .as("an explicit tenant wins - the forwarder never overwrites a header already set")
                .isEqualTo("42");
        assertThat(verifyThroughGatewayFilter(sent)).isEqualTo(200);
    }

    @Test
    @DisplayName("outside any request, a sentinel caller is still accepted")
    void sentinelCallerOutsideARequestIsAccepted() {
        CredentialClient client = signingClient();
        serverAnswers();

        client.getAccessTokenInfo("SYSTEM", "openai");

        assertThat(verifyThroughGatewayFilter(captureHeaders())).isEqualTo(200);
    }
}
