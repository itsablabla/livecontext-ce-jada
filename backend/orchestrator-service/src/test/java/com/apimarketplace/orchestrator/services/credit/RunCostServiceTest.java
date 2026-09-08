package com.apimarketplace.orchestrator.services.credit;

import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunCostService - accumulate agent cost onto a run + hand it to the workflow's period budget")
class RunCostServiceTest {

    private static final UUID WORKFLOW_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowPeriodSpendAccumulator periodSpendAccumulator;
    @Mock private WorkflowEventPublisher eventPublisher;

    private MeterRegistry meterRegistry;
    private RunCostService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new RunCostService(runRepository, periodSpendAccumulator, eventPublisher, meterRegistry);
    }

    /**
     * @param productionRun the run IS the workflow's production run (FK)
     * @param editorRun     the run carries {@code __editorRun__}
     */
    private static WorkflowBudgetState state(boolean productionRun, boolean editorRun, String cap) {
        return new WorkflowBudgetState(
                WORKFLOW_ID, productionRun, editorRun,
                cap == null ? null : new BigDecimal(cap),
                "monthly", BigDecimal.ZERO,
                WorkflowBudgetPeriod.periodStart("monthly", Instant.now()));
    }

    private void settleLands(String total, String epochCost) {
        when(runRepository.incrementRunCost(any(), any(), any(), any())).thenReturn(1);
        when(runRepository.findCostCreditsByRunIdPublic("run-1")).thenReturn(Optional.of(new BigDecimal(total)));
        when(runRepository.findEpochCostByRunIdPublic(any(), any()))
                .thenReturn(Optional.of(new BigDecimal(epochCost)));
    }

    @Test
    @DisplayName("increments the run + epoch bucket and emits the fresh totals")
    void incrementsAndEmits() {
        when(runRepository.incrementRunCost("run-1", "org-1", "2", new BigDecimal("0.50"))).thenReturn(1);
        when(runRepository.findCostCreditsByRunIdPublic("run-1")).thenReturn(Optional.of(new BigDecimal("1.50")));
        when(runRepository.findEpochCostByRunIdPublic("run-1", "2")).thenReturn(Optional.of(new BigDecimal("0.50")));
        when(runRepository.findBudgetStateByRunIdPublic("run-1"))
                .thenReturn(Optional.of(state(false, true, "10")));

        service.recordAgentCost("run-1", "org-1", 2, new BigDecimal("0.50"));

        verify(runRepository).incrementRunCost("run-1", "org-1", "2", new BigDecimal("0.50"));
        verify(eventPublisher).emitRunCost("run-1", 2,
                new BigDecimal("0.50"), new BigDecimal("1.50"), null, new BigDecimal("10"));
    }

    @Test
    @DisplayName("emits a null cap when the workflow has none set")
    void emitsNullBudgetWhenUnset() {
        when(runRepository.incrementRunCost(eq("run-1"), isNull(), eq("1"), any())).thenReturn(1);
        when(runRepository.findCostCreditsByRunIdPublic("run-1")).thenReturn(Optional.of(new BigDecimal("0.25")));
        when(runRepository.findEpochCostByRunIdPublic("run-1", "1")).thenReturn(Optional.of(new BigDecimal("0.25")));
        when(runRepository.findBudgetStateByRunIdPublic("run-1")).thenReturn(Optional.empty());

        service.recordAgentCost("run-1", null, 1, new BigDecimal("0.25"));

        verify(eventPublisher).emitRunCost("run-1", 1,
                new BigDecimal("0.25"), new BigDecimal("0.25"), null, null);
    }

    // ─── WHOSE spend counts. This is the same predicate that decides whose run
    //     gets refused, so a run can never be refused over spend it was not
    //     allowed to contribute. ───

    @Test
    @DisplayName("the production run feeds the period budget, and the event carries the figure it returns")
    void productionRunFeedsPeriodBudget() {
        settleLands("4", "1");
        when(runRepository.findBudgetStateByRunIdPublic("run-1"))
                .thenReturn(Optional.of(state(true, false, "10")));
        when(periodSpendAccumulator.accumulate(eq("run-1"), any(), eq(new BigDecimal("1"))))
                .thenReturn(new BigDecimal("4"));

        service.recordAgentCost("run-1", "org-1", 1, new BigDecimal("1"));

        verify(periodSpendAccumulator).accumulate(eq("run-1"), any(), eq(new BigDecimal("1")));
        verify(eventPublisher).emitRunCost("run-1", 1,
                new BigDecimal("1"), new BigDecimal("4"), new BigDecimal("4"), new BigDecimal("10"));
    }

    @Test
    @DisplayName("a BUILDER test fire never touches the period budget: a test must not eat the allowance")
    void builderRunDoesNotFeedPeriodBudget() {
        settleLands("2", "2");
        when(runRepository.findBudgetStateByRunIdPublic("run-1"))
                .thenReturn(Optional.of(state(false, true, "10")));

        service.recordAgentCost("run-1", "org-1", 1, new BigDecimal("2"));

        verifyNoInteractions(periodSpendAccumulator);
        verify(eventPublisher).emitRunCost("run-1", 1,
                new BigDecimal("2"), new BigDecimal("2"), null, new BigDecimal("10"));
    }

    @Test
    @DisplayName("counting uses the SAME predicate as refusing, not the production FK on its own")
    void unpinnedNonEditorRunStillCounts() {
        // productionRun=false (no pin) but no __editorRun__ flag either, so
        // appliesToRun() says the cap governs it. What matters here is that
        // COUNTING uses the very same predicate as REFUSING: a run must never
        // be refused over spend it was not allowed to contribute, and the two
        // used to be decided separately.
        settleLands("3", "3");
        when(runRepository.findBudgetStateByRunIdPublic("run-1"))
                .thenReturn(Optional.of(state(false, false, "10")));
        when(periodSpendAccumulator.accumulate(any(), any(), any())).thenReturn(new BigDecimal("3"));

        service.recordAgentCost("run-1", "org-1", 1, new BigDecimal("3"));

        verify(periodSpendAccumulator).accumulate(eq("run-1"), any(), eq(new BigDecimal("3")));
    }

    @Test
    @DisplayName("a PROMOTED editor run counts: pinning never strips the flag, so the FK has to win")
    void promotedEditorRunCounts() {
        // Pinning promotes an editor run to production and leaves __editorRun__
        // on it forever. Trusting the flag alone would leave the production run
        // of every pinned workflow permanently exempt, so the cap would be inert
        // on precisely the runs it is meant to govern.
        settleLands("6", "6");
        when(runRepository.findBudgetStateByRunIdPublic("run-1"))
                .thenReturn(Optional.of(state(true, true, "10")));
        when(periodSpendAccumulator.accumulate(any(), any(), any())).thenReturn(new BigDecimal("6"));

        service.recordAgentCost("run-1", "org-1", 1, new BigDecimal("6"));

        verify(periodSpendAccumulator).accumulate(eq("run-1"), any(), eq(new BigDecimal("6")));
    }

    @Test
    @DisplayName("an uncapped workflow still accumulates its spend, so the UI can show it")
    void uncappedWorkflowStillTracksSpend() {
        settleLands("5", "5");
        when(runRepository.findBudgetStateByRunIdPublic("run-1"))
                .thenReturn(Optional.of(state(true, false, null)));
        when(periodSpendAccumulator.accumulate(any(), any(), any())).thenReturn(new BigDecimal("5"));

        service.recordAgentCost("run-1", "org-1", 1, new BigDecimal("5"));

        verify(eventPublisher).emitRunCost("run-1", 1,
                new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("5"), null);
    }

    // ─── Isolation: the budget is bookkeeping, the run cost is the record ───

    @Test
    @DisplayName("a period-budget failure still emits the run figures, and is counted rather than only logged")
    void periodBudgetFailureStillEmits() {
        // The accumulator runs in its OWN transaction (REQUIRES_NEW), which is
        // what makes this catch meaningful: catching an exception raised in THIS
        // transaction would leave it rollback-only and the settle would fail at
        // commit anyway, silently discarding the run's own cost as well.
        settleLands("1", "1");
        when(runRepository.findBudgetStateByRunIdPublic("run-1"))
                .thenReturn(Optional.of(state(true, false, "10")));
        when(periodSpendAccumulator.accumulate(any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        service.recordAgentCost("run-1", "org-1", 1, new BigDecimal("1"));

        verify(eventPublisher).emitRunCost("run-1", 1,
                new BigDecimal("1"), new BigDecimal("1"), null, new BigDecimal("10"));
        assertThat(meterRegistry.counter("workflow.budget.errors", "stage", "accumulate").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("workflow deleted between the settle and the period write -> no period figure, no throw")
    void periodIncrementNoRowMatched() {
        settleLands("1", "1");
        when(runRepository.findBudgetStateByRunIdPublic("run-1"))
                .thenReturn(Optional.of(state(true, false, null)));
        when(periodSpendAccumulator.accumulate(any(), any(), any())).thenReturn(null);

        service.recordAgentCost("run-1", "org-1", 1, new BigDecimal("1"));

        verify(eventPublisher).emitRunCost("run-1", 1,
                new BigDecimal("1"), new BigDecimal("1"), null, null);
    }

    // ─── No-ops and failures on the run's own increment ───

    @Test
    @DisplayName("zero credits is a no-op (no increment, no emit)")
    void zeroCreditsNoop() {
        service.recordAgentCost("run-1", "org-1", 1, BigDecimal.ZERO);
        verifyNoInteractions(runRepository, periodSpendAccumulator, eventPublisher);
    }

    @Test
    @DisplayName("negative credits is a no-op")
    void negativeCreditsNoop() {
        service.recordAgentCost("run-1", "org-1", 1, new BigDecimal("-1"));
        verifyNoInteractions(runRepository, periodSpendAccumulator, eventPublisher);
    }

    @Test
    @DisplayName("null / blank runId is a no-op")
    void blankRunIdNoop() {
        service.recordAgentCost(null, "org-1", 1, new BigDecimal("1"));
        service.recordAgentCost("  ", "org-1", 1, new BigDecimal("1"));
        verifyNoInteractions(runRepository, periodSpendAccumulator, eventPublisher);
    }

    @Test
    @DisplayName("no row matched (run deleted or scope mismatch) -> no event, and nothing charged to the period")
    void zeroRowsNoEmit() {
        when(runRepository.incrementRunCost(any(), any(), any(), any())).thenReturn(0);

        service.recordAgentCost("run-1", "org-1", 1, new BigDecimal("1"));

        verify(eventPublisher, never()).emitRunCost(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any());
        verifyNoInteractions(periodSpendAccumulator);
    }

    @Test
    @DisplayName("a negative epoch is clamped to 0 for the bucket key")
    void negativeEpochClampedToZero() {
        when(runRepository.incrementRunCost("run-1", "org-1", "0", new BigDecimal("1"))).thenReturn(1);
        when(runRepository.findCostCreditsByRunIdPublic("run-1")).thenReturn(Optional.of(new BigDecimal("1")));
        when(runRepository.findEpochCostByRunIdPublic("run-1", "0")).thenReturn(Optional.of(new BigDecimal("1")));
        when(runRepository.findBudgetStateByRunIdPublic("run-1")).thenReturn(Optional.empty());

        service.recordAgentCost("run-1", "org-1", -5, new BigDecimal("1"));

        verify(runRepository).incrementRunCost("run-1", "org-1", "0", new BigDecimal("1"));
    }

    @Test
    @DisplayName("an increment failure is swallowed and counted (never throws to the caller)")
    void incrementFailureSwallowed() {
        when(runRepository.incrementRunCost(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        // must not throw
        service.recordAgentCost("run-1", "org-1", 1, new BigDecimal("1"));

        verify(eventPublisher, never()).emitRunCost(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any());
        assertThat(meterRegistry.counter("workflow.budget.errors", "stage", "run_cost").count())
                .isEqualTo(1.0);
    }
}
