package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.trigger.TriggerEpochManager;
import com.apimarketplace.orchestrator.validation.DAGIndependenceValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StepRerunService")
class StepRerunServiceTest {

    @Mock private WorkflowRunRepository mockRunRepository;
    @Mock private WorkflowStepDataRepository mockStepDataRepository;
    @Mock private WorkflowExecutionService mockExecutionService;
    @Mock private WorkflowStreamingService mockStreamingService;
    @Mock private WorkflowResumeService mockResumeService;
    @Mock private ExecutionContextManager mockContextManager;
    @Mock private StateSnapshotService mockStateSnapshotService;
    @Mock private TriggerEpochManager mockTriggerEpochManager;
    @Mock private DAGIndependenceValidator mockDagValidator;
    @Mock private UnifiedSignalService mockSignalService;
    @Mock private WorkflowExecution mockExecution;

    private StepRerunService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new StepRerunService(
            mockRunRepository, mockStepDataRepository, mockExecutionService,
            mockStreamingService, mockResumeService, mockContextManager,
            mockStateSnapshotService, mockTriggerEpochManager, mockDagValidator
        );
        // Inject @Lazy @Autowired(required=false) field via reflection
        setField("unifiedSignalService", mockSignalService);
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = StepRerunService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    // =========================================================================
    // HELPERS: Plan builders (real plans for WorkflowGraphBuilder.build)
    // =========================================================================

