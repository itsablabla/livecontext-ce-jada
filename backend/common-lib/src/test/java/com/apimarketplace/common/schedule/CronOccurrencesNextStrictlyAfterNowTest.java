package com.apimarketplace.common.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CronOccurrences#next} must never return a fire time that has already passed.
 *
 * <p>This is the daemon's entry point, not a display helper. trigger-service calls it to
 * compute {@code scheduled_executions.next_execution_at} every time a schedule is created,
 * toggled, or claimed after a fire. The claim query is
 * {@code WHERE next_execution_at <= now}, so a returned instant in the past is immediately
 * due again: the schedule is re-claimed on the following minute tick and the job runs a
 * SECOND time. Nothing downstream catches it - the in-JVM guard is per-pod and
 * {@code shouldExecuteNow} has no callers.
 *
 * <p>The trap is that {@code between} is INCLUSIVE of its window start (a calendar cell
 * beginning at 09:00 must draw the 09:00 job), so it walks from one second before. Sharing
 * that walk with "the next fire from now" silently makes the answer inclusive of the second
 * that just elapsed - which is exactly the second in which the daemon asks the question,
 * having just fired that very slot.
 */
class CronOccurrencesNextStrictlyAfterNowTest {

    @Test
    @DisplayName("never returns an instant at or before now, asked in the same second as a fire")
    void neverReturnsAPastInstant() {
        // A per-SECOND cron makes this deterministic: whenever it is asked, the previous
        // slot is always less than a second in the past, which is precisely the window the
        // defect lives in. With a minutely cron the same bug only shows if the call happens
        // to land in the second after :00, so it hides from any short test.
        for (int i = 0; i < 5; i++) {
            Instant asked = Instant.now();
            List<Instant> next = CronOccurrences.next("* * * * * *", "UTC", 1);

            assertThat(next).isNotEmpty();
            assertThat(next.get(0))
                    .as("next fire computed at %s must be strictly after it", asked)
                    .isAfter(asked);
        }
    }

    @Test
    @DisplayName("between never reaches back BEFORE its own window start")
    void betweenDoesNotOverreachBackwards() {
        // The root cause, stated without a clock. `between` is inclusive of `from`, and the
        // walk starts before it to achieve that - but starting a whole SECOND early makes it
        // inclusive of the entire preceding second too. `next()` passes Instant.now() as
        // `from`, so that surplus second is the one the daemon just fired in.
        Instant justAfterTheSlot = Instant.parse("2026-09-01T14:05:00.005Z");

        List<Instant> found = CronOccurrences
                .between("* * * * *", "UTC", justAfterTheSlot, null, 1).occurrences();

        assertThat(found).isNotEmpty();
        assertThat(found.get(0))
                .as("an occurrence before `from` is outside the requested window")
                .isAfterOrEqualTo(justAfterTheSlot);
    }

    @Test
    @DisplayName("a schedule created in the same second as its own slot does not fire immediately")
    void doesNotArmAScheduleOnASlotThatJustPassed() {
        // The creation path (StandaloneScheduleService / ScheduleController) writes this
        // value straight into next_execution_at. A value at or before now means the very
        // next daemon tick claims it.
        Instant before = Instant.now();
        Instant armed = CronOccurrences.next("* * * * * *", "UTC", 1).get(0);

        assertThat(armed).isAfter(before);
        assertThat(armed).isAfter(Instant.now().minusSeconds(1));
    }

    @Test
    @DisplayName("every returned fire is in the future and strictly ascending")
    void allReturnedFiresAreFuture() {
        Instant asked = Instant.now();
        List<Instant> next = CronOccurrences.next("* * * * * *", "UTC", 3);

        assertThat(next).hasSize(3);
        assertThat(next).isSorted();
        assertThat(next).allSatisfy(fire -> assertThat(fire).isAfter(asked));
    }

    @Test
    @DisplayName("an unresolvable timezone leaves the daemon path INERT, it does not fall back to UTC")
    void unknownTimezoneDoesNotArmAnything() {
        // The two callers need opposite failure modes. The calendar draws a corrupt row in
        // UTC rather than fail a whole month. The daemon must NOT: its answer is written to
        // next_execution_at, so a UTC fallback silently starts firing a schedule at a
        // wall-clock time nobody chose. Empty keeps it inert, which is what it did before
        // the maths moved into this class.
        assertThat(CronOccurrences.next("0 9 * * *", "Mars/Olympus_Mons", 1)).isEmpty();
        assertThat(CronOccurrences.isResolvableTimezone("Mars/Olympus_Mons")).isFalse();

        // Blank means UTC, and must stay armable.
        assertThat(CronOccurrences.next("0 9 * * *", "", 1)).isNotEmpty();
        assertThat(CronOccurrences.next("0 9 * * *", null, 1)).isNotEmpty();

        // The calendar keeps its tolerant behaviour.
        assertThat(CronOccurrences.between("0 9 * * *", "Mars/Olympus_Mons",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T23:59:59Z"), 5)
                .occurrences()).containsExactly(Instant.parse("2026-09-01T09:00:00Z"));
    }

    @Test
    @DisplayName("between STAYS inclusive of its window start - the calendar depends on it")
    void betweenRemainsInclusive() {
        // The fix must not be "stop walking from one second early" everywhere: a day view
        // opening at 09:00 has to show the 09:00 job. The two callers want different
        // boundaries, and this pins that they keep them.
        Instant nineOClock = Instant.parse("2026-09-01T09:00:00Z");

        assertThat(CronOccurrences.between("0 9 * * *", "UTC", nineOClock, nineOClock, 1).occurrences())
                .containsExactly(nineOClock);
    }
}
