package com.apimarketplace.orchestrator.services.credit;

import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Accumulates the cost of a workflow run from its agent executions.
 *
 * <p><b>Scope, stated plainly because a spending cap is built on it:</b> what
 * reaches this service is AGENT spend, and only that. Agent-service settles an
 * execution's credits and notifies the orchestrator (see
 * {@code InternalRunCostController}). Paid catalog calls and resold generation
 * are billed inside catalog-service, against the {@code X-Lc-Billing-Scope-*}
 * headers, and never come back here - so they land in neither
 * {@code cost_credits} nor the period budget. Every label the user reads says
 * "agent spend" for that reason. Widening the cap to cover them is a
 * cross-service change (catalog-service would have to report what it charged),
 * not a tweak to this class.
 *
 * <p>Cost is stored in credits (1 credit = $0.001). The total is kept across ALL
 * epochs of the run, with a per-epoch breakdown so the RunInfo panel can show
 * both "cost of this run" and per-epoch cost.
 *
 * <p>A run's cost ALSO accumulates onto its workflow's period budget (V474) when
 * {@link WorkflowBudgetState#appliesToRun()} says the cap governs it, which is
 * what the spending cap is compared against. A builder test fire is excluded:
 * it costs real credits and is recorded on the run, but it never eats the
 * allowance. Counting and refusing go through the SAME predicate, so a run can
 * never be refused over spend it was not allowed to contribute.
 */
@Service
public class RunCostService {

    private static final Logger log = LoggerFactory.getLogger(RunCostService.class);

    private final WorkflowRunRepository runRepository;
    private final WorkflowPeriodSpendAccumulator periodSpendAccumulator;
    private final WorkflowEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    public RunCostService(WorkflowRunRepository runRepository,
                          WorkflowPeriodSpendAccumulator periodSpendAccumulator,
                          WorkflowEventPublisher eventPublisher,
                          MeterRegistry meterRegistry) {
        this.runRepository = runRepository;
        this.periodSpendAccumulator = periodSpendAccumulator;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Record a settled agent cost on a run and emit the fresh totals.
     *
     * <p>Best-effort. The increment is a pure monotonic add, so it is safe to
     * repeat only in the sense that callers send exactly once per settled
     * execution: there is no idempotency key on this path, so a retry or a
     * replayed notification WOULD double-count. A zero (or negative) delta is a
     * no-op. Never throws to the caller: a cost-tracking failure must not break
     * the notification endpoint.
     *
     * <p><b>Deliberately NOT {@code @Transactional}.</b> Every statement it
     * issues is already transactional on its own ({@code incrementRunCost}
     * carries {@code @Transactional}; the period write runs in
     * {@code REQUIRES_NEW}), and the read-backs are supposed to observe what
     * committed, including a concurrent settle's increment, which is exactly
     * what they get outside a transaction. Wrapping the method instead made
     * every settle hold TWO pooled connections at once, three on the crossing:
     * the outer one from the first UPDATE all the way through the Redis
     * publish, plus the inner one the period write must take. On the shape this
     * feature exists to catch, a split settling dozens of agents together, that
     * is enough threads each holding one connection and waiting for another to
     * exhaust the pool and stall every settle until the connection timeout,
     * after which the period counter silently under-counts. One connection at a
     * time, held briefly, is the whole point.
     *
     * @param runIdPublic public run id (also the WS channel key)
     * @param orgId       run's organization id, or null for personal scope
     * @param epoch       epoch the agent executed in
     * @param credits     credits consumed by this agent execution
     */
    public void recordAgentCost(String runIdPublic, String orgId, int epoch, BigDecimal credits) {
        if (runIdPublic == null || runIdPublic.isBlank()) {
            return;
        }
        if (credits == null || credits.signum() <= 0) {
            // Nothing consumed (0-token/cached-only or a rejected consumption):
            // no total change, nothing to broadcast.
            return;
        }

        String epochKey = Integer.toString(Math.max(epoch, 0));
        int rows;
        try {
            rows = runRepository.incrementRunCost(runIdPublic, orgId, epochKey, credits);
        } catch (Exception e) {
            meterRegistry.counter("workflow.budget.errors", "stage", "run_cost").increment();
            log.warn("[RunCost] increment failed for runId={} epoch={} credits={}: {}",
                    runIdPublic, epoch, credits, e.getMessage());
            return;
        }
        if (rows == 0) {
            // Run deleted between execution and settle, or a cross-scope
            // notification (orgId mismatch). Either way there is nothing to
            // update - do not emit a stale event.
            log.debug("[RunCost] no run matched runId={} orgId={} (deleted or scope mismatch)", runIdPublic, orgId);
            return;
        }

        // Read back the fresh figures so the event reflects this increment (and
        // any concurrent one that committed meanwhile). The UPDATE above has
        // already committed on its own, so these reads see it.
        BigDecimal total = runRepository.findCostCreditsByRunIdPublic(runIdPublic).orElse(BigDecimal.ZERO);
        BigDecimal epochCost = runRepository.findEpochCostByRunIdPublic(runIdPublic, epochKey).orElse(BigDecimal.ZERO);

        // One round-trip carries the cap, the period bookkeeping AND the two
        // identity flags that decide whether the cap governs this run.
        WorkflowBudgetState state = runRepository.findBudgetStateByRunIdPublic(runIdPublic).orElse(null);
        BigDecimal budget = state != null ? state.budgetCredits() : null;
        BigDecimal periodSpent = null;

        if (state != null && state.appliesToRun() && state.workflowId() != null) {
            periodSpent = accumulatePeriodSpend(runIdPublic, state, credits);
        }

        try {
            eventPublisher.emitRunCost(runIdPublic, epoch, epochCost, total, periodSpent, budget);
        } catch (Exception e) {
            log.warn("[RunCost] emitRunCost failed for runId={}: {}", runIdPublic, e.getMessage());
        }
        log.debug("[RunCost] runId={} epoch={} +{} -> total={} periodSpent={} (budget={})",
                runIdPublic, epoch, credits, total, periodSpent, budget);
    }

    /**
     * Absorb a period-budget failure so it cannot take the run's own cost down
     * with it.
     *
     * <p>The write happens in a SEPARATE transaction (see
     * {@link WorkflowPeriodSpendAccumulator}), so by the time the exception
     * reaches this catch that transaction has already rolled back on its own
     * and nothing of it is left to poison the settle. This method is not
     * transactional either, so the run's cost - committed by its own statement
     * before we ever got here - stands. Counted, not just logged: a cap that
     * has silently stopped
     * accumulating is indistinguishable from a cap that is never reached.
     */
    private BigDecimal accumulatePeriodSpend(String runIdPublic, WorkflowBudgetState state, BigDecimal credits) {
        try {
            return periodSpendAccumulator.accumulate(runIdPublic, state, credits);
        } catch (Exception e) {
            meterRegistry.counter("workflow.budget.errors", "stage", "accumulate").increment();
            log.warn("[RunCost] period-budget increment failed for workflowId={}: {}",
                    state.workflowId(), e.getMessage());
            return null;
        }
    }
}
