package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod;
import com.apimarketplace.orchestrator.services.credit.WorkflowBudgetState;
import com.apimarketplace.orchestrator.services.events.WorkflowBudgetReachedEvent;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The epoch gate: the enforcement point that stops the NEXT trigger fire.
 *
 * <p>Distinct from the pre-flight guard in {@code AgentNode}, and both are
 * needed. The guard stops an epoch that is already looping on an agent; this
 * gate stops the schedule from opening a fresh one at 3am. Neither covers the
 * other's case.
 *
 * <p>The only test this gate previously had exercised a bare numeric
 * comparison and asserted nothing about which run gets refused, or about the
 * two things a refusal has to emit. That left the whole
 * decision, including the durable notification a sleeping user depends on,
 * covered by nothing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - the epoch gate of the workflow spending cap")
class ReusableTriggerServiceBudgetEpochGateTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowEventPublisher workflowEventPublisher;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ReusableTriggerService service;

    private static final String RUN_ID = "run-budget-gate-1";
    private static final UUID WORKFLOW_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                runRepository,
                mock(WorkflowRepository.class),
                mock(WorkflowPlanVersionRepository.class),
                mock(TriggerEpochManager.class),
                mock(WorkflowStreamingService.class),
                mock(WorkflowExecutionService.class),
                mock(com.apimarketplace.orchestrator.services.TriggerResolverService.class),
                mock(StateSnapshotService.class),
                mock(EpochConcurrencyLimiter.class),
                mock(com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue.class),
                mock(CreditBudgetService.class));
        ReflectionTestUtils.setField(service, "unifiedSignalService", mock(UnifiedSignalService.class));
        ReflectionTestUtils.setField(service, "snapshotService", mock(SnapshotService.class));
        ReflectionTestUtils.setField(service, "workflowEventPublisher", workflowEventPublisher);
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
    }

    private WorkflowEntity cappedWorkflow(String cap) {
        WorkflowEntity workflow = new WorkflowEntity("tenant-a", "Nightly Digest", "user-a");
        workflow.setId(WORKFLOW_ID);
        workflow.setBudgetCredits(new BigDecimal(cap));
        return workflow;
    }

    private void budgetState(boolean productionRun, boolean editorRun,
                             String cap, String mode, String spent, Instant periodStart) {
        when(runRepository.findBudgetStateByRunIdPublic(RUN_ID)).thenReturn(Optional.of(
                new WorkflowBudgetState(WORKFLOW_ID, productionRun, editorRun,
                        cap == null ? null : new BigDecimal(cap), mode,
                        spent == null ? null : new BigDecimal(spent), periodStart)));
    }

    private static Instant thisMonth() {
        return WorkflowBudgetPeriod.periodStart("monthly", Instant.now());
    }

    @Test
    @DisplayName("refuses the next fire once the period spend has reached the cap")
    void refusesWhenTheCapIsReached() {
        budgetState(true, false, "10", "monthly", "10", thisMonth());

        String refusal = service.refuseFireIfBudgetReached(RUN_ID, cappedWorkflow("10"));

        assertThat(refusal)
                .as("the message the run history shows, so it must name the figures")
                .contains("10").contains("no new epoch");
    }

    @Test
    @DisplayName("lets the fire through while the period spend is still under the cap")
    void allowsUnderTheCap() {
        budgetState(true, false, "10", "monthly", "9.99", thisMonth());

        assertThat(service.refuseFireIfBudgetReached(RUN_ID, cappedWorkflow("10"))).isNull();
        verifyNoInteractions(workflowEventPublisher, eventPublisher);
    }

    @Test
    @DisplayName("lets the fire through once the period has rolled over: the cap is an allowance, not a sentence")
    void rolloverReopensFiring() {
        // The defect this whole change exists to remove: the old gate compared
        // the RUN's lifetime cost, so a pinned workflow (one production run
        // accumulating epochs forever) crossed the cap once and then never fired
        // again, with nothing to clear it.
        budgetState(true, false, "10", "monthly", "50", thisMonth().minus(40, ChronoUnit.DAYS));

        assertThat(service.refuseFireIfBudgetReached(RUN_ID, cappedWorkflow("10"))).isNull();
    }

    @Test
    @DisplayName("never refuses a BUILDER test fire, even while production sits over the cap")
    void neverRefusesABuilderRun() {
        // The counter belongs to the WORKFLOW, so a builder run reads a figure
        // it never contributed to. Refusing here would freeze every test fire
        // the moment production hit its cap, which is the opposite of what the
        // settings screen promises.
        budgetState(false, true, "10", "monthly", "50", thisMonth());

        assertThat(service.refuseFireIfBudgetReached(RUN_ID, cappedWorkflow("10"))).isNull();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("refuses an UNPINNED workflow's scheduled fire: no pin does not mean no cap")
    void refusesUnpinnedScheduledFire() {
        // production_run_id is null and the run carries no __editorRun__ flag.
        // The chokepoint upstream refuses this shape before the gate sees it,
        // so this pins the gate's RULE, not a reachable hole: the gate asks
        // appliesToRun() and does not re-derive the answer.
        budgetState(false, false, "10", "monthly", "12", thisMonth());

        assertThat(service.refuseFireIfBudgetReached(RUN_ID, cappedWorkflow("10"))).isNotNull();
    }

    @Test
    @DisplayName("a refusal raises the DURABLE notification, naming the period it belongs to")
    void refusalRaisesTheDurableNotification() {
        // The toast only reaches somebody with the run open. A workflow that
        // stops firing at 3am needs a row in the bell, and this gate is the only
        // hook that fires when the user LOWERS a cap below what is already
        // spent: that crosses nothing, so the settle hook never announces it.
        budgetState(true, false, "10", "monthly", "12", thisMonth());

        service.refuseFireIfBudgetReached(RUN_ID, cappedWorkflow("10"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(WorkflowBudgetReachedEvent.class);
        WorkflowBudgetReachedEvent event = (WorkflowBudgetReachedEvent) captor.getValue();
        assertThat(event.workflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(event.spentCredits()).isEqualByComparingTo("12");
        assertThat(event.capCredits()).isEqualByComparingTo("10");
        assertThat(event.periodMode()).isEqualTo("monthly");
        // Dedup is (workflow, period): without the period in the key, one
        // notification would stand in for every month, forever.
        assertThat(event.sourceId()).contains(event.periodStart().toString());
    }

    @Test
    @DisplayName("the toast carries the period mode, so the message can say when firing resumes")
    void toastCarriesThePeriodMode() {
        budgetState(true, false, "10", "weekly", "12", WorkflowBudgetPeriod.periodStart("weekly", Instant.now()));

        service.refuseFireIfBudgetReached(RUN_ID, cappedWorkflow("10"));

        verify(workflowEventPublisher).emitRunBudgetBlocked(
                org.mockito.ArgumentMatchers.eq(RUN_ID),
                org.mockito.ArgumentMatchers.argThat(v -> v.compareTo(new BigDecimal("12")) == 0),
                org.mockito.ArgumentMatchers.argThat(v -> v.compareTo(new BigDecimal("10")) == 0),
                org.mockito.ArgumentMatchers.eq("weekly"));
    }

    @Test
    @DisplayName("a broken toast still refuses the fire, and still raises the notification")
    void toastFailureDoesNotSwallowTheRefusal() {
        // Refusing to spend is the job; telling the browser about it is not.
        budgetState(true, false, "10", "monthly", "12", thisMonth());
        doThrow(new RuntimeException("bus down")).when(workflowEventPublisher)
                .emitRunBudgetBlocked(anyString(), any(), any(), anyString());

        assertThat(service.refuseFireIfBudgetReached(RUN_ID, cappedWorkflow("10"))).isNotNull();
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("a broken notification still refuses the fire")
    void notificationFailureDoesNotSwallowTheRefusal() {
        budgetState(true, false, "10", "monthly", "12", thisMonth());
        doThrow(new RuntimeException("listener blew up")).when(eventPublisher).publishEvent(any(Object.class));

        assertThat(service.refuseFireIfBudgetReached(RUN_ID, cappedWorkflow("10"))).isNotNull();
    }

    @Test
    @DisplayName("no state for the run (deleted mid-fire) lets it through rather than refusing blind")
    void missingStateDoesNotRefuse() {
        when(runRepository.findBudgetStateByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());

        assertThat(service.refuseFireIfBudgetReached(RUN_ID, cappedWorkflow("10"))).isNull();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }
}
