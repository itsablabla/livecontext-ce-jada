package com.apimarketplace.orchestrator.services.agenda;

import com.apimarketplace.orchestrator.schedule.ScheduleExecutorService;
import com.apimarketplace.orchestrator.services.agenda.AgendaActionService.ActionResult;
import com.apimarketplace.orchestrator.services.agenda.AgendaActionService.Failure;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agenda's three writes.
 *
 * <p>The run-early case carries the subtlest requirement in the feature. The daemon's
 * {@code executeNow} advances the schedule past its pending slot, which is correct for a
 * fire it is claiming but wrong for a user who asked to run a job EARLY: they did not ask
 * to skip the run they can see on the calendar. So the pending time is written back - and
 * deliberately is NOT written back when it was already due, because that would hand the
 * daemon a second fire within the minute.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgendaActionService")
class AgendaActionServiceTest {

    @Mock private TriggerClient triggerClient;
    @Mock private ScheduleExecutorService scheduleExecutorService;

    private AgendaActionService service;

    private static final UUID SCHEDULE_ID = UUID.randomUUID();
    private static final String TENANT = "user-1";
    private static final String ORG = "org-1";

    @BeforeEach
    void setUp() {
        service = new AgendaActionService(triggerClient, scheduleExecutorService);
    }

    private ScheduledExecutionDto schedule(String cron, Instant nextFire) {
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(SCHEDULE_ID);
        dto.setTenantId(TENANT);
        dto.setOrganizationId(ORG);
        dto.setCronExpression(cron);
        dto.setTimezone("UTC");
        dto.setEnabled(true);
        dto.setIsActive(true);
        dto.setNextExecutionAt(nextFire);
        return dto;
    }

    private void givenSchedule(ScheduledExecutionDto dto) {
        when(triggerClient.getSchedule(SCHEDULE_ID)).thenReturn(dto);
    }

    private static TriggerExecutionResult fired() {
        return TriggerExecutionResult.success("run-1", "trigger:daily", TriggerType.SCHEDULE, Set.of(), 4);
    }

    @Nested
    @DisplayName("move - this occurrence only")
    class MoveNext {

