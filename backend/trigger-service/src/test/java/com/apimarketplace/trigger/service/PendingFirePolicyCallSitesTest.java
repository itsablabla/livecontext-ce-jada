package com.apimarketplace.trigger.service;

import com.apimarketplace.trigger.client.dto.StandaloneScheduleRequest;
import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * That the pending-fire rule is actually APPLIED where the row is written.
 *
 * <p>{@link PendingFirePolicyTest} proves the function is right. It cannot prove the
 * function is called, and that is the half that has already gone wrong: eight places in
 * this service wrote {@code next_execution_at}, seven recomputed it unconditionally, and
 * the first fix repaired one of the eight while a doc line claimed the override was
 * durable in general. A pure-function test would not have noticed, and would not notice a
 * site being added back or dropped.
 *
 * <p>Each case here asks the two questions that matter at a call site: does a rewrite with
 * the SAME shape keep the user's moved occurrence, and does a genuine shape change still
 * recompute? A fix that only ever preserved would be the opposite bug, leaving a paused
 * schedule advertising a fire time that had already gone by.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PendingFirePolicy - applied at the write sites")
class PendingFirePolicyCallSitesTest {

    @Mock private ScheduledExecutionRepository scheduleRepository;
    @Mock private ScheduleCronParser cronParser;
    @Mock private PlanLimitHelper planLimitHelper;

    private StandaloneScheduleService service;

    private static final String TENANT = "1";
    private static final String ORG = "d040d0cd-52b2-4bb0-9b57-25597e35050f";
    private static final UUID SCHEDULE_ID = UUID.randomUUID();

    /** The occurrence the user dragged to a new time. Far enough ahead to stay pending. */
    private static final Instant MOVED_TO = Instant.now().plusSeconds(7200);
    /** Where the cron would put it instead. */
    private static final Instant CRON_SLOT = Instant.now().plusSeconds(86_400);