    /** trigger:start → mcp:step_a → mcp:step_b → mcp:step_c */
    private WorkflowPlan buildLinearPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "start", "type", "manual", "strategy", "single")));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "step_a", "type", "mcp"),
            Map.of("id", "s2", "label", "step_b", "type", "mcp"),
            Map.of("id", "s3", "label", "step_c", "type", "mcp")));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "mcp:step_a"),
            Map.of("from", "mcp:step_a", "to", "mcp:step_b"),
            Map.of("from", "mcp:step_b", "to", "mcp:step_c")));
        return WorkflowPlan.fromMap(data);
    }

    /**
     * A real while-loop shape, mirroring the prod workflow that surfaced the 2026-07-30 overrun:
     * trigger:start → mcp:before → core:wait_render (loop, maxIterations=60)
     *   body: → mcp:poll → mcp:check → back to core:wait_render (iterate)
     *   exit: → mcp:after
     * The back-edge SOURCE is mcp:check, several hops downstream of the loop controller.
     */
    private WorkflowPlan buildMultiHopLoopPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "start", "type", "manual", "strategy", "single")));
        data.put("mcps", List.of(
            Map.of("id", "s0", "label", "before", "type", "mcp"),
            Map.of("id", "s1", "label", "poll", "type", "mcp"),
            Map.of("id", "s2", "label", "check", "type", "mcp"),
            Map.of("id", "s3", "label", "after", "type", "mcp")));
        data.put("cores", List.of(
            Map.of("id", "core:wait_render", "label", "wait_render", "type", "loop",
                   "loopCondition", "true", "maxIterations", 60)));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "mcp:before"),
            Map.of("from", "mcp:before", "to", "core:wait_render"),
            Map.of("from", "core:wait_render:body", "to", "mcp:poll"),
            Map.of("from", "mcp:poll", "to", "mcp:check"),
            Map.of("from", "mcp:check", "to", "core:wait_render:iterate"),
            Map.of("from", "core:wait_render:exit", "to", "mcp:after")));
        return WorkflowPlan.fromMap(data);
    }

    /** mcp:step_a → mcp:step_b, with no trigger at all: nothing owns an epoch history. */
    private WorkflowPlan buildTriggerlessPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of());
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "step_a", "type", "mcp"),
            Map.of("id", "s2", "label", "step_b", "type", "mcp")));
        data.put("edges", List.of(Map.of("from", "mcp:step_a", "to", "mcp:step_b")));
        return WorkflowPlan.fromMap(data);
    }

    /** trigger:start → mcp:step_a → {mcp:step_b, mcp:step_c} */
    private WorkflowPlan buildBranchingPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "start", "type", "manual", "strategy", "single")));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "step_a", "type", "mcp"),
            Map.of("id", "s2", "label", "step_b", "type", "mcp"),
            Map.of("id", "s3", "label", "step_c", "type", "mcp")));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "mcp:step_a"),
            Map.of("from", "mcp:step_a", "to", "mcp:step_b"),
            Map.of("from", "mcp:step_a", "to", "mcp:step_c")));
        return WorkflowPlan.fromMap(data);
    }

    /** trigger:start → mcp:step_a → {mcp:step_b, mcp:step_c} → mcp:step_d → mcp:step_e */
    private WorkflowPlan buildMergePlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "start", "type", "manual", "strategy", "single")));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "step_a", "type", "mcp"),
            Map.of("id", "s2", "label", "step_b", "type", "mcp"),
            Map.of("id", "s3", "label", "step_c", "type", "mcp"),
            Map.of("id", "s4", "label", "step_d", "type", "mcp"),
            Map.of("id", "s5", "label", "step_e", "type", "mcp")));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "mcp:step_a"),
            Map.of("from", "mcp:step_a", "to", "mcp:step_b"),
            Map.of("from", "mcp:step_a", "to", "mcp:step_c"),
            Map.of("from", "mcp:step_b", "to", "mcp:step_d"),
            Map.of("from", "mcp:step_c", "to", "mcp:step_d"),
            Map.of("from", "mcp:step_d", "to", "mcp:step_e")));
        return WorkflowPlan.fromMap(data);
    }

    /** trigger:start → core:my_loop → (body: mcp:body_step → iterate), (exit: mcp:after_loop) */
    private WorkflowPlan buildLoopPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "start", "type", "manual", "strategy", "single")));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "body_step", "type", "mcp"),
            Map.of("id", "s2", "label", "after_loop", "type", "mcp")));
        data.put("cores", List.of(
            Map.of("id", "c1", "type", "loop", "label", "my_loop",
                "loopCondition", "true", "maxIterations", 10)));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "core:my_loop:body"),
            Map.of("from", "core:my_loop:body", "to", "mcp:body_step"),
            Map.of("from", "mcp:body_step", "to", "core:my_loop:iterate"),
            Map.of("from", "core:my_loop:exit", "to", "mcp:after_loop")));
        return WorkflowPlan.fromMap(data);
    }

    // =========================================================================
    // HELPERS: Common objects
    // =========================================================================

    private WorkflowRunEntity createRunEntity(RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic("run-1");
        run.setStatus(status);
        run.setUpdatedAt(Instant.now());
        run.setMetadata(new HashMap<>());
        return run;
    }

    private StateSnapshot snapshotWithCompleted(String... nodeIds) {
        return new StateSnapshot(
            3, 0L, Map.of(),
            Set.of(nodeIds),  // completed
            Set.of(),         // failed
            Set.of(),         // skipped
            Set.of(),         // running
            Set.of(),         // ready
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            Set.of(),         // awaitingSignal
            0L,               // totalDurationMs
            Instant.now()
        );
    }

    private StateSnapshot snapshotWithState(Set<String> completed, Set<String> failed,
                                             Set<String> awaiting, Set<String> ready) {
        return new StateSnapshot(
            3, 5L, Map.of(),
            completed, failed,
            Set.of(),     // skipped
            Set.of(),     // running
            ready,
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            awaiting,
            0L,           // totalDurationMs
            Instant.now()
        );
    }

    /**
     * Common mock setup for successful rerunFromStep invocation.
     */
    private void setupRerunMocks(WorkflowPlan plan, WorkflowRunEntity runEntity,
                                  StateSnapshot snapshot) {
        when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(runEntity));
        when(mockResumeService.refreshPlanFromWorkflowDefinition("run-1", false)).thenReturn(plan);
        when(mockStateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);

        String triggerId = plan.getTriggers().get(0).getNormalizedKey();
        lenient().when(mockDagValidator.findOwnerTrigger(eq(plan), anyString()))
            .thenReturn(Optional.of(triggerId));
        lenient().when(mockTriggerEpochManager.incrementSpawn(any(WorkflowRunEntity.class), eq(triggerId)))
            .thenReturn(1);
        lenient().when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", triggerId))
            .thenReturn(0);

        WorkflowRunState mockState = mock(WorkflowRunState.class);
        lenient().when(mockState.readySteps()).thenReturn(Set.of("mcp:step_a"));
        lenient().when(mockState.status()).thenReturn(RunStatus.RUNNING);
        lenient().when(mockResumeService.reconstructStateForApi("run-1")).thenReturn(mockState);

        lenient().when(mockContextManager.rebuildExecutionContext(eq("run-1"), any()))
            .thenReturn(mockExecution);
    }

    // =========================================================================
    // EXISTING TESTS: Record tests (unchanged)
    // =========================================================================

    @Nested
    @DisplayName("closeCycleAfterAutoExecution")
    class CloseCycleAfterAutoExecutionTests {

        private com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService mockSignalResume;

        @BeforeEach
        void injectFunnel() throws Exception {
            mockSignalResume = mock(com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService.class);
            setField("signalResumeService", mockSignalResume);
        }

        private WorkflowRunEntity runWithTriggers(List<Map<String, Object>> triggers) {
            Map<String, Object> planMap = new HashMap<>();
            planMap.put("id", "test-plan");
            planMap.put("tenant_id", "test-tenant");
            planMap.put("triggers", triggers);
            planMap.put("mcps", List.of(Map.of("id", "s1", "label", "step_a", "type", "mcp")));
            planMap.put("edges", List.of());
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            lenient().when(run.getPlan()).thenReturn(planMap);
            lenient().when(run.getTenantId()).thenReturn("test-tenant");
            lenient().when(run.getWorkflow()).thenReturn(null);
            return run;
        }

        @Test
        @DisplayName("Funnels a reusable owner trigger into performDeferredReset with the rerun epoch")
        void reusableOwnerFunnelsIntoDeferredReset() {
            WorkflowRunEntity run = runWithTriggers(List.of(
                Map.of("id", "t1", "label", "start", "type", "manual")));
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            service.closeCycleAfterAutoExecution("run-1", "trigger:start", 4);

            verify(mockSignalResume).performDeferredReset("run-1", "trigger:start", 4);
        }

        @Test
        @DisplayName("A label-less trigger resolves through the id fallback of the canonical parser")
        void labelLessTriggerResolvesThroughIdFallback() {
            // Regression: a hand-rolled label-only match would silently skip the close
            // for plans whose trigger has no label (normalized key falls back to the id).
            Map<String, Object> labelLess = new HashMap<>();
            labelLess.put("id", "hook1");
            labelLess.put("type", "webhook");
            WorkflowRunEntity run = runWithTriggers(List.of(labelLess));
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            service.closeCycleAfterAutoExecution("run-1", "trigger:hook1", 2);

            verify(mockSignalResume).performDeferredReset("run-1", "trigger:hook1", 2);
        }

        @Test
        @DisplayName("A non-reusable owner type skips the close (one-shot runs are never re-armed)")
        void nonReusableOwnerTypeSkipsTheClose() {
            WorkflowRunEntity run = runWithTriggers(List.of(
                Map.of("id", "t1", "label", "start", "type", "not_a_reusable_type")));
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            service.closeCycleAfterAutoExecution("run-1", "trigger:start", 4);

            verifyNoInteractions(mockSignalResume);
        }

        @Test
        @DisplayName("A null owner trigger id is a no-op")
        void nullOwnerTriggerIdIsNoOp() {
            service.closeCycleAfterAutoExecution("run-1", null, 4);

            verifyNoInteractions(mockSignalResume);
            verifyNoInteractions(mockRunRepository);
        }

        @Test
        @DisplayName("A missing run is a no-op")
        void missingRunIsNoOp() {
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.empty());

            service.closeCycleAfterAutoExecution("run-1", "trigger:start", 4);

            verifyNoInteractions(mockSignalResume);
        }

        @Test
        @DisplayName("A deferred-reset failure is contained (the rerun already succeeded)")
        void deferredResetFailureIsContained() {
            WorkflowRunEntity run = runWithTriggers(List.of(
                Map.of("id", "t1", "label", "start", "type", "manual")));
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
            doThrow(new RuntimeException("redis down"))
                .when(mockSignalResume).performDeferredReset(anyString(), anyString(), anyInt());

            assertDoesNotThrow(() -> service.closeCycleAfterAutoExecution("run-1", "trigger:start", 4));
        }
    }

    @Nested
    @DisplayName("RerunResult record")
    class RerunResultTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            StepRerunService.RerunResult result = new StepRerunService.RerunResult(
                "run-1",
                "mcp:api_call",
                2,
                1,
                Set.of("mcp:api_call", "mcp:process"),
                Set.of("mcp:api_call"),
                "RUNNING",
                0L,
                "trigger:start"
            );

            assertEquals("run-1", result.runId());
            assertEquals("mcp:api_call", result.stepId());
            assertEquals(2, result.epoch());
            assertEquals(1, result.spawn());
            assertEquals(2, result.resetSteps().size());
            assertTrue(result.resetSteps().contains("mcp:api_call"));
            assertTrue(result.resetSteps().contains("mcp:process"));
            assertEquals(Set.of("mcp:api_call"), result.readySteps());
            assertEquals("RUNNING", result.status());
            assertEquals("trigger:start", result.ownerTriggerId());
        }

        @Test
        @DisplayName("Should support empty sets")
        void shouldSupportEmptySets() {
            StepRerunService.RerunResult result = new StepRerunService.RerunResult(
                "run-1", "mcp:step", 1, 0, Set.of(), Set.of(), "PAUSED", 0L, "trigger:start"
            );

            assertTrue(result.resetSteps().isEmpty());
            assertTrue(result.readySteps().isEmpty());
        }

        @Test
        @DisplayName("Should be equal for same values")
        void shouldBeEqualForSameValues() {
            StepRerunService.RerunResult a = new StepRerunService.RerunResult(
                "run-1", "mcp:step", 1, 0, Set.of("mcp:step"), Set.of("mcp:step"), "RUNNING", 0L, "trigger:start"
            );
            StepRerunService.RerunResult b = new StepRerunService.RerunResult(
                "run-1", "mcp:step", 1, 0, Set.of("mcp:step"), Set.of("mcp:step"), "RUNNING", 0L, "trigger:start"
            );

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }
    }

    @Nested
    @DisplayName("StepAttemptRecord record")
    class StepAttemptRecordTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            Instant start = Instant.now();
            Instant end = start.plusMillis(500);
            UUID outputId = UUID.randomUUID();

            StepRerunService.StepAttemptRecord record = new StepRerunService.StepAttemptRecord(
                1, "COMPLETED", start, end, null, outputId
            );

            assertEquals(1, record.epoch());
            assertEquals("COMPLETED", record.status());
            assertEquals(start, record.startTime());
            assertEquals(end, record.endTime());
            assertNull(record.errorMessage());
            assertEquals(outputId, record.outputStorageId());
        }

        @Test
        @DisplayName("Should create failed attempt with error message")
        void shouldCreateFailedAttempt() {
            StepRerunService.StepAttemptRecord record = new StepRerunService.StepAttemptRecord(
                0, "FAILED", Instant.now(), Instant.now(), "Connection timeout", null
            );

            assertEquals("FAILED", record.status());
            assertEquals("Connection timeout", record.errorMessage());
            assertNull(record.outputStorageId());
        }

        @Test
        @DisplayName("Should be equal for same values")
        void shouldBeEqualForSameValues() {
            Instant time = Instant.now();
            UUID id = UUID.randomUUID();

            StepRerunService.StepAttemptRecord a = new StepRerunService.StepAttemptRecord(
                0, "COMPLETED", time, time, null, id
            );
            StepRerunService.StepAttemptRecord b = new StepRerunService.StepAttemptRecord(
                0, "COMPLETED", time, time, null, id
            );

            assertEquals(a, b);
        }
    }

    // =========================================================================
    // NEW TESTS: RerunFromStep - Happy Path
    // =========================================================================

    @Nested
    @DisplayName("rerunFromStep - happy path")
    class RerunFromStepHappyPath {

        @Test
        @DisplayName("should reset target and all downstream steps")
        void shouldResetTargetAndDownstreamSteps() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted(
                "trigger:start", "mcp:step_a", "mcp:step_b", "mcp:step_c");
            setupRerunMocks(plan, runEntity, snapshot);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_b");

            // Rerun from B → reset B and C (downstream), target is B
            assertTrue(result.resetSteps().contains("mcp:step_b"));
            assertTrue(result.resetSteps().contains("mcp:step_c"));
            // A should NOT be in reset set (upstream)
            assertFalse(result.resetSteps().contains("mcp:step_a"));
            assertEquals("mcp:step_b", result.stepId());
        }

        @Test
        @DisplayName("should return correct epoch, spawn, and status")
        void shouldReturnCorrectEpochSpawnAndStatus() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted("trigger:start", "mcp:step_a");
            setupRerunMocks(plan, runEntity, snapshot);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_a");

            assertEquals("run-1", result.runId());
            assertEquals(0, result.epoch());  // epoch stays the same
            assertEquals(1, result.spawn());  // spawn incremented
            assertEquals("running", result.status());  // RunStatus.RUNNING.getValue()
        }
    }

    // =========================================================================
    // NEW TESTS: RerunFromStep - Validation
    // =========================================================================

    @Nested
    @DisplayName("rerunFromStep - validation")
    class RerunFromStepValidation {

        @Test
        @DisplayName("should throw when run not found")
        void shouldThrowWhenRunNotFound() {
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_a"));
        }

        @Test
        @DisplayName("should throw when step not found in graph")
        void shouldThrowWhenStepNotFoundInGraph() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(runEntity));
            when(mockResumeService.refreshPlanFromWorkflowDefinition("run-1", false)).thenReturn(plan);

            // "mcp:nonexistent" is not in the plan
            assertThrows(IllegalArgumentException.class,
                () -> service.rerunFromStep("run-1", "mcp:nonexistent"));
        }

        @Test
        @DisplayName("should throw when step is in running state")
        void shouldThrowWhenStepIsRunning() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(runEntity));
            when(mockResumeService.refreshPlanFromWorkflowDefinition("run-1", false)).thenReturn(plan);

            // Step is neither completed, failed, awaiting, nor ready → running
            StateSnapshot snapshot = snapshotWithState(Set.of(), Set.of(), Set.of(), Set.of());
            when(mockStateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);

            assertThrows(IllegalStateException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_a"));
        }

        @Test
        @DisplayName("should allow rerun for completed step")
        void shouldAllowRerunForCompletedStep() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted("trigger:start", "mcp:step_a");
            setupRerunMocks(plan, runEntity, snapshot);

            // Should not throw
            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_a");
            assertNotNull(result);
        }

        @Test
        @DisplayName("should allow rerun for failed step")
        void shouldAllowRerunForFailedStep() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.FAILED);
            StateSnapshot snapshot = snapshotWithState(
                Set.of("trigger:start"), Set.of("mcp:step_a"), Set.of(), Set.of());
            setupRerunMocks(plan, runEntity, snapshot);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_a");
            assertNotNull(result);
        }
    }

    // =========================================================================
    // NEW TESTS: RerunFromStep - Signal Cancellation
    // =========================================================================

    @Nested
    @DisplayName("rerunFromStep - signal cancellation")
    class RerunFromStepSignalCancellation {

        @Test
        @DisplayName("should cancel active signals for the awaiting target via per-node cancel")
        void shouldCancelActiveSignalWhenAwaiting() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            StateSnapshot snapshot = snapshotWithState(
                Set.of("trigger:start"), Set.of(), Set.of("mcp:step_a"), Set.of());
            setupRerunMocks(plan, runEntity, snapshot);

            service.rerunFromStep("run-1", "mcp:step_a");

            // Surgical per-node cancel over the whole reset set (target included),
            // scoped to the rerun's current epoch (0 in this fixture);
            // cancelByDagAndEpoch is no longer used (it also killed parallel-branch
            // siblings' signals).
            verify(mockSignalService).cancelForNodes(eq("run-1"),
                argThat(set -> set.contains("mcp:step_a")), eq(0));
            verify(mockSignalService, never()).cancelByDagAndEpoch(any(), any(), anyInt());
        }

        @Test
        @DisplayName("should cancel stale signals of DOWNSTREAM reset nodes (regression: stale downstream approval survives rerun)")
        void shouldCancelDownstreamSignalsOnRerun() {
            // Regression: rerun from step_a resets {a, b, c}; if step_c is awaiting an
            // approval from the pre-rerun pass, that signal row stayed PENDING and a later
            // approval resumed onto the freshly reset DAG state (signals carry no spawn).
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            // Target step_a is COMPLETED; downstream step_c is awaiting a signal.
            StateSnapshot snapshot = snapshotWithState(
                Set.of("trigger:start", "mcp:step_a", "mcp:step_b"), Set.of(),
                Set.of("mcp:step_c"), Set.of());
            setupRerunMocks(plan, runEntity, snapshot);

            service.rerunFromStep("run-1", "mcp:step_a");

            // The per-node cancel must receive the FULL reset set, so step_c's stale
            // signal is cancelled even though the rerun target is step_a.
            verify(mockSignalService).cancelForNodes(eq("run-1"),
                argThat(set -> set.contains("mcp:step_a")
                    && set.contains("mcp:step_b")
                    && set.contains("mcp:step_c")),
                eq(0));
        }

        @Test
        @DisplayName("per-node cancel is invoked even for a completed target (covers downstream awaiting nodes)")
        void shouldInvokePerNodeCancelWhenCompleted() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted("trigger:start", "mcp:step_a");
            setupRerunMocks(plan, runEntity, snapshot);

            service.rerunFromStep("run-1", "mcp:step_a");

            // The reset set may contain downstream awaiting nodes regardless of the
            // target's own state - cancelForNodes is always called (no-op when no
            // active signals match) and the broad DAG+epoch cancel never is.
            verify(mockSignalService).cancelForNodes(eq("run-1"), anySet(), eq(0));
            verify(mockSignalService, never()).cancelByDagAndEpoch(any(), any(), anyInt());
        }

        @Test
        @DisplayName("should handle null signal service gracefully")
        void shouldHandleNullSignalService() throws Exception {
            // Inject null to simulate @Autowired(required=false)
            setField("unifiedSignalService", null);

            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.RUNNING);
            StateSnapshot snapshot = snapshotWithState(
                Set.of("trigger:start"), Set.of(), Set.of("mcp:step_a"), Set.of());
            setupRerunMocks(plan, runEntity, snapshot);

            // Should not throw NPE even though step is awaiting signal
            assertDoesNotThrow(() -> service.rerunFromStep("run-1", "mcp:step_a"));
        }

        /**
         * Prod fix 2026-07-30: while-loop iteration counts are shared across replicas in Redis,
         * keyed on (runId, EPOCH, edgeId) - epoch because it is the only coordinate persisted on
         * the signal and therefore stable under a live loop. A rerun deliberately keeps that epoch
         * (it increments the SPAWN instead), so the counter it must restart is the very one it
         * would otherwise read back: with terminated=1 from the previous attempt, the reset loop
         * would exit after zero iterations until the 24h TTL.
         */
        @Test
        @DisplayName("regression: rerun clears the shared loop counters of the back-edges it resets")
        void rerunClearsSharedLoopCountersOfResetBackEdges() throws Exception {
            var loopStore = mock(
                com.apimarketplace.orchestrator.execution.v2.state.RedisLoopIterationStore.class);
            setField("loopIterationStore", loopStore);

            // REAL loop topology, no spy: the guarantee under test is that the downstream walk
            // reaches the body TAIL (the node that owns the iterate edge) from an UPSTREAM rerun
            // target. That works because the graph builder adds the iterate edge as an ordinary
            // successor edge - not because of WorkflowNode.loopBody(), which nothing populates.
            WorkflowPlan plan = buildMultiHopLoopPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted(
                "trigger:start", "mcp:before", "core:wait_render", "mcp:poll", "mcp:check");
            setupRerunMocks(plan, runEntity, snapshot);

            service.rerunFromStep("run-1", "mcp:before");

            // maxIterations comes from the plan's loop core (60), not from a Redis read.
            verify(loopStore).clearBackEdge("run-1", 0, "mcp:check->core:wait_render:iterate", 60);
        }

        @Test
        @DisplayName("rerun does not touch loops it is not resetting")
        void rerunLeavesUnrelatedLoopsAlone() throws Exception {
            var loopStore = mock(
                com.apimarketplace.orchestrator.execution.v2.state.RedisLoopIterationStore.class);
            setField("loopIterationStore", loopStore);

            WorkflowPlan plan = buildMultiHopLoopPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted(
                "trigger:start", "mcp:before", "core:wait_render", "mcp:poll", "mcp:check", "mcp:after");
            setupRerunMocks(plan, runEntity, snapshot);

            // mcp:after is downstream of the loop exit - the loop itself is NOT reset.
            service.rerunFromStep("run-1", "mcp:after");

            verify(loopStore, never()).clearBackEdge(anyString(), anyInt(), anyString(), anyInt());
        }

        @Test
        @DisplayName("should handle a missing loop store gracefully (no Redis wired)")
        void shouldHandleNullLoopStore() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted("trigger:start", "mcp:step_a");
            setupRerunMocks(plan, runEntity, snapshot);

            // loopIterationStore is never injected in this fixture - the rerun must still work.
            assertDoesNotThrow(() -> service.rerunFromStep("run-1", "mcp:step_a"));
        }
    }

    // =========================================================================
    // NEW TESTS: RerunFromStep - Downstream Detection
    // =========================================================================

    @Nested
    @DisplayName("rerunFromStep - downstream detection")
    class RerunFromStepDownstreamDetection {

        @Test
        @DisplayName("should find full linear downstream chain")
        void shouldFindLinearDownstreamChain() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted(
                "trigger:start", "mcp:step_a", "mcp:step_b", "mcp:step_c");
            setupRerunMocks(plan, runEntity, snapshot);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_a");

            // Rerun from A → reset {A, B, C}
            assertTrue(result.resetSteps().contains("mcp:step_a"));
            assertTrue(result.resetSteps().contains("mcp:step_b"));
            assertTrue(result.resetSteps().contains("mcp:step_c"));
            assertEquals(3, result.resetSteps().size());
        }

        @Test
        @DisplayName("should find branching downstream")
        void shouldFindBranchingDownstream() {
            WorkflowPlan plan = buildBranchingPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted(
                "trigger:start", "mcp:step_a", "mcp:step_b", "mcp:step_c");
            setupRerunMocks(plan, runEntity, snapshot);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_a");

            // Rerun from A → reset {A, B, C} (both branches)
            assertTrue(result.resetSteps().contains("mcp:step_a"));
            assertTrue(result.resetSteps().contains("mcp:step_b"));
            assertTrue(result.resetSteps().contains("mcp:step_c"));
        }

        @Test
        @DisplayName("should include loop body steps in downstream")
        void shouldIncludeLoopBodyInDownstream() {
            WorkflowPlan plan = buildLoopPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted(
                "trigger:start", "core:my_loop", "mcp:body_step", "mcp:after_loop");
            setupRerunMocks(plan, runEntity, snapshot);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "core:my_loop");

            // Rerun from loop → should include body step and after_loop
            assertTrue(result.resetSteps().contains("core:my_loop"));
            assertTrue(result.resetSteps().contains("mcp:body_step"));
            assertTrue(result.resetSteps().contains("mcp:after_loop"));
        }

        @Test
        @DisplayName("should handle merge node in downstream")
        void shouldHandleMergeNode() {
            WorkflowPlan plan = buildMergePlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted(
                "trigger:start", "mcp:step_a", "mcp:step_b", "mcp:step_c",
                "mcp:step_d", "mcp:step_e");
            setupRerunMocks(plan, runEntity, snapshot);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_a");

            // Rerun from A → reset all downstream {A, B, C, D, E}
            assertTrue(result.resetSteps().contains("mcp:step_a"));
            assertTrue(result.resetSteps().contains("mcp:step_b"));
            assertTrue(result.resetSteps().contains("mcp:step_c"));
            assertTrue(result.resetSteps().contains("mcp:step_d"));
            assertTrue(result.resetSteps().contains("mcp:step_e"));
        }

        @Test
        @DisplayName("should not reset upstream steps")
        void shouldNotResetUpstreamSteps() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted(
                "trigger:start", "mcp:step_a", "mcp:step_b", "mcp:step_c");
            setupRerunMocks(plan, runEntity, snapshot);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_b");

            // Rerun from B → reset {B, C}; A is upstream and should NOT be reset
            assertTrue(result.resetSteps().contains("mcp:step_b"));
            assertTrue(result.resetSteps().contains("mcp:step_c"));
            assertFalse(result.resetSteps().contains("mcp:step_a"));
            assertFalse(result.resetSteps().contains("trigger:start"));
        }
    }

    // =========================================================================
    // NEW TESTS: RerunFromStep - Epoch/Spawn management
    // =========================================================================

    @Nested
    @DisplayName("rerunFromStep - epoch/spawn management")
    class RerunFromStepEpochSpawn {

        @Test
        @DisplayName("should increment spawn, not epoch")
        void shouldIncrementSpawnNotEpoch() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted("trigger:start", "mcp:step_a");
            setupRerunMocks(plan, runEntity, snapshot);

            service.rerunFromStep("run-1", "mcp:step_a");

            String triggerId = plan.getTriggers().get(0).getNormalizedKey();
            verify(mockTriggerEpochManager).incrementSpawn(any(WorkflowRunEntity.class), eq(triggerId));
            verify(mockTriggerEpochManager, never()).incrementEpoch(any(WorkflowRunEntity.class));
            verify(mockTriggerEpochManager, never()).incrementEpoch(any(WorkflowRunEntity.class), anyString());
        }

        @Test
        @DisplayName("should use DAG-scoped epoch when owner trigger found")
        void shouldUseDagScopedEpoch() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted("trigger:start", "mcp:step_a");
            setupRerunMocks(plan, runEntity, snapshot);

            String triggerId = plan.getTriggers().get(0).getNormalizedKey();
            when(mockDagValidator.findOwnerTrigger(plan, "mcp:step_a"))
                .thenReturn(Optional.of(triggerId));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", triggerId)).thenReturn(3);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_a");

            assertEquals(3, result.epoch());
            verify(mockTriggerEpochManager).getGlobalEpochForDag("run-1", triggerId);
        }

        @Test
        @DisplayName("should fallback to first trigger when findOwnerTrigger is empty")
        void shouldFallbackToFirstTrigger() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted("trigger:start", "mcp:step_a");
            setupRerunMocks(plan, runEntity, snapshot);

            // findOwnerTrigger returns empty → fallback to first trigger
            when(mockDagValidator.findOwnerTrigger(eq(plan), anyString()))
                .thenReturn(Optional.empty());

            String firstTriggerKey = plan.getTriggers().get(0).getNormalizedKey();
            when(mockTriggerEpochManager.incrementSpawn(any(WorkflowRunEntity.class), eq(firstTriggerKey)))
                .thenReturn(2);
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", firstTriggerKey)).thenReturn(0);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_a");

            // Should use first trigger's key as fallback
            assertEquals(2, result.spawn());
            verify(mockTriggerEpochManager).incrementSpawn(any(WorkflowRunEntity.class), eq(firstTriggerKey));
        }
    }

    // =========================================================================
    // NEW TESTS: RerunFromStep - State Reset
    // =========================================================================

    @Nested
    @DisplayName("rerunFromStep - state reset")
    class RerunFromStepStateReset {

        @Test
        @DisplayName("should call resetDagAndSetReady with correct arguments")
        void shouldCallResetDagAndSetReady() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted(
                "trigger:start", "mcp:step_a", "mcp:step_b", "mcp:step_c");
            setupRerunMocks(plan, runEntity, snapshot);

            service.rerunFromStep("run-1", "mcp:step_b");

            // resetDagAndSetReady(runId, stepsToReset, targetStepId, ownerTriggerId, epoch)
            verify(mockStateSnapshotService).resetDagAndSetReady(
                eq("run-1"),
                argThat(set -> set.contains("mcp:step_b") && set.contains("mcp:step_c")),
                eq("mcp:step_b"),
                eq("trigger:start"), anyInt());
        }

        @Test
        @DisplayName("should target READY marker at the owner trigger's DAG (multi-trigger correctness)")
        void shouldPassOwnerTriggerIdToResetDagAndSetReady() {
            // Regression: the 3-arg resetDagAndSetReady resolved the ready node's DAG via
            // the flat getDefaultTriggerId(), which picks an ARBITRARY real trigger in
            // multi-trigger snapshots. The rerun service must pass the owner trigger it
            // already resolved via DAGIndependenceValidator.findOwnerTrigger.
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted("trigger:start", "mcp:step_a");
            setupRerunMocks(plan, runEntity, snapshot);

            service.rerunFromStep("run-1", "mcp:step_a");

            verify(mockStateSnapshotService).resetDagAndSetReady(
                eq("run-1"), anySet(), eq("mcp:step_a"), eq("trigger:start"), anyInt());
            // The flat 3-arg variant must NOT be used when the owner trigger is known.
            verify(mockStateSnapshotService, never()).resetDagAndSetReady(
                    anyString(), anySet(), anyString());
        }

        @Test
        @DisplayName("should clear cached state before reconstructing, excluding STREAMING domain")
        void shouldClearCacheBeforeReconstruct() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted("trigger:start", "mcp:step_a");
            setupRerunMocks(plan, runEntity, snapshot);

            service.rerunFromStep("run-1", "mcp:step_a");

            // Verify order: clearCachedState BEFORE reconstructStateForApi
            InOrder inOrder = inOrder(mockResumeService);
            inOrder.verify(mockResumeService).clearCachedStateForRerun(eq("run-1"), anySet());
            inOrder.verify(mockResumeService).reconstructStateForApi("run-1");
        }

        @Test
        @DisplayName("rerun preserves STREAMING caches across rerun (regression: seq race on rerun, 2026-05-05)")
        void rerunExcludesStreamingDomain() {
            // Run is set to RUNNING immediately before clearCachedStateForRerun is called.
            // Same race window as the SBS refire bug: deferred publishes from the pre-rerun
            // state would collide with post-rerun seqs if WsEventSequencer.counters were
            // purged → frontend strict-< drops events → UI freezes.
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted("trigger:start", "mcp:step_a");
            setupRerunMocks(plan, runEntity, snapshot);

            service.rerunFromStep("run-1", "mcp:step_a");

            verify(mockResumeService).clearCachedStateForRerun(eq("run-1"),
                argThat(s -> s.contains(RunScopedCache.CacheDomain.STREAMING)));
        }

        @Test
        @DisplayName("should set run status to RUNNING")
        void shouldSetStatusRunning() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted("trigger:start", "mcp:step_a");
            setupRerunMocks(plan, runEntity, snapshot);

            service.rerunFromStep("run-1", "mcp:step_a");

            // Run entity should be set to RUNNING and saved
            assertEquals(RunStatus.RUNNING, runEntity.getStatus());
            verify(mockRunRepository, atLeastOnce()).save(runEntity);
        }

        @Test
        @DisplayName("should update metadata with rerun information")
        void shouldUpdateMetadata() {
            WorkflowPlan plan = buildLinearPlan();
            WorkflowRunEntity runEntity = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = snapshotWithCompleted("trigger:start", "mcp:step_a");
            setupRerunMocks(plan, runEntity, snapshot);

            service.rerunFromStep("run-1", "mcp:step_a");

            Map<String, Object> metadata = runEntity.getMetadata();
            assertEquals("mcp:step_a", metadata.get("lastRerunStepId"));
            assertNotNull(metadata.get("lastRerunTime"));
            assertEquals(1, metadata.get("lastRerunSpawn"));
            assertNotNull(metadata.get("resetSteps"));
        }
    }

    // =========================================================================
    // NEW TESTS: getStepAttemptCount
    // =========================================================================

    @Nested
    @DisplayName("getStepAttemptCount")
    class GetStepAttemptCount {

        @Test
        @DisplayName("should return count from repository")
        void shouldReturnCountFromRepo() {
            WorkflowStepDataEntity e1 = mock(WorkflowStepDataEntity.class);
            WorkflowStepDataEntity e2 = mock(WorkflowStepDataEntity.class);
            WorkflowStepDataEntity e3 = mock(WorkflowStepDataEntity.class);
            when(mockStepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc("run-1", "mcp:step_a"))
                .thenReturn(List.of(e1, e2, e3));

            int count = service.getStepAttemptCount("run-1", "mcp:step_a");

            assertEquals(3, count);
        }
    }

    // =========================================================================
    // NEW TESTS: getStepHistory
    // =========================================================================

    @Nested
    @DisplayName("getStepHistory")
    class GetStepHistory {

        @Test
        @DisplayName("should map entity fields to StepAttemptRecord correctly")
        void shouldMapToStepAttemptRecord() {
            Instant start = Instant.now().minusSeconds(60);
            Instant end = Instant.now();
            UUID outputId = UUID.randomUUID();

            WorkflowStepDataEntity entity = mock(WorkflowStepDataEntity.class);
            when(entity.getEpoch()).thenReturn(2);
            when(entity.getStatus()).thenReturn("COMPLETED");
            when(entity.getStartTime()).thenReturn(start);
            when(entity.getEndTime()).thenReturn(end);
            when(entity.getErrorMessage()).thenReturn(null);
            when(entity.getOutputStorageId()).thenReturn(outputId);

            when(mockStepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc("run-1", "mcp:step_a"))
                .thenReturn(List.of(entity));

            List<StepRerunService.StepAttemptRecord> history =
                service.getStepHistory("run-1", "mcp:step_a");

            assertEquals(1, history.size());
            StepRerunService.StepAttemptRecord record = history.get(0);
            assertEquals(2, record.epoch());
            assertEquals("COMPLETED", record.status());
            assertEquals(start, record.startTime());
            assertEquals(end, record.endTime());
            assertNull(record.errorMessage());
            assertEquals(outputId, record.outputStorageId());
        }

        @Test
        @DisplayName("should return empty list for no history")
        void shouldReturnEmptyForNoHistory() {
            when(mockStepDataRepository.findByRunIdAndNormalizedKeyOrderByEpochDesc("run-1", "mcp:step_a"))
                .thenReturn(List.of());

            List<StepRerunService.StepAttemptRecord> history =
                service.getStepHistory("run-1", "mcp:step_a");

            assertTrue(history.isEmpty());
        }
    }

    // =========================================================================
    // EPOCH RESOLUTION: rerun on a run whose execution cycle already closed
    // =========================================================================

    /**
     * A rerun must execute in the epoch that HOLDS THE OUTPUTS.
     *
     * <p>After an AUTO cycle runs to quiescence, the executed epoch is closed and pruned and
     * {@code prepareNextCycle} points {@code currentEpoch} at a dormant epoch staged for the
     * next fire. Following that dormant epoch (the pre-fix behaviour) leaves every
     * {@code {{upstream.output.x}}} resolving to empty while every node still reports success:
     * a green run with no data. Reopening the executed epoch is what makes "restart from a
     * node, reusing what already ran" actually reuse anything.
     */
    @Nested
    @DisplayName("Epoch resolution on rerun")
    class EpochResolutionTests {

        private static final String TRIGGER = "trigger:start";
        private static final int EXECUTED_EPOCH = 5;
        private static final int DORMANT_EPOCH = 6;

        @Mock private com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService mockEpochService;

        @BeforeEach
        void injectEpochService() throws Exception {
            setField("workflowEpochService", mockEpochService);
        }

        /** Cycle closed: executed epoch pruned, a dormant epoch staged with only the trigger ready. */
        private StateSnapshot closedCycleSnapshot(String... completedNodes) {
            DagState dag = new DagState(DORMANT_EPOCH, 0, 3,
                Map.of(DORMANT_EPOCH, EpochState.fresh().addReadyNode(TRIGGER)), Set.of());
            return dagSnapshot(dag, completedNodes);
        }

        /** Epoch still open (step-by-step, or an AUTO run mid-flight). */
        private StateSnapshot activeEpochSnapshot(String... completedNodes) {
            EpochState open = EpochState.fresh().markNodeCompleted(TRIGGER);
            for (String node : completedNodes) {
                open = open.markNodeCompleted(node);
            }
            DagState dag = new DagState(DORMANT_EPOCH, 0, 3,
                Map.of(DORMANT_EPOCH, open), Set.of(DORMANT_EPOCH));
            return dagSnapshot(dag, completedNodes);
        }

        /**
         * NodeCounts accumulate across epochs and survive the prune, so they are what makes the
         * rerun target read as terminal once its epoch is gone.
         */
        private StateSnapshot dagSnapshot(DagState dag, String... completedNodes) {
            return dagSnapshot(TRIGGER, dag, completedNodes);
        }

        private StateSnapshot dagSnapshot(String dagKey, DagState dag, String... completedNodes) {
            Map<String, StateSnapshot.NodeCounts> nodes = new HashMap<>();
            for (String node : completedNodes) {
                nodes.put(node, StateSnapshot.NodeCounts.zero().increment("COMPLETED"));
            }
            return new StateSnapshot(3, 5L, Map.of(dagKey, dag),
                null, null, null, null, null,
                nodes, new HashMap<>(),
                null, null, null, null, null, null);
        }

        private void headerExistsForExecutedEpoch() {
            when(mockEpochService.getFullEpochState("run-1", TRIGGER, EXECUTED_EPOCH))
                .thenReturn(EpochState.fresh().markNodeCompleted(TRIGGER).markNodeCompleted("mcp:step_a"));
        }

        @Test
        @DisplayName("Reopens the executed epoch when the cycle already closed")
        void reopensExecutedEpochWhenCycleClosed() {
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED);
            setupRerunMocks(buildLinearPlan(), run, closedCycleSnapshot("mcp:step_b"));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(EXECUTED_EPOCH);
            headerExistsForExecutedEpoch();

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_b");

            assertEquals(EXECUTED_EPOCH, result.epoch(),
                "the rerun must report the epoch that holds the outputs, not the staged one");
            verify(mockStateSnapshotService).reopenEpochResetDagAndSetReady(
                eq("run-1"), eq(TRIGGER), eq(EXECUTED_EPOCH),
                argThat(state -> state.getCompletedNodeIds().contains("mcp:step_a")),
                argThat(steps -> steps.contains("mcp:step_b") && steps.contains("mcp:step_c")),
                eq("mcp:step_b"));
            verify(mockStateSnapshotService, never())
                .resetDagAndSetReady(anyString(), anySet(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Scopes the signal cancel to the reopened epoch, not the dormant one")
        void cancelsSignalsInTheReopenedEpoch() {
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED);
            setupRerunMocks(buildLinearPlan(), run, closedCycleSnapshot("mcp:step_b"));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(EXECUTED_EPOCH);
            headerExistsForExecutedEpoch();

            service.rerunFromStep("run-1", "mcp:step_b");

            verify(mockSignalService).cancelForNodes(eq("run-1"), anySet(), eq(EXECUTED_EPOCH));
        }

        @Test
        @DisplayName("Persists the reopened epoch as the DAG's last epoch")
        void writesReopenedEpochToMetadata() {
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED);
            setupRerunMocks(buildLinearPlan(), run, closedCycleSnapshot("mcp:step_b"));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(EXECUTED_EPOCH);
            headerExistsForExecutedEpoch();

            service.rerunFromStep("run-1", "mcp:step_b");

            @SuppressWarnings("unchecked")
            Map<String, Object> dagLastEpoch = (Map<String, Object>) run.getMetadata().get("dagLastEpoch");
            assertEquals(EXECUTED_EPOCH, dagLastEpoch.get(TRIGGER),
                "context loading filters by this epoch - drifting it is what empties the templates");
        }

        @Test
        @DisplayName("Naming the epoch the default would have picked keeps writing the pointer")
        void namingTheDefaultEpochStillSyncsTheDagEpochPointer() {
            // The canvas restart on a SETTLED run lands exactly here: the cycle closed, so
            // DagState.currentEpoch points at the dormant staged fire, the last executed epoch
            // reads as "not the live one", and the client names it. The replay DOES reopen (the
            // snapshot is not on that epoch), and it is still the same epoch the epoch-less path
            // would have resolved to - so the pointer has to be written, exactly as the default
            // does. Keyed on the reopen alone this skipped the write and left the pointer stale.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            setupRerunMocks(buildLinearPlan(), run, closedCycleSnapshot("mcp:step_b"));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(EXECUTED_EPOCH);
            // Its own header, listing the TARGET: naming an epoch adds a check the default path
            // skips (the node must have run in it), and the shared helper's header stops at
            // mcp:step_a. That extra check is the one real difference between the two paths.
            //
            // Scope note: on a settled run the pointer already equals the epoch being named, so
            // writing and skipping leave the same value and this test cannot tell them apart on
            // its own. It pins the SHAPE (a reopen still writes); the case where the two differ
            // - the pointer strictly below the replayed epoch - is
            // namingTheCurrentEpochStillSyncsTheDagEpochPointer, and that one does fail
            // pre-change.
            when(mockEpochService.getFullEpochState("run-1", TRIGGER, EXECUTED_EPOCH)).thenReturn(
                EpochState.fresh().markNodeCompleted(TRIGGER)
                    .markNodeCompleted("mcp:step_a").markNodeCompleted("mcp:step_b"));

            service.rerunFromStep("run-1", "mcp:step_b", false, EXECUTED_EPOCH);

            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) run.getMetadata().get("dagLastEpoch");
            assertEquals(EXECUTED_EPOCH, after.get(TRIGGER),
                "context loading filters by this epoch - drifting it is what empties the templates");
        }

        @Test
        @DisplayName("An epoch-LESS rerun writes the pointer even when it syncs DOWN to the snapshot")
        void defaultPathWritesThePointerEvenSyncingDown() {
            // The default path resolves its own epoch and can land BELOW metadata.dagLastEpoch:
            // step 5b syncs to DagState.currentEpoch whenever the two disagree, and a targeted
            // older-epoch replay earlier on this run is exactly what moves DagState.currentEpoch
            // backward while the pointer stays high. Skipping the write there leaves execution
            // running in the synced epoch while RunContextService loads context from the stale
            // pointer: every upstream template reads another fire's rows, on a green run.
            //
            // So the skip is keyed on the CALLER having chosen an epoch as well as on the move
            // being backward. Keyed on the direction alone, this test fails.
            WorkflowRunEntity run = createRunEntity(RunStatus.RUNNING);
            Map<String, Object> pointer = new HashMap<>();
            pointer.put(TRIGGER, 8);
            run.getMetadata().put("dagLastEpoch", pointer);
            // Epoch 5 is ACTIVE, so this is a plain sync - no reopen, no header lookup.
            EpochState live = EpochState.fresh()
                .markNodeCompleted(TRIGGER)
                .markNodeCompleted("mcp:step_a")
                .markNodeCompleted("mcp:step_b");
            DagState dag = new DagState(5, 0, 6, Map.of(5, live), Set.of(5));
            Map<String, StateSnapshot.NodeCounts> nodes = new HashMap<>();
            for (String node : List.of("mcp:step_a", "mcp:step_b", "mcp:step_c")) {
                nodes.put(node, StateSnapshot.NodeCounts.zero().increment("COMPLETED"));
            }
            setupRerunMocks(buildLinearPlan(), run, new StateSnapshot(3, 5L, Map.of(TRIGGER, dag),
                null, null, null, null, null, nodes, new HashMap<>(),
                null, null, null, null, null, null));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(8);

            service.rerunFromStep("run-1", "mcp:step_b");

            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) run.getMetadata().get("dagLastEpoch");
            assertEquals(5, after.get(TRIGGER),
                "the epoch that will EXECUTE is 5 - context loading has to be filtered on it");
        }

        @Test
        @DisplayName("Falls back to the dormant epoch when no epoch header exists (legacy run)")
        void fallsBackToDormantEpochWithoutHeader() {
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED);
            setupRerunMocks(buildLinearPlan(), run, closedCycleSnapshot("mcp:step_b"));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(EXECUTED_EPOCH);
            when(mockEpochService.getFullEpochState("run-1", TRIGGER, EXECUTED_EPOCH)).thenReturn(null);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_b");

            assertEquals(DORMANT_EPOCH, result.epoch());
            verify(mockStateSnapshotService)
                .resetDagAndSetReady("run-1", Set.of("mcp:step_b", "mcp:step_c"), "mcp:step_b", TRIGGER, DORMANT_EPOCH);
            verifyNoReopen();
        }

        @Test
        @DisplayName("Falls back to the dormant epoch when the epoch service is not wired")
        void fallsBackWhenEpochServiceUnwired() throws Exception {
            setField("workflowEpochService", null);
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED);
            setupRerunMocks(buildLinearPlan(), run, closedCycleSnapshot("mcp:step_b"));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(EXECUTED_EPOCH);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_b");

            assertEquals(DORMANT_EPOCH, result.epoch());
            verifyNoReopen();
        }

        @Test
        @DisplayName("Sentinel DAG beside a real one: no reopen, but the epoch sync still applies")
        void neverReopensUnderTheSentinelDagBesideARealOne() {
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED);
            Map<String, StateSnapshot.NodeCounts> nodes = Map.of(
                "mcp:step_b", StateSnapshot.NodeCounts.zero().increment("COMPLETED"));
            StateSnapshot snapshot = new StateSnapshot(3, 5L, Map.of(
                    StateSnapshot.DEFAULT_TRIGGER_SENTINEL, new DagState(DORMANT_EPOCH, 0, 3,
                        Map.of(DORMANT_EPOCH, EpochState.fresh().addReadyNode(TRIGGER)), Set.of()),
                    TRIGGER, new DagState(DORMANT_EPOCH, 0, 3,
                        Map.of(DORMANT_EPOCH, EpochState.fresh()), Set.of())),
                null, null, null, null, null,
                nodes, new HashMap<>(),
                null, null, null, null, null, null);
            WorkflowPlan plan = buildLinearPlan();
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
            when(mockResumeService.refreshPlanFromWorkflowDefinition("run-1", false)).thenReturn(plan);
            when(mockStateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);
            when(mockDagValidator.findOwnerTrigger(eq(plan), anyString()))
                .thenReturn(Optional.of(StateSnapshot.DEFAULT_TRIGGER_SENTINEL));
            lenient().when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", StateSnapshot.DEFAULT_TRIGGER_SENTINEL))
                .thenReturn(EXECUTED_EPOCH);
            WorkflowRunState state = mock(WorkflowRunState.class);
            lenient().when(state.readySteps()).thenReturn(Set.of("mcp:step_b"));
            lenient().when(state.status()).thenReturn(RunStatus.RUNNING);
            lenient().when(mockResumeService.reconstructStateForApi("run-1")).thenReturn(state);
            lenient().when(mockContextManager.rebuildExecutionContext(eq("run-1"), any())).thenReturn(mockExecution);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_b");

            verifyNoReopen();
            verify(mockEpochService, never()).getFullEpochState(anyString(), anyString(), anyInt());
            // The sentinel guard must veto the REOPEN only. Suppressing the pre-existing sync
            // as well would send V2 execution back to writing completions into an inactive epoch.
            assertEquals(DORMANT_EPOCH, result.epoch(),
                "the epoch sync must still apply under the sentinel");
        }

        @Test
        @DisplayName("Sentinel-only run (no real DAG): reopening is still allowed")
        void reopensOnASentinelOnlyRun() {
            // resetDag only discards the sentinel's epochs when a REAL DAG coexists, so a
            // sentinel-only run has nothing to lose and must not be locked out of the fix.
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED);
            StateSnapshot snapshot = dagSnapshot(
                StateSnapshot.DEFAULT_TRIGGER_SENTINEL,
                new DagState(DORMANT_EPOCH, 0, 3,
                    Map.of(DORMANT_EPOCH, EpochState.fresh().addReadyNode(TRIGGER)), Set.of()),
                "mcp:step_b");
            WorkflowPlan plan = buildLinearPlan();
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
            when(mockResumeService.refreshPlanFromWorkflowDefinition("run-1", false)).thenReturn(plan);
            when(mockStateSnapshotService.getSnapshot("run-1")).thenReturn(snapshot);
            when(mockDagValidator.findOwnerTrigger(eq(plan), anyString()))
                .thenReturn(Optional.of(StateSnapshot.DEFAULT_TRIGGER_SENTINEL));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", StateSnapshot.DEFAULT_TRIGGER_SENTINEL))
                .thenReturn(EXECUTED_EPOCH);
            when(mockEpochService.getFullEpochState("run-1", StateSnapshot.DEFAULT_TRIGGER_SENTINEL, EXECUTED_EPOCH))
                .thenReturn(EpochState.fresh().markNodeCompleted("mcp:step_a"));
            WorkflowRunState state = mock(WorkflowRunState.class);
            lenient().when(state.readySteps()).thenReturn(Set.of("mcp:step_b"));
            lenient().when(state.status()).thenReturn(RunStatus.RUNNING);
            lenient().when(mockResumeService.reconstructStateForApi("run-1")).thenReturn(state);
            lenient().when(mockContextManager.rebuildExecutionContext(eq("run-1"), any())).thenReturn(mockExecution);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_b");

            assertEquals(EXECUTED_EPOCH, result.epoch());
            verify(mockStateSnapshotService).reopenEpochResetDagAndSetReady(
                eq("run-1"), eq(StateSnapshot.DEFAULT_TRIGGER_SENTINEL), eq(EXECUTED_EPOCH),
                any(), anySet(), eq("mcp:step_b"));
        }

        @Test
        @DisplayName("Takes the per-run advisory lock before deciding which epoch to use")
        void takesTheAdvisoryLockBeforeDeciding() throws Exception {
            var lockHelper = mock(com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHelper.class);
            setField("advisoryLockHelper", lockHelper);
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED);
            setupRerunMocks(buildLinearPlan(), run, closedCycleSnapshot("mcp:step_b"));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(EXECUTED_EPOCH);
            headerExistsForExecutedEpoch();

            service.rerunFromStep("run-1", "mcp:step_b");

            // The whole read-decide-write window must sit inside the lock, not just the write:
            // the run row and the plan refresh are read BEFORE the epoch is chosen, and the
            // refresh itself writes run.plan through its own transaction.
            InOrder order = inOrder(lockHelper, mockRunRepository, mockResumeService, mockStateSnapshotService);
            order.verify(lockHelper).acquireForRun("run-1");
            order.verify(mockRunRepository).findByRunIdPublic("run-1");
            order.verify(mockResumeService).refreshPlanFromWorkflowDefinition("run-1", false);
            order.verify(mockStateSnapshotService).getSnapshot("run-1");
        }

        @Test
        @DisplayName("Without the lock helper the rerun still resolves the right epoch")
        void degradesToTheRowLockWithoutTheHelper() {
            // The helper is optional; degrading must lose the serialisation, never the fix.
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED);
            setupRerunMocks(buildLinearPlan(), run, closedCycleSnapshot("mcp:step_b"));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(EXECUTED_EPOCH);
            headerExistsForExecutedEpoch();

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_b");

            assertEquals(EXECUTED_EPOCH, result.epoch());
            verify(mockStateSnapshotService).reopenEpochResetDagAndSetReady(
                eq("run-1"), eq(TRIGGER), eq(EXECUTED_EPOCH), any(), anySet(), eq("mcp:step_b"));
        }

        @Test
        @DisplayName("The reset closure is NOT symmetric around a loop, and the javadoc says so")
        void closureFromInsideALoopBodyDoesNotWrapAround() {
            // Pins the corrected javadoc on findAllDownstreamSteps. WorkflowGraphBuilder drops
            // back edges, so a walk STARTED inside the body stops at the body, while a walk from
            // the loop node covers it. The loop-counter clear keys off that set, so the day the
            // builder starts keeping back edges this must fail rather than silently reset a loop.
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED);
            setupRerunMocks(buildLoopPlan(), run, closedCycleSnapshot("mcp:body_step"));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(EXECUTED_EPOCH);
            headerExistsForExecutedEpoch();

            StepRerunService.RerunResult fromBody = service.rerunFromStep("run-1", "mcp:body_step");

            assertEquals(Set.of("mcp:body_step"), fromBody.resetSteps(),
                "a walk from inside the body must not wrap around through the iterate edge");
        }

        @Test
        @DisplayName("The closure from the LOOP node itself does cover the body and the exit")
        void closureFromTheLoopNodeCoversTheBody() {
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED);
            setupRerunMocks(buildLoopPlan(), run, closedCycleSnapshot("core:my_loop"));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(EXECUTED_EPOCH);
            headerExistsForExecutedEpoch();

            StepRerunService.RerunResult fromLoop = service.rerunFromStep("run-1", "core:my_loop");

            assertTrue(fromLoop.resetSteps().containsAll(Set.of("core:my_loop", "mcp:body_step", "mcp:after_loop")),
                "restarting the loop must replay its body and whatever follows the exit: " + fromLoop.resetSteps());
        }

        @Test
        @DisplayName("Refuses a run the user cancelled: reviving it is a re-trigger decision")
        void refusesACancelledRun() {
            // Enforced in the service, not the callers: the REST endpoint and the
            // restart_from_node agent action both reach the rerun through here, and a guard
            // that lives only in the React callback protects neither.
            WorkflowRunEntity run = createRunEntity(RunStatus.CANCELLED);
            setupRerunMocks(buildLinearPlan(), run, closedCycleSnapshot("mcp:step_b"));

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b"));

            assertTrue(error.getMessage().contains("cancelled"), error.getMessage());
            verify(mockStateSnapshotService, never())
                .resetDagAndSetReady(anyString(), anySet(), anyString(), anyString(), anyInt());
            verifyNoReopen();
        }

        @Test
        @DisplayName("Refuses a run that timed out")
        void refusesATimedOutRun() {
            WorkflowRunEntity run = createRunEntity(RunStatus.TIMEOUT);
            setupRerunMocks(buildLinearPlan(), run, closedCycleSnapshot("mcp:step_b"));

            assertThrows(IllegalStateException.class, () -> service.rerunFromStep("run-1", "mcp:step_b"));
        }

        @Test
        @DisplayName("A run that merely FINISHED stays restartable: that is the common case")
        void allowsAFinishedRun() {
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED);
            setupRerunMocks(buildLinearPlan(), run, closedCycleSnapshot("mcp:step_b"));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(EXECUTED_EPOCH);
            headerExistsForExecutedEpoch();

            assertEquals(EXECUTED_EPOCH, service.rerunFromStep("run-1", "mcp:step_b").epoch());
        }

        @Test
        @DisplayName("Refuses a node executing RIGHT NOW on an automatic run")
        void refusesANodeRunningOnAnAutomaticRun() {
            // The terminality check is satisfied by cumulative NodeCounts, which survive epoch
            // close, so a node that completed in an EARLIER epoch and is running in this one
            // reads as rerunnable. Resetting it races the in-flight execution, which still
            // writes its own completion: double execution, lost write.
            WorkflowRunEntity run = createRunEntity(RunStatus.RUNNING);
            run.setExecutionMode(ExecutionMode.AUTOMATIC);
            setupRerunMocks(buildLinearPlan(), run, runningNodeSnapshot("mcp:step_b"));

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b"));

            assertTrue(error.getMessage().contains("still executing"), error.getMessage());
        }

        @Test
        @DisplayName("Keeps the RUNNING escape hatch when the user drives the run by hand")
        void allowsANodeRunningOnASteppedRun() {
            // A stuck while-loop or a long agent: in step-by-step the user IS the scheduler.
            WorkflowRunEntity run = createRunEntity(RunStatus.RUNNING);
            run.setExecutionMode(ExecutionMode.STEP_BY_STEP);
            setupRerunMocks(buildLinearPlan(), run, runningNodeSnapshot("mcp:step_b"));

            assertDoesNotThrow(() -> service.rerunFromStep("run-1", "mcp:step_b"));
        }

        /** A node that completed in an earlier epoch AND is running in the current one. */
        private StateSnapshot runningNodeSnapshot(String nodeId) {
            EpochState live = new EpochState(
                Set.of(TRIGGER), Set.of(), Set.of(), Set.of(),
                Set.of(nodeId),   // runningNodeIds
                Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), Instant.now());
            DagState dag = new DagState(EXECUTED_EPOCH, 0, 3,
                Map.of(EXECUTED_EPOCH, live), Set.of(EXECUTED_EPOCH));
            // dagSnapshot seeds NodeCounts, which is what makes the node read as terminal
            // despite running - exactly the shape the new guard exists for.
            return dagSnapshot(dag, nodeId);
        }

        private void verifyNoReopen() {
            verify(mockStateSnapshotService, never()).reopenEpochResetDagAndSetReady(
                anyString(), anyString(), anyInt(), any(), anySet(), anyString());
        }

        @Test
        @DisplayName("Keeps syncing to the snapshot epoch while it is still ACTIVE (step-by-step)")
        void keepsSnapshotEpochWhileActive() {
            WorkflowRunEntity run = createRunEntity(RunStatus.PAUSED);
            setupRerunMocks(buildLinearPlan(), run, activeEpochSnapshot("mcp:step_b"));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(EXECUTED_EPOCH);

            StepRerunService.RerunResult result = service.rerunFromStep("run-1", "mcp:step_b");

            assertEquals(DORMANT_EPOCH, result.epoch(),
                "an active epoch is authoritative: syncing to it is the pre-existing behaviour");
            verify(mockStateSnapshotService)
                .resetDagAndSetReady("run-1", Set.of("mcp:step_b", "mcp:step_c"), "mcp:step_b", TRIGGER, DORMANT_EPOCH);
            verify(mockEpochService, never()).getFullEpochState(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Hands the reopen the epoch's REAL state, not a count-derived reconstruction")
        void passesTheDurableEpochStateDown() {
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED);
            setupRerunMocks(buildLinearPlan(), run, closedCycleSnapshot("mcp:step_b"));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(EXECUTED_EPOCH);
            EpochState durable = EpochState.fresh()
                .markNodeCompleted(TRIGGER)
                .markNodeCompleted("mcp:step_a")
                .markNodeSkipped("mcp:step_c");
            when(mockEpochService.getFullEpochState("run-1", TRIGGER, EXECUTED_EPOCH)).thenReturn(durable);

            service.rerunFromStep("run-1", "mcp:step_b");

            // SKIPPED marks are what let a downstream merge unblock; the cumulative NodeCounts
            // fallback cannot carry them, so the durable state has to travel down unchanged.
            verify(mockStateSnapshotService).reopenEpochResetDagAndSetReady(
                anyString(), anyString(), anyInt(),
                argThat(state -> state.getSkippedNodeIds().contains("mcp:step_c")),
                anySet(), anyString());
            verify(mockEpochService, times(1))
                .getFullEpochState("run-1", TRIGGER, EXECUTED_EPOCH);
        }
    }

    /**
     * The caller names WHICH fire of the run to replay.
     *
     * <p>A run fired N times keeps one epoch per fire, each with its own outputs. Without this
     * the rerun always lands on the most recent epoch, so repairing an older one is impossible:
     * the replay redoes work that was already correct, reports success, and the epoch that
     * actually failed is left exactly as it was.
     */
    @Nested
    @DisplayName("rerunFromStep - epoch chosen by the caller")
    class CallerChosenEpochTests {

        private static final String TRIGGER = "trigger:start";
        /** What the run last fired: the epoch the default resolution would pick. */
        private static final int LATEST_EPOCH = 20;
        /** An older fire, closed and pruned from the snapshot, whose header still holds its state. */
        private static final int OLD_EPOCH = 11;

        @Mock private com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService mockEpochService;

        @BeforeEach
        void injectEpochService() throws Exception {
            setField("workflowEpochService", mockEpochService);
        }

        /** The run settled: only the latest epoch is left in the snapshot, nothing is active. */
        private StateSnapshot settledSnapshot(Set<Integer> activeEpochs) {
            EpochState latest = EpochState.fresh()
                .markNodeCompleted(TRIGGER)
                .markNodeCompleted("mcp:step_a")
                .markNodeCompleted("mcp:step_b");
            DagState dag = new DagState(LATEST_EPOCH, 2, 20, Map.of(LATEST_EPOCH, latest), activeEpochs);
            Map<String, StateSnapshot.NodeCounts> nodes = new HashMap<>();
            for (String node : List.of("mcp:step_a", "mcp:step_b", "mcp:step_c")) {
                nodes.put(node, StateSnapshot.NodeCounts.zero().increment("COMPLETED"));
            }
            return new StateSnapshot(3, 5L, Map.of(TRIGGER, dag),
                null, null, null, null, null, nodes, new HashMap<>(),
                null, null, null, null, null, null);
        }

        private void latestEpochIsTwenty() {
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(LATEST_EPOCH);
        }

        /** The old fire, as workflow_epochs recorded it: the rerun target DID run in it. */
        private EpochState oldEpochHeader() {
            EpochState header = EpochState.fresh()
                .markNodeCompleted(TRIGGER)
                .markNodeCompleted("mcp:step_a")
                .markNodeCompleted("mcp:step_b");
            when(mockEpochService.getFullEpochState("run-1", TRIGGER, OLD_EPOCH)).thenReturn(header);
            return header;
        }

        @Test
        @DisplayName("Replays the epoch that was asked for, not the run's most recent one")
        void replaysTheChosenEpoch() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            latestEpochIsTwenty();
            oldEpochHeader();

            StepRerunService.RerunResult result =
                service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH);

            assertEquals(OLD_EPOCH, result.epoch(),
                "the reported epoch is what the caller drives the replay with - it must be the chosen one");
            verify(mockStateSnapshotService).reopenEpochResetDagAndSetReady(
                eq("run-1"), eq(TRIGGER), eq(OLD_EPOCH),
                argThat(state -> state.getCompletedNodeIds().contains("mcp:step_a")),
                argThat(steps -> steps.contains("mcp:step_b") && steps.contains("mcp:step_c")),
                eq("mcp:step_b"));
            verify(mockStateSnapshotService, never())
                .resetDagAndSetReady(anyString(), anySet(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Scopes the signal cancel to the chosen epoch")
        void scopesTheSignalCancelToTheChosenEpoch() {
            // Signal waits are keyed by epoch. Cancelling in epoch 20 would strand the old epoch's
            // waits and kill the latest fire's instead.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            latestEpochIsTwenty();
            oldEpochHeader();

            service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH);

            verify(mockSignalService).cancelForNodes(eq("run-1"), anySet(), eq(OLD_EPOCH));
        }

        @Test
        @DisplayName("Naming the epoch the DAG is ALREADY on syncs the pointer, exactly as omitting it does")
        void namingTheCurrentEpochStillSyncsTheDagEpochPointer() {
            // Reopening nothing means replaying exactly what the default resolution would have
            // replayed, so the same metadata has to be left behind. Skipping the sync on the
            // mere fact that an epoch was NAMED left dagLastEpoch stale here, and a later fire
            // of this DAG counts from it (nextEpoch = it + 1) - so it would reuse a number that
            // already has history. Reachable from any caller that names a DAG's current epoch,
            // which is what a client comparing against a RUN-wide epoch does on a multi-trigger
            // run: it takes the max across DAGs and cannot see that this DAG is behind it.
            // The pointer sits BELOW the epoch being replayed. That disagreement is a real
            // state, not a contrived one: the metadata pointer and the snapshot are two stores,
            // and rerunFromStep carries an explicit branch for exactly this ("Epoch mismatch:
            // dagLastEpoch=..., snapshot.currentEpoch=..."). It is also what makes the
            // assertion able to FAIL: with the pointer already equal to the replayed epoch,
            // skipping the write and performing it are indistinguishable.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            Map<String, Object> stalePointer = new HashMap<>();
            stalePointer.put(TRIGGER, OLD_EPOCH);
            run.getMetadata().put("dagLastEpoch", stalePointer);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            when(mockTriggerEpochManager.getGlobalEpochForDag("run-1", TRIGGER)).thenReturn(OLD_EPOCH);

            // LATEST_EPOCH is the epoch the DAG is already on, so nothing is reopened.
            service.rerunFromStep("run-1", "mcp:step_b", false, LATEST_EPOCH);

            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) run.getMetadata().get("dagLastEpoch");
            assertEquals(LATEST_EPOCH, after.get(TRIGGER),
                "no reopen happened, so the pointer must track the epoch that ran - forward from a "
                + "pointer that had fallen behind it");
            verify(mockStateSnapshotService, never()).reopenEpochResetDagAndSetReady(
                anyString(), anyString(), anyInt(), any(), anySet(), anyString());
        }

        @Test
        @DisplayName("Leaves metadata.dagLastEpoch alone: the next fire must keep counting forward")
        void doesNotRegressTheDagEpochPointer() {
            // dagLastEpoch is what the next cycle counts from (nextEpoch = it + 1). Pointing it at
            // epoch 11 would make the next fire reuse epoch 12, which already has history: its rows
            // would be rewritten under a higher spawn and supersede that fire's real outputs.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            Map<String, Object> dagLastEpoch = new HashMap<>();
            dagLastEpoch.put(TRIGGER, LATEST_EPOCH);
            run.getMetadata().put("dagLastEpoch", dagLastEpoch);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            latestEpochIsTwenty();
            oldEpochHeader();

            service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH);

            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) run.getMetadata().get("dagLastEpoch");
            assertEquals(LATEST_EPOCH, after.get(TRIGGER),
                "the pointer must still name the run's latest fire, not the replayed one");
        }

        @Test
        @DisplayName("An epoch the run never had is refused, with where to find the real ones")
        void refusesAnEpochThatDoesNotExist() {
            // Materialising it would give the replay an EMPTY epoch: every upstream template
            // resolves to nothing and every node still reports success.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            latestEpochIsTwenty();
            when(mockEpochService.getFullEpochState("run-1", TRIGGER, 99)).thenReturn(null);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b", false, 99));

            // "cannot be restarted on its own", not "does not exist": the same null header also
            // covers a run whose epoch record was never written, and that caller DOES see the
            // epoch in the listing (it is built from counter rows, which survive without a
            // header). Telling them it does not exist sends them back to a list that shows it.
            assertTrue(error.getMessage().contains("cannot be restarted on its own"), error.getMessage());
            assertTrue(error.getMessage().contains("no restorable record"), error.getMessage());
            assertTrue(error.getMessage().contains("get_run"), "the message must say where to read the real epochs");
            verify(mockStateSnapshotService, never()).reopenEpochResetDagAndSetReady(
                anyString(), anyString(), anyInt(), any(), anySet(), anyString());
            // A refusal has to be a true no-op, provably and not by relying on this method's
            // transaction rolling back: the spawn is a coordinate every later read and write of
            // the epoch in flight resolves through. Both bump methods are verified, or moving the
            // floor-aware one back above the validation would pass unnoticed.
            verify(mockTriggerEpochManager, never()).incrementSpawn(any(WorkflowRunEntity.class), anyString());
            verify(mockTriggerEpochManager, never())
                .incrementSpawnAtLeast(any(WorkflowRunEntity.class), anyString(), anyInt());
        }

        @Test
        @DisplayName("A negative epoch is refused instead of being read as 'unspecified'")
        void refusesANegativeEpoch() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            latestEpochIsTwenty();

            assertThrows(IllegalArgumentException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b", false, -1));
        }

        @Test
        @DisplayName("Refused while another epoch of the same trigger is still executing")
        void refusesWhileAnotherEpochIsLive() {
            // Reopening an old epoch moves DagState.currentEpoch BACKWARD, and every write that
            // resolves through "current" would then land in the replayed epoch instead of the live
            // one - a silent cross-epoch corruption, which is why this is a refusal and not a warning.
            WorkflowRunEntity run = createRunEntity(RunStatus.RUNNING);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of(LATEST_EPOCH)));
            latestEpochIsTwenty();

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH));

            assertTrue(error.getMessage().contains("still executing"), error.getMessage());
            verify(mockStateSnapshotService, never()).reopenEpochResetDagAndSetReady(
                anyString(), anyString(), anyInt(), any(), anySet(), anyString());
            // Same no-op requirement as the unknown-epoch refusal, and it matters more here: the
            // run has an epoch IN FLIGHT, whose reads and writes resolve through this counter.
            verify(mockTriggerEpochManager, never()).incrementSpawn(any(WorkflowRunEntity.class), anyString());
            verify(mockTriggerEpochManager, never())
                .incrementSpawnAtLeast(any(WorkflowRunEntity.class), anyString(), anyInt());
        }

        @Test
        @DisplayName("An epoch the snapshot still carries is reopened without restoring anything")
        void reopensASnapshotCarriedEpochWithoutARestore() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            DagState dag = new DagState(LATEST_EPOCH, 2, 20, Map.of(
                LATEST_EPOCH, EpochState.fresh().markNodeCompleted(TRIGGER),
                OLD_EPOCH, EpochState.fresh().markNodeCompleted(TRIGGER)
                    .markNodeCompleted("mcp:step_a").markNodeCompleted("mcp:step_b")),
                Set.of());
            Map<String, StateSnapshot.NodeCounts> nodes = new HashMap<>();
            nodes.put("mcp:step_b", StateSnapshot.NodeCounts.zero().increment("COMPLETED"));
            StateSnapshot snapshot = new StateSnapshot(3, 5L, Map.of(TRIGGER, dag),
                null, null, null, null, null, nodes, new HashMap<>(),
                null, null, null, null, null, null);
            setupRerunMocks(buildLinearPlan(), run, snapshot);
            latestEpochIsTwenty();

            service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH);

            // Nothing to restore, so no header read at all - and null travels down, which the
            // snapshot writer reads as "the epoch is already there".
            verify(mockEpochService, never()).getFullEpochState(anyString(), anyString(), anyInt());
            verify(mockStateSnapshotService).reopenEpochResetDagAndSetReady(
                eq("run-1"), eq(TRIGGER), eq(OLD_EPOCH), isNull(), anySet(), eq("mcp:step_b"));
        }

        @Test
        @DisplayName("Refused when the node hangs off the migration sentinel beside a real DAG")
        void refusesUnderTheSentinelBesideARealDag() {
            // resetDag() closes ALL of the sentinel's active epochs, so an epoch reopened for it
            // would be closed again in the same write and the replay would run against nothing.
            // Saying so beats reopening an epoch that cannot survive the next statement.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            DagState sentinel = new DagState(LATEST_EPOCH, 0, 1,
                Map.of(LATEST_EPOCH, EpochState.fresh()), Set.of());
            DagState real = new DagState(LATEST_EPOCH, 0, 1,
                Map.of(LATEST_EPOCH, EpochState.fresh()), Set.of());
            Map<String, StateSnapshot.NodeCounts> nodes = new HashMap<>();
            nodes.put("mcp:step_b", StateSnapshot.NodeCounts.zero().increment("COMPLETED"));
            StateSnapshot snapshot = new StateSnapshot(3, 5L,
                Map.of(StateSnapshot.DEFAULT_TRIGGER_SENTINEL, sentinel, TRIGGER, real),
                null, null, null, null, null, nodes, new HashMap<>(),
                null, null, null, null, null, null);
            setupRerunMocks(buildLinearPlan(), run, snapshot);
            when(mockDagValidator.findOwnerTrigger(any(), anyString()))
                .thenReturn(Optional.of(StateSnapshot.DEFAULT_TRIGGER_SENTINEL));

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH));

            assertTrue(error.getMessage().contains("Omit the epoch"), error.getMessage());
            verify(mockStateSnapshotService, never()).reopenEpochResetDagAndSetReady(
                anyString(), anyString(), anyInt(), any(), anySet(), anyString());
        }

        @Test
        @DisplayName("Omitting the epoch keeps the existing behaviour exactly")
        void omittedEpochKeepsTheDefault() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            latestEpochIsTwenty();

            StepRerunService.RerunResult result =
                service.rerunFromStep("run-1", "mcp:step_b", false, null);

            assertEquals(LATEST_EPOCH, result.epoch());
            verify(mockEpochService, never()).getFullEpochState(anyString(), anyString(), anyInt());
            verify(mockStateSnapshotService).resetDagAndSetReady(
                eq("run-1"), anySet(), eq("mcp:step_b"), eq(TRIGGER), eq(LATEST_EPOCH));
        }

        @Test
        @DisplayName("Naming the epoch the pointer already names replays there, and nowhere else")
        void namingTheCurrentEpochStillReplaysThere() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            latestEpochIsTwenty();

            StepRerunService.RerunResult result =
                service.rerunFromStep("run-1", "mcp:step_b", false, LATEST_EPOCH);

            assertEquals(LATEST_EPOCH, result.epoch());
            verify(mockStateSnapshotService).resetDagAndSetReady(
                eq("run-1"), anySet(), eq("mcp:step_b"), eq(TRIGGER), eq(LATEST_EPOCH));
        }

        @Test
        @DisplayName("A named epoch is honoured even when it equals the run's epoch pointer but not the DAG's")
        void namedEpochIsNotDivertedWhenItMatchesThePointer() {
            // The regression this pins: deciding "the caller chose an epoch" by comparing against
            // metadata.dagLastEpoch. The two pointers diverge (a previous targeted replay leaves
            // DagState.currentEpoch on the epoch it reopened), and naming the epoch dagLastEpoch
            // holds then read as "no choice made" and fell into the default resolution, which syncs
            // to the DAG's current epoch: the caller named 20, the replay happened in 11, the
            // response said 11, and none of the checks a named epoch owes ever ran.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            EpochState reopenedOld = EpochState.fresh().markNodeCompleted(TRIGGER).markNodeCompleted("mcp:step_b");
            EpochState latest = EpochState.fresh().markNodeCompleted(TRIGGER).markNodeCompleted("mcp:step_b");
            // DagState.currentEpoch is the OLD epoch (reopened and active), dagLastEpoch is 20.
            DagState dag = new DagState(OLD_EPOCH, 2, 20,
                Map.of(OLD_EPOCH, reopenedOld, LATEST_EPOCH, latest), Set.of(OLD_EPOCH));
            Map<String, StateSnapshot.NodeCounts> nodes = new HashMap<>();
            nodes.put("mcp:step_b", StateSnapshot.NodeCounts.zero().increment("COMPLETED"));
            StateSnapshot snapshot = new StateSnapshot(3, 5L, Map.of(TRIGGER, dag),
                null, null, null, null, null, nodes, new HashMap<>(),
                null, null, null, null, null, null);
            setupRerunMocks(buildLinearPlan(), run, snapshot);
            latestEpochIsTwenty();

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b", false, LATEST_EPOCH));

            // Epoch 11 is live, so naming 20 is REFUSED rather than silently executed in 11.
            assertTrue(error.getMessage().contains("still executing"), error.getMessage());
            assertTrue(error.getMessage().contains(String.valueOf(OLD_EPOCH)), error.getMessage());
            // The way out has to be callable: both actions named, both with the run id.
            assertTrue(error.getMessage().contains("stop_run") && error.getMessage().contains("get_run"),
                error.getMessage());
            verify(mockStateSnapshotService, never()).resetDagAndSetReady(
                anyString(), anySet(), anyString(), anyString(), anyInt());
            verify(mockStateSnapshotService, never()).reopenEpochResetDagAndSetReady(
                anyString(), anyString(), anyInt(), any(), anySet(), anyString());
        }

        @Test
        @DisplayName("Starts above the chosen epoch's own highest spawn, not the DAG counter")
        void startsAboveTheChosenEpochsOwnSpawn() {
            // dagCurrentSpawn is per DAG and every fire resets it to 0, so "the next spawn" is only
            // known-unused in the epoch the last fire opened. Replaying an older epoch on the DAG
            // counter can land on a spawn that epoch already used - and the supersede filter keeps
            // ALL rows tied at the max spawn, so the previous attempt's rows would count again
            // beside the new ones (a branch this replay deactivates keeps reporting COMPLETED).
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            latestEpochIsTwenty();
            oldEpochHeader();
            when(mockStepDataRepository.findMaxSpawnForEpoch("run-1", OLD_EPOCH)).thenReturn(3);

            service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH);

            verify(mockTriggerEpochManager).incrementSpawnAtLeast(any(WorkflowRunEntity.class), eq(TRIGGER), eq(4));
            verify(mockTriggerEpochManager, never()).incrementSpawn(any(WorkflowRunEntity.class), anyString());
        }

        @Test
        @DisplayName("An epoch merely STAGED for the next fire is refused, not replayed into")
        void refusesAnEpochStagedForTheNextFire() {
            // prepareNextCycle leaves an epoch in the snapshot holding nothing but the trigger's
            // ready marker, numbered dagLastEpoch + 1 - the number the NEXT fire will open.
            // Replaying in it resolves every upstream template to nothing AND leaves rows in a
            // fire that has not happened yet.
            int stagedEpoch = LATEST_EPOCH + 1;
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            DagState dag = new DagState(stagedEpoch, 0, 20, Map.of(
                LATEST_EPOCH, EpochState.fresh().markNodeCompleted(TRIGGER).markNodeCompleted("mcp:step_b"),
                stagedEpoch, EpochState.fresh().addReadyNode(TRIGGER)), Set.of());
            Map<String, StateSnapshot.NodeCounts> nodes = new HashMap<>();
            nodes.put("mcp:step_b", StateSnapshot.NodeCounts.zero().increment("COMPLETED"));
            StateSnapshot snapshot = new StateSnapshot(3, 5L, Map.of(TRIGGER, dag),
                null, null, null, null, null, nodes, new HashMap<>(),
                null, null, null, null, null, null);
            setupRerunMocks(buildLinearPlan(), run, snapshot);
            latestEpochIsTwenty();
            when(mockEpochService.getFullEpochState("run-1", TRIGGER, stagedEpoch)).thenReturn(null);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b", false, stagedEpoch));

            // The staged epoch has no header either (staging writes none), so it lands on the
            // same refusal as an epoch the run never had.
            assertTrue(error.getMessage().contains("cannot be restarted on its own"), error.getMessage());
            verify(mockStateSnapshotService, never()).resetDagAndSetReady(
                anyString(), anySet(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("An epoch whose header exists but recorded nothing is refused, in its own words")
        void refusesAnEpochWhoseHeaderIsEmpty() {
            // openEpoch dual-writes a header as soon as a fire starts, so get_run lists an epoch
            // that has executed nothing yet. Refusing it with "does not exist" would send the
            // agent back to a list that contains it, which is why this case has its own message.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            latestEpochIsTwenty();
            when(mockEpochService.getFullEpochState("run-1", TRIGGER, OLD_EPOCH))
                .thenReturn(EpochState.fresh());

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH));

            assertTrue(error.getMessage().contains("has not executed anything"), error.getMessage());
            assertFalse(error.getMessage().contains("does not exist"),
                "an epoch get_run lists must not be described as non-existent");
        }

        @Test
        @DisplayName("Naming an epoch is honoured, not refused, when nothing has to be reopened")
        void namingTheDagsOwnEpochSkipsTheReopenGuards() {
            // The guards exist for reopening. Naming the epoch the DAG already sits on reopens
            // nothing and is the same work as omitting the parameter, so refusing it would punish
            // a caller for following the help. A stepped run is the sharpest case: omitting the
            // epoch works there, so naming the current one must too.
            WorkflowRunEntity run = createRunEntity(RunStatus.PAUSED);
            run.setExecutionMode(ExecutionMode.STEP_BY_STEP);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of(LATEST_EPOCH)));
            latestEpochIsTwenty();

            StepRerunService.RerunResult result =
                service.rerunFromStep("run-1", "mcp:step_b", false, LATEST_EPOCH);

            assertEquals(LATEST_EPOCH, result.epoch());
            verify(mockStateSnapshotService).resetDagAndSetReady(
                eq("run-1"), anySet(), eq("mcp:step_b"), eq(TRIGGER), eq(LATEST_EPOCH));
        }

        @Test
        @DisplayName("A named epoch that needs no reopen is executed even with the pointers divergent")
        void namedEpochIsRoutedNotJustBlockedWhenPointersDiverge() {
            // The counterpart of the refusal case: same divergence (DagState.currentEpoch on the
            // old epoch, dagLastEpoch on 20) but nothing active, and the caller names the OLD
            // epoch, which is the one the DAG is on. Proves the fix ROUTES the request rather than
            // merely blocking the dangerous shape.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            EpochState old = EpochState.fresh().markNodeCompleted(TRIGGER).markNodeCompleted("mcp:step_b");
            DagState dag = new DagState(OLD_EPOCH, 2, 20, Map.of(OLD_EPOCH, old), Set.of());
            Map<String, StateSnapshot.NodeCounts> nodes = new HashMap<>();
            nodes.put("mcp:step_b", StateSnapshot.NodeCounts.zero().increment("COMPLETED"));
            StateSnapshot snapshot = new StateSnapshot(3, 5L, Map.of(TRIGGER, dag),
                null, null, null, null, null, nodes, new HashMap<>(),
                null, null, null, null, null, null);
            setupRerunMocks(buildLinearPlan(), run, snapshot);
            latestEpochIsTwenty();

            StepRerunService.RerunResult result =
                service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH);

            assertEquals(OLD_EPOCH, result.epoch());
            verify(mockStateSnapshotService).resetDagAndSetReady(
                eq("run-1"), anySet(), eq("mcp:step_b"), eq(TRIGGER), eq(OLD_EPOCH));
            verify(mockEpochService, never()).getFullEpochState(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("A pruned epoch is restored from its header even when it is already the current one")
        void restoresAPrunedEpochEvenWithNothingToReopen() {
            // The shape a "symmetry" cleanup would break: the DAG still points AT the epoch (so
            // nothing has to be reopened) but the snapshot no longer carries its state, because the
            // prune ran before currentEpoch advanced. The restore only happens on the reopen path,
            // so that path must be taken regardless - otherwise the replay runs against an epoch
            // with no state and every upstream template resolves empty on a green run.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            DagState dag = new DagState(OLD_EPOCH, 2, 20,
                Map.of(LATEST_EPOCH, EpochState.fresh().markNodeCompleted(TRIGGER)), Set.of());
            Map<String, StateSnapshot.NodeCounts> nodes = new HashMap<>();
            nodes.put("mcp:step_b", StateSnapshot.NodeCounts.zero().increment("COMPLETED"));
            StateSnapshot snapshot = new StateSnapshot(3, 5L, Map.of(TRIGGER, dag),
                null, null, null, null, null, nodes, new HashMap<>(),
                null, null, null, null, null, null);
            setupRerunMocks(buildLinearPlan(), run, snapshot);
            latestEpochIsTwenty();
            oldEpochHeader();

            StepRerunService.RerunResult result =
                service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH);

            assertEquals(OLD_EPOCH, result.epoch());
            verify(mockStateSnapshotService).reopenEpochResetDagAndSetReady(
                eq("run-1"), eq(TRIGGER), eq(OLD_EPOCH),
                argThat(state -> state.getCompletedNodeIds().contains("mcp:step_a")),
                anySet(), eq("mcp:step_b"));
            verify(mockStateSnapshotService, never())
                .resetDagAndSetReady(anyString(), anySet(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Refused when the node was SKIPPED in the chosen epoch, naming the fix")
        void refusesANodeSkippedInThatEpoch() {
            // The run-wide check passes (the node completed in ANOTHER fire), which is exactly why
            // the rule has to be re-applied inside the epoch that was named.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            latestEpochIsTwenty();
            when(mockEpochService.getFullEpochState("run-1", TRIGGER, OLD_EPOCH)).thenReturn(
                EpochState.fresh().markNodeCompleted(TRIGGER).markNodeSkipped("mcp:step_b"));

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH));

            assertTrue(error.getMessage().contains("branch was not taken"), error.getMessage());
            assertTrue(error.getMessage().contains("decision node"), "the message must say what to do instead");
            verify(mockStateSnapshotService, never()).reopenEpochResetDagAndSetReady(
                anyString(), anyString(), anyInt(), any(), anySet(), anyString());
        }

        @Test
        @DisplayName("Refused when the node never ran in the chosen epoch at all")
        void refusesANodeThatNeverRanInThatEpoch() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            latestEpochIsTwenty();
            when(mockEpochService.getFullEpochState("run-1", TRIGGER, OLD_EPOCH)).thenReturn(
                EpochState.fresh().markNodeCompleted(TRIGGER).markNodeCompleted("mcp:step_a"));

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH));

            assertTrue(error.getMessage().contains("recorded no result"), error.getMessage());
            assertTrue(error.getMessage().contains("get_run"),
                "the message must name an action the agent can actually call, with its run id");
            assertTrue(error.getMessage().contains("run-1"), error.getMessage());
        }

        @Test
        @DisplayName("Refused on a stepped run: nothing there would ever close the reopened epoch")
        void refusesOnAStepByStepRun() {
            WorkflowRunEntity run = createRunEntity(RunStatus.PAUSED);
            run.setExecutionMode(ExecutionMode.STEP_BY_STEP);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            latestEpochIsTwenty();

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH));

            assertTrue(error.getMessage().contains("stepped by hand"), error.getMessage());
            verify(mockEpochService, never()).getFullEpochState(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("A plan with no trigger is refused before an epoch can even be addressed")
        void aTriggerlessPlanIsRefusedUpstream() {
            // Documents where the "no DAG owns this node" case actually lands: the graph build
            // rejects a plan with no trigger, so the guard for a null owner inside the epoch
            // resolution is defence in depth rather than a reachable path. Worth pinning, because
            // the alternative reading (the guard is dead code, delete it) is wrong: ownerTriggerId
            // is nullable by type and the resolution must not dereference it.
            // Wired by hand: the shared fixture assumes the plan has a trigger, which is the very
            // thing this case does not have.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
            when(mockResumeService.refreshPlanFromWorkflowDefinition("run-1", false))
                .thenReturn(buildTriggerlessPlan());

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH));

            assertTrue(error.getMessage().contains("No trigger node found"), error.getMessage());
            verify(mockEpochService, never()).getFullEpochState(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Refused when the epoch history is not wired: a chosen epoch cannot be verified")
        void refusesWhenTheEpochServiceIsUnwired() throws Exception {
            // The default path degrades with a warning here; a NAMED epoch must not, because there
            // is then no way to tell an epoch that exists from one that never ran.
            setField("workflowEpochService", null);
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER);
            setupRerunMocks(buildLinearPlan(), run, settledSnapshot(Set.of()));
            latestEpochIsTwenty();

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.rerunFromStep("run-1", "mcp:step_b", false, OLD_EPOCH));

            assertTrue(error.getMessage().contains("not available"), error.getMessage());
        }
    }
}