        @Test
        @DisplayName("writes the new fire time and never touches the cron")
        void writesNextFireOnly() {
            Instant target = Instant.now().plusSeconds(7200);
            givenSchedule(schedule("0 9 * * *", Instant.now().plusSeconds(3600)));
            when(triggerClient.setScheduleNextFire(eq(SCHEDULE_ID), eq(target), isNull(), eq(ORG), eq(TENANT)))
                    .thenReturn(schedule("0 9 * * *", target));

            ActionResult result = service.moveNextOccurrence(SCHEDULE_ID, target, null, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
            // A caller that named no occurrence has nothing to compare against, so the
            // write is unguarded - the expectation must be null, not invented.
            verify(triggerClient).setScheduleNextFire(SCHEDULE_ID, target, null, ORG, TENANT);
            verify(triggerClient, never()).updateScheduleCron(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses a move aimed at an occurrence that is not the pending one")
        void refusesANonPendingOccurrence() {
            // The calendar draws up to 200 identical-looking chips for one schedule, but the
            // row holds ONE pending fire. Writing the 25th's new time into it does not move
            // the 25th - it cancels every run from now until then, while the dialog promises
            // "later runs return to their usual slot".
            Instant pending = Instant.now().plusSeconds(3600);
            Instant aLaterOccurrence = pending.plusSeconds(20 * 86400);
            givenSchedule(schedule("0 9 * * *", pending));

            ActionResult result = service.moveNextOccurrence(
                    SCHEDULE_ID, aLaterOccurrence.plusSeconds(1800), aLaterOccurrence, TENANT, ORG);

            assertThat(result.failure()).isEqualTo(Failure.NOT_THE_NEXT_OCCURRENCE);
            // The reason carries the fire that CAN be moved, so the page can say which one.
            assertThat(result.detail()).isEqualTo(pending.toString());
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("accepts a move that names the pending occurrence")
        void acceptsThePendingOccurrence() {
            Instant pending = Instant.now().plusSeconds(3600);
            Instant target = pending.plusSeconds(7200);
            givenSchedule(schedule("0 9 * * *", pending));
            when(triggerClient.setScheduleNextFire(eq(SCHEDULE_ID), eq(target), eq(pending), eq(ORG), eq(TENANT)))
                    .thenReturn(schedule("0 9 * * *", target));

            ActionResult result = service.moveNextOccurrence(SCHEDULE_ID, target, pending, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
            // The occurrence the guard checked is FORWARDED, so trigger-service can refuse
            // the write if the row moved on in between. Dropping it here would leave the
            // check and the write a network hop apart, which is the race it exists to close.
            verify(triggerClient).setScheduleNextFire(SCHEDULE_ID, target, pending, ORG, TENANT);
        }

        @Test
        @DisplayName("refuses a schedule from another workspace without disclosing it exists")
        void refusesCrossScope() {
            ScheduledExecutionDto foreign = schedule("0 9 * * *", Instant.now().plusSeconds(3600));
            foreign.setTenantId("someone-else");
            foreign.setOrganizationId("org-other");
            givenSchedule(foreign);

            ActionResult result = service.moveNextOccurrence(SCHEDULE_ID, Instant.now().plusSeconds(7200), null, TENANT, ORG);

            assertThat(result.failure()).isEqualTo(Failure.NOT_FOUND);
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses to move a schedule that will never fire again")
        void refusesToMoveAnUnarmedSchedule() {
            // Rescheduling something that can never run reports a success the user cannot
            // observe: the calendar draws no occurrence for it either way.
            ScheduledExecutionDto paused = schedule("0 9 * * *", Instant.now().plusSeconds(3600));
            paused.setEnabled(false);
            givenSchedule(paused);

            assertThat(service.moveNextOccurrence(SCHEDULE_ID, Instant.now().plusSeconds(7200),
                    null, TENANT, ORG).failure()).isEqualTo(Failure.NOT_ARMED);
            assertThat(service.moveAllOccurrences(SCHEDULE_ID,
                    Instant.parse("2026-09-03T14:30:00Z"), TENANT, ORG).failure())
                    .isEqualTo(Failure.NOT_ARMED);
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
            verify(triggerClient, never()).updateScheduleCron(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses an unknown schedule and an anonymous caller")
        void refusesUnknownAndAnonymous() {
            when(triggerClient.getSchedule(SCHEDULE_ID)).thenReturn(null);
            assertThat(service.moveNextOccurrence(SCHEDULE_ID, Instant.now(), null, TENANT, ORG).failure())
                    .isEqualTo(Failure.NOT_FOUND);

            assertThat(service.moveNextOccurrence(SCHEDULE_ID, Instant.now(), null, null, ORG).failure())
                    .isEqualTo(Failure.NOT_FOUND);
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("move - all occurrences")
    class MoveAll {

        @Test
        @DisplayName("rewrites a daily cron to the new time of day")
        void rewritesDailyCron() {
            Instant target = Instant.parse("2026-09-03T14:30:00Z");
            givenSchedule(schedule("0 9 * * *", Instant.now().plusSeconds(3600)));
            when(triggerClient.updateScheduleCron(eq(SCHEDULE_ID), eq("30 14 * * *"), eq("UTC"), eq(ORG), eq(TENANT)))
                    .thenReturn(schedule("30 14 * * *", target));

            ActionResult result = service.moveAllOccurrences(SCHEDULE_ID, target, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
            verify(triggerClient).updateScheduleCron(SCHEDULE_ID, "30 14 * * *", "UTC", ORG, TENANT);
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses an interval rather than inventing a frequency for it")
        void refusesAmbiguousPattern() {
            // "Every 15 minutes" has no time of day. Rewriting it to a daily 14:30 would
            // silently stop 95 runs a day the user is relying on.
            givenSchedule(schedule("*/15 * * * *", Instant.now().plusSeconds(60)));

            ActionResult result = service.moveAllOccurrences(
                    SCHEDULE_ID, Instant.parse("2026-09-03T14:30:00Z"), TENANT, ORG);

            assertThat(result.failure()).isEqualTo(Failure.PATTERN_NOT_SHIFTABLE);
            verify(triggerClient, never()).updateScheduleCron(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("reports a weekday-set miss specifically, so the page can explain it")
        void reportsWeekdaySetMiss() {
            givenSchedule(schedule("30 8 * * 1-5", Instant.now().plusSeconds(3600)));

            ActionResult result = service.moveAllOccurrences(
                    SCHEDULE_ID, Instant.parse("2026-09-05T18:00:00Z"), TENANT, ORG);   // a Saturday

            assertThat(result.failure()).isEqualTo(Failure.WEEKDAY_SET_NOT_MATCHED);
        }

        @Test
        @DisplayName("reports an unsafe day-of-month specifically")
        void reportsUnsafeDayOfMonth() {
            givenSchedule(schedule("0 9 1 * *", Instant.now().plusSeconds(3600)));

            ActionResult result = service.moveAllOccurrences(
                    SCHEDULE_ID, Instant.parse("2026-09-30T09:00:00Z"), TENANT, ORG);

            assertThat(result.failure()).isEqualTo(Failure.DAY_OF_MONTH_UNSAFE);
        }
    }

    @Nested
    @DisplayName("run now")
    class RunNow {

        @Test
        @DisplayName("restores the pending occurrence after the early run, so it still happens")
        void restoresPendingOccurrence() {
            Instant pending = Instant.now().plusSeconds(3600);
            Instant advancedBySideEffect = pending.plusSeconds(86400);
            givenSchedule(schedule("0 9 * * *", pending));
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());
            when(triggerClient.getSchedule(SCHEDULE_ID)).thenReturn(
                    schedule("0 9 * * *", pending), schedule("0 9 * * *", advancedBySideEffect));
            when(triggerClient.setScheduleNextFire(eq(SCHEDULE_ID), eq(pending), eq(ORG), eq(TENANT)))
                    .thenReturn(schedule("0 9 * * *", pending));

            ActionResult result = service.runNow(SCHEDULE_ID, true, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
            verify(scheduleExecutorService).executeNow(any());
            verify(triggerClient).setScheduleNextFire(SCHEDULE_ID, pending, ORG, TENANT);
            assertThat(result.schedule().getNextExecutionAt()).isEqualTo(pending);
        }

        @Test
        @DisplayName("a run that STARTED is never reported as a failure, even if the write-back throws")
        void writeBackFailureDoesNotMaskASuccessfulRun() {
            // The run has happened; credits are spent. setScheduleNextFire propagates its
            // errors by design, so a 409 (archived in the interval) or a timeout used to
            // escape runNow, be caught by the controller's pass-through, and answer
            // SCHEDULE_REJECTED - telling the user their run did not happen and inviting a
            // second click on the ONE call this feature marks non-idempotent.
            //
            // Losing the occurrence-restore is the lesser failure and is visible on the
            // calendar. A false negative on a run that happened is not.
            Instant pending = Instant.now().plusSeconds(3600);
            givenSchedule(schedule("0 9 * * *", pending));
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());
            when(triggerClient.setScheduleNextFire(any(), any(), any(), any()))
                    .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                            org.springframework.http.HttpStatus.CONFLICT, "archived"));

            ActionResult result = service.runNow(SCHEDULE_ID, true, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("consuming the occurrence WRITES the following slot - the advance does not")
        void consumesOccurrenceWhenAsked() {
            // The defect this covers: "Run instead of the scheduled run" did nothing at all.
            // The optimistic advance recomputes the next slot FROM NOW, and an early run is
            // before the occurrence it replaces, so for a daily 09:00 job run the evening
            // before, "the next slot from now" IS tomorrow 09:00 - the very fire the user
            // asked to give up. The row came back unchanged, the calendar redrew it, and the
            // scheduled run happened anyway on top of the one just paid for.
            Instant pending = Instant.parse("2030-06-10T09:00:00Z");
            Instant following = Instant.parse("2030-06-11T09:00:00Z");
            givenSchedule(schedule("0 9 * * *", pending));
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());
            when(triggerClient.setScheduleNextFire(eq(SCHEDULE_ID), eq(following), eq(pending),
                    eq(ORG), eq(TENANT))).thenReturn(schedule("0 9 * * *", following));

            ActionResult result = service.runNow(SCHEDULE_ID, false, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
            // The value observed before the write is passed as the compare-and-set, so the
            // daemon claiming the fire in the interval makes trigger-service refuse rather
            // than let this stamp over the slot AFTER the one being given up.
            verify(triggerClient).setScheduleNextFire(SCHEDULE_ID, following, pending, ORG, TENANT);
            assertThat(result.schedule().getNextExecutionAt()).isEqualTo(following);
            // What the bell's sentence reads. "in place of the scheduled run" is only true
            // when this is true.
            assertThat(result.occurrenceConsumed()).isTrue();
        }

        @Test
        @DisplayName("an early run that KEEPS the occurrence answers nothing about consuming it")
        void keepingLeavesTheConsumedFlagUnset() {
            // Null rather than false: the question does not apply, and a false would read as
            // "we tried to give the occurrence up and could not".
            Instant pending = Instant.parse("2030-06-10T09:00:00Z");
            givenSchedule(schedule("0 9 * * *", pending));
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());

            assertThat(service.runNow(SCHEDULE_ID, true, TENANT, ORG).occurrenceConsumed()).isNull();
        }

        @Test
        @DisplayName("consuming asks the cron - it does not just add the interval it guesses")
        void consumesUsingTheCronsOwnNextSlot() {
            // A weekday-only schedule is the case a naive "+1 day" gets wrong: Friday's run
            // is followed by MONDAY, and inventing Saturday would arm a fire the expression
            // never produces, on a calendar that draws the expression.
            Instant friday = Instant.parse("2030-06-07T09:00:00Z");
            Instant monday = Instant.parse("2030-06-10T09:00:00Z");
            givenSchedule(schedule("0 9 * * 1-5", friday));
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());

            service.runNow(SCHEDULE_ID, false, TENANT, ORG);

            verify(triggerClient).setScheduleNextFire(SCHEDULE_ID, monday, friday, ORG, TENANT);
        }

        @Test
        @DisplayName("consuming an OVERDUE occurrence never arms a slot that is already due")
        void consumingAnOverdueOccurrenceArmsAFutureSlot() {
            // Walking from the fire itself would answer with a slot that is also in the past,
            // and the daemon claims anything at or before now on its next tick: the user asks
            // to SKIP a run and is given an extra one within the minute.
            //
            // The row still carries the overdue time AFTER the run, which is the shape of one
            // real situation and only one: the optimistic advance failed. `advanceSchedule`
            // swallows its HTTP error and returns null, and the run goes ahead anyway - so an
            // overdue fire that nothing moved is exactly what this method then reads. (When
            // the advance DOES land, the row already points past the overdue time and the
            // "already consumed elsewhere" guard answers first.)
            Instant overdue = Instant.now().minusSeconds(7200);
            givenSchedule(schedule("*/5 * * * *", overdue));
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());

            service.runNow(SCHEDULE_ID, false, TENANT, ORG);

            ArgumentCaptor<Instant> written = ArgumentCaptor.forClass(Instant.class);
            verify(triggerClient).setScheduleNextFire(eq(SCHEDULE_ID), written.capture(),
                    eq(overdue), eq(ORG), eq(TENANT));
            assertThat(written.getValue()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("leaves the occurrence alone when the cron has no slot after it")
        void doesNotConsumeWhenTheCronIsExhausted() {
            // An expression that can never fire again. "31 February" is the shape the cron
            // walk itself names: it parses, and the search for a next occurrence comes back
            // empty. There is then no later slot to point at, so the only honest answers are
            // to write nothing and to NOT tell the user their run replaced the scheduled one,
            // which with nothing written still stands.
            Instant pending = Instant.parse("2030-02-28T09:00:00Z");
            givenSchedule(schedule("0 9 31 2 *", pending));
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());

            ActionResult result = service.runNow(SCHEDULE_ID, false, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.occurrenceConsumed()).isFalse();
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses to arm a slot computed in UTC because the row's zone does not resolve")
        void doesNotConsumeWhenTheTimezoneDoesNotResolve() {
            // The rule that separates DRAWING a schedule from ARMING one. The calendar may
            // fall back to UTC rather than lose a month; this value goes into
            // next_execution_at, so falling back would start the schedule firing at a
            // wall-clock time nobody chose.
            Instant pending = Instant.parse("2030-06-10T09:00:00Z");
            ScheduledExecutionDto corrupt = schedule("0 9 * * *", pending);
            corrupt.setTimezone("Mars/Olympus_Mons");
            givenSchedule(corrupt);
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());

            ActionResult result = service.runNow(SCHEDULE_ID, false, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.occurrenceConsumed()).isFalse();
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("claims no consumption when the row cannot be re-read after the run")
        void doesNotConsumeWhenTheRowIsGone() {
            // The row is read a second time to compute the slot from what it NOW carries, and
            // that read can come back empty (archived and reaped in the interval, or
            // trigger-service refusing). The run happened, so it is still a success; nothing
            // was written, so nothing may be claimed.
            Instant pending = Instant.parse("2030-06-10T09:00:00Z");
            when(triggerClient.getSchedule(SCHEDULE_ID))
                    .thenReturn(schedule("0 9 * * *", pending), (ScheduledExecutionDto) null);
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());

            ActionResult result = service.runNow(SCHEDULE_ID, false, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.occurrenceConsumed()).isFalse();
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("claims no consumption when the schedule had no pending fire at all")
        void doesNotConsumeWhenThereWasNothingPending() {
            // A row with no next_execution_at draws no occurrence on the calendar, so there is
            // nothing to give up and nothing to move. This pins the null guard specifically:
            // the check below it is an EQUALITY, and two nulls are equal - drop the guard and
            // "the row still points at the occurrence" becomes true for a schedule that has
            // no occurrence, which then walks the cron from now and arms a fire nobody asked
            // for.
            givenSchedule(schedule("0 9 * * *", null));
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());

            ActionResult result = service.runNow(SCHEDULE_ID, false, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.occurrenceConsumed()).isFalse();
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("leaves it alone when the row was moved EARLIER, not only when moved past")
        void doesNotConsumeAnOccurrenceTheRowNoLongerPointsAt() {
            // The half a "has it moved past it" guard cannot see. Every cron write in
            // trigger-service recomputes the fire time (PendingFirePolicy returns the
            // recomputed value whenever the shape changed), so replacing a rare expression
            // with a frequent one moves the pending fire BACKWARDS - here from next June to
            // tomorrow. The occurrence the user was looking at no longer exists on this row,
            // and consuming it would stamp the slot after NEXT JUNE over tomorrow's fire,
            // skipping every run in between. That is a silent, unbounded loss of runs, which
            // is why the guard is equality rather than an ordering.
            Instant pending = Instant.parse("2030-06-10T09:00:00Z");
            Instant movedEarlier = Instant.parse("2030-06-11T09:00:00Z").minus(java.time.Duration.ofDays(300));
            when(triggerClient.getSchedule(SCHEDULE_ID)).thenReturn(
                    schedule("0 9 10 6 *", pending),        // before: once a year
                    schedule("0 9 * * *", movedEarlier));   // edited in between: every day
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());

            ActionResult result = service.runNow(SCHEDULE_ID, false, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.occurrenceConsumed()).isFalse();
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("reads the cron off the row as it stands AFTER the run")
        void usesTheCronTheRowCarriesNow() {
            // One read, one moment. The guard above means the write only ever happens while
            // the row still points at the occurrence being given up, so the expression in
            // force is whatever THAT read carried - and this pins that the walk uses it,
            // rather than a value captured before the run under a different set of
            // assumptions about what can change in between.
            Instant pending = Instant.parse("2030-06-10T09:00:00Z");
            givenSchedule(schedule("0 9 * * 1-5", pending));  // weekdays: the 10th is a Monday
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());

            service.runNow(SCHEDULE_ID, false, TENANT, ORG);

            verify(triggerClient).setScheduleNextFire(SCHEDULE_ID,
                    Instant.parse("2030-06-11T09:00:00Z"), pending, ORG, TENANT);
        }

        @Test
        @DisplayName("does not consume an occurrence something else already moved PAST")
        void doesNotConsumeTwice() {
            // The daemon can claim the fire while the manual run is in flight. Its advance is
            // the real consumption, so a second write here would skip the occurrence AFTER
            // the one the user gave up - a run silently lost, with nothing to see.
            Instant pending = Instant.parse("2030-06-10T09:00:00Z");
            Instant alreadyAdvanced = Instant.parse("2030-06-11T09:00:00Z");
            when(triggerClient.getSchedule(SCHEDULE_ID)).thenReturn(
                    schedule("0 9 * * *", pending), schedule("0 9 * * *", alreadyAdvanced));
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());

            ActionResult result = service.runNow(SCHEDULE_ID, false, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
            // And the bell must not say "in place of the scheduled run": the daemon consumed
            // this occurrence, so the user's early run was an EXTRA one.
            assertThat(result.occurrenceConsumed()).isFalse();
        }

        @Test
        @DisplayName("a consuming run that STARTED is not reported as a failure when the write throws")
        void consumeFailureDoesNotMaskASuccessfulRun() {
            // Same contract as the restore branch, and the reason the two share one catch:
            // the run has happened and credits are spent, so a 409 or a timeout on the
            // bookkeeping must not answer "it did not run" on the one call this feature
            // marks non-idempotent. The lost occurrence is visible on the calendar; a false
            // negative on a run that happened invites a second click.
            Instant pending = Instant.parse("2030-06-10T09:00:00Z");
            givenSchedule(schedule("0 9 * * *", pending));
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());
            when(triggerClient.setScheduleNextFire(any(), any(), any(), any(), any()))
                    .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                            org.springframework.http.HttpStatus.CONFLICT, "it fired in between"));

            ActionResult result = service.runNow(SCHEDULE_ID, false, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
            // Success, and NOT a replacement: the write is what would have given the
            // occurrence up, and it threw.
            assertThat(result.occurrenceConsumed()).isFalse();
        }

        @Test
        @DisplayName("does NOT restore an already-due occurrence - that would fire twice")
        void doesNotRestoreAnOverdueOccurrence() {
            // Writing an overdue time back makes the daemon claim it on the next tick, so
            // one click would produce two runs.
            Instant overdue = Instant.now().minusSeconds(120);
            givenSchedule(schedule("0 9 * * *", overdue));
            when(scheduleExecutorService.executeNow(any())).thenReturn(fired());

            ActionResult result = service.runNow(SCHEDULE_ID, true, TENANT, ORG);

            assertThat(result.isSuccess()).isTrue();
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("reports the failure and writes NOTHING - the undo belongs one layer down")
        void doesNotWriteBackOnFailure() {
            // The advance is optimistic: it lands BEFORE the work is attempted, so a failed
            // run leaves the schedule advanced past a run that never happened. Undoing that
            // is now ScheduleExecutorService's job, and deliberately so: this layer can only
            // reach next_execution_at, while the advance also moved execution_count and
            // last_execution_at. Putting one of three back here looked like the problem was
            // handled while a capped schedule stayed permanently retired, and gave the
            // column a second writer with different rules.
            //
            // ScheduleExecutorServiceFailedManualRunTest owns the restore itself. What
            // matters here is that this layer does not write - a second, partial undo is
            // exactly the shape of bug the move was meant to end.
            Instant pending = Instant.now().plusSeconds(3600);
            givenSchedule(schedule("0 9 * * *", pending));
            when(scheduleExecutorService.executeNow(any())).thenReturn(
                    TriggerExecutionResult.failure(null, null, TriggerType.SCHEDULE,
                            "Provider deepseek is not configured"));

            ActionResult result = service.runNow(SCHEDULE_ID, true, TENANT, ORG);

            assertThat(result.failure()).isEqualTo(Failure.EXECUTION_REFUSED);
            assertThat(result.detail()).contains("Provider deepseek");
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("writes nothing for an ALREADY-DUE occurrence either")
        void doesNotRestoreAnOverdueOccurrenceOnFailure() {
            // Same rule, different starting state. Kept as its own case because the overdue
            // shape is the one that tempts a future reader to reintroduce a write here.
            givenSchedule(schedule("0 9 * * *", Instant.now().minusSeconds(120)));
            when(scheduleExecutorService.executeNow(any())).thenReturn(
                    TriggerExecutionResult.failure(null, null, TriggerType.SCHEDULE, "Workflow is not pinned"));

            ActionResult result = service.runNow(SCHEDULE_ID, true, TENANT, ORG);

            assertThat(result.failure()).isEqualTo(Failure.EXECUTION_REFUSED);
            verify(triggerClient, never()).setScheduleNextFire(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses to run a paused schedule instead of quietly re-arming it")
        void refusesPausedSchedule() {
            ScheduledExecutionDto paused = schedule("0 9 * * *", Instant.now().plusSeconds(3600));
            paused.setEnabled(false);
            givenSchedule(paused);

            ActionResult result = service.runNow(SCHEDULE_ID, true, TENANT, ORG);

            assertThat(result.failure()).isEqualTo(Failure.NOT_ARMED);
            verify(scheduleExecutorService, never()).executeNow(any());
        }

        @Test
        @DisplayName("refuses a schedule that has exhausted its max-executions cap")
        void refusesAnExhaustedSchedule() {
            // "Armed" means enabled, not archived, AND under the cap - the same definition
            // the calendar greys a schedule out on. Checking only `enabled` let the endpoint
            // hand out a run the UI already says is over.
            ScheduledExecutionDto exhausted = schedule("0 9 * * *", Instant.now().plusSeconds(3600));
            exhausted.setMaxExecutions(5);
            exhausted.setExecutionCount(5);
            givenSchedule(exhausted);

            ActionResult result = service.runNow(SCHEDULE_ID, true, TENANT, ORG);

            assertThat(result.failure()).isEqualTo(Failure.NOT_ARMED);
            verify(scheduleExecutorService, never()).executeNow(any());
        }

        @Test
        @DisplayName("refuses an archived schedule, which never dispatches again")
        void refusesAnArchivedSchedule() {
            ScheduledExecutionDto archived = schedule("0 9 * * *", Instant.now().plusSeconds(3600));
            archived.setIsActive(false);
            givenSchedule(archived);

            assertThat(service.runNow(SCHEDULE_ID, true, TENANT, ORG).failure())
                    .isEqualTo(Failure.NOT_ARMED);
            verify(scheduleExecutorService, never()).executeNow(any());
        }

        @Test
        @DisplayName("refuses a schedule from another workspace")
        void refusesCrossScope() {
            ScheduledExecutionDto foreign = schedule("0 9 * * *", Instant.now().plusSeconds(3600));
            foreign.setOrganizationId("org-other");
            foreign.setTenantId("someone-else");
            givenSchedule(foreign);

            ActionResult result = service.runNow(SCHEDULE_ID, true, TENANT, ORG);

            assertThat(result.failure()).isEqualTo(Failure.NOT_FOUND);
            verify(scheduleExecutorService, never()).executeNow(any());
        }
    }
}