    @BeforeEach
    void setUp() {
        service = new StandaloneScheduleService(scheduleRepository, cronParser, planLimitHelper);
        when(cronParser.isAcceptableInput(any())).thenReturn(true);
        when(cronParser.getNextExecution(any(), any())).thenReturn(CRON_SLOT);
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ScheduledExecutionEntity movedRow() {
        ScheduledExecutionEntity e = new ScheduledExecutionEntity();
        e.setId(SCHEDULE_ID);
        e.setTenantId(TENANT);
        e.setOrganizationId(ORG);
        e.setCronExpression("0 9 * * *");
        e.setTimezone("Europe/Paris");
        e.setNextExecutionAt(MOVED_TO);
        return e;
    }

    private void givenRow(ScheduledExecutionEntity row) {
        when(scheduleRepository.findByIdAndOrganizationIdStrict(SCHEDULE_ID, ORG))
                .thenReturn(Optional.of(row));
    }

    @Nested
    @DisplayName("update - the path sync takes on every save, pin and run start")
    class Update {

        @Test
        @DisplayName("keeps the moved occurrence when the cron is unchanged")
        void sameCronKeepsTheMove() {
            // This is the site whose own comment calls it "the shortest-lived way to lose a
            // moved occurrence": sync re-upserts with the SAME cron constantly, so before the
            // policy a drag survived only until the workflow next ran.
            ScheduledExecutionEntity row = movedRow();
            givenRow(row);

            service.update(TENANT, ORG, SCHEDULE_ID,
                    new StandaloneScheduleRequest(null, null, "0 9 * * *", "Europe/Paris",
                            null, true, null, null));

            assertThat(row.getNextExecutionAt()).isEqualTo(MOVED_TO);
        }

        @Test
        @DisplayName("recomputes when the caller genuinely changes the cron")
        void changedCronRecomputes() {
            // The other half. Without it a schedule edited to a new cadence would keep firing
            // at the old slot once more.
            ScheduledExecutionEntity row = movedRow();
            givenRow(row);

            service.update(TENANT, ORG, SCHEDULE_ID,
                    new StandaloneScheduleRequest(null, null, "0 18 * * *", "Europe/Paris",
                            null, true, null, null));

            assertThat(row.getNextExecutionAt()).isEqualTo(CRON_SLOT);
        }

        @Test
        @DisplayName("a TIMEZONE-only change recomputes, even with no cron in the request")
        void timezoneOnlyChangeRecomputes() {
            // The cron used to be handled inside its own block while the timezone was
            // written after it, so this update changed the schedule's shape and left the
            // fire time pointing at the old zone's slot - the one case the policy exists to
            // recompute, skipped because the two halves of "shape" lived in different places.
            ScheduledExecutionEntity row = movedRow();   // Europe/Paris
            givenRow(row);

            service.update(TENANT, ORG, SCHEDULE_ID,
                    new StandaloneScheduleRequest(null, null, null, "Asia/Kolkata",
                            null, true, null, null));

            assertThat(row.getTimezone()).isEqualTo("Asia/Kolkata");
            assertThat(row.getNextExecutionAt())
                    .as("09:00 means a different instant now; the stored slot is stale")
                    .isEqualTo(CRON_SLOT);
        }

        @Test
        @DisplayName("a name-only change keeps the cron AND leaves a moved occurrence alone")
        void nameOnlyChangeIsNotAShapeChange() {
            // Resolving the pair must not turn every partial edit into a recompute. The
            // request carries no cron, so the row's own is reused and nothing about the
            // shape has moved.
            ScheduledExecutionEntity row = movedRow();
            givenRow(row);

            service.update(TENANT, ORG, SCHEDULE_ID,
                    new StandaloneScheduleRequest("Renamed", null, null, "Europe/Paris",
                            null, true, null, null));

            assertThat(row.getCronExpression()).isEqualTo("0 9 * * *");
            assertThat(row.getNextExecutionAt()).isEqualTo(MOVED_TO);
        }

        @Test
        @DisplayName("an omitted timezone IS \"UTC\", and that counts as a shape change")
        void omittedTimezoneMeansUtcByContract() {
            // Worth pinning because it looks like a bug and is not. A review flagged the
            // absent zone as falling back to UTC and arming a Europe/Paris row at the wrong
            // wall-clock time. StandaloneScheduleRequest's compact constructor normalises an
            // absent zone to "UTC" before this code sees it, so "omitted" and "UTC" are the
            // same request by contract - a caller cannot say "keep the zone" through this
            // DTO, and adding a fallback for it would be unreachable code implying otherwise.
            ScheduledExecutionEntity row = movedRow();
            givenRow(row);

            service.update(TENANT, ORG, SCHEDULE_ID,
                    new StandaloneScheduleRequest(null, null, "0 9 * * *", null,
                            null, true, null, null));

            org.mockito.Mockito.verify(cronParser).getNextExecution("0 9 * * *", "UTC");
            // Same cron, but "UTC" against a stored Europe/Paris is a genuine zone change,
            // so the move does NOT survive it. That is the policy working, not failing.
            assertThat(row.getNextExecutionAt()).isEqualTo(CRON_SLOT);
        }
    }

    @Nested
    @DisplayName("an unresolvable timezone is refused, not stored")
    class UnknownTimezone {

        /*
         * Not a validation nicety. The arming maths answers null for a zone the platform
         * cannot resolve, and EVERY caller that arms a row falls back to "in 60 seconds" -
         * then record-execution recomputes the same null and falls back again. A stored
         * unresolvable zone therefore made the schedule fire every minute forever, spending
         * credits on each run, and the legacy-cron reaper could not catch it because its
         * predicate asks whether the CRON is valid and the cron is fine.
         *
         * A doc line and a code comment both asserted that returning empty "leaves it
         * inert". It does not, and believing it did is what kept this open.
         */

        @Test
        @DisplayName("create refuses it")
        void createRefusesAnUnknownZone() {
            assertThatThrownBy(() -> service.create(TENANT, ORG, "PRO",
                    new StandaloneScheduleRequest(null, null, "0 9 * * *", "Mars/Olympus",
                            null, true, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown timezone");
        }

        @Test
        @DisplayName("update refuses it, and the row keeps the zone it had")
        void updateRefusesAnUnknownZone() {
            ScheduledExecutionEntity row = movedRow();
            givenRow(row);

            assertThatThrownBy(() -> service.update(TENANT, ORG, SCHEDULE_ID,
                    new StandaloneScheduleRequest(null, null, "0 9 * * *", "Mars/Olympus",
                            null, true, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown timezone");
            assertThat(row.getTimezone()).isEqualTo("Europe/Paris");
        }

        @Test
        @DisplayName("a real zone still passes - the guard must not refuse everything")
        void realZonesStillPass() {
            // The over-correction: a guard that rejected any zone it did not recognise
            // would lock every user out of their own schedules.
            ScheduledExecutionEntity row = movedRow();
            givenRow(row);

            service.update(TENANT, ORG, SCHEDULE_ID,
                    new StandaloneScheduleRequest(null, null, "0 9 * * *", "Pacific/Kiritimati",
                            null, true, null, null));

            assertThat(row.getTimezone()).isEqualTo("Pacific/Kiritimati");
        }
    }

    @Nested
    @DisplayName("toggle - resume")
    class Toggle {

        @Test
        @DisplayName("resuming keeps a moved occurrence still ahead")
        void resumeKeepsTheMove() {
            // The paused marker promises "would resume {when}" using this value. Recomputing
            // on resume threw that away and fired at the cron slot, contradicting what the
            // UI had just told the user.
            ScheduledExecutionEntity row = movedRow();
            row.setEnabled(false);
            givenRow(row);

            service.toggle(TENANT, ORG, SCHEDULE_ID, true);

            assertThat(row.getNextExecutionAt()).isEqualTo(MOVED_TO);
        }

        @Test
        @DisplayName("resuming recomputes a fire time the pause outlived")
        void resumeRecomputesAnExpiredFire() {
            // Keeping a past instant would have the daemon claim it within the minute - its
            // query is `next_execution_at <= now` - and advertise a resume time already gone.
            ScheduledExecutionEntity row = movedRow();
            row.setEnabled(false);
            row.setNextExecutionAt(Instant.now().minusSeconds(600));
            givenRow(row);

            service.toggle(TENANT, ORG, SCHEDULE_ID, true);

            assertThat(row.getNextExecutionAt()).isEqualTo(CRON_SLOT);
        }

        @Test
        @DisplayName("pausing does not touch the fire time at all")
        void pauseLeavesItAlone() {
            // The value is what the rail shows as "would resume {when}".
            ScheduledExecutionEntity row = movedRow();
            givenRow(row);

            service.toggle(TENANT, ORG, SCHEDULE_ID, false);

            assertThat(row.getNextExecutionAt()).isEqualTo(MOVED_TO);
        }
    }
}
