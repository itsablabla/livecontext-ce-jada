package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.PausedReason;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Why a schedule is paused, which decides whether the agenda may offer to resume it.
 *
 * <p>The rail showed a Resume button on every un-armed schedule, and two thirds of them
 * could not be resumed. Worse, the call did not fail: {@code armSchedule} short-circuits on
 * a row that is already ACTIVE and returns {@code true} without writing, so the user got a
 * "Resumed" toast on a schedule that still could not fire, and the row was still sitting
 * there greyed out after a refresh.
 *
 * <p>The three states are not interchangeable and only one is a pause the user owns:
 * an exhausted run count is finished work, and a platform suspension is a symptom of the
 * plan, which is where the fix has to happen.
 */
@DisplayName("ActiveAutomationsService.pausedReason")
class ActiveAutomationsServicePausedReasonTest {

    @Test
    @DisplayName("an armed schedule has no paused reason")
    void armedHasNoReason() {
        assertThat(ActiveAutomationsService.pausedReason(schedule(true, null, 3, null))).isNull();
    }

    @Test
    @DisplayName("a user pause is USER - the one case Resume is for")
    void userPauseIsResumable() {
        assertThat(ActiveAutomationsService.pausedReason(schedule(false, "USER_DISABLED", 3, null)))
                .isEqualTo(PausedReason.USER);
    }

    @Test
    @DisplayName("a reason-less legacy row counts as a user pause")
    void legacyRowsAreTreatedAsUserPauses() {
        // Rows disabled before reasons were recorded. Refusing to resume one the user CAN
        // fix is the worse error of the two, and an arm that does not stick shows up on the
        // next refresh rather than silently.
        assertThat(ActiveAutomationsService.pausedReason(schedule(false, null, 3, null)))
                .isEqualTo(PausedReason.USER);
        assertThat(ActiveAutomationsService.pausedReason(schedule(false, "  ", 3, null)))
                .isEqualTo(PausedReason.USER);
        assertThat(ActiveAutomationsService.pausedReason(schedule(false, "LEGACY_DISABLED", 3, null)))
                .isEqualTo(PausedReason.USER);
    }

    @Test
    @DisplayName("an exhausted run count is CAP_REACHED even while `enabled` is still true")
    void exhaustedCapIsNotAPause() {
        // The shape that made the button lie: nothing disabled this row, so `enabled` reads
        // true and armSchedule treats it as already armed. It simply has no runs left.
        assertThat(ActiveAutomationsService.pausedReason(schedule(true, null, 5, 5)))
                .isEqualTo(PausedReason.CAP_REACHED);
    }

    @Test
    @DisplayName("the cap wins over a user pause when a row carries both")
    void capBeatsTheReason() {
        // A schedule the user paused that had ALSO run out. Reporting USER here would offer
        // a resume that puts it back into a state where it still cannot fire - the same lie
        // with an extra step.
        assertThat(ActiveAutomationsService.pausedReason(schedule(false, "USER_DISABLED", 5, 5)))
                .isEqualTo(PausedReason.CAP_REACHED);
    }

    @Test
    @DisplayName("a platform suspension is PLATFORM - the cause is in the plan")
    void platformSuspensionIsNotResumable() {
        // Re-arming rebuilds exactly the orphan the suspension sweep exists to retire, and
        // the next tick suspends it again.
        for (String reason : new String[]{"PLAN_TRIGGER_REMOVED", "WORKFLOW_UNPINNED",
                "WORKFLOW_DELETED", "NO_PRODUCTION_RUN", "TENANT_SUSPENDED"}) {
            assertThat(ActiveAutomationsService.pausedReason(schedule(false, reason, 3, null)))
                    .as("reason %s", reason)
                    .isEqualTo(PausedReason.PLATFORM);
        }
    }

    private static ScheduledExecutionDto schedule(boolean enabled, String reason,
                                                  Integer maxExecutions, Integer executionCount) {
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setEnabled(enabled);
        dto.setIsActive(true);
        dto.setLastDisabledReason(reason);
        dto.setMaxExecutions(maxExecutions);
        dto.setExecutionCount(executionCount != null ? executionCount : 0);
        return dto;
    }
}
