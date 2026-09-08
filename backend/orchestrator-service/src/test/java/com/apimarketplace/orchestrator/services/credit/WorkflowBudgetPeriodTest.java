package com.apimarketplace.orchestrator.services.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WorkflowBudgetPeriod - the rollover rule behind a workflow's spending cap")
class WorkflowBudgetPeriodTest {

    /** 2026-09-02T14:35:12Z, a Wednesday. */
    private static final Instant NOW =
            ZonedDateTime.of(2026, 9, 2, 14, 35, 12, 0, ZoneOffset.UTC).toInstant();

    private static Instant utc(int y, int m, int d) {
        return ZonedDateTime.of(y, m, d, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
    }

    @Nested
    @DisplayName("periodStart")
    class PeriodStart {

        @Test
        @DisplayName("monthly truncates to the 1st at midnight UTC")
        void monthlyTruncatesToFirstOfMonth() {
            assertThat(WorkflowBudgetPeriod.periodStart("monthly", NOW)).isEqualTo(utc(2026, 9, 1));
        }

        @Test
        @DisplayName("weekly truncates to the ISO Monday at midnight UTC")
        void weeklyTruncatesToMonday() {
            // 2026-09-02 is a Wednesday, so its ISO week opened on Monday the 31st.
            assertThat(WorkflowBudgetPeriod.periodStart("weekly", NOW)).isEqualTo(utc(2026, 8, 31));
        }

        @Test
        @DisplayName("weekly on a Monday returns that same Monday, not the previous one")
        void weeklyOnMondayIsIdempotent() {
            Instant monday = ZonedDateTime.of(2026, 8, 31, 9, 0, 0, 0, ZoneOffset.UTC).toInstant();
            assertThat(WorkflowBudgetPeriod.periodStart("weekly", monday)).isEqualTo(utc(2026, 8, 31));
        }

        @Test
        @DisplayName("cumulative has no period, so there is nothing to reset")
        void cumulativeHasNoPeriod() {
            assertThat(WorkflowBudgetPeriod.periodStart("cumulative", NOW)).isNull();
        }

        @Test
        @DisplayName("an unknown mode falls back to MONTHLY, never to cumulative")
        void unknownModeFallsBackToMonthly() {
            // Falling back to cumulative would silently turn a cap into a lifetime
            // cap: the workflow would stop firing for good, months later, with no
            // reset - the exact failure this feature removes.
            assertThat(WorkflowBudgetPeriod.periodStart("montly", NOW)).isEqualTo(utc(2026, 9, 1));
            assertThat(WorkflowBudgetPeriod.periodStart(null, NOW)).isEqualTo(utc(2026, 9, 1));
            assertThat(WorkflowBudgetPeriod.periodStart("  ", NOW)).isEqualTo(utc(2026, 9, 1));
        }

        @Test
        @DisplayName("the mode is matched case-insensitively")
        void modeIsCaseInsensitive() {
            assertThat(WorkflowBudgetPeriod.periodStart("WEEKLY", NOW)).isEqualTo(utc(2026, 8, 31));
            assertThat(WorkflowBudgetPeriod.periodStart("Cumulative", NOW)).isNull();
        }
    }

    @Nested
    @DisplayName("isBlocked - is the cap refusing this workflow's fires right now")
    class IsBlocked {

        // The predicate the agenda asks before it draws a schedule's future.
        // Getting it wrong in one direction greys a calendar that is fine; in
        // the other it promises fires that will be refused.

        @Test
        @DisplayName("no cap means never blocked, whatever has been spent")
        void noCapNeverBlocks() {
            assertThat(WorkflowBudgetPeriod.isBlocked(null, "monthly",
                    new BigDecimal("9999"), utc(2026, 9, 1), NOW)).isFalse();
        }

        @Test
        @DisplayName("a zero or negative cap is 'no cap', matching every other reader of it")
        void nonPositiveCapNeverBlocks() {
            assertThat(WorkflowBudgetPeriod.isBlocked(BigDecimal.ZERO, "monthly",
                    new BigDecimal("50"), utc(2026, 9, 1), NOW)).isFalse();
            assertThat(WorkflowBudgetPeriod.isBlocked(new BigDecimal("-5"), "monthly",
                    new BigDecimal("50"), utc(2026, 9, 1), NOW)).isFalse();
        }

        @Test
        @DisplayName("under the cap is not blocked; reaching it exactly is")
        void boundary() {
            assertThat(WorkflowBudgetPeriod.isBlocked(new BigDecimal("10"), "monthly",
                    new BigDecimal("9.9999"), utc(2026, 9, 1), NOW)).isFalse();
            assertThat(WorkflowBudgetPeriod.isBlocked(new BigDecimal("10"), "monthly",
                    new BigDecimal("10"), utc(2026, 9, 1), NOW)).isTrue();
        }

        @Test
        @DisplayName("a spend from an EXPIRED period does not block: the allowance already restarted")
        void rolledOverSpendDoesNotBlock() {
            // The failure this prevents: a workflow that hit its cap in August
            // would have its whole September calendar greyed out, for an
            // allowance that reset weeks ago.
            assertThat(WorkflowBudgetPeriod.isBlocked(new BigDecimal("10"), "monthly",
                    new BigDecimal("50"), utc(2026, 8, 1), NOW)).isFalse();
        }

        @Test
        @DisplayName("a cumulative cap stays blocked however old the figure is")
        void cumulativeStaysBlocked() {
            assertThat(WorkflowBudgetPeriod.isBlocked(new BigDecimal("10"), "cumulative",
                    new BigDecimal("50"), utc(2024, 1, 1), NOW)).isTrue();
        }

        @Test
        @DisplayName("a workflow that has never spent is not blocked (null spend, null period)")
        void neverSpentIsNotBlocked() {
            assertThat(WorkflowBudgetPeriod.isBlocked(new BigDecimal("10"), "monthly", null, null, NOW))
                    .isFalse();
        }

        @Test
        @DisplayName("it agrees with the run-level rule on the same figures")
        void agreesWithTheRunLevelRule() {
            // Two entry points, one answer. If these ever disagreed, the agenda
            // would grey fires the enforcement points let through, or the
            // reverse - and the calendar would be lying either way.
            WorkflowBudgetState governed = new WorkflowBudgetState(
                    java.util.UUID.randomUUID(), true, false,
                    new BigDecimal("10"), "monthly", new BigDecimal("10"), utc(2026, 9, 1));
            assertThat(WorkflowBudgetPeriod.isBlocked(new BigDecimal("10"), "monthly",
                    new BigDecimal("10"), utc(2026, 9, 1), NOW))
                    .isEqualTo(governed.isExceededAt(NOW));
        }
    }

    @Nested
    @DisplayName("nextPeriodStart - when a blocked workflow starts firing again")
    class NextPeriodStart {

        // The one date worth showing someone who has just been stopped, and the
        // one the agenda needs to know how far to grey a schedule. It is derived
        // from periodStart on purpose: the two can never disagree about where a
        // period ends.

        @Test
        @DisplayName("monthly rolls to the 1st of the next month, at UTC midnight")
        void monthlyRollsToTheFirst() {
            assertThat(WorkflowBudgetPeriod.nextPeriodStart("monthly", utc(2026, 9, 15)))
                    .isEqualTo(utc(2026, 10, 1));
        }

        @Test
        @DisplayName("monthly crosses the year boundary")
        void monthlyCrossesTheYear() {
            assertThat(WorkflowBudgetPeriod.nextPeriodStart("monthly", utc(2026, 12, 31)))
                    .isEqualTo(utc(2027, 1, 1));
        }

        @Test
        @DisplayName("weekly rolls to the next Monday, even when today IS Monday")
        void weeklyRollsToNextMonday() {
            // 2026-09-07 is a Monday: the period that opened today ends in seven
            // days, not today. An off-by-one here tells a blocked user their
            // workflow resumes immediately.
            assertThat(WorkflowBudgetPeriod.nextPeriodStart("weekly", utc(2026, 9, 7)))
                    .isEqualTo(utc(2026, 9, 14));
            assertThat(WorkflowBudgetPeriod.nextPeriodStart("weekly", utc(2026, 9, 11)))
                    .isEqualTo(utc(2026, 9, 14));
        }

        @Test
        @DisplayName("cumulative never rolls over, so there is no date to promise")
        void cumulativeHasNoNextPeriod() {
            assertThat(WorkflowBudgetPeriod.nextPeriodStart("cumulative", NOW)).isNull();
        }

        @Test
        @DisplayName("an unknown mode follows the monthly fallback, never the never-resets one")
        void unknownModeFollowsMonthly() {
            assertThat(WorkflowBudgetPeriod.nextPeriodStart("quarterly", utc(2026, 9, 15)))
                    .isEqualTo(utc(2026, 10, 1));
        }

        @Test
        @DisplayName("a null clock yields no date rather than an exception")
        void nullNowYieldsNull() {
            assertThat(WorkflowBudgetPeriod.nextPeriodStart("monthly", null)).isNull();
        }

        @Test
        @DisplayName("a NULL mode follows the monthly fallback, which is the real DB path")
        void nullModeFollowsMonthly() {
            // Not hypothetical: a workflow row written before the cadence column
            // existed reads null here, and every caller passes the raw column.
            // Falling to the never-resets mode instead would answer "no date"
            // and the agenda would grey that workflow's calendar forever.
            assertThat(WorkflowBudgetPeriod.nextPeriodStart(null, utc(2026, 9, 15)))
                    .isEqualTo(utc(2026, 10, 1));
            assertThat(WorkflowBudgetPeriod.nextPeriodStart("", utc(2026, 9, 15)))
                    .isEqualTo(utc(2026, 10, 1));
        }

        @Test
        @DisplayName("it is always strictly after the period it closes")
        void alwaysAfterTheCurrentPeriod() {
            for (String mode : new String[] {"monthly", "weekly"}) {
                assertThat(WorkflowBudgetPeriod.nextPeriodStart(mode, NOW))
                        .as(mode)
                        .isAfter(WorkflowBudgetPeriod.periodStart(mode, NOW));
            }
        }
    }

    @Nested
    @DisplayName("effectiveSpent")
    class EffectiveSpent {

        @Test
        @DisplayName("keeps the stored spend when it belongs to the period now open")
        void keepsCurrentPeriodSpend() {
            assertThat(WorkflowBudgetPeriod.effectiveSpent(
                    "monthly", utc(2026, 9, 1), new BigDecimal("4.25"), NOW))
                    .isEqualByComparingTo("4.25");
        }

        @Test
        @DisplayName("drops a spend recorded in an earlier period")
        void dropsExpiredPeriodSpend() {
            assertThat(WorkflowBudgetPeriod.effectiveSpent(
                    "monthly", utc(2026, 8, 1), new BigDecimal("99"), NOW))
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a spend recorded later in the same period still counts (clock skew between pods)")
        void futureWithinPeriodStillCounts() {
            Instant laterSameMonth = ZonedDateTime.of(2026, 9, 20, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
            assertThat(WorkflowBudgetPeriod.effectiveSpent(
                    "monthly", laterSameMonth, new BigDecimal("3"), NOW))
                    .isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("cumulative never drops anything, however old")
        void cumulativeKeepsEverything() {
            assertThat(WorkflowBudgetPeriod.effectiveSpent(
                    "cumulative", utc(2024, 1, 1), new BigDecimal("120"), NOW))
                    .isEqualByComparingTo("120");
        }

        @Test
        @DisplayName("nothing recorded yet reads as zero")
        void nullsReadAsZero() {
            assertThat(WorkflowBudgetPeriod.effectiveSpent("monthly", null, null, NOW))
                    .isEqualByComparingTo("0");
            assertThat(WorkflowBudgetPeriod.effectiveSpent("monthly", null, new BigDecimal("5"), NOW))
                    .isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("isExceeded")
    class IsExceeded {

        @Test
        @DisplayName("no cap set means never exceeded")
        void nullOrZeroCapNeverExceeds() {
            assertThat(WorkflowBudgetPeriod.isExceeded(new BigDecimal("1000"), null)).isFalse();
            assertThat(WorkflowBudgetPeriod.isExceeded(new BigDecimal("1000"), BigDecimal.ZERO)).isFalse();
            assertThat(WorkflowBudgetPeriod.isExceeded(new BigDecimal("1000"), new BigDecimal("-5"))).isFalse();
        }

        @Test
        @DisplayName("reaching the cap exactly already blocks: the next call would cross it")
        void equalIsExceeded() {
            assertThat(WorkflowBudgetPeriod.isExceeded(new BigDecimal("10"), new BigDecimal("10"))).isTrue();
        }

        @Test
        @DisplayName("under the cap passes, over the cap blocks")
        void underPassesOverBlocks() {
            assertThat(WorkflowBudgetPeriod.isExceeded(new BigDecimal("9.9999"), new BigDecimal("10"))).isFalse();
            assertThat(WorkflowBudgetPeriod.isExceeded(new BigDecimal("10.0001"), new BigDecimal("10"))).isTrue();
        }

        @Test
        @DisplayName("a null spend is treated as zero")
        void nullSpendIsZero() {
            assertThat(WorkflowBudgetPeriod.isExceeded(null, new BigDecimal("10"))).isFalse();
        }

        @Test
        @DisplayName("scale does not change the verdict (10.00 is not more than 10)")
        void scaleDoesNotChangeTheVerdict() {
            assertThat(WorkflowBudgetPeriod.isExceeded(new BigDecimal("10.00"), new BigDecimal("10"))).isTrue();
            assertThat(WorkflowBudgetPeriod.isExceeded(new BigDecimal("9.99"), new BigDecimal("10.0000"))).isFalse();
        }
    }

    @Nested
    @DisplayName("WorkflowBudgetState - the rule applied to a stored row")
    class State {

        /** The production run of a pinned workflow: the ordinary capped case. */
        private WorkflowBudgetState state(String cap, String mode, String spent, Instant start) {
            return run(true, false, cap, mode, spent, start);
        }

        private WorkflowBudgetState run(boolean productionRun, boolean editorRun,
                                        String cap, String mode, String spent, Instant start) {
            return new WorkflowBudgetState(
                    java.util.UUID.randomUUID(), productionRun, editorRun,
                    cap == null ? null : new BigDecimal(cap), mode,
                    spent == null ? null : new BigDecimal(spent), start);
        }

        @Test
        @DisplayName("a workflow whose period has rolled over is no longer blocked")
        void rolloverUnblocks() {
            WorkflowBudgetState lastMonth = state("10", "monthly", "10", utc(2026, 8, 1));
            assertThat(lastMonth.isExceededAt(NOW)).isFalse();
            assertThat(lastMonth.effectiveSpent(NOW)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a workflow that hit its cap this period is blocked")
        void currentPeriodBlocks() {
            assertThat(state("10", "monthly", "10", utc(2026, 9, 1)).isExceededAt(NOW)).isTrue();
        }

        @Test
        @DisplayName("cumulative mode keeps a workflow blocked forever, which is what it promises")
        void cumulativeStaysBlocked() {
            assertThat(state("10", "cumulative", "10", utc(2024, 1, 1)).isExceededAt(NOW)).isTrue();
        }

        @Test
        @DisplayName("blocksAt refuses a PRODUCTION run that has reached its cap")
        void blocksProductionOverCap() {
            assertThat(state("10", "monthly", "10", utc(2026, 9, 1)).blocksAt(NOW)).isTrue();
        }

        @Test
        @DisplayName("blocksAt NEVER refuses an editor run: the period counter is not its spend")
        void neverBlocksAnEditorRun() {
            // The counter belongs to the WORKFLOW, so an editor run reads a
            // figure it never contributed to. Checking the amount alone would
            // freeze every test fire the moment production reached its cap,
            // which is the opposite of what the settings screen promises.
            WorkflowBudgetState editorRun = run(false, true,
                    "10", "monthly", "50", utc(2026, 9, 1));
            assertThat(editorRun.isExceededAt(NOW))
                    .as("the amount alone says 'over'")
                    .isTrue();
            assertThat(editorRun.blocksAt(NOW))
                    .as("but the full rule lets a test run through")
                    .isFalse();
        }

        @Test
        @DisplayName("blocksAt lets a production run through once its period has rolled over")
        void rolloverUnblocksProduction() {
            assertThat(state("10", "monthly", "10", utc(2026, 8, 1)).blocksAt(NOW)).isFalse();
        }

        @Test
        @DisplayName("blocksAt never refuses an uncapped workflow")
        void blocksAtNeedsACap() {
            assertThat(state(null, "monthly", "500", utc(2026, 9, 1)).blocksAt(NOW)).isFalse();
        }

        @Test
        @DisplayName("a run that is neither production nor a builder fire is governed: the rule is not FK-only")
        void unpinnedNonEditorRunIsGoverned() {
            // Neither production nor a builder fire. A trigger cannot start
            // such a run today (the chokepoint refuses it upstream), so this
            // pins the RULE rather than a live hole: one predicate answers
            // "does the cap govern this run", and it does not delegate the
            // answer to whatever another guard happens to filter out.
            WorkflowBudgetState unpinned = run(false, false, "10", "monthly", "10", utc(2026, 9, 1));
            assertThat(unpinned.appliesToRun()).isTrue();
            assertThat(unpinned.blocksAt(NOW)).isTrue();
        }

        @Test
        @DisplayName("a PROMOTED editor run is governed: pinning never strips the flag, so the FK wins")
        void promotedEditorRunIsGoverned() {
            // Pinning turns an editor run into the production run and leaves
            // __editorRun__ on it forever. If the flag alone could excuse a run,
            // the production run of every pinned workflow would be exempt, and
            // the cap would be inert on exactly the runs it governs.
            WorkflowBudgetState promoted = run(true, true, "10", "monthly", "10", utc(2026, 9, 1));
            assertThat(promoted.appliesToRun()).isTrue();
            assertThat(promoted.blocksAt(NOW)).isTrue();
        }

        @Test
        @DisplayName("appliesToRun is the SAME predicate that decides whose spend is counted")
        void appliesToRunIsTheSingleRule() {
            // RunCostService counts through appliesToRun() and both enforcement
            // points refuse through blocksAt(), which delegates to it. Stated as
            // a test because a run refused over spend it was never allowed to
            // contribute would be the cruellest possible bug here.
            WorkflowBudgetState builderRun = run(false, true, "10", "monthly", "10", utc(2026, 9, 1));
            assertThat(builderRun.appliesToRun()).isFalse();
            assertThat(builderRun.blocksAt(NOW)).isFalse();
        }

        @Test
        @DisplayName("no cap set is never blocked, whatever has been spent")
        void noCapNeverBlocks() {
            WorkflowBudgetState uncapped = state(null, "monthly", "500", utc(2026, 9, 1));
            assertThat(uncapped.hasCap()).isFalse();
            assertThat(uncapped.isExceededAt(NOW)).isFalse();
            // The spend is still tracked, so the UI can show it next to "no cap".
            assertThat(uncapped.effectiveSpent(NOW)).isEqualByComparingTo("500");
        }
    }
}
