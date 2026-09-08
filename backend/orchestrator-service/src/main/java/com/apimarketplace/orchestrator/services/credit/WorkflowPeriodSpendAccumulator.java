package com.apimarketplace.orchestrator.services.credit;

import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.events.WorkflowBudgetReachedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Adds a settled cost to its workflow's period budget, and raises the durable
 * notification on the settle that crosses the cap.
 *
 * <p><b>Why this is a separate bean, and why the transaction annotation is
 * load-bearing.</b> Three things depend on it, and none of them is obvious:
 *
 * <ol>
 *   <li><b>The notification fires only once the spend is durable.</b> The
 *       crossing is published as a Spring event whose listener runs
 *       {@code AFTER_COMMIT}. A commit is what it waits for, so this method has
 *       to open a transaction for it to hang off. Remove the annotation and the
 *       row is announced before, or instead of, being written.</li>
 *   <li><b>The UPDATE and the read-back it trusts must be one unit.</b> The
 *       fresh figure is read back on the same connection that holds the row
 *       lock, which is what makes it the true post-increment value under
 *       concurrent settles.</li>
 *   <li><b>The failure stays here.</b> The caller
 *       ({@code RunCostService.recordAgentCost}) is deliberately NOT
 *       transactional today, so a failure in here cannot poison it - but the
 *       propagation says so explicitly, and that is the point. If anyone ever
 *       wraps the caller again, a JDBC error raised inside its transaction
 *       would mark the WHOLE thing rollback-only, the caller's catch could not
 *       clear the mark, and the commit would throw
 *       {@code UnexpectedRollbackException} and take the run's own cost
 *       increment down with it. {@code REQUIRES_NEW} keeps that from ever being
 *       reintroduced by accident.</li>
 * </ol>
 *
 * <p>A private method could not do any of it: Spring's {@code @Transactional}
 * is proxy-based, so propagation on a self-call is silently ignored.
 *
 * <p>The transaction is also kept SHORT on purpose: the workflow row lock is
 * released as soon as it commits, and every settle of the same workflow
 * serialises on that row, which is not academic on a split fanning out dozens
 * of agents.
 *
 * <p>Deliberately does NOT catch its own persistence failures: the caller
 * catches, counts and logs, outside this transaction boundary.
 */
@Service
public class WorkflowPeriodSpendAccumulator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPeriodSpendAccumulator.class);

    private final WorkflowRepository workflowRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final MeterRegistry meterRegistry;

    public WorkflowPeriodSpendAccumulator(WorkflowRepository workflowRepository,
                                          ApplicationEventPublisher applicationEventPublisher,
                                          MeterRegistry meterRegistry) {
        this.workflowRepository = workflowRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Add {@code credits} to the workflow's period budget and return the fresh
     * period spend, or {@code null} when no workflow row matched (deleted).
     *
     * <p>The period start is computed here, in Java, and passed to the UPDATE:
     * the statement resets the counter in place when the stored period has
     * expired, so a rollover can never lose the first cost of the new period.
     *
     * @throws RuntimeException on any persistence failure, for the caller to
     *                          absorb outside this transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BigDecimal accumulate(String runIdPublic, WorkflowBudgetState state, BigDecimal credits) {
        Instant now = Instant.now();
        Instant periodStart = WorkflowBudgetPeriod.periodStart(state.periodMode(), now);
        // Two statements, picked here, rather than one that takes a nullable
        // timestamp: see WorkflowRepository.incrementBudgetPeriodSpendWithReset
        // for why a null bind on this column is a trap this repo has already
        // fallen into once.
        int rows = periodStart == null
                ? workflowRepository.incrementBudgetPeriodSpendCumulative(state.workflowId(), credits)
                : workflowRepository.incrementBudgetPeriodSpendWithReset(state.workflowId(), credits, periodStart);
        if (rows == 0) {
            log.debug("[RunCost] no workflow matched id={} for period spend (deleted?)", state.workflowId());
            return null;
        }
        // Read the committed figure back rather than deriving it. Deriving
        // (stored + credits) is wrong whenever two settles race, which is
        // routine inside a split: both read the same stored value before the
        // update, so both compute the same total, both under-report it to the
        // UI, and - the part that actually costs money - both conclude they are
        // still under the cap, so the crossing notification is never published
        // and every later settle sees "already over" and stays silent. The row
        // is serialised by the UPDATE's lock, so this read returns the true
        // post-increment value.
        BigDecimal freshSpend = workflowRepository.findBudgetPeriodSpentById(state.workflowId())
                .orElseGet(() -> state.effectiveSpent(now).add(credits));
        notifyIfCapJustReached(runIdPublic, state, freshSpend, credits, now, periodStart);
        return freshSpend;
    }

    /**
     * Raise the durable BUDGET_REACHED notification on the settle that crosses
     * the cap.
     *
     * <p>This is the moment that matters, and it is NOT the same moment the
     * trigger gate refuses a fire. The case this whole feature exists for is a
     * single epoch looping on an agent all night: the pre-flight guard in
     * {@code AgentNode} then blocks every further call inside that epoch, and no
     * new fire is ever attempted, so the trigger gate never runs and would never
     * notify. Publishing from the settle covers both shapes with one hook,
     * because every credit an agent spends passes through here.
     *
     * <p>Fires only on the CROSSING, so a workflow parked over its cap does not
     * re-publish on every settle. The crossing is decided on the figure read
     * back from the row, minus this settle's own credits, so exactly one of two
     * racing settles claims it. Deriving it from the value read BEFORE the
     * update instead would make both of them conclude they are still under, and
     * the notification would be lost for good: the emitter's
     * {@code (workflow, period)} dedup suppresses duplicates, it cannot
     * resurrect an event nobody published.
     *
     * <p>Best-effort: a notification that fails to publish must not roll back
     * the spend it was reporting, so this one failure IS swallowed here, inside
     * the transaction it must not poison. It is counted, because a cap that has
     * silently stopped announcing itself looks exactly like a cap that is simply
     * never reached.
     */
    private void notifyIfCapJustReached(String runIdPublic, WorkflowBudgetState state,
                                        BigDecimal freshSpend, BigDecimal credits,
                                        Instant now, Instant periodStart) {
        if (!state.hasCap()) {
            return;
        }
        // "Was under" is measured on the value MINUS this settle's own credits,
        // not on the figure read before the update: under a race, another settle
        // may have committed in between, and that one is not this one's crossing
        // to announce.
        BigDecimal before = freshSpend.subtract(credits);
        boolean wasUnder = !WorkflowBudgetPeriod.isExceeded(before, state.budgetCredits());
        boolean isNowOver = WorkflowBudgetPeriod.isExceeded(freshSpend, state.budgetCredits());
        if (!wasUnder || !isNowOver) {
            return;
        }
        try {
            applicationEventPublisher.publishEvent(new WorkflowBudgetReachedEvent(
                    runIdPublic,
                    state.workflowId(),
                    freshSpend,
                    state.budgetCredits(),
                    WorkflowBudgetPeriod.normaliseMode(state.periodMode()),
                    periodStart,
                    now));
            log.info("[RunCost] workflow {} reached its {} cap ({}/{} credits) on run {}",
                    state.workflowId(), WorkflowBudgetPeriod.normaliseMode(state.periodMode()),
                    freshSpend, state.budgetCredits(), runIdPublic);
        } catch (Exception e) {
            meterRegistry.counter("workflow.budget.errors", "stage", "publish").increment();
            log.warn("[RunCost] failed to publish budget-reached for workflowId={}: {}",
                    state.workflowId(), e.getMessage());
        }
    }
}
