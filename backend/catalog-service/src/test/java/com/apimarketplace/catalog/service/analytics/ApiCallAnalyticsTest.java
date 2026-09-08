package com.apimarketplace.catalog.service.analytics;

import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.service.ToolContextService;
import com.apimarketplace.catalog.service.exception.InsufficientCreditsException;
import com.apimarketplace.common.analytics.PostHogAnalyticsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code api_call_completed} property contract: which integration, which
 * outcome, from which caller, and NOTHING the user typed or the provider returned.
 */
class ApiCallAnalyticsTest {

    private ToolContextService.ToolContext context;

    @BeforeEach
    void context() {
        context = new ToolContextService.ToolContext();
        context.setApiSlug("elevenlabs");
        context.setToolSlug("elevenlabs-text-to-speech");
        context.setToolName("text_to_speech");
        context.setHttpMethod("POST");
        context.setExecutionMode("http");
    }

    private static ToolExecutionResponse response(boolean success, Integer httpCode, Map<String, Object> extraMeta) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (httpCode != null) meta.put("httpStatus", Map.of("code", httpCode, "error", "should never be emitted"));
        if (extraMeta != null) meta.putAll(extraMeta);
        return ToolExecutionResponse.builder()
                .success(success)
                .result(Map.of("secret_payload", "never emitted"))
                .error(success ? null : "provider said: user@example.com is not allowed")
                .metadata(meta)
                .executionTimeMs(12L)
                .build();
    }

    @Nested
    @DisplayName("outcome classification")
    class Outcome {

        @Test
        @DisplayName("a 200 envelope with success=true is SUCCESS")
        void success() {
            Map<String, Object> p = ApiCallAnalytics.buildProps(context, null, null, response(true, 200, null), null, 12L);
            assertEquals(true, p.get("success"));
            assertEquals("SUCCESS", p.get("outcome"));
            assertEquals(200, p.get("http_status"));
        }

        @Test
        @DisplayName("a provider refusal (real HTTP status, success=false) is UPSTREAM_REJECTED")
        void upstreamRejected() {
            Map<String, Object> p = ApiCallAnalytics.buildProps(context, null, null, response(false, 429, null), null, 12L);
            assertEquals(false, p.get("success"));
            assertEquals("UPSTREAM_REJECTED", p.get("outcome"));
            assertEquals(429, p.get("http_status"));
        }

        @Test
        @DisplayName("the code-0 exception envelope is INTERNAL")
        void internalEnvelope() {
            Map<String, Object> p = ApiCallAnalytics.buildProps(context, null, null, response(false, 0, null), null, 12L);
            assertEquals("INTERNAL", p.get("outcome"));
        }

        @Test
        @DisplayName("an InsufficientCreditsException rethrown for the controller is INSUFFICIENT_CREDITS")
        void insufficientCredits() {
            Throwable t = mock(InsufficientCreditsException.class);
            Map<String, Object> p = ApiCallAnalytics.buildProps(context, null, null, null, t, 3L);
            assertEquals("INSUFFICIENT_CREDITS", p.get("outcome"));
            assertEquals(false, p.get("success"));
        }

        @Test
        @DisplayName("plan gate and credential-selection refusals are classified by exception name")
        void refusalsByName() {
            class PlanUpgradeRequiredException extends RuntimeException {}
            class CredentialSelectionException extends RuntimeException {}
            assertEquals(ApiCallAnalytics.Outcome.PLAN_UPGRADE_REQUIRED,
                    ApiCallAnalytics.classify(null, new PlanUpgradeRequiredException()));
            assertEquals(ApiCallAnalytics.Outcome.CREDENTIAL_SELECTION,
                    ApiCallAnalytics.classify(null, new CredentialSelectionException()));
            assertEquals(ApiCallAnalytics.Outcome.INTERNAL,
                    ApiCallAnalytics.classify(null, new IllegalStateException("boom")));
        }

        @Test
        @DisplayName("nothing to classify (no response, no failure) builds no props")
        void nothing() {
            assertNull(ApiCallAnalytics.buildProps(context, null, null, null, null, 0L));
        }
    }

    @Nested
    @DisplayName("attribution")
    class Attribution {

        @Test
        @DisplayName("a RUN-scoped call from the orchestrator is attributed to the workflow, run and node")
        void workflowNode() {
            ToolExecutionRequest req = ToolExecutionRequest.builder().build();
            req.setBillingScopeKind("RUN");
            req.setBillingScopeId("run_123");
            req.setAnalyticsWorkflowId("wf-uuid");
            req.setAnalyticsNodeId("mcp:elevenlabs/text_to_speech");
            req.setContext("orchestrator");

            Map<String, Object> p = ApiCallAnalytics.buildProps(context, req, "org-1", response(true, 200, null), null, 12L);

            assertEquals("workflow", p.get("source"));
            assertEquals("run_123", p.get("workflow_run_id"));
            assertEquals("wf-uuid", p.get("workflow_id"));
            assertEquals("mcp:elevenlabs/text_to_speech", p.get("node_id"));
            assertEquals("org-1", p.get("organization_id"));
            assertFalse(p.containsKey("stream_id"));
        }

        @Test
        @DisplayName("a STREAM-scoped call is an agent call carrying the stream id")
        void agentStream() {
            ToolExecutionRequest req = ToolExecutionRequest.builder().build();
            req.setBillingScopeKind("STREAM");
            req.setBillingScopeId("stream_9");

            Map<String, Object> p = ApiCallAnalytics.buildProps(context, req, null, response(true, 200, null), null, 12L);

            assertEquals("agent", p.get("source"));
            assertEquals("stream_9", p.get("stream_id"));
            assertFalse(p.containsKey("workflow_run_id"));
            assertFalse(p.containsKey("organization_id"));
        }

        @Test
        @DisplayName("credential pool and cache hit are surfaced from the response metadata")
        void credentialSourceAndCache() {
            Map<String, Object> p = ApiCallAnalytics.buildProps(context, null, null,
                    response(true, 200, Map.of("credentialSource", "platform", "cached", true)), null, 1L);
            assertEquals("platform", p.get("credential_source"));
            assertEquals(true, p.get("cached"));
        }

        @Test
        @DisplayName("integration identity comes from the resolved catalog row")
        void identity() {
            Map<String, Object> p = ApiCallAnalytics.buildProps(context, null, null, response(true, 200, null), null, 12L);
            assertEquals("elevenlabs", p.get("api_slug"));
            assertEquals("elevenlabs-text-to-speech", p.get("tool_slug"));
            assertEquals("text_to_speech", p.get("tool_name"));
            assertEquals("POST", p.get("http_method"));
            assertEquals(12L, p.get("duration_ms"));
        }
    }

    @Nested
    @DisplayName("privacy")
    class Privacy {

        @Test
        @DisplayName("neither parameters, result payload, nor error text ever reach the property bag")
        void noPayloadNoErrorText() {
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .parameters(Map.of("text", "call me at +33 6 12 34 56 78"))
                    .build();
            Map<String, Object> p = ApiCallAnalytics.buildProps(context, req, null, response(false, 400, null), null, 12L);

            String flat = p.toString();
            assertFalse(flat.contains("+33"), "parameter value leaked");
            assertFalse(flat.contains("secret_payload"), "result payload leaked");
            assertFalse(flat.contains("user@example.com"), "error text leaked");
            assertFalse(flat.contains("should never be emitted"), "httpStatus.error leaked");
        }

        @Test
        @DisplayName("an exception contributes only its class name")
        void exceptionClassOnly() {
            Map<String, Object> p = ApiCallAnalytics.buildProps(context, null, null, null,
                    new IllegalStateException("token=sk_live_abc"), 5L);
            assertEquals("IllegalStateException", p.get("error_type"));
            assertFalse(p.toString().contains("sk_live"));
        }
    }

    @Nested
    @DisplayName("emit gating")
    class Emit {

        @Test
        @DisplayName("captures with the tenant as distinct_id when the client is active")
        void captures() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            when(client.isActive()).thenReturn(true);
            ApiCallAnalytics analytics = new ApiCallAnalytics();
            ReflectionTestUtils.setField(analytics, "postHog", client);

            analytics.emit(context, null, "42", "org-1", response(true, 200, null), null, 7L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> props = ArgumentCaptor.forClass(Map.class);
            verify(client).capture(eq("42"), eq("api_call_completed"), props.capture());
            assertEquals("SUCCESS", props.getValue().get("outcome"));
            assertTrue(props.getValue().containsKey("api_slug"));
        }

        @Test
        @DisplayName("silent when inactive, when there is no tenant, or when the bean is absent")
        void silent() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            when(client.isActive()).thenReturn(false);
            ApiCallAnalytics inactive = new ApiCallAnalytics();
            ReflectionTestUtils.setField(inactive, "postHog", client);
            inactive.emit(context, null, "42", null, response(true, 200, null), null, 7L);

            when(client.isActive()).thenReturn(true);
            inactive.emit(context, null, " ", null, response(true, 200, null), null, 7L);
            verify(client, never()).capture(anyString(), anyString(), any());

            new ApiCallAnalytics().emit(context, null, "42", null, response(true, 200, null), null, 7L); // no bean: no throw
        }
    }
}
