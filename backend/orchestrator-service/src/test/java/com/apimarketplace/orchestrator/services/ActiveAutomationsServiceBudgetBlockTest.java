package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a workflow's spending cap is refusing its fires, and when that stops.
 *
 * <p>This is what the agenda asks before it draws a schedule's future, and it is
 * a DIFFERENT question from {@code pausedReason}. A paused schedule is durable:
 * someone has to act. A spending block is temporary and lifts on a date the
 * system already knows, which is why the two travel separately and why the
 * calendar can grey part of a week and leave the rest alone.
 */
@DisplayName("ActiveAutomationsService.budgetBlock")
class ActiveAutomationsServiceBudgetBlockTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    private static WorkflowEntity workflow(String cap, String mode, String spent, Instant periodStart) {
        WorkflowEntity w = new WorkflowEntity("tenant-1", "Nightly Digest", "user-1");
        w.setBudgetCredits(cap == null ? null : new BigDecimal(cap));
        w.setBudgetPeriodMode(mode);
        // The period columns are DB-managed (insertable/updatable=false), so the
        // entity has no setters for them - reflection is how a unit test states
        // a stored counter.
        org.springframework.test.util.ReflectionTestUtils.setField(
                w, "budgetPeriodSpent", spent == null ? null : new BigDecimal(spent));
        org.springframework.test.util.ReflectionTestUtils.setField(w, "budgetPeriodStartedAt", periodStart);
        return w;
    }

    @Test
    @DisplayName("an AGENT schedule has no workflow to cap it, and must not read as blocked")
    void noOwnerIsNeverBlocked() {
        // The branch most likely to rot: an agent's budget is a different
        // subsystem with its own counter and its own reset. Treating a null
        // owner as anything but "not blocked" would grey every agent schedule
        // on the calendar.
        var block = ActiveAutomationsService.budgetBlock(null, NOW);
        assertThat(block.blocked()).isFalse();
        assertThat(block.until()).isNull();
    }

    @Test
    @DisplayName("an uncapped workflow is never blocked, whatever it has spent")
    void uncappedIsNeverBlocked() {
        assertThat(ActiveAutomationsService.budgetBlock(
                workflow(null, "monthly", "9999", Instant.parse("2026-09-01T00:00:00Z")), NOW).blocked())
                .isFalse();
    }

    @Test
    @DisplayName("a workflow under its cap is not blocked")
    void underCapIsNotBlocked() {
        assertThat(ActiveAutomationsService.budgetBlock(
                workflow("10", "monthly", "4", Instant.parse("2026-09-01T00:00:00Z")), NOW).blocked())
                .isFalse();
    }

    @Test
    @DisplayName("a workflow at its cap is blocked until the period rolls over, and says WHEN")
    void atCapIsBlockedUntilTheNextPeriod() {
        var block = ActiveAutomationsService.budgetBlock(
                workflow("10", "monthly", "10", Instant.parse("2026-09-01T00:00:00Z")), NOW);

        assertThat(block.blocked()).isTrue();
        // The date the agenda stops greying, and it must be the SAME instant the
        // counter resets on - a second calendar rule here would grey a day too
        // many or a day too few.
        assertThat(block.until())
                .isEqualTo(WorkflowBudgetPeriod.nextPeriodStart("monthly", NOW))
                .isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    @DisplayName("a weekly cap lifts on the next Monday, not in a month")
    void weeklyLiftsOnTheNextMonday() {
        var block = ActiveAutomationsService.budgetBlock(
                workflow("10", "weekly", "10", WorkflowBudgetPeriod.periodStart("weekly", NOW)), NOW);

        assertThat(block.blocked()).isTrue();
        assertThat(block.until()).isEqualTo(WorkflowBudgetPeriod.nextPeriodStart("weekly", NOW));
    }

    @Test
    @DisplayName("a cap that never resets is blocked with NO date, which is not the same as unblocked")
    void cumulativeIsBlockedIndefinitely() {
        var block = ActiveAutomationsService.budgetBlock(
                workflow("10", "cumulative", "50", null), NOW);

        assertThat(block.blocked()).isTrue();
        assertThat(block.until())
                .as("null while blocked means it does not lift on its own")
                .isNull();
    }

    @Test
    @DisplayName("a spend from an EXPIRED period does not block: the allowance already restarted")
    void rolledOverSpendDoesNotBlock() {
        // Without this, a workflow that hit its cap in August would have its
        // whole September calendar greyed out, for an allowance that reset weeks
        // ago and is firing perfectly well.
        var block = ActiveAutomationsService.budgetBlock(
                workflow("10", "monthly", "50", NOW.minus(60, ChronoUnit.DAYS)), NOW);

        assertThat(block.blocked()).isFalse();
        assertThat(block.until()).isNull();
    }

    @Test
    @DisplayName("a workflow that has never spent is not blocked")
    void neverSpentIsNotBlocked() {
        assertThat(ActiveAutomationsService.budgetBlock(workflow("10", "monthly", null, null), NOW).blocked())
                .isFalse();
    }
}
