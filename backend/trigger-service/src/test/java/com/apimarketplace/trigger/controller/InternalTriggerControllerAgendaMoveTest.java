package com.apimarketplace.trigger.controller;

import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.domain.TriggerState;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.service.ScheduleCronParser;
import com.apimarketplace.trigger.service.StandaloneChatEndpointService;
import com.apimarketplace.trigger.service.StandaloneFormEndpointService;
import com.apimarketplace.trigger.service.StandaloneScheduleService;
import com.apimarketplace.trigger.service.StandaloneWebhookService;
import com.apimarketplace.trigger.service.TriggerLifecycleManager;
import com.apimarketplace.trigger.service.WebhookTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two writes the agenda performs when a user drags an occurrence.
 *
 * <p>The invariant worth protecting is the difference between them: "this occurrence
 * only" must leave the cron untouched (so later runs return to their normal slot), and
 * "all occurrences" must re-derive the pending fire (so the schedule does not fire once
 * more at the time the user just moved away from). Both are asserted explicitly, along
 * with the refusals, because a silent accept here writes a schedule the user cannot see
 * is wrong until a run they were counting on does not happen.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalTriggerController - agenda occurrence moves")
class InternalTriggerControllerAgendaMoveTest {

    @Mock private WebhookTokenService tokenService;
    @Mock private StandaloneWebhookService standaloneWebhookService;
    @Mock private StandaloneScheduleService standaloneScheduleService;
    @Mock private StandaloneChatEndpointService chatEndpointService;
    @Mock private StandaloneFormEndpointService formEndpointService;
    @Mock private StandaloneWebhookRepository webhookRepository;
    @Mock private StandaloneChatEndpointRepository chatEndpointRepository;
    @Mock private StandaloneFormEndpointRepository formEndpointRepository;
    @Mock private ScheduledExecutionRepository scheduleRepository;
    @Mock private TriggerLifecycleManager triggerLifecycleManager;

    private InternalTriggerController controller;

    private static final UUID SCHEDULE_ID = UUID.randomUUID();
    private static final String TENANT = "user-1";
    private static final String ORG = "org-1";
    private static final Instant ORIGINAL_NEXT = Instant.parse("2026-09-02T09:00:00Z");

    @BeforeEach
    void setUp() {
        // A real parser, not a mock: these endpoints exist to reject bad cron, and a
        // stubbed validator would certify whatever the test happens to assume.
        controller = new InternalTriggerController(tokenService, standaloneWebhookService,
                standaloneScheduleService, chatEndpointService, formEndpointService,
                webhookRepository, chatEndpointRepository, formEndpointRepository,
                scheduleRepository, new ScheduleCronParser(), triggerLifecycleManager);
    }

    private ScheduledExecutionEntity schedule() {
        ScheduledExecutionEntity e = new ScheduledExecutionEntity();
        e.setId(SCHEDULE_ID);
        e.setWorkflowId(UUID.randomUUID());
        e.setTriggerId("trigger:daily");
        e.setTenantId(TENANT);
        e.setOrganizationId(ORG);
        e.setCronExpression("0 9 * * *");
        e.setTimezone("UTC");
        e.setEnabled(true);
        e.setState(TriggerState.ACTIVE);
        e.setNextExecutionAt(ORIGINAL_NEXT);
        return e;
    }

