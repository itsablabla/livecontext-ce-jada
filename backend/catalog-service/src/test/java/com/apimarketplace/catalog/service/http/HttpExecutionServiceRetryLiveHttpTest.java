package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.web.NoRedirectSimpleClientHttpRequestFactory;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;

/**
 * The retry against a REAL provider: a real socket, a real {@code RestTemplate}, real HTTP
 * statuses, a real {@code Retry-After} header and a real wait.
 *
 * <p><b>Why this exists alongside the mocked tests.</b> Everything the mocked suite asserts is
 * about our own branching. Four things can only be observed from the other end of a socket, and
 * each of them is load-bearing:
 *
 * <ol>
 *   <li>that Spring actually raises {@code HttpClientErrorException.TooManyRequests} with the
 *       response headers attached, which is the input the whole engine reads;</li>
 *   <li>that the wait really is the one the provider asked for, measured in wall-clock time
 *       rather than asserted on a verdict object;</li>
 *   <li>that a re-sent POST arrives byte-identical, which the javadoc claims and no mock can
 *       show, because a mocked {@code RestTemplate} never serialises the body;</li>
 *   <li>that the provider receives exactly the number of requests the policy allows, counted by
 *       the provider itself.</li>
 * </ol>
 *
 * <p>The containerised e2e (an {@code e2e-slots} stack) could not run on this machine: the image
 * build fails at {@code apk add} because TLS to the Alpine index is intercepted locally, which no
 * repository change can work around. This test covers the runtime half that the container would
 * have exercised, and unlike the container it runs in CI.
 *
 * <p>{@link UrlSafetyValidator} is the one thing stubbed: it forbids a loopback target by design,
 * and a local server is necessarily loopback. The guard has its own tests.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Provider retry - live HTTP against a server that really refuses")
class HttpExecutionServiceRetryLiveHttpTest {

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;
    private int port;
    private HttpExecutionService service;

    /** What the provider actually received, in order: the retry seen from the other side. */
    private final List<Long> requestTimes = new ArrayList<>();
    private final List<String> requestBodies = new ArrayList<>();
    private final AtomicInteger throttleCalls = new AtomicInteger();

    @BeforeEach
    void startProvider() throws Exception {
        lenient().when(apiToolParameterRepository.findByApiToolId(any())).thenReturn(List.of());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        // Refuses once with a one-second Retry-After, then succeeds.
        server.createContext("/throttle-once", exchange -> {
            record(exchange.getRequestBody());
            if (throttleCalls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                respond(exchange, 429, "{\"error\":\"rate limited\"}");
            } else {
                respond(exchange, 200, "{\"ok\":true}");
            }
        });

        // Refuses every time, with a wait short enough to fit the budget.
        server.createContext("/throttle-always", exchange -> {
            record(exchange.getRequestBody());
            exchange.getResponseHeaders().add("Retry-After", "0");
            respond(exchange, 429, "{\"error\":\"rate limited\"}");
        });

        // A refusal only the account owner can act on.
        server.createContext("/refuse", exchange -> {
            record(exchange.getRequestBody());
            respond(exchange, 400, "{\"error\":{\"code\":\"spam_risk_too_many_posts\"}}");
        });

        // A gateway answering 502 with a body a "retry" rule matches: the trap.
        server.createContext("/gateway-error", exchange -> {
            record(exchange.getRequestBody());
            respond(exchange, 502, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}");
        });

        server.setExecutor(null);
        server.start();

        // The same factory and timeouts DataConfig builds for production.
        NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        service = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, new RestTemplate(factory),
                new ErrorPolicyEngine(2, 10_000L));
    }

    @AfterEach
    void stopProvider() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void record(InputStream body) throws java.io.IOException {
        requestTimes.add(System.currentTimeMillis());
        requestBodies.add(new String(body.readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body)
            throws java.io.IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private ApiEntity api(String errorPolicy) {
        ApiEntity api = new ApiEntity();
        api.setId(UUID.randomUUID());
        api.setBaseUrl("http://127.0.0.1:" + port);
        api.setApiName("Fake Provider");
        api.setIconSlug("fakeprovider");
        api.setErrorPolicy(errorPolicy);
        return api;
    }

    private ApiToolEntity tool(String method, String endpoint) {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
        tool.setMethod(method);
        tool.setEndpoint(endpoint);
        return tool;
    }

    /** Declares a body parameter on the tool, the way an imported endpoint does. */
    private void declareBodyParam(ApiToolEntity tool, String name) {
        com.apimarketplace.catalog.domain.ApiToolParameterEntity p =
                new com.apimarketplace.catalog.domain.ApiToolParameterEntity();
        p.setId(UUID.randomUUID());
        p.setApiToolId(tool.getId());
        p.setName(name);
        p.setParameterType("body");
        p.setDataType("string");
        lenient().when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(p));
    }

    private ArrayNode params(String name, String value) {
        ArrayNode arr = objectMapper.createArrayNode();
        ObjectNode node = objectMapper.createObjectNode();
        node.put(name, value);
        arr.add(node);
        return arr;
    }

    private Map<String, Object> call(ApiEntity api, ApiToolEntity tool, ArrayNode parameters) {
        try (MockedStatic<UrlSafetyValidator> urlValidator = mockStatic(UrlSafetyValidator.class)) {
            urlValidator.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(i -> null);
            // allowedParamNames = null, so the engine resolves the tool's declared parameters
            // from the repository the way it does in production. Passing an empty set would mean
            // "no parameter is allowed" and every request would go out with an empty body.
            return service.executeHttpCallWithCredentials(
                    api, tool, parameters, null, null, null);
        }
    }

    @Test
    @DisplayName("a real 429 is waited out for the time the provider asked, and the call recovers")
    void honoursARealRetryAfterHeader() {
        long startedAt = System.currentTimeMillis();

        Map<String, Object> result = call(api(null), tool("GET", "/throttle-once"),
                objectMapper.createArrayNode());

        assertThat(result.get("success"))
                .as("the provider refused once and then served the call")
                .isEqualTo(true);
        assertThat(requestTimes).hasSize(2);
        // Retry-After: 1, so the second request cannot arrive sooner. This is the assertion the
        // mocked suite cannot make: there, the wait is a number on a verdict, not a wait.
        assertThat(requestTimes.get(1) - requestTimes.get(0))
                .as("the provider said one second, so the re-send waits one second")
                .isGreaterThanOrEqualTo(900L);
        assertThat(System.currentTimeMillis() - startedAt).isLessThan(9_000L);
    }

    @Test
    @DisplayName("the provider receives exactly the allowed number of attempts, and no more")
    void stopsAtTheConfiguredAttempts() {
        Map<String, Object> result = call(api(null), tool("GET", "/throttle-always"),
                objectMapper.createArrayNode());

        assertThat(result.get("status")).isEqualTo(429);
        // Counted by the provider, not by a mock: one call plus the two configured retries.
        assertThat(requestTimes).hasSize(3);
    }

    @Test
    @DisplayName("a re-sent POST arrives byte-identical, which is what makes re-sending safe")
    void resendCarriesTheSameBody() {
        ApiEntity api = api(
                "[{\"match\":{\"bodyContains\":\"rate limited\"},\"action\":\"retry\",\"waitMs\":250}]");
        ApiToolEntity tool = tool("POST", "/throttle-once");
        // The engine only sends parameters the tool DECLARES, so the body is empty without this.
        declareBodyParam(tool, "caption");

        call(api, tool, params("caption", "hello from the test"));

        assertThat(requestBodies).hasSize(2);
        assertThat(requestBodies.get(0))
                .as("the first attempt carried the caption")
                .contains("hello from the test");
        assertThat(requestBodies.get(1))
                .as("a re-send that dropped or altered the body would publish something else")
                .isEqualTo(requestBodies.get(0));
    }

    @Test
    @DisplayName("a declared rule replaces the provider's raw body with something the reader "
            + "can act on, and keeps the original alongside")
    void rewritesARealRefusal() {
        ApiEntity api = api("[{\"match\":{\"bodyContains\":\"spam_risk_too_many_posts\"},"
                + "\"action\":\"user_error\","
                + "\"message\":\"This account reached its daily posting limit.\"}]");

        ApiToolEntity tool = tool("POST", "/refuse");
        declareBodyParam(tool, "caption");

        Map<String, Object> result = call(api, tool, params("caption", "anything"));

        assertThat(result.get("status")).isEqualTo(400);
        assertThat(String.valueOf(result.get("error")))
                .isEqualTo("This account reached its daily posting limit.");
        assertThat(String.valueOf(result.get("errorBody"))).contains("spam_risk_too_many_posts");
        assertThat(requestTimes).as("a user_error is not retried").hasSize(1);
    }

    @Test
    @DisplayName("THE safety case: a gateway 502 on a POST is never re-sent, even though the "
            + "seed's rule says retry and its body matches")
    void neverResendsAWriteOnAGatewayError() {
        // The shape tiktok.json ships: a body-only retry rule, on endpoints that are POSTs. If a
        // 502 carrying the provider's rate-limit code were re-sent, the post would go out twice.
        ApiEntity api = api(
                "[{\"match\":{\"bodyContains\":\"rate_limit_exceeded\"},\"action\":\"retry\",\"waitMs\":250}]");

        ApiToolEntity tool = tool("POST", "/gateway-error");
        declareBodyParam(tool, "caption");

        Map<String, Object> result = call(api, tool, params("caption", "would be published twice"));

        assertThat(result.get("status")).isEqualTo(502);
        assertThat(requestTimes)
                .as("the provider must see this write exactly once")
                .hasSize(1);
    }

    @Test
    @DisplayName("and the same rule still retries that 502 for a read, where it is safe")
    void stillRetriesAGatewayErrorOnARead() {
        ApiEntity api = api(
                "[{\"match\":{\"bodyContains\":\"rate_limit_exceeded\"},\"action\":\"retry\",\"waitMs\":250}]");

        call(api, tool("GET", "/gateway-error"), objectMapper.createArrayNode());

        assertThat(requestTimes).hasSize(3);
    }
}
