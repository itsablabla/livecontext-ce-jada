package com.apimarketplace.orchestrator.services.credit;

import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.events.WorkflowBudgetReachedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The workflow period counter, and the one notification that announces the cap.
 *
 * <p>Every assertion here is about money or about the user being told. The
 * read-back tests in particular are not paranoia: deriving the fresh spend in
 * application code silently loses the crossing under a race, and the loss is
 * permanent, because the emitter's dedup can suppress a duplicate but cannot
 * resurrect an event nobody published.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPeriodSpendAccumulator - period counter + the cap notification")
class WorkflowPeriodSpendAccumulatorTest {

    private static final UUID WORKFLOW_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private WorkflowRepository workflowRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private MeterRegistry meterRegistry;
    private WorkflowPeriodSpendAccumulator accumulator;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        accumulator = new WorkflowPeriodSpendAccumulator(
                workflowRepository, applicationEventPublisher, meterRegistry);
    }

    private static Instant thisMonth() {
        return WorkflowBudgetPeriod.periodStart("monthly", Instant.now());
    }

    private static WorkflowBudgetState state(String cap, String mode, String storedSpent, Instant periodStart) {
        return new WorkflowBudgetState(
                WORKFLOW_ID, true, false,
                cap == null ? null : new BigDecimal(cap),
                mode,
                storedSpent == null ? null : new BigDecimal(storedSpent),
                periodStart);
    }

    /** The row holds {@code committed} once the atomic increment has landed. */
    private void rowLandsAt(String committed) {
        when(workflowRepository.incrementBudgetPeriodSpendWithReset(eq(WORKFLOW_ID), any(), any())).thenReturn(1);
        when(workflowRepository.findBudgetPeriodSpentById(WORKFLOW_ID))
                .thenReturn(Optional.of(new BigDecimal(committed)));
    }

    /** Same, for the cumulative mode, which uses the other statement. */
    private void cumulativeRowLandsAt(String committed) {
        when(workflowRepository.incrementBudgetPeriodSpendCumulative(eq(WORKFLOW_ID), any())).thenReturn(1);
        when(workflowRepository.findBudgetPeriodSpentById(WORKFLOW_ID))
                .thenReturn(Optional.of(new BigDecimal(committed)));
    }

    // ─── The transaction boundary, which is the whole reason this is a bean ───

    @Test
    @DisplayName("runs in its OWN transaction, or a budget-write failure kills the settle that called it")
    void accumulateRequiresANewTransaction() throws NoSuchMethodException {
        // Not a style assertion: three behaviours rest on this annotation.
        //
        // The one that breaks SILENTLY is the notification. Its listener runs
        // AFTER_COMMIT, so it needs a transaction to commit; with no
        // transaction here the BUDGET_REACHED row is announced before, or
        // instead of, being written, and nothing fails loudly.
        //
        // It also keeps the UPDATE and the read-back it trusts on one
        // connection holding one row lock, and it guarantees a failure here
        // cannot mark a CALLER's transaction rollback-only. That last one is
        // dormant today (recordAgentCost is deliberately not transactional) and
        // is exactly why the propagation is pinned rather than assumed: wrap
        // the caller again and, without REQUIRES_NEW, a budget-write failure
        // takes the run's own cost increment down with it at commit.
        Method m = WorkflowPeriodSpendAccumulator.class.getMethod(
                "accumulate", String.class, WorkflowBudgetState.class, BigDecimal.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).as("accumulate() must carry @Transactional").isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("lets a persistence failure OUT, so the caller's transaction is not poisoned by a swallow")
    void persistenceFailurePropagates() {
        when(workflowRepository.incrementBudgetPeriodSpendWithReset(eq(WORKFLOW_ID), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> accumulator.accumulate("run-1", state("10", "monthly", "1", thisMonth()),
                new BigDecimal("1")))
                .hasMessageContaining("db down");
    }

    // ─── The counter ───

    @Test
    @DisplayName("returns the figure the ROW holds, not stored + this settle")
    void trustsTheReadBackNotArithmetic() {
        // Under a race the value read before the update is already stale:
        // another settle committed in between. Deriving under-reports the spend
        // to the run bar and, worse, hides the crossing.
        rowLandsAt("10.2");

        BigDecimal fresh = accumulator.accumulate("run-1", state("10", "monthly", "9", thisMonth()),
                new BigDecimal("0.6"));

        assertThat(fresh).isEqualByComparingTo("10.2");
    }

    @Test
    @DisplayName("falls back to arithmetic when the row cannot be read back")
    void fallsBackWhenTheReadBackIsEmpty() {
        // The figure the UI is waiting for must not silently vanish because the
        // second statement found nothing.
        when(workflowRepository.incrementBudgetPeriodSpendWithReset(eq(WORKFLOW_ID), any(), any())).thenReturn(1);
        when(workflowRepository.findBudgetPeriodSpentById(WORKFLOW_ID)).thenReturn(Optional.empty());

        BigDecimal fresh = accumulator.accumulate("run-1", state("10", "monthly", "3", thisMonth()),
                new BigDecimal("1"));

        assertThat(fresh).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("no workflow row matched (deleted mid-settle) -> null, and no read-back attempted")
    void deletedWorkflowYieldsNull() {
        when(workflowRepository.incrementBudgetPeriodSpendWithReset(eq(WORKFLOW_ID), any(), any())).thenReturn(0);

        BigDecimal fresh = accumulator.accumulate("run-1", state("10", "monthly", "1", thisMonth()),
                new BigDecimal("1"));

        assertThat(fresh).isNull();
        verify(workflowRepository, org.mockito.Mockito.never()).findBudgetPeriodSpentById(any());
        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    @DisplayName("cumulative mode uses the plain-add statement, and binds NO timestamp at all")
    void cumulativeUsesTheAddOnlyStatement() {
        // The point is not which method is called, it is that no null timestamp
        // is ever bound: Postgres cannot infer a null parameter's type, H2
        // accepts it, and this repo has already shipped that exact bug once. A
        // failure here would be swallowed by the caller, so cumulative caps
        // would simply never accumulate, in silence.
        cumulativeRowLandsAt("5");

        accumulator.accumulate("run-1", state(null, "cumulative", "4", null), new BigDecimal("1"));

        verify(workflowRepository).incrementBudgetPeriodSpendCumulative(eq(WORKFLOW_ID), eq(new BigDecimal("1")));
        verify(workflowRepository, org.mockito.Mockito.never())
                .incrementBudgetPeriodSpendWithReset(any(), any(), any());
    }

    @Test
    @DisplayName("monthly mode passes THIS period's start, which is what makes the reset lazy")
    void monthlyPassesTheCurrentPeriodStart() {
        rowLandsAt("5");

        accumulator.accumulate("run-1", state(null, "monthly", "4", thisMonth()), new BigDecimal("1"));

        verify(workflowRepository).incrementBudgetPeriodSpendWithReset(eq(WORKFLOW_ID), any(), eq(thisMonth()));
        verify(workflowRepository, org.mockito.Mockito.never())
                .incrementBudgetPeriodSpendCumulative(any(), any());
    }

    // ─── The crossing ───

    @Test
    @DisplayName("publishes BUDGET_REACHED on the settle that takes the spend to the cap")
    void publishesOnTheCrossing() {
        rowLandsAt("10");

        accumulator.accumulate("run-1", state("10", "monthly", "9.5", thisMonth()), new BigDecimal("0.5"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(WorkflowBudgetReachedEvent.class);
        WorkflowBudgetReachedEvent event = (WorkflowBudgetReachedEvent) captor.getValue();
        assertThat(event.workflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(event.runIdPublic()).isEqualTo("run-1");
        assertThat(event.spentCredits()).isEqualByComparingTo("10");
        assertThat(event.capCredits()).isEqualByComparingTo("10");
        assertThat(event.periodMode()).isEqualTo("monthly");
        // The dedup key must name the period this crossing belongs to, or every
        // period would collapse onto one notification, forever.
        assertThat(event.sourceId()).contains(event.periodStart().toString());
    }

    @Test
    @DisplayName("stays silent while the spend is still under the cap")
    void silentUnderTheCap() {
        rowLandsAt("2");

        accumulator.accumulate("run-1", state("10", "monthly", "1", thisMonth()), new BigDecimal("1"));

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    @DisplayName("does not re-publish on a settle that lands after the cap was already crossed")
    void doesNotRepublishOnceOver() {
        // A straggler execution that was already in flight. Re-publishing would
        // be one notification per straggler, all saying the same thing.
        rowLandsAt("13");

        accumulator.accumulate("run-1", state("10", "monthly", "12", thisMonth()), new BigDecimal("1"));

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    @DisplayName("never publishes for an uncapped workflow, whatever it spends")
    void noCapNeverPublishes() {
        rowLandsAt("600");

        accumulator.accumulate("run-1", state(null, "monthly", "500", thisMonth()), new BigDecimal("100"));

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    @DisplayName("given the two post-update figures a race produces, only the crossing one announces")
    void oneRacingSettleClaimsTheCrossing() {
        // Both settles read stored 9 before the update. The one that took the
        // row from 9.6 to 10.2 crossed; the one that took it from 9 to 9.6 did
        // not. Deciding from the pre-update figure would make BOTH conclude
        // "still under", and the notification would be lost for good.
        //
        // Scope, stated honestly: this pins the ARITHMETIC on the two figures a
        // real race hands back. That the UPDATE actually serialises the two
        // settles is a property of the statement, covered in
        // WorkflowBudgetPeriodRepositoryIntegrationTest, not of this test.
        rowLandsAt("9.6");
        accumulator.accumulate("run-1", state("10", "monthly", "9", thisMonth()), new BigDecimal("0.6"));
        verifyNoInteractions(applicationEventPublisher);

        org.mockito.Mockito.reset(workflowRepository);
        rowLandsAt("10.2");
        accumulator.accumulate("run-1", state("10", "monthly", "9", thisMonth()), new BigDecimal("0.6"));
        verify(applicationEventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("a period that has rolled over starts from zero, so last month's crossing does not re-fire")
    void rolledOverPeriodDoesNotRepublish() {
        // Stored spend belongs to LAST month and is above the cap. The UPDATE
        // resets it in place, so the row lands at just this settle's credits and
        // the workflow is nowhere near its cap.
        rowLandsAt("1");

        accumulator.accumulate("run-1",
                state("10", "monthly", "50", thisMonth().minus(40, ChronoUnit.DAYS)),
                new BigDecimal("1"));

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    @DisplayName("a failed publish never rolls back the spend it was reporting, and is counted")
    void publishFailureIsSwallowedAndCounted() {
        rowLandsAt("10");
        doThrow(new RuntimeException("bus down")).when(applicationEventPublisher).publishEvent(any(Object.class));

        BigDecimal fresh = accumulator.accumulate("run-1", state("10", "monthly", "9.5", thisMonth()),
                new BigDecimal("0.5"));

        // The figure survives: the notification is the optional half.
        assertThat(fresh).isEqualByComparingTo("10");
        // Counted, because a cap that has silently stopped announcing itself
        // looks exactly like a cap that is simply never reached.
        assertThat(meterRegistry.counter("workflow.budget.errors", "stage", "publish").count()).isEqualTo(1.0);
    }
}
