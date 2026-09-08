package com.apimarketplace.common.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The occurrence maths the agenda draws with. Every assertion here is a promise the
 * calendar makes to the user, so the cases that matter are the boundaries (an occurrence
 * exactly on the window edge), the honesty of the cap, and timezone handling: the cron is
 * interpreted in the SCHEDULE's zone, and a DST jump must not duplicate or drop a day.
 */
class CronOccurrencesTest {

    private static Instant utc(String isoLocal) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal), ZoneId.of("UTC")).toInstant();
    }

    @Nested
    @DisplayName("isValid")
    class Validity {

        @Test
        @DisplayName("accepts the 5-field expressions the schedule inspector produces")
        void acceptsFiveFieldExpressions() {
            assertThat(CronOccurrences.isValid("0 9 * * *")).isTrue();
            assertThat(CronOccurrences.isValid("30 8 * * 1-5")).isTrue();
            assertThat(CronOccurrences.isValid("*/15 * * * *")).isTrue();
        }

        @Test
        @DisplayName("refuses a step larger than its field as INPUT, while still drawing it")
        void rejectsOversizedStep() {
            // Original intent, preserved: Spring parses this and fires hourly at :00 while
            // the user asked for every 120 minutes, so it must not be accepted from a
            // caller. What changed is HOW the calendar-versus-daemon contradiction is
            // avoided. Refusing it in isValid also emptied the occurrence walk, so a row
            // already carrying such a cron stopped firing and was then archived
            // permanently by the reaper. Both sides now expand it the same way instead,
            // which is the agreement the original comment was really after.
            assertThat(CronOccurrences.isAcceptableInput("*/120 * * * *")).isFalse();
            assertThat(CronOccurrences.isAcceptableInput("0,*/120 * * * *")).isFalse();
            assertThat(CronOccurrences.isValid("*/120 * * * *")).isTrue();
        }

        @Test
        @DisplayName("rejects a step larger than the range it steps over, not just the field")
        void rejectsStepWiderThanItsRange() {
            // Moved from isValid to isAcceptableInput: refusing it at the door is right,
            // refusing it in the predicate the reapers use destroyed working rows.
            // `1-5/10` steps over five values, so it collapses to minute 1 alone - the same
            // silent collapse the guard exists to reject. Comparing the step against the
            // field's own 0-59 waves it through.
            assertThat(CronOccurrences.isAcceptableInput("1-5/10 * * * *")).isFalse();
            assertThat(CronOccurrences.isAcceptableInput("0 0-3/6 * * *")).isFalse();
            // Weekday spans 8 values (0-7), so a step of 8 keeps Sunday only.
            assertThat(CronOccurrences.isAcceptableInput("0 9 * * 1-5/8")).isFalse();

            // A step that genuinely fits its range stays valid.
            assertThat(CronOccurrences.isAcceptableInput("0-30/10 * * * *")).isTrue();
            assertThat(CronOccurrences.isAcceptableInput("*/15 * * * *")).isTrue();
            assertThat(CronOccurrences.isAcceptableInput("0 9 * * 1-5")).isTrue();
        }

        @Test
        @DisplayName("rejects an inverted range")
        void rejectsInvertedRange() {
            assertThat(CronOccurrences.isValid("30-10/2 * * * *")).isFalse();
        }

        @Test
        @DisplayName("rejects blank and unparseable input")
        void rejectsGarbage() {
            assertThat(CronOccurrences.isValid(null)).isFalse();
            assertThat(CronOccurrences.isValid("   ")).isFalse();
            assertThat(CronOccurrences.isValid("not a cron")).isFalse();
        }
    }

    @Nested
    @DisplayName("between")
    class Between {

        @Test
        @DisplayName("includes an occurrence landing exactly on the window start")
        void includesWindowStartBoundary() {
            // Spring's next() is strictly-after; a day view starting at 09:00 must still
            // show the 09:00 job, so the cursor has to start before the window.
            CronOccurrences.Window window = CronOccurrences.between(
                    "0 9 * * *", "UTC", utc("2026-09-01T09:00:00"), utc("2026-09-01T23:59:59"), 50);

            assertThat(window.occurrences()).containsExactly(utc("2026-09-01T09:00:00"));
            assertThat(window.truncated()).isFalse();
        }

        @Test
        @DisplayName("excludes an occurrence one second past the window end")
        void excludesPastWindowEnd() {
            CronOccurrences.Window window = CronOccurrences.between(
                    "0 9 * * *", "UTC", utc("2026-09-01T00:00:00"), utc("2026-09-01T08:59:59"), 50);

            assertThat(window.occurrences()).isEmpty();
        }

        @Test
        @DisplayName("returns every daily fire across a month window, ascending")
        void expandsDailyAcrossMonth() {
            CronOccurrences.Window window = CronOccurrences.between(
                    "0 9 * * *", "UTC", utc("2026-09-01T00:00:00"), utc("2026-09-30T23:59:59"), 500);

            assertThat(window.occurrences()).hasSize(30);
            assertThat(window.occurrences()).isSorted();
            assertThat(window.occurrences().get(0)).isEqualTo(utc("2026-09-01T09:00:00"));
            assertThat(window.occurrences().get(29)).isEqualTo(utc("2026-09-30T09:00:00"));
            assertThat(window.truncated()).isFalse();
        }

        @Test
        @DisplayName("reports truncated when the cap stops it short of the window end")
        void reportsTruncation() {
            // Once a minute over a day is 1440 fires. The calendar must say it is showing
            // a prefix rather than imply the schedule stops at 00:10.
            CronOccurrences.Window window = CronOccurrences.between(
                    "* * * * *", "UTC", utc("2026-09-01T00:00:00"), utc("2026-09-01T23:59:59"), 10);

            assertThat(window.occurrences()).hasSize(10);
            assertThat(window.truncated()).isTrue();
        }

        @Test
        @DisplayName("does not report truncated when the cap is met exactly at the window end")
        void capMetExactlyIsNotTruncation() {
            // Exactly 3 fires exist; a cap of 3 saw all of them, so claiming truncation
            // would show a "more occurrences" warning that is simply false.
            CronOccurrences.Window window = CronOccurrences.between(
                    "0 9 * * *", "UTC", utc("2026-09-01T00:00:00"), utc("2026-09-03T23:59:59"), 3);

            assertThat(window.occurrences()).hasSize(3);
            assertThat(window.truncated()).isFalse();
        }

        @Test
        @DisplayName("interprets the expression in the schedule's timezone, not UTC")
        void interpretsInScheduleTimezone() {
            // 09:00 Paris in September is 07:00 UTC. Reading it as UTC would draw the
            // occurrence two hours late on every European user's calendar.
            CronOccurrences.Window window = CronOccurrences.between(
                    "0 9 * * *", "Europe/Paris", utc("2026-09-01T00:00:00"), utc("2026-09-01T23:59:59"), 50);

            assertThat(window.occurrences()).containsExactly(utc("2026-09-01T07:00:00"));
        }

        @Test
        @DisplayName("fires once per day across a DST transition")
        void survivesDstTransition() {
            // Europe/Paris falls back on 2026-10-25. A daily 09:00 job fires once that
            // day like any other - the shift lands in the hour that repeats at 03:00.
            CronOccurrences.Window window = CronOccurrences.between(
                    "0 9 * * *", "Europe/Paris", utc("2026-10-24T00:00:00"), utc("2026-10-26T23:00:00"), 50);

            assertThat(window.occurrences()).hasSize(3);
            List<Instant> fires = window.occurrences();
            assertThat(fires.get(0)).isEqualTo(utc("2026-10-24T07:00:00"));   // CEST, UTC+2
            assertThat(fires.get(1)).isEqualTo(utc("2026-10-25T08:00:00"));   // CET, UTC+1
            assertThat(fires.get(2)).isEqualTo(utc("2026-10-26T08:00:00"));
        }

        @Test
        @DisplayName("falls back to UTC for an unknown timezone instead of failing the window")
        void unknownTimezoneFallsBackToUtc() {
            // One corrupt row must not take the whole calendar response down.
            CronOccurrences.Window window = CronOccurrences.between(
                    "0 9 * * *", "Mars/Olympus_Mons", utc("2026-09-01T00:00:00"),
                    utc("2026-09-01T23:59:59"), 50);

            assertThat(window.occurrences()).containsExactly(utc("2026-09-01T09:00:00"));
        }

        @Test
        @DisplayName("returns nothing for an invalid cron, an inverted window, or a non-positive cap")
        void degradesToEmpty() {
            Instant from = utc("2026-09-01T00:00:00");
            Instant to = utc("2026-09-02T00:00:00");

            // An expression Spring cannot parse at all. It used to be "*/120", which
            // Spring parses perfectly well - the emptiness came from the strict validator
            // gating the walk, and asserting it here pinned the very behaviour that made
            // those schedules inert. A cron that fires must be drawn.
            assertThat(CronOccurrences.between("not a cron", "UTC", from, to, 50).occurrences()).isEmpty();
            assertThat(CronOccurrences.between("0 9 * * *", "UTC", to, from, 50).occurrences()).isEmpty();
            assertThat(CronOccurrences.between("0 9 * * *", "UTC", from, to, 0).occurrences()).isEmpty();
            assertThat(CronOccurrences.between("0 9 * * *", "UTC", null, to, 50).occurrences()).isEmpty();
        }

        @Test
        @DisplayName("never returns a fire past the window end, even across a spring-forward gap")
        void springForwardCannotEscapeTheWindow() {
            // The window is an interval of INSTANTS; the walk cursors in local time, and on
            // the day the clocks go forward the two disagree. A 02:30 slot does not exist,
            // so Spring answers 03:30 local, whose instant is an hour later than the local
            // comparison believes - and the walk returned it despite the window ending 30
            // minutes earlier. A chip drawn on a day the caller never asked about, and one
            // the next window would draw again.
            Instant to = utc("2026-03-29T01:00:00");

            CronOccurrences.Window window = CronOccurrences.between(
                    "30 2 * * *", "Europe/Paris", utc("2026-03-28T00:00:00"), to, 50);

            assertThat(window.occurrences()).allSatisfy(fire ->
                    assertThat(fire).isBeforeOrEqualTo(to));
        }

        @Test
        @DisplayName("still returns a fire exactly ON the window end")
        void theWindowEndIsInclusive() {
            // The other side of the same comparison: tightening it must not start dropping
            // the boundary occurrence, which is the one a day view opening at 09:00 needs.
            Instant nine = utc("2026-06-15T09:00:00");

            assertThat(CronOccurrences.between("0 9 * * *", "UTC", nine, nine, 5).occurrences())
                    .containsExactly(nine);
        }

        @Test
        @DisplayName("honours the cap alone when the window has no end")
        void openEndedWindowIsBoundedByCap() {
            CronOccurrences.Window window = CronOccurrences.between(
                    "0 9 * * *", "UTC", utc("2026-09-01T00:00:00"), null, 5);

            assertThat(window.occurrences()).hasSize(5);
            assertThat(window.truncated()).isTrue();
        }
    }

    @Nested
    @DisplayName("toSpringCron")
    class SpringForm {

        @Test
        @DisplayName("prepends a seconds field to a 5-field expression and leaves 6-field alone")
        void normalisesFieldCount() {
            assertThat(CronOccurrences.toSpringCron("0 9 * * *")).isEqualTo("0 0 9 * * *");
            assertThat(CronOccurrences.toSpringCron("30 0 9 * * *")).isEqualTo("30 0 9 * * *");
            assertThat(CronOccurrences.toSpringCron("  0 9 * * *  ")).isEqualTo("0 0 9 * * *");
            assertThat(CronOccurrences.toSpringCron(null)).isNull();
        }
    }
}
