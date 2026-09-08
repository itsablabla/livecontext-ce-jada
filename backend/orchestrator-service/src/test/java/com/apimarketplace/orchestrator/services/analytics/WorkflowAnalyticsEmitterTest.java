package com.apimarketplace.orchestrator.services.analytics;

import com.apimarketplace.common.analytics.PostHogAnalyticsClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.completion.StepCompletionContext;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The property contracts of the four workflow-lifecycle events, and the gating
 * that keeps the emitter silent without a configured client.
 */
class WorkflowAnalyticsEmitterTest {

    private static WorkflowRunEntity run() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId("7");
        run.setRunIdPublic("run_1");
        run.setOrganizationId("org-1");
        run.setExecutionMode(ExecutionMode.AUTOMATIC);
        run.setPlanVersion(3);
        run.setSource("ui");
        run.setMetadata(Map.of("__editorRun__", true, "lastCycleResult", "COMPLETED"));
        run.setCostByEpoch(Map.of("2", 0.42, "3", "not a number"));
        return run;
    }

    private static EpochState epoch(Set<String> completed, Set<String> failed, Set<String> skipped,
                                    Set<String> running, Set<String> awaiting) {
        return new EpochState(completed, failed, skipped, running, Set.of(), awaiting,
                Map.of(), Map.of(), Map.of(), Instant.now());
    }

    @Nested
    @DisplayName("workflow_epoch_started")
    class Started {
        @Test
        @DisplayName("carries run identity, trigger type, epoch, refire and mode")
        void props() {
            Map<String, Object> p = WorkflowAnalyticsEmitter.buildEpochStartedProps(
                    run(), TriggerType.SCHEDULE, 4, true, ExecutionMode.STEP_BY_STEP);
            assertEquals("run_1", p.get("run_id"));
            assertEquals("org-1", p.get("organization_id"));
            assertEquals("schedule", p.get("trigger_type"));
            assertEquals(4, p.get("epoch"));
            assertEquals(true, p.get("refire"));
            assertEquals("step_by_step", p.get("execution_mode"));
            assertEquals(3, p.get("plan_version"));
            assertEquals("ui", p.get("run_source"));
            assertEquals(true, p.get("editor_run"));
            assertFalse(p.containsKey("agent_initiated"));
        }

        @Test
        @DisplayName("a null mode defaults to automatic (the engine default)")
        void nullMode() {
            Map<String, Object> p = WorkflowAnalyticsEmitter.buildEpochStartedProps(run(), TriggerType.WEBHOOK, 1, false, null);
            assertEquals("automatic", p.get("execution_mode"));
        }
    }

    @Nested
    @DisplayName("workflow_epoch_completed")
    class Completed {
        @Test
        @DisplayName("counts every node bucket and derives COMPLETED when nothing failed or is pending")
        void completed() {
            EpochState state = epoch(Set.of("a", "b"), Set.of(), Set.of("c"), Set.of(), Set.of());
            Map<String, Object> p = WorkflowAnalyticsEmitter.buildEpochCompletedProps(run(), 2, state, 1500L, "cycle_end");
            assertEquals("COMPLETED", p.get("run_status"));
            assertEquals(2, p.get("nodes_completed"));
            assertEquals(0, p.get("nodes_failed"));
            assertEquals(1, p.get("nodes_skipped"));
            assertEquals(0, p.get("nodes_unfinished"));
            assertEquals(1500L, p.get("duration_ms"));
            assertEquals("cycle_end", p.get("close_reason"));
            assertEquals(0.42, p.get("credits_consumed"));
            assertEquals("automatic", p.get("execution_mode"));
        }

        @Test
        @DisplayName("FAILED wins over pending work; INTERRUPTED when only pending work remains")
        void statusDerivation() {
            assertEquals("FAILED", WorkflowAnalyticsEmitter.deriveEpochStatus(1, 5));
            assertEquals("INTERRUPTED", WorkflowAnalyticsEmitter.deriveEpochStatus(0, 1));
            assertEquals("COMPLETED", WorkflowAnalyticsEmitter.deriveEpochStatus(0, 0));

            EpochState interrupted = epoch(Set.of("a"), Set.of(), Set.of(), Set.of("b"), Set.of("c"));
            Map<String, Object> p = WorkflowAnalyticsEmitter.buildEpochCompletedProps(run(), 1, interrupted, 10L, "deferred");
            assertEquals("INTERRUPTED", p.get("run_status"));
            assertEquals(2, p.get("nodes_unfinished"));
        }

        @Test
        @DisplayName("credits are read per epoch and only when numeric")
        void credits() {
            assertEquals(0.42, WorkflowAnalyticsEmitter.epochCredits(run(), 2));
            assertNull(WorkflowAnalyticsEmitter.epochCredits(run(), 3));
            assertNull(WorkflowAnalyticsEmitter.epochCredits(run(), 9));
            WorkflowRunEntity noCost = run();
            noCost.setCostByEpoch(null);
            assertNull(WorkflowAnalyticsEmitter.epochCredits(noCost, 2));
        }
    }

    @Nested
    @DisplayName("workflow_node_failed")
    class NodeFailed {
        private StepCompletionContext ctx(String nodeId, Exception error) {
            WorkflowPlan plan = new WorkflowPlan("wf-1", "7", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
            WorkflowExecution execution = new WorkflowExecution("run_1", plan, Map.of());
            StepExecutionResult result = StepExecutionResult.failure(nodeId, "provider said no: user@example.com", error, 321L);
            return new StepCompletionContext(execution, nodeId, "My Secret Label", result, 2, 1, null, 5, false);
        }

        @Test
        @DisplayName("names the node kind and error class, never the label or message")
        void props() {
            Map<String, Object> p = WorkflowAnalyticsEmitter.buildNodeFailedProps(
                    ctx("mcp:elevenlabs/text_to_speech", new IllegalStateException("token=sk_live_x")), "org-1");
            assertEquals("run_1", p.get("run_id"));
            assertEquals("wf-1", p.get("workflow_id"));
            assertEquals("org-1", p.get("organization_id"));
            assertEquals("mcp:elevenlabs/text_to_speech", p.get("node_id"));
            assertEquals("mcp:elevenlabs", p.get("node_kind"));
            assertEquals(5, p.get("epoch"));
            assertEquals(2, p.get("item_index"));
            assertEquals(1, p.get("iteration"));
            assertEquals(321L, p.get("duration_ms"));
            assertEquals("IllegalStateException", p.get("error_type"));
            String flat = p.toString();
            assertFalse(flat.contains("Secret Label"));
            assertFalse(flat.contains("user@example.com"));
            assertFalse(flat.contains("sk_live"));
        }

        @Test
        @DisplayName("node kind is the type family, with the api slug for catalog nodes")
        void nodeKind() {
            assertEquals("core", WorkflowAnalyticsEmitter.nodeKind("core:my_decision"));
            assertEquals("agent", WorkflowAnalyticsEmitter.nodeKind("agent:writer"));
            assertEquals("mcp:slack", WorkflowAnalyticsEmitter.nodeKind("mcp:slack/post_message"));
            assertEquals("mcp", WorkflowAnalyticsEmitter.nodeKind("mcp:weird"));
            assertEquals("unknown", WorkflowAnalyticsEmitter.nodeKind("nocolon"));
            assertNull(WorkflowAnalyticsEmitter.nodeKind(null));
        }

        @Test
        @DisplayName("a successful completion is never emitted as a failure")
        void successIgnored() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            when(client.isActive()).thenReturn(true);
            WorkflowAnalyticsEmitter emitter = new WorkflowAnalyticsEmitter();
            ReflectionTestUtils.setField(emitter, "postHog", client);

            WorkflowPlan plan = new WorkflowPlan("wf-1", "7", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
            WorkflowExecution execution = new WorkflowExecution("run_1", plan, Map.of());
            StepCompletionContext ok = new StepCompletionContext(execution, "core:x", "x",
                    StepExecutionResult.success("core:x", Map.of(), 1L), 0, 0, null, 1, false);
            emitter.nodeFailed(ok);
            verify(client, never()).capture(anyString(), anyString(), any());

            emitter.nodeFailed(ctx("core:x", null));
            verify(client).capture(eq("7"), eq("workflow_node_failed"), any());
        }

        @Test
        @DisplayName("off a request thread the organization comes from the run row (regression: thread-local is empty there)")
        void orgFromRunRow() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            when(client.isActive()).thenReturn(true);
            WorkflowRunRepository repo = mock(WorkflowRunRepository.class);
            when(repo.findByRunIdPublic("run_1")).thenReturn(Optional.of(run()));
            WorkflowAnalyticsEmitter emitter = new WorkflowAnalyticsEmitter();
            ReflectionTestUtils.setField(emitter, "postHog", client);
            ReflectionTestUtils.setField(emitter, "runRepository", repo);

            emitter.nodeFailed(ctx("core:x", null));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(client).capture(eq("7"), eq("workflow_node_failed"), captor.capture());
            assertEquals("org-1", captor.getValue().get("organization_id"));
            assertEquals(Map.of("organization", "org-1"), captor.getValue().get("$groups"));
        }

        @Test
        @DisplayName("the run row wins over the request scope (a support action on a request thread names the caller's workspace, not the run's)")
        void runRowWinsOverScope() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            when(client.isActive()).thenReturn(true);
            WorkflowRunRepository repo = mock(WorkflowRunRepository.class);
            when(repo.findByRunIdPublic("run_1")).thenReturn(Optional.of(run()));
            WorkflowAnalyticsEmitter emitter = new WorkflowAnalyticsEmitter();
            ReflectionTestUtils.setField(emitter, "postHog", client);
            ReflectionTestUtils.setField(emitter, "runRepository", repo);

            com.apimarketplace.common.web.TenantResolver.runWithOrgScope("caller-org", () ->
                    emitter.nodeFailed(ctx("core:x", null)));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(client).capture(eq("7"), eq("workflow_node_failed"), captor.capture());
            assertEquals("org-1", captor.getValue().get("organization_id"));
        }

        @Test
        @DisplayName("without a readable run row the request scope is the fallback")
        void scopeIsTheFallback() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            when(client.isActive()).thenReturn(true);
            WorkflowRunRepository repo = mock(WorkflowRunRepository.class);
            when(repo.findByRunIdPublic(anyString())).thenReturn(Optional.empty());
            WorkflowAnalyticsEmitter emitter = new WorkflowAnalyticsEmitter();
            ReflectionTestUtils.setField(emitter, "postHog", client);
            ReflectionTestUtils.setField(emitter, "runRepository", repo);

            com.apimarketplace.common.web.TenantResolver.runWithOrgScope("caller-org", () ->
                    emitter.nodeFailed(ctx("core:x", null)));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(client).capture(eq("7"), eq("workflow_node_failed"), captor.capture());
            assertEquals("caller-org", captor.getValue().get("organization_id"));
        }
    }

    @Nested
    @DisplayName("workflow_signal_resolved")
    class Signal {
        @Test
        @DisplayName("resolves the tenant from the run row and names type, resolution and wait")
        void props() {
            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setRunId("run_1");
            entity.setNodeId("core:approval");
            entity.setSignalType(SignalType.USER_APPROVAL);
            entity.setResolution(SignalResolution.TIMEOUT);
            entity.setEpoch(3);

            Map<String, Object> p = WorkflowAnalyticsEmitter.buildSignalResolvedProps(run(), entity, 60_000L);
            assertEquals("USER_APPROVAL", p.get("signal_type"));
            assertEquals("TIMEOUT", p.get("resolution"));
            assertEquals(true, p.get("timed_out"));
            assertEquals("core", p.get("node_kind"));
            assertEquals(3, p.get("epoch"));
            assertEquals(60_000L, p.get("wait_ms"));
            assertEquals("run_1", p.get("run_id"));

            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            when(client.isActive()).thenReturn(true);
            WorkflowRunRepository repo = mock(WorkflowRunRepository.class);
            when(repo.findByRunIdPublic("run_1")).thenReturn(Optional.of(run()));
            WorkflowAnalyticsEmitter emitter = new WorkflowAnalyticsEmitter();
            ReflectionTestUtils.setField(emitter, "postHog", client);
            ReflectionTestUtils.setField(emitter, "runRepository", repo);

            emitter.signalResolved(entity, 60_000L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(client).capture(eq("7"), eq("workflow_signal_resolved"), captor.capture());
            assertEquals("USER_APPROVAL", captor.getValue().get("signal_type"));
        }

        @Test
        @DisplayName("an unknown run emits nothing")
        void unknownRun() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            when(client.isActive()).thenReturn(true);
            WorkflowRunRepository repo = mock(WorkflowRunRepository.class);
            when(repo.findByRunIdPublic(anyString())).thenReturn(Optional.empty());
            WorkflowAnalyticsEmitter emitter = new WorkflowAnalyticsEmitter();
            ReflectionTestUtils.setField(emitter, "postHog", client);
            ReflectionTestUtils.setField(emitter, "runRepository", repo);
            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setRunId("ghost");
            emitter.signalResolved(entity, 1L);
            verify(client, never()).capture(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("gating")
    class Gating {
        @Test
        @DisplayName("no client, inactive client, or blank tenant: nothing is captured and nothing throws")
        void silent() {
            WorkflowAnalyticsEmitter none = new WorkflowAnalyticsEmitter();
            none.epochStarted(run(), TriggerType.MANUAL, 1, false, ExecutionMode.AUTOMATIC);
            none.epochCompleted(run(), 1, EpochState.fresh(), 1L, "cycle_end");

            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            when(client.isActive()).thenReturn(false);
            WorkflowAnalyticsEmitter inactive = new WorkflowAnalyticsEmitter();
            ReflectionTestUtils.setField(inactive, "postHog", client);
            inactive.epochStarted(run(), TriggerType.MANUAL, 1, false, ExecutionMode.AUTOMATIC);

            when(client.isActive()).thenReturn(true);
            WorkflowRunEntity noTenant = run();
            noTenant.setTenantId(" ");
            inactive.epochStarted(noTenant, TriggerType.MANUAL, 1, false, ExecutionMode.AUTOMATIC);
            inactive.epochCompleted(noTenant, 1, EpochState.fresh(), 1L, "cycle_end");
            verify(client, never()).capture(anyString(), anyString(), any());

            inactive.epochStarted(run(), TriggerType.MANUAL, 1, false, ExecutionMode.AUTOMATIC);
            verify(client).capture(eq("7"), eq("workflow_epoch_started"), any());
        }
    }
}
