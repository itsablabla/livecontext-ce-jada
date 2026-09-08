package com.apimarketplace.common.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "move ALL occurrences" rewrite. Half of this class asserts REFUSALS, and that is
 * the point: a rewrite that guesses turns a schedule the user relies on into a different
 * one, and they find out only when a run they expected never happened. Refusing leaves
 * them the "this occurrence only" path, which is always exact.
 */
class CronShifterTest {

    private static Instant at(String isoLocal, String zone) {
        return ZonedDateTime.of(LocalDateTime.parse(isoLocal), ZoneId.of(zone)).toInstant();
    }

    @Nested
    @DisplayName("supported shapes are rewritten")
    class Rewrites {

        @Test
        @DisplayName("daily: only the time of day changes")
        void dailyShiftsTime() {
            // 2026-09-03 is a Thursday; a daily schedule must stay daily, not become weekly.
            CronShifter.ShiftResult result = CronShifter.shiftAll(
                    "0 9 * * *", "UTC", at("2026-09-03T14:30", "UTC"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.cron()).isEqualTo("30 14 * * *");
        }

        @Test
        @DisplayName("weekly on a single day: the weekday follows the drop target")
        void weeklySingleDayFollowsTarget() {
            // Every Monday 09:00 dropped on Wednesday 16:00 becomes every Wednesday 16:00.
            CronShifter.ShiftResult result = CronShifter.shiftAll(
                    "0 9 * * 1", "UTC", at("2026-09-02T16:00", "UTC"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.cron()).isEqualTo("0 16 * * 3");
        }

        @Test
        @DisplayName("weekly on Sunday: cron numbering wraps java.time's SUNDAY(7) to 0")
        void sundayIsZero() {
            CronShifter.ShiftResult result = CronShifter.shiftAll(
                    "0 9 * * 1", "UTC", at("2026-09-06T08:15", "UTC"));   // a Sunday

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.cron()).isEqualTo("15 8 * * 0");
        }

        @Test
        @DisplayName("weekday set: the set is preserved verbatim when the target falls inside it")
        void weekdaySetKeepsItsDays() {
            // Weekdays-at-08:30 moved to Thursday 18:00 stays "every weekday", now at 18:00.
            CronShifter.ShiftResult result = CronShifter.shiftAll(
                    "30 8 * * 1-5", "UTC", at("2026-09-03T18:00", "UTC"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.cron()).isEqualTo("0 18 * * 1-5");
        }

        @Test
        @DisplayName("monthly: day-of-month and time both follow the target")
        void monthlyShiftsDayAndTime() {
            CronShifter.ShiftResult result = CronShifter.shiftAll(
                    "0 9 1 * *", "UTC", at("2026-09-12T07:45", "UTC"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.cron()).isEqualTo("45 7 12 * *");
        }

        @Test
        @DisplayName("fields are derived in the SCHEDULE's zone, not the instant's UTC wall clock")
        void derivesFieldsInScheduleTimezone() {
            // The user drops the job on 14:30 Paris. The cron is interpreted in Paris, so
            // it must read 14:30 - stamping the 12:30 UTC wall clock would move the job
            // two hours off for everyone.
            CronShifter.ShiftResult result = CronShifter.shiftAll(
                    "0 9 * * *", "Europe/Paris", at("2026-09-03T14:30", "Europe/Paris"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.cron()).isEqualTo("30 14 * * *");
        }

        @Test
        @DisplayName("a 6-field expression with a zero seconds field is treated as its 5-field form")
        void sixFieldWithZeroSecondsIsSupported() {
            CronShifter.ShiftResult result = CronShifter.shiftAll(
                    "0 0 9 * * *", "UTC", at("2026-09-03T14:30", "UTC"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.cron()).isEqualTo("30 14 * * *");
        }
    }

    @Nested
    @DisplayName("ambiguous shapes are refused, with a reason")
    class Refusals {

        @Test
        @DisplayName("an interval has no single time of day to move")
        void refusesIntervals() {
            assertThat(CronShifter.shiftAll("0 */2 * * *", "UTC", at("2026-09-03T14:30", "UTC")).reason())
                    .isEqualTo(CronShifter.Reason.UNSUPPORTED_PATTERN);
            assertThat(CronShifter.shiftAll("*/15 * * * *", "UTC", at("2026-09-03T14:30", "UTC")).reason())
                    .isEqualTo(CronShifter.Reason.UNSUPPORTED_PATTERN);
            assertThat(CronShifter.shiftAll("0 * * * *", "UTC", at("2026-09-03T14:30", "UTC")).reason())
                    .isEqualTo(CronShifter.Reason.UNSUPPORTED_PATTERN);
        }

        @Test
        @DisplayName("an hour or minute list would need an arbitrary pick")
        void refusesTimeLists() {
            assertThat(CronShifter.shiftAll("0 9,17 * * *", "UTC", at("2026-09-03T14:30", "UTC")).reason())
                    .isEqualTo(CronShifter.Reason.UNSUPPORTED_PATTERN);
        }

        @Test
        @DisplayName("a month constraint is not something a date drag can express")
        void refusesMonthConstraint() {
            assertThat(CronShifter.shiftAll("0 9 1 1 *", "UTC", at("2026-09-03T14:30", "UTC")).reason())
                    .isEqualTo(CronShifter.Reason.UNSUPPORTED_PATTERN);
        }

        @Test
        @DisplayName("a weekday set is refused when the target is not one of its days")
        void refusesWeekdaySetMiss() {
            // Weekdays-only dropped on a Saturday: honouring it would ADD Saturday to the
            // set, changing how often the schedule runs.
            CronShifter.ShiftResult result = CronShifter.shiftAll(
                    "30 8 * * 1-5", "UTC", at("2026-09-05T18:00", "UTC"));   // a Saturday

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.reason()).isEqualTo(CronShifter.Reason.WEEKDAY_SET_NOT_MATCHED);
            assertThat(result.cron()).isNull();
        }

        @Test
        @DisplayName("a monthly move past the 28th is refused: those months would be skipped")
        void refusesUnsafeDayOfMonth() {
            CronShifter.ShiftResult result = CronShifter.shiftAll(
                    "0 9 1 * *", "UTC", at("2026-09-30T09:00", "UTC"));

            assertThat(result.reason()).isEqualTo(CronShifter.Reason.DAY_OF_MONTH_UNSAFE);
        }

        @Test
        @DisplayName("a sub-minute schedule has no time of day")
        void refusesSecondsPrecision() {
            assertThat(CronShifter.shiftAll("30 0 9 * * *", "UTC", at("2026-09-03T14:30", "UTC")).reason())
                    .isEqualTo(CronShifter.Reason.UNSUPPORTED_PATTERN);
        }

        @Test
        @DisplayName("named weekdays are refused rather than guessed")
        void refusesNamedWeekdays() {
            assertThat(CronShifter.shiftAll("0 9 * * MON-FRI", "UTC", at("2026-09-03T14:30", "UTC")).reason())
                    .isEqualTo(CronShifter.Reason.UNSUPPORTED_PATTERN);
        }

        @Test
        @DisplayName("an invalid cron is reported as invalid, not as unsupported")
        void reportsInvalidSeparately() {
            assertThat(CronShifter.shiftAll("not a cron", "UTC", at("2026-09-03T14:30", "UTC")).reason())
                    .isEqualTo(CronShifter.Reason.INVALID_CRON);
        }

        @Test
        @DisplayName("a stepped expression is UNSUPPORTED, not INVALID - it is a working schedule")
        void steppedExpressionsAreUnsupportedNotInvalid() {
            // This used to report INVALID_CRON, because the shifter validated through a
            // predicate that folded the input rule in. That told the user their schedule
            // was broken when it fires perfectly well; the true answer is that this shifter
            // only rewrites plain daily, weekly and monthly shapes. The distinction is the
            // whole point of having two reasons - one says fix your cron, the other says
            // move this occurrence individually instead.
            assertThat(CronShifter.shiftAll("*/120 * * * *", "UTC", at("2026-09-03T14:30", "UTC")).reason())
                    .isEqualTo(CronShifter.Reason.UNSUPPORTED_PATTERN);
            assertThat(CronShifter.shiftAll("*/15 * * * *", "UTC", at("2026-09-03T14:30", "UTC")).reason())
                    .isEqualTo(CronShifter.Reason.UNSUPPORTED_PATTERN);
        }

        @Test
        @DisplayName("a null target is refused instead of stamping the current time")
        void refusesNullTarget() {
            assertThat(CronShifter.shiftAll("0 9 * * *", "UTC", null).isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("supportsShiftAll")
    class ShapeProbe {

        @Test
        @DisplayName("accepts the three inspector shapes and rejects everything else")
        void classifiesShapes() {
            assertThat(CronShifter.supportsShiftAll("0 9 * * *")).isTrue();
            assertThat(CronShifter.supportsShiftAll("0 9 * * 1")).isTrue();
            assertThat(CronShifter.supportsShiftAll("30 8 * * 1-5")).isTrue();
            assertThat(CronShifter.supportsShiftAll("0 9 15 * *")).isTrue();

            assertThat(CronShifter.supportsShiftAll("*/15 * * * *")).isFalse();
            assertThat(CronShifter.supportsShiftAll("0 9,17 * * *")).isFalse();
            assertThat(CronShifter.supportsShiftAll("0 9 1 1 *")).isFalse();
            assertThat(CronShifter.supportsShiftAll("garbage")).isFalse();
        }

        @Test
        @DisplayName("a shape it accepts can still be refused for a specific target")
        void shapeProbeIsNotAPromise() {
            // The UI offers "all occurrences" on shape alone; the target check happens at
            // submit. Both halves of that contract are asserted here so they stay aligned.
            assertThat(CronShifter.supportsShiftAll("30 8 * * 1-5")).isTrue();
            assertThat(CronShifter.shiftAll("30 8 * * 1-5", "UTC", at("2026-09-05T18:00", "UTC")).isSuccess())
                    .isFalse();
        }
    }
}
