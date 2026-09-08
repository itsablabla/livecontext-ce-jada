package com.apimarketplace.catalog.service.analytics;

import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.service.ToolContextService;
import com.apimarketplace.catalog.service.exception.InsufficientCreditsException;
import com.apimarketplace.common.analytics.PostHogAnalyticsClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits one PII-free {@code api_call_completed} product-analytics event (PostHog)
 * per third-party catalog API execution - the "which integrations work, for whom,
 * from where" signal.
 *
 * <p>Why here and not in an agent-tool interceptor: {@code ToolExecutionManager}
 * is the ONE frame every caller converges on with the endpoint identity resolved
 * from the catalog row (workflow {@code mcp:} nodes through the orchestrator
 * gateway, agent {@code catalog} tool calls through the service tools controller,
 * generation, the MCP bridge, the CE relay). The agent-side interceptor only ever
 * sees {@code tool_name="catalog"}, and never sees a workflow node at all.</p>
 *
 * <p>Emits only identifiers, enums, counts and booleans: api/tool slugs (public
 * catalog data), outcome, HTTP status, duration, scope ids. Never the request
 * parameters, the response payload or an error message (both can carry user
 * content or provider secrets). Best-effort: no-op when analytics is not
 * configured, never throws, never blocks (the client enqueues and returns).</p>
 */
@Component
@Slf4j
public class ApiCallAnalytics {

    public static final String EVENT = "api_call_completed";

    /** Same field-injection pattern as the other emit sites: absent bean = no-op. */
    @Autowired(required = false)
    private PostHogAnalyticsClient postHog;

    /** Outcome vocabulary, bounded so PostHog breakdowns stay readable. */
    public enum Outcome {
        SUCCESS,
        /** The provider answered with a non-2xx status or a failure envelope. */
        UPSTREAM_REJECTED,
        /** The account had no credits to reserve; the provider was never called. */
        INSUFFICIENT_CREDITS,
        /** The plan does not include this integration; the provider was never called. */
        PLAN_UPGRADE_REQUIRED,
        /** The step named a credential that could not be honoured. */
        CREDENTIAL_SELECTION,
        /** Exception on our side before or while calling (code 0 envelope). */
        INTERNAL
    }

    /**
     * Emit for a finished execution. Exactly one of {@code response} / {@code failure}
     * is expected to be non-null; when both are null nothing is emitted.
     */
    public void emit(ToolContextService.ToolContext context,
                     ToolExecutionRequest request,
                     String userId,
                     String orgId,
                     ToolExecutionResponse response,
                     Throwable failure,
                     long durationMs) {
        if (postHog == null || !postHog.isActive()) return;
        if (userId == null || userId.isBlank() || context == null) return;
        try {
            Map<String, Object> props = buildProps(context, request, orgId, response, failure, durationMs);
            if (props != null) postHog.capture(userId, EVENT, props);
        } catch (Exception e) {
            log.debug("[posthog] api_call_completed dropped: {}", e.toString());
        }
    }

    /** Package-private + static so the property contract is unit-testable without Spring. */
    static Map<String, Object> buildProps(ToolContextService.ToolContext context,
                                          ToolExecutionRequest request,
                                          String orgId,
                                          ToolExecutionResponse response,
                                          Throwable failure,
                                          long durationMs) {
        if (response == null && failure == null) return null;

        Outcome outcome = classify(response, failure);
        Integer httpStatus = httpStatusOf(response);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("api_slug", context.getApiSlug());
        props.put("tool_slug", context.getToolSlug());
        props.put("tool_name", context.getToolName());
        putIfNotBlank(props, "http_method", context.getHttpMethod());
        putIfNotBlank(props, "execution_mode", context.getExecutionMode());
        props.put("success", outcome == Outcome.SUCCESS);
        props.put("outcome", outcome.name());
        if (httpStatus != null) props.put("http_status", httpStatus);
        props.put("duration_ms", durationMs);
        if (failure != null) props.put("error_type", failure.getClass().getSimpleName());
        putIfNotBlank(props, "organization_id", orgId);
        if (orgId != null && !orgId.isBlank()) props.put("$groups", Map.of("organization", orgId));

        if (request != null) {
            String scopeKind = request.getBillingScopeKind();
            String scopeId = request.getBillingScopeId();
            // Only header-sealed facts decide the source: the body's `context`
            // field is caller-writable and must not be able to claim a workflow.
            boolean fromWorkflow = "RUN".equalsIgnoreCase(scopeKind)
                    || (request.getAnalyticsWorkflowId() != null && !request.getAnalyticsWorkflowId().isBlank());
            props.put("source", fromWorkflow ? "workflow" : "agent");
            if ("RUN".equalsIgnoreCase(scopeKind)) {
                putIfNotBlank(props, "workflow_run_id", scopeId);
            } else if ("STREAM".equalsIgnoreCase(scopeKind)) {
                putIfNotBlank(props, "stream_id", scopeId);
            }
            putIfNotBlank(props, "workflow_id", request.getAnalyticsWorkflowId());
            putIfNotBlank(props, "node_id", request.getAnalyticsNodeId());
            putIfNotBlank(props, "credential_source_requested", request.getCredentialSource());
            if (request.getGenerationModelId() != null && !request.getGenerationModelId().isBlank()) {
                props.put("generation_model", request.getGenerationModelId());
            }
        } else {
            props.put("source", "agent");
        }

        if (response != null && response.getMetadata() != null) {
            Object credentialSource = response.getMetadata().get("credentialSource");
            if (credentialSource instanceof String s && !s.isBlank()) props.put("credential_source", s);
            if (Boolean.TRUE.equals(response.getMetadata().get("cached"))) props.put("cached", true);
        }
        return props;
    }

    static Outcome classify(ToolExecutionResponse response, Throwable failure) {
        if (failure != null) {
            if (failure instanceof InsufficientCreditsException) return Outcome.INSUFFICIENT_CREDITS;
            String name = failure.getClass().getSimpleName();
            // Matched by name: the two exceptions live in packages this module must
            // not depend on for a best-effort emitter (plan gate is an optional bean).
            if (name.contains("PlanUpgradeRequired")) return Outcome.PLAN_UPGRADE_REQUIRED;
            if (name.contains("CredentialSelection")) return Outcome.CREDENTIAL_SELECTION;
            return Outcome.INTERNAL;
        }
        if (response.isSuccess()) return Outcome.SUCCESS;
        Integer status = httpStatusOf(response);
        // The generic-exception envelope stamps code 0; a provider refusal carries a real status.
        return (status == null || status == 0) ? Outcome.INTERNAL : Outcome.UPSTREAM_REJECTED;
    }

    /** Reads {@code metadata.httpStatus.code} (set on both success and error envelopes). */
    static Integer httpStatusOf(ToolExecutionResponse response) {
        if (response == null || response.getMetadata() == null) return null;
        Object hs = response.getMetadata().get("httpStatus");
        if (hs instanceof Map<?, ?> m && m.get("code") instanceof Number n) return n.intValue();
        return null;
    }

    private static void putIfNotBlank(Map<String, Object> props, String key, String value) {
        if (value != null && !value.isBlank()) props.put(key, value);
    }
}
