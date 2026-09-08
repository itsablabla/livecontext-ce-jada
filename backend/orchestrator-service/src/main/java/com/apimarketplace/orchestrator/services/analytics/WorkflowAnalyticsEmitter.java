package com.apimarketplace.orchestrator.services.analytics;

import com.apimarketplace.common.analytics.PostHogAnalyticsClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.completion.StepCompletionContext;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Product-analytics (PostHog) emitter for the workflow run lifecycle, covering
 * BOTH execution modes.
 *
 * <p>Why a dedicated emitter: the only lifecycle event that existed before this
 * class ({@code workflow_run_completed}, from {@code V2WorkflowFinalizer}) fires
 * solely for plans without a reusable trigger, and every known trigger type IS
 * reusable, so production runs (a trigger firing an epoch, AUTOMATIC or
 * STEP_BY_STEP) were invisible: one event in a month. The unit of work that
 * actually happens in production is the EPOCH (one trigger fire), so the events
 * below are keyed on it:</p>
 * <ul>
 *   <li>{@code workflow_epoch_started}: a trigger fired (trigger type, refire, mode)</li>
 *   <li>{@code workflow_epoch_completed}: the epoch closed (outcome, node counts, duration, credits)</li>
 *   <li>{@code workflow_node_failed}: a node reached a TERMINAL failure (node kind, error type)</li>
 *   <li>{@code workflow_signal_resolved}: an approval / interface / timer / webhook wait ended</li>
 * </ul>
 *
 * <p>Contract shared with every other emit site: PII-free (ids, enums, counts,
 * durations; never node outputs, labels typed by the user, or error messages),
 * best-effort (no-op unless configured, never throws, never blocks: the client
 * enqueues on a bounded pool), distinct_id = tenant id, organization_id read from
 * the run row rather than a request thread-local that is empty on execution
 * threads.</p>
 */