    private void givenSchedule(ScheduledExecutionEntity entity) {
        when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(entity));
        when(scheduleRepository.save(any(ScheduledExecutionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Toggle loads through the strict-org finder and re-reads by id after arming, so it
     * needs both lookups stubbed - unlike the move endpoints, which only use findById.
     */
    private void givenToggleableSchedule(ScheduledExecutionEntity entity) {
        when(scheduleRepository.findByIdAndOrganizationIdStrict(SCHEDULE_ID, ORG))
                .thenReturn(Optional.of(entity));
        when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(entity));
    }

    private static Map<String, Object> body(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    @Nested
    @DisplayName("next-fire (this occurrence only)")
    class NextFire {

        @Test
        @DisplayName("refuses the move when the row no longer carries the fire the caller checked")
        void refusesWhenThePendingFireMovedUnderneath() {
            // The agenda refuses a move aimed at anything but the pending fire, but that
            // check ran in orchestrator while the write lands here - two services and a
            // network hop apart. Drag the 09:00 chip at 08:59:59: the check passes, the
            // daemon fires 09:00 and advances the row, and this write then stamps 14:30
            // TODAY over tomorrow's slot. An extra run the user did not ask for, tomorrow's
            // skipped, and nothing anywhere reports it. Closing the window means checking
            // where the write happens.
            ScheduledExecutionEntity entity = schedule();
            entity.setNextExecutionAt(Instant.parse("2026-09-03T09:00:00Z"));  // already advanced
            // findById only: a refusal must not reach save at all, so stubbing it would be
            // an unused stub - and Mockito saying so is itself part of the assertion.
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(entity));

            Map<String, Object> request = body("nextFireAt", "2026-09-02T14:30:00Z");
            request.put("expectedNextFireAt", "2026-09-02T09:00:00Z");         // what the caller saw

            ResponseEntity<?> response = controller.setScheduleNextFire(SCHEDULE_ID, TENANT, ORG, request);

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(entity.getNextExecutionAt())
                    .as("the advanced slot must survive a stale move")
                    .isEqualTo(Instant.parse("2026-09-03T09:00:00Z"));
            verify(scheduleRepository, never()).save(any(ScheduledExecutionEntity.class));
        }

        @Test
        @DisplayName("applies the move when the expected fire still matches")
        void appliesWhenTheExpectationHolds() {
            // The guard must not refuse the ordinary case, which is every move that is not
            // racing its own fire time.
            ScheduledExecutionEntity entity = schedule();
            givenSchedule(entity);

            Map<String, Object> request = body("nextFireAt", "2026-09-02T14:30:00Z");
            request.put("expectedNextFireAt", ORIGINAL_NEXT.toString());

            ResponseEntity<?> response = controller.setScheduleNextFire(SCHEDULE_ID, TENANT, ORG, request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(entity.getNextExecutionAt()).isEqualTo(Instant.parse("2026-09-02T14:30:00Z"));
        }

        @Test
        @DisplayName("writes unconditionally when no expectation is sent")
        void unguardedCallersStillWork() {
            // The parameter is optional so existing callers that do not know the expected
            // value keep working; making it mandatory would break them silently.
            ScheduledExecutionEntity entity = schedule();
            givenSchedule(entity);

            ResponseEntity<?> response = controller.setScheduleNextFire(
                    SCHEDULE_ID, TENANT, ORG, body("nextFireAt", "2026-09-02T14:30:00Z"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(entity.getNextExecutionAt()).isEqualTo(Instant.parse("2026-09-02T14:30:00Z"));
        }

        @Test
        @DisplayName("moves the pending fire and leaves the cron expression untouched")
        void movesOnlyTheNextFire() {
            // The whole contract: later occurrences are recomputed from this same cron,
            // so they must return to 09:00 on their own.
            ScheduledExecutionEntity entity = schedule();
            givenSchedule(entity);

            ResponseEntity<?> response = controller.setScheduleNextFire(
                    SCHEDULE_ID, TENANT, ORG, body("nextFireAt", "2026-09-02T14:30:00Z"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(entity.getNextExecutionAt()).isEqualTo(Instant.parse("2026-09-02T14:30:00Z"));
            assertThat(entity.getCronExpression()).isEqualTo("0 9 * * *");
            assertThat(((ScheduledExecutionDto) response.getBody()).getCronExpression()).isEqualTo("0 9 * * *");
        }

        @Test
        @DisplayName("accepts a target in the past - it means fire at the next daemon tick")
        void acceptsPastTarget() {
            // Refusing would be worse than accepting: the daemon already treats an
            // overdue next_execution_at as due, which is exactly what the user asked for.
            ScheduledExecutionEntity entity = schedule();
            givenSchedule(entity);

            ResponseEntity<?> response = controller.setScheduleNextFire(
                    SCHEDULE_ID, TENANT, ORG, body("nextFireAt", "2020-01-01T00:00:00Z"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(entity.getNextExecutionAt()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
        }

        @Test
        @DisplayName("refuses an archived schedule - it would draw an occurrence that can never fire")
        void refusesArchived() {
            ScheduledExecutionEntity entity = schedule();
            entity.setState(TriggerState.ARCHIVED);
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(entity));

            ResponseEntity<?> response = controller.setScheduleNextFire(
                    SCHEDULE_ID, TENANT, ORG, body("nextFireAt", "2026-09-02T14:30:00Z"));

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(entity.getNextExecutionAt()).isEqualTo(ORIGINAL_NEXT);
            verify(scheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("404s a schedule owned by another workspace instead of revealing it exists")
        void refusesCrossScope() {
            ScheduledExecutionEntity entity = schedule();
            entity.setTenantId("someone-else");
            entity.setOrganizationId("org-other");
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(entity));

            ResponseEntity<?> response = controller.setScheduleNextFire(
                    SCHEDULE_ID, TENANT, ORG, body("nextFireAt", "2026-09-02T14:30:00Z"));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            verify(scheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("401s without a caller identity")
        void refusesAnonymous() {
            ResponseEntity<?> response = controller.setScheduleNextFire(
                    SCHEDULE_ID, null, ORG, body("nextFireAt", "2026-09-02T14:30:00Z"));

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            verify(scheduleRepository, never()).findById(any());
        }

        @Test
        @DisplayName("400s a missing or unparseable instant rather than 500ing on it")
        void rejectsBadInstant() {
            ScheduledExecutionEntity entity = schedule();
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(entity));

            assertThat(controller.setScheduleNextFire(SCHEDULE_ID, TENANT, ORG, Map.of())
                    .getStatusCode().value()).isEqualTo(400);
            assertThat(controller.setScheduleNextFire(SCHEDULE_ID, TENANT, ORG,
                    body("nextFireAt", "tomorrow")).getStatusCode().value()).isEqualTo(400);
            assertThat(entity.getNextExecutionAt()).isEqualTo(ORIGINAL_NEXT);
            verify(scheduleRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("resume, against a moved occurrence")
    class Resume {

        @Test
        @DisplayName("keeps a pending fire that is still in the future - a move is a decision, not staleness")
        void resumeKeepsAFutureOverride() {
            // Reachable in four clicks: move Tuesday's 09:00 run to 14:30, pause, resume.
            // Recomputing from the cron on resume threw the move away and fired at 09:00,
            // while the paused marker had just told the user "would resume Tue 14:30".
            Instant moved = Instant.now().plusSeconds(6 * 3600);
            ScheduledExecutionEntity entity = schedule();
            entity.setEnabled(false);
            entity.setNextExecutionAt(moved);
            givenToggleableSchedule(entity);

            controller.toggleSchedule(SCHEDULE_ID, ORG, Map.of("enabled", true));

            assertThat(entity.getNextExecutionAt()).isEqualTo(moved);
            // Nothing is written at all on this path - the stored decision stands as it is.
            verify(scheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("recomputes a pending fire that has already passed - a long pause outlived it")
        void resumeRecomputesAnExpiredFire() {
            // The other face of the same rule: a pause longer than one cron period leaves a
            // fire time that cannot be honoured, and a marker advertising a resume in the past.
            ScheduledExecutionEntity entity = schedule();
            entity.setEnabled(false);
            entity.setNextExecutionAt(Instant.now().minusSeconds(48 * 3600));
            givenToggleableSchedule(entity);
            when(scheduleRepository.save(any(ScheduledExecutionEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            controller.toggleSchedule(SCHEDULE_ID, ORG, Map.of("enabled", true));

            assertThat(entity.getNextExecutionAt()).isAfter(Instant.now());
            assertThat(entity.getNextExecutionAt().atZone(java.time.ZoneOffset.UTC).getHour()).isEqualTo(9);
        }
    }

    @Nested
    @DisplayName("cron (all occurrences)")
    class CronRewrite {

        @Test
        @DisplayName("stores the new cron AND re-derives the pending fire from it")
        void rewritesCronAndNextFire() {
            // Leaving next_execution_at alone would fire once more at 09:00, the very time
            // the user moved away from.
            ScheduledExecutionEntity entity = schedule();
            givenSchedule(entity);

            ResponseEntity<?> response = controller.updateScheduleCron(
                    SCHEDULE_ID, TENANT, ORG, body("cron", "30 14 * * *"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(entity.getCronExpression()).isEqualTo("30 14 * * *");
            assertThat(entity.getNextExecutionAt()).isNotEqualTo(ORIGINAL_NEXT);
            assertThat(entity.getNextExecutionAt()).isAfter(Instant.now());
            assertThat(entity.getNextExecutionAt().atZone(java.time.ZoneOffset.UTC).getHour()).isEqualTo(14);
            assertThat(entity.getNextExecutionAt().atZone(java.time.ZoneOffset.UTC).getMinute()).isEqualTo(30);
        }

        @Test
        @DisplayName("keeps the schedule's own timezone when the caller does not send one")
        void keepsTimezoneByDefault() {
            ScheduledExecutionEntity entity = schedule();
            entity.setTimezone("Europe/Paris");
            givenSchedule(entity);

            controller.updateScheduleCron(SCHEDULE_ID, TENANT, ORG, body("cron", "0 9 * * *"));

            assertThat(entity.getTimezone()).isEqualTo("Europe/Paris");
        }

        @Test
        @DisplayName("re-validates the cron instead of trusting the caller")
        void revalidatesCron() {
            // A step value Spring silently collapses would otherwise be persisted and then
            // auto-archived on its next tick by the legacy-cron guard.
            ScheduledExecutionEntity entity = schedule();
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(entity));

            assertThat(controller.updateScheduleCron(SCHEDULE_ID, TENANT, ORG,
                    body("cron", "*/120 * * * *")).getStatusCode().value()).isEqualTo(400);
            assertThat(controller.updateScheduleCron(SCHEDULE_ID, TENANT, ORG,
                    body("cron", "nonsense")).getStatusCode().value()).isEqualTo(400);
            assertThat(controller.updateScheduleCron(SCHEDULE_ID, TENANT, ORG,
                    body("cron", "  ")).getStatusCode().value()).isEqualTo(400);

            assertThat(entity.getCronExpression()).isEqualTo("0 9 * * *");
            verify(scheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("400s a timezone the platform cannot resolve rather than storing it")
        void rejectsUnresolvableTimezone() {
            // A zone the arming maths cannot resolve makes next() return nothing, so the row
            // goes inert with no error anyone would ever see.
            ScheduledExecutionEntity entity = schedule();
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(entity));
            Map<String, Object> body = body("cron", "30 14 * * *");
            body.put("timezone", "Mars/Olympus_Mons");

            assertThat(controller.updateScheduleCron(SCHEDULE_ID, TENANT, ORG, body)
                    .getStatusCode().value()).isEqualTo(400);
            verify(scheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("accepts and stores a caller-supplied timezone that does resolve")
        void acceptsAnExplicitTimezone() {
            ScheduledExecutionEntity entity = schedule();
            givenSchedule(entity);
            Map<String, Object> body = body("cron", "30 14 * * *");
            body.put("timezone", "Asia/Tokyo");

            controller.updateScheduleCron(SCHEDULE_ID, TENANT, ORG, body);

            assertThat(entity.getTimezone()).isEqualTo("Asia/Tokyo");
        }

        @Test
        @DisplayName("400s a syntactically valid cron that can never fire")
        void rejectsUnfireableCron() {
            // February 31st parses fine and never happens; storing it leaves a schedule
            // that looks armed forever.
            ScheduledExecutionEntity entity = schedule();
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(entity));

            ResponseEntity<?> response = controller.updateScheduleCron(
                    SCHEDULE_ID, TENANT, ORG, body("cron", "0 0 31 2 *"));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            verify(scheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("refuses archived rows and foreign workspaces, like the single-occurrence move")
        void refusesArchivedAndCrossScope() {
            ScheduledExecutionEntity archived = schedule();
            archived.setState(TriggerState.ARCHIVED);
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(archived));
            assertThat(controller.updateScheduleCron(SCHEDULE_ID, TENANT, ORG,
                    body("cron", "30 14 * * *")).getStatusCode().value()).isEqualTo(409);

            ScheduledExecutionEntity foreign = schedule();
            foreign.setTenantId("someone-else");
            foreign.setOrganizationId("org-other");
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(foreign));
            assertThat(controller.updateScheduleCron(SCHEDULE_ID, TENANT, ORG,
                    body("cron", "30 14 * * *")).getStatusCode().value()).isEqualTo(404);

            verify(scheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("401s without a caller identity")
        void refusesAnonymous() {
            assertThat(controller.updateScheduleCron(SCHEDULE_ID, null, ORG,
                    body("cron", "30 14 * * *")).getStatusCode().value()).isEqualTo(401);
            verify(scheduleRepository, never()).findById(any());
        }
    }
}
