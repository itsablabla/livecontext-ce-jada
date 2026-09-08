package com.apimarketplace.orchestrator.services.credit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Everything needed to decide whether a run may spend, read in one round-trip.
 *
 * <p>Loaded from a run id because every enforcement point (the pre-flight guard
 * before an agent call, the epoch gate on a trigger fire) knows the run, not the
 * workflow. Kept as a record so the query maps to a typed value instead of an
 * {@code Object[]} the callers would have to index by hand.
 *
 * @param workflowId     the workflow the run belongs to, for the increment and
 *                       for the notification's subject
 * @param productionRun  is this run the workflow's live production run? Keyed on
 *                       the {@code production_run_id} FK, NEVER on the
 *                       {@code __editorRun__} metadata flag, which pinning does
 *                       not strip when it promotes an editor run to production
 * @param editorRun      does the run carry {@code __editorRun__}? Only meaningful
 *                       together with {@code productionRun}, see
 *                       {@link #appliesToRun()}
 * @param budgetCredits  the cap, {@code null} or {@code <= 0} when there is none
 * @param periodMode     monthly / weekly / cumulative
 * @param periodSpent    stored spend, which may belong to a period that has
 *                       already rolled over - always read it through
 *                       {@link WorkflowBudgetPeriod#effectiveSpent}
 * @param periodStartedAt start of the period {@code periodSpent} belongs to
 */
public record WorkflowBudgetState(
        UUID workflowId,
        boolean productionRun,
        boolean editorRun,
        BigDecimal budgetCredits,
        String periodMode,
        BigDecimal periodSpent,
        Instant periodStartedAt) {

    /** Does this workflow cap spend at all? */
    public boolean hasCap() {
        return budgetCredits != null && budgetCredits.signum() > 0;
    }

    /** Spend that counts against the cap at {@code now}, after any rollover. */
    public BigDecimal effectiveSpent(Instant now) {
        return WorkflowBudgetPeriod.effectiveSpent(periodMode, periodStartedAt, periodSpent, now);
    }

    /** Is the cap reached at {@code now}? Always false when there is no cap. */
    public boolean isExceededAt(Instant now) {
        return hasCap() && WorkflowBudgetPeriod.isExceeded(effectiveSpent(now), budgetCredits);
    }

    /**
     * Does the cap govern this run at all - both for counting its spend and for
     * refusing it?
     *
     * <p>The rule is <b>"every run except a run of the builder"</b>, expressed
     * with BOTH signals: the FK proves a run IS production and wins, and the
     * {@code __editorRun__} flag only excuses a run the FK has not claimed. The
     * flag alone cannot express it, because pinning promotes an editor run to
     * production without stripping the flag, so a promoted run reads as an
     * editor run forever.
     *
     * <p><b>Why not the production FK alone, stated accurately.</b> Verified on
     * a live stack: a non-editor run of an UNPINNED workflow never reaches
     * either enforcement point, because {@code ReusableTriggerService}'s
     * chokepoint refuses it first ("has no pinned version. Pin a version to
     * enable production triggers"). So the two rules agree on every run a
     * trigger can actually start today, and this is not fixing a live hole -
     * an earlier draft of this comment claimed it was, and the claim did not
     * survive the e2e.
     *
     * <p>It is still the rule worth having, for a reason that does not depend
     * on another guard's behaviour: it is the SINGLE predicate for both
     * counting spend ({@code RunCostService}) and refusing a run (both
     * enforcement points). Before that, the trigger gate had no editor check at
     * all while the counter had one, so the two disagreed about the same run.
     * A rule that is correct only because a different guard happens to filter
     * its input is a rule waiting to break.
     */
    public boolean appliesToRun() {
        return productionRun || !editorRun;
    }

    /**
     * The full enforcement rule: may this run be refused right now?
     *
     * <p><b>Use this at every enforcement point rather than {@link #isExceededAt}
     * alone.</b> The period counter belongs to the WORKFLOW, so a builder run
     * reads a figure it never contributed to; checking only the amount would
     * freeze every test fire the moment production reached its cap, which is the
     * opposite of what the settings screen promises ("editor runs stay free").
     * Both enforcement points (the pre-flight guard before an agent call and the
     * epoch gate on a trigger fire) go through here so the rule cannot drift
     * between them, and {@code RunCostService} counts spend through the same
     * {@link #appliesToRun()} so what is counted and what is refused can never
     * disagree.
     */
    public boolean blocksAt(Instant now) {
        return appliesToRun() && isExceededAt(now);
    }
}
