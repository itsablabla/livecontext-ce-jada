package com.apimarketplace.orchestrator.services.credit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * The rollover rule behind a workflow's spending cap.
 *
 * <p>A workflow's cap ({@code workflows.budget_credits}) applies to what its
 * the governed runs spend inside one period. This class is the single place that
 * answers the three questions the rule needs:
 *
 * <ul>
 *   <li>which period are we in right now ({@link #periodStart}),</li>
 *   <li>how much of the stored spend still counts ({@link #effectiveSpent}),</li>
 *   <li>is the cap reached ({@link #isExceeded}).</li>
 * </ul>
 *
 * <p>Deliberately pure and static: the arithmetic that decides whether a run is
 * allowed to spend must be unit-testable without a database, and the SAME rule
 * has to hold in three places that cannot share a transaction - the increment
 * (which resets in place), the pre-flight guard before an agent call, and the
 * epoch gate on a trigger fire. A second implementation anywhere is a bug
 * waiting to happen, so callers pass values in and take the verdict out.
 *
 * <p>Periods are calendar periods in UTC, not sliding windows from the pin date.
 * A user compares the number against a monthly provider invoice, so "resets on
 * the 1st" is the reading that matches what they are checking it against.
 */
public final class WorkflowBudgetPeriod {

    /** Resets on the 1st of each month, 00:00 UTC. The default. */
    public static final String MODE_MONTHLY = "monthly";
    /** Resets every Monday, 00:00 UTC. */
    public static final String MODE_WEEKLY = "weekly";
    /** Never resets: the cap covers the workflow's whole production life. */
    public static final String MODE_CUMULATIVE = "cumulative";

    private WorkflowBudgetPeriod() {
    }

    /**
     * Is a workflow's spending cap refusing its fires right now?
     *
     * <p>The workflow-level twin of {@code WorkflowBudgetState.isExceededAt}:
     * that one answers for a RUN (and needs to know whether the cap governs it),
     * this one answers for the workflow itself, which is the question the agenda
     * asks before it draws a schedule's future.
     */
    public static boolean isBlocked(BigDecimal cap, String mode,
                                    BigDecimal storedSpent, Instant storedStart, Instant now) {
        if (cap == null || cap.signum() <= 0) {
            return false;
        }
        return isExceeded(effectiveSpent(mode, storedStart, storedSpent, now), cap);
    }

    /**
     * When the period open at {@code now} rolls over, or {@code null} for the
     * cumulative mode, which never does.
     *
     * <p>This is the moment a workflow blocked by its cap starts firing again,
     * so it is the one date worth showing a user who has just been stopped, and
     * the one the agenda needs to know how far to grey a schedule's occurrences.
     *
     * <p>Computed here and shipped to the client rather than recomputed there.
     * The calendar rule (UTC, ISO weeks starting Monday, months on the 1st) is
     * the same rule the counter resets on; a second implementation in TypeScript
     * would agree right up until one of them was edited.
     */
    public static Instant nextPeriodStart(String mode, Instant now) {
        Instant start = periodStart(mode, now);
        if (start == null) {
            return null;
        }
        ZonedDateTime utc = start.atZone(ZoneOffset.UTC);
        if (MODE_WEEKLY.equalsIgnoreCase(nullSafe(mode))) {
            return utc.plusWeeks(1).toInstant();
        }
        return utc.plusMonths(1).toInstant();
    }

    /**
     * Start of the period {@code now} falls into, or {@code null} for
     * {@link #MODE_CUMULATIVE} (which has no period, so nothing to reset).
     *
     * <p>An unrecognised mode is treated as monthly, NOT as cumulative. A typo
     * must not silently turn a cap into a lifetime cap: that is exactly the
     * failure this feature exists to remove, and it would only surface months
     * later as a workflow that stopped firing on its own.
     */
    public static Instant periodStart(String mode, Instant now) {
        if (now == null) {
            return null;
        }
        if (MODE_CUMULATIVE.equalsIgnoreCase(nullSafe(mode))) {
            return null;
        }
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        if (MODE_WEEKLY.equalsIgnoreCase(nullSafe(mode))) {
            // ISO week: Monday is day 1.
            return utc.truncatedTo(ChronoUnit.DAYS)
                    .minusDays(utc.getDayOfWeek().getValue() - 1L)
                    .toInstant();
        }
        return utc.truncatedTo(ChronoUnit.DAYS)
                .withDayOfMonth(1)
                .toInstant();
    }

    /**
     * The stored spend re-read against the period open right now.
     *
     * <p>Returns zero when the stored figure belongs to a period that has since
     * rolled over, so a reader never has to know whether the row has been
     * touched since the rollover. The reset is therefore lazy: no scheduler
     * walks the table at midnight, and a workflow nobody fires costs nothing to
     * keep correct.
     */
    public static BigDecimal effectiveSpent(String mode,
                                            Instant storedPeriodStart,
                                            BigDecimal storedSpent,
                                            Instant now) {
        BigDecimal spent = storedSpent != null ? storedSpent : BigDecimal.ZERO;
        if (MODE_CUMULATIVE.equalsIgnoreCase(nullSafe(mode))) {
            return spent;
        }
        Instant current = periodStart(mode, now);
        if (current == null || storedPeriodStart == null) {
            // Nothing recorded yet (or no clock): nothing counts against the cap.
            return BigDecimal.ZERO;
        }
        return storedPeriodStart.isBefore(current) ? BigDecimal.ZERO : spent;
    }

    /**
     * Is the cap reached? {@code budget} null or {@code <= 0} means no cap.
     *
     * <p>The comparison is {@code >=}: reaching the cap exactly is already too
     * far, because the next agent call would cross it. Same semantics the epoch
     * gate has always used, kept identical on purpose.
     */
    public static boolean isExceeded(BigDecimal spent, BigDecimal budget) {
        if (budget == null || budget.signum() <= 0) {
            return false;
        }
        BigDecimal actual = spent != null ? spent : BigDecimal.ZERO;
        return actual.compareTo(budget) >= 0;
    }

    /** Normalise the stored mode, defaulting a null/blank column to monthly. */
    public static String normaliseMode(String mode) {
        String m = nullSafe(mode);
        if (MODE_CUMULATIVE.equalsIgnoreCase(m)) {
            return MODE_CUMULATIVE;
        }
        if (MODE_WEEKLY.equalsIgnoreCase(m)) {
            return MODE_WEEKLY;
        }
        return MODE_MONTHLY;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s.trim();
    }
}