@Component
public class WorkflowAnalyticsEmitter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAnalyticsEmitter.class);

    static final String EPOCH_STARTED = "workflow_epoch_started";
    static final String EPOCH_COMPLETED = "workflow_epoch_completed";
    static final String NODE_FAILED = "workflow_node_failed";
    static final String SIGNAL_RESOLVED = "workflow_signal_resolved";

    @Autowired(required = false)
    private PostHogAnalyticsClient postHog;

    /** Used only to resolve tenant/org for a signal, whose entity carries neither. */
    @Autowired(required = false)
    private WorkflowRunRepository runRepository;

    private boolean inactive() {
        return postHog == null || !postHog.isActive();
    }

    // ── epoch started ─────────────────────────────────────────────────────────

    public void epochStarted(WorkflowRunEntity run, TriggerType triggerType, int epoch,
                             boolean refire, ExecutionMode mode) {
        if (inactive() || run == null) return;
        try {
            String tenant = run.getTenantId();
            if (tenant == null || tenant.isBlank()) return;
            postHog.capture(tenant, EPOCH_STARTED, buildEpochStartedProps(run, triggerType, epoch, refire, mode));
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", EPOCH_STARTED, e.toString());
        }
    }

    static Map<String, Object> buildEpochStartedProps(WorkflowRunEntity run, TriggerType triggerType,
                                                      int epoch, boolean refire, ExecutionMode mode) {
        Map<String, Object> props = new LinkedHashMap<>();
        putRunIdentity(props, run);
        props.put("trigger_type", triggerType != null ? triggerType.getValue() : null);
        props.put("epoch", epoch);
        props.put("refire", refire);
        props.put("execution_mode", (mode != null ? mode : ExecutionMode.AUTOMATIC).getValue());
        return props;
    }

    // ── epoch completed ───────────────────────────────────────────────────────

    /**
     * @param closeReason {@code cycle_end} when the engine closed the epoch at the
     *                    end of its cycle, {@code deferred} when it was closed later
     *                    by the next fire or a cancellation (STEP_BY_STEP closes late).
     */
    public void epochCompleted(WorkflowRunEntity run, int epoch,
                               EpochState finalState, long durationMs, String closeReason) {
        if (inactive() || run == null || finalState == null) return;
        try {
            String tenant = run.getTenantId();
            if (tenant == null || tenant.isBlank()) return;
            postHog.capture(tenant, EPOCH_COMPLETED,
                    buildEpochCompletedProps(run, epoch, finalState, durationMs, closeReason));
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", EPOCH_COMPLETED, e.toString());
        }
    }

    static Map<String, Object> buildEpochCompletedProps(WorkflowRunEntity run, int epoch, EpochState state,
                                                        long durationMs, String closeReason) {
        int completed = size(state.getCompletedNodeIds());
        int failed = size(state.getFailedNodeIds());
        int partialFailed = size(state.getPartialFailedNodeIds());
        int skipped = size(state.getSkippedNodeIds());
        int unfinished = size(state.getRunningNodeIds()) + size(state.getReadyNodeIds())
                + size(state.getAwaitingSignalNodeIds());

        Map<String, Object> props = new LinkedHashMap<>();
        putRunIdentity(props, run);
        props.put("epoch", epoch);
        props.put("run_status", deriveEpochStatus(failed, unfinished));
        props.put("nodes_completed", completed);
        props.put("nodes_failed", failed);
        props.put("nodes_partial_failed", partialFailed);
        props.put("nodes_skipped", skipped);
        props.put("nodes_unfinished", unfinished);
        props.put("duration_ms", durationMs);
        props.put("close_reason", closeReason);
        props.put("execution_mode", (run.getExecutionMode() != null ? run.getExecutionMode() : ExecutionMode.AUTOMATIC).getValue());
        Object credits = epochCredits(run, epoch);
        if (credits != null) props.put("credits_consumed", credits);
        return props;
    }

    /**
     * FAILED when any node failed; INTERRUPTED when the epoch closed with work still
     * pending and nothing failed (a cancel, or a deferred close of a paused run);
     * COMPLETED otherwise. Mirrors the engine's own binary cycle status, plus the one
     * case the engine never names because it stops asking.
     */
    static String deriveEpochStatus(int failed, int unfinished) {
        if (failed > 0) return "FAILED";
        if (unfinished > 0) return "INTERRUPTED";
        return "COMPLETED";
    }

    /** Reads {@code cost_by_epoch[epoch]} when the run row carries it. */
    static Object epochCredits(WorkflowRunEntity run, int epoch) {
        Map<String, Object> byEpoch = run.getCostByEpoch();
        if (byEpoch == null) return null;
        Object v = byEpoch.get(String.valueOf(epoch));
        return v instanceof Number ? v : null;
    }

    // ── node failed ───────────────────────────────────────────────────────────

    public void nodeFailed(StepCompletionContext ctx) {
        if (inactive() || ctx == null || ctx.result() == null) return;
        if (ctx.result().status() != NodeStatus.FAILED) return;
        try {
            String tenant = ctx.execution() != null && ctx.execution().getPlan() != null
                    ? ctx.execution().getPlan().getTenantId() : null;
            if (tenant == null || tenant.isBlank()) return;
            postHog.capture(tenant, NODE_FAILED, buildNodeFailedProps(ctx, resolveOrganizationId(ctx)));
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", NODE_FAILED, e.toString());
        }
    }

    /**
     * The run row is the authority: it always carries the organization the run
     * belongs to. The request thread-local is only a fallback for a run row that
     * cannot be read, and on a request thread (step-by-step, rerun-from-step,
     * support actions) it can name the CALLER's workspace rather than the run's.
     */
    String resolveOrganizationId(StepCompletionContext ctx) {
        if (runRepository != null && ctx.execution() != null && ctx.execution().getRunId() != null) {
            try {
                String fromRun = runRepository.findByRunIdPublic(ctx.execution().getRunId())
                        .map(WorkflowRunEntity::getOrganizationId).orElse(null);
                if (fromRun != null && !fromRun.isBlank()) return fromRun;
            } catch (RuntimeException ignored) {
                // fall through to the scope
            }
        }
        String fromScope = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId();
        return fromScope != null && !fromScope.isBlank() ? fromScope : null;
    }

    static Map<String, Object> buildNodeFailedProps(StepCompletionContext ctx, String organizationId) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (ctx.execution() != null) {
            props.put("run_id", ctx.execution().getRunId());
            if (ctx.execution().getWorkflowRunId() != null) {
                props.put("workflow_run_id", ctx.execution().getWorkflowRunId().toString());
            }
            if (ctx.execution().getPlan() != null) props.put("workflow_id", ctx.execution().getPlan().getId());
        }
        if (organizationId != null && !organizationId.isBlank()) {
            props.put("organization_id", organizationId);
            props.put("$groups", Map.of("organization", organizationId));
        }
        props.put("node_id", ctx.nodeId());
        props.put("node_kind", nodeKind(ctx.nodeId()));
        props.put("epoch", ctx.epoch());
        props.put("item_index", ctx.itemIndex());
        props.put("iteration", ctx.iteration());
        props.put("duration_ms", ctx.result().executionTime());
        Exception error = ctx.result().error();
        if (error != null) props.put("error_type", error.getClass().getSimpleName());
        return props;
    }

    /**
     * The node-type family: the prefix before the first {@code :} of a node id
     * ({@code core}, {@code mcp}, {@code agent}, {@code table}, {@code interface},
     * {@code trigger}). For {@code mcp:<api>/<tool>} the api slug is appended so an
     * integration that fails as a workflow node is attributable without sending
     * the user's label. Never the label itself.
     */
    static String nodeKind(String nodeId) {
        if (nodeId == null) return null;
        int colon = nodeId.indexOf(':');
        if (colon <= 0) return "unknown";
        String family = nodeId.substring(0, colon);
        if ("mcp".equals(family)) {
            String rest = nodeId.substring(colon + 1);
            int slash = rest.indexOf('/');
            return slash > 0 ? "mcp:" + rest.substring(0, slash) : "mcp";
        }
        return family;
    }

    // ── signal resolved ───────────────────────────────────────────────────────

    public void signalResolved(SignalWaitEntity entity, long waitDurationMs) {
        if (inactive() || entity == null || runRepository == null) return;
        try {
            Optional<WorkflowRunEntity> run = runRepository.findByRunIdPublic(entity.getRunId());
            if (run.isEmpty() || run.get().getTenantId() == null) return;
            postHog.capture(run.get().getTenantId(), SIGNAL_RESOLVED,
                    buildSignalResolvedProps(run.get(), entity, waitDurationMs));
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", SIGNAL_RESOLVED, e.toString());
        }
    }

    static Map<String, Object> buildSignalResolvedProps(WorkflowRunEntity run, SignalWaitEntity entity,
                                                        long waitDurationMs) {
        Map<String, Object> props = new LinkedHashMap<>();
        putRunIdentity(props, run);
        props.put("signal_type", entity.getSignalType() != null ? entity.getSignalType().name() : null);
        props.put("resolution", entity.getResolution() != null ? entity.getResolution().name() : null);
        props.put("node_kind", nodeKind(entity.getNodeId()));
        props.put("epoch", entity.getEpoch());
        props.put("wait_ms", waitDurationMs);
        props.put("timed_out", entity.getResolution() != null
                && "TIMEOUT".equals(entity.getResolution().name()));
        return props;
    }

    // ── shared ────────────────────────────────────────────────────────────────

    private static void putRunIdentity(Map<String, Object> props, WorkflowRunEntity run) {
        props.put("run_id", run.getRunIdPublic());
        if (run.getId() != null) props.put("workflow_run_id", run.getId().toString());
        try {
            // getId() on the LAZY proxy never initialises it.
            if (run.getWorkflow() != null && run.getWorkflow().getId() != null) {
                props.put("workflow_id", run.getWorkflow().getId().toString());
            }
        } catch (RuntimeException ignored) {
            // detached proxy: identity stays partial rather than failing the emit
        }
        if (run.getOrganizationId() != null && !run.getOrganizationId().isBlank()) {
            props.put("organization_id", run.getOrganizationId());
            props.put("$groups", Map.of("organization", run.getOrganizationId()));
        }
        if (run.getPlanVersion() != null) props.put("plan_version", run.getPlanVersion());
        if (run.getSource() != null && !run.getSource().isBlank()) props.put("run_source", run.getSource());
        Map<String, Object> meta = run.getMetadata();
        if (meta != null) {
            if (Boolean.TRUE.equals(meta.get("__editorRun__"))) props.put("editor_run", true);
            if (Boolean.TRUE.equals(meta.get("__agentInitiated__"))) props.put("agent_initiated", true);
        }
    }

    private static int size(Set<?> s) {
        return s == null ? 0 : s.size();
    }
}
