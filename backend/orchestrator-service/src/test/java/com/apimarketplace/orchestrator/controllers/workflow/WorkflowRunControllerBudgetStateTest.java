package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod;
import com.apimarketplace.orchestrator.services.credit.WorkflowBudgetState;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * What {@code /state} seeds the run panel's spending gauge with.
 *
 * <p>This is the panel's ONLY figure until the first agent settles, so getting
 * it wrong is not cosmetic: the user watches a gauge that is absent, or wrong,
 * for as long as the run is quiet.
 *
 * <p>It is also the branch where the governance rule had drifted. Three call
 * sites went through {@code WorkflowBudgetState.appliesToRun()} and this one
 * re-derived "is this run governed" from {@code production_run_id} alone, which
 * is the exact shortcut that rule exists to replace. A workflow nobody ever
 * pinned still fires on a schedule, still has its spend counted, and is still
 * refused at the cap, so showing it no gauge at all was the most misleading of
 * the possible answers.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunController - /state seeds the spending gauge")
class WorkflowRunControllerBudgetStateTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowResumeService resumeService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowEpochService workflowEpochService;

    @InjectMocks private WorkflowRunController controller;

    private static final String RUN_ID = "run-budget-state";
    private static final String TENANT_ID = "tenant-A";
    private static final UUID WORKFLOW_ID = UUID.fromString("11112222-3333-4444-5555-666677778888");

    @BeforeEach
    void wireRunAndState() {
        WorkflowEntity workflow = new WorkflowEntity(TENANT_ID, "Nightly Digest", "user-a");
        workflow.setId(WORKFLOW_ID);
        workflow.setBudgetCredits(new BigDecimal("10"));
        workflow.setBudgetPeriodMode("monthly");

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(TENANT_ID);
        run.setWorkflow(workflow);

        lenient().when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        lenient().when(workflowEpochService.listEpochTimestamps(RUN_ID)).thenReturn(List.of());
        lenient().when(resumeService.reconstructStateForApi(RUN_ID)).thenReturn(new WorkflowRunState(
                RUN_ID, WORKFLOW_ID.toString(), RunStatus.RUNNING, ExecutionMode.AUTOMATIC,
                Instant.now(), null, Map.of(), List.of(), List.of(),
                Set.of(), Set.of(), Set.of(), Set.of(),
                new HashSet<>(), Map.of(), List.of()));
        lenient().when(stateSnapshotService.getSnapshot(RUN_ID))
                .thenReturn(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.empty());
    }

    private void budgetState(boolean productionRun, boolean editorRun, String spent, Instant periodStart) {
        when(workflowRunRepository.findBudgetStateByRunIdPublic(RUN_ID)).thenReturn(Optional.of(
                new WorkflowBudgetState(WORKFLOW_ID, productionRun, editorRun,
                        new BigDecimal("10"), "monthly", new BigDecimal(spent), periodStart)));
    }

    private static Instant thisMonth() {
        return WorkflowBudgetPeriod.periodStart("monthly", Instant.now());
    }

    @SuppressWarnings("unchecked")
    private static Object field(ResponseEntity<?> response, String key) {
        return ((Map<String, Object>) response.getBody()).get(key);
    }

    @Test
    @DisplayName("seeds the period spend for the production run")
    void seedsForProductionRun() {
        budgetState(true, false, "6", thisMonth());

        ResponseEntity<?> response = controller.getRunState(RUN_ID, false, TENANT_ID, null, null);

        assertThat((BigDecimal) field(response, "periodSpentCredits")).isEqualByComparingTo("6");
        assertThat(field(response, "budgetCredits")).isEqualTo(new BigDecimal("10"));
        assertThat(field(response, "budgetPeriodMode")).isEqualTo("monthly");
    }

    @Test
    @DisplayName("seeds it for an UNPINNED workflow's run too, which is the case the old shortcut hid")
    void seedsForUnpinnedRun() {
        // production_run_id is null, so the FK-only test said "not production"
        // and the panel showed no gauge - on a run that IS counted and WILL be
        // refused at the cap. The user could not see the stop coming.
        budgetState(false, false, "6", thisMonth());

        ResponseEntity<?> response = controller.getRunState(RUN_ID, false, TENANT_ID, null, null);

        assertThat((BigDecimal) field(response, "periodSpentCredits")).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("seeds the date the allowance starts again, so the panel can say WHEN")
    void seedsTheResetDate() {
        budgetState(true, false, "6", thisMonth());

        ResponseEntity<?> response = controller.getRunState(RUN_ID, false, TENANT_ID, null, null);

        assertThat(field(response, "budgetPeriodResetsAt"))
                .isEqualTo(WorkflowBudgetPeriod.nextPeriodStart("monthly", java.time.Instant.now()));
    }

    @Test
    @DisplayName("sends no figure for a BUILDER test fire, which never counts against the cap")
    void noFigureForBuilderRun() {
        budgetState(false, true, "6", thisMonth());

        ResponseEntity<?> response = controller.getRunState(RUN_ID, false, TENANT_ID, null, null);

        assertThat(field(response, "periodSpentCredits")).isNull();
        // The cap itself is still sent: the builder shows what the workflow is
        // capped at, it simply does not show this run eating into it.
        assertThat(field(response, "budgetCredits")).isEqualTo(new BigDecimal("10"));
    }

    @Test
    @DisplayName("rolls an expired period over, so a quiet run does not open on last month's figure")
    void rollsOverAnExpiredPeriod() {
        // The stored figure is only reset when the next cost is recorded, so a
        // workflow that stopped spending keeps last month's number. Seeding it
        // raw would paint the gauge red on a workflow that is free to run.
        budgetState(true, false, "50", thisMonth().minus(40, ChronoUnit.DAYS));

        ResponseEntity<?> response = controller.getRunState(RUN_ID, false, TENANT_ID, null, null);

        assertThat((BigDecimal) field(response, "periodSpentCredits")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("no budget state for the run leaves the figure out rather than guessing zero")
    void missingStateSendsNothing() {
        when(workflowRunRepository.findBudgetStateByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getRunState(RUN_ID, false, TENANT_ID, null, null);

        assertThat(field(response, "periodSpentCredits")).isNull();
    }
}
