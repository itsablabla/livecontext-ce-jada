package com.apimarketplace.orchestrator.controllers.monitoring;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.services.agenda.AgendaActionService;
import com.apimarketplace.orchestrator.services.agenda.AgendaActionService.ActionResult;
import com.apimarketplace.orchestrator.services.agenda.AgendaActionService.Failure;
import com.apimarketplace.orchestrator.services.agenda.AgendaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The agenda's HTTP edge: who may write, what a bad request looks like, and how each
 * refusal reaches the page.
 *
 * <p>Two things here are not cosmetic. The VIEWER gate is the only thing stopping a
 * read-only member from rescheduling the workspace and spending credits - the frontend's
 * {@code canMutate} hides the menu but not the drag, and returns true for anonymous
 * callers. And the status mapping is what lets the page tell "gone" from "cannot right
 * now" from "that move is not expressible"; collapsing them would leave the user with a
 * number instead of a next step.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgendaController")
class AgendaControllerTest {

    @Mock private AgendaService agendaService;
    @Mock private AgendaActionService agendaActionService;
    @Mock private TenantResolver tenantResolver;

    private AgendaController controller;

    private static final UUID SCHEDULE_ID = UUID.randomUUID();
    private static final String TENANT = "user-1";
    private static final String ORG = "org-1";

    @BeforeEach
    void setUp() {
        controller = new AgendaController(agendaService, agendaActionService, tenantResolver);
        when(tenantResolver.resolve(any())).thenReturn(TENANT);
    }

    private MockHttpServletRequest request(String orgRole) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", TENANT);
        request.addHeader("X-Organization-ID", ORG);
        if (orgRole != null) request.addHeader("X-Organization-Role", orgRole);
        return request;
    }

    private static Map<String, Object> moveBody(String startAt, String scope) {
        Map<String, Object> body = new HashMap<>();
        body.put("startAt", startAt);
        body.put("scope", scope);
        return body;
    }

    @Nested
    @DisplayName("who may write")
    class RoleGate {

        @Test
        @DisplayName("refuses an org VIEWER on both writes, before touching the service")
        void refusesViewer() {
            ResponseEntity<?> moved = controller.move(SCHEDULE_ID,
                    moveBody("2026-09-03T14:30:00Z", "NEXT"), request("VIEWER"));
            ResponseEntity<?> ran = controller.runNow(SCHEDULE_ID, Map.of(), request("VIEWER"));

            assertThat(moved.getStatusCode().value()).isEqualTo(403);
            assertThat(ran.getStatusCode().value()).isEqualTo(403);
            assertThat(((Map<?, ?>) moved.getBody()).get("reason")).isEqualTo("VIEWER_ROLE");
            verifyNoInteractions(agendaActionService);
        }

        @Test
        @DisplayName("matches the role case-insensitively, as the header is not normalised")
        void viewerRoleIsCaseInsensitive() {
            assertThat(controller.move(SCHEDULE_ID, moveBody("2026-09-03T14:30:00Z", "NEXT"),
                    request("viewer")).getStatusCode().value()).isEqualTo(403);
        }

        @Test
        @DisplayName("lets a MEMBER through")
        void allowsMember() {
            when(agendaActionService.moveNextOccurrence(any(), any(), any(), any(), any()))
                    .thenReturn(new ActionResult(null, null, null));

            ResponseEntity<?> response = controller.move(SCHEDULE_ID,
                    moveBody("2026-09-03T14:30:00Z", "NEXT"), request("MEMBER"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("does not gate a personal workspace, which has no roles")
        void allowsPersonalWorkspace() {
            MockHttpServletRequest personal = new MockHttpServletRequest();
            personal.addHeader("X-User-ID", TENANT);
            when(agendaActionService.runNow(any(), eq(true), any(), any()))
                    .thenReturn(new ActionResult(null, null, null));

            assertThat(controller.runNow(SCHEDULE_ID, Map.of(), personal)
                    .getStatusCode().value()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("trigger-service refusals")
    class PassThrough {

        /*
         * When trigger-service refuses the write, the page must be told THAT, not "something
         * went wrong". It is the authority on an archived row and on an instant it cannot
         * parse, and the frontend carries a dedicated sentence for exactly this reason -
         * which is what made an untested pass-through worth writing down: the branch
         * existed, the message existed, and nothing proved the reason ever reached it.
         */
        @Test
        @DisplayName("move surfaces SCHEDULE_REJECTED, keeping the upstream status")
        void moveSurfacesTheRefusal() {
            when(agendaActionService.moveNextOccurrence(any(), any(), any(), any(), any()))
                    .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT, "archived"));

            ResponseEntity<?> response = controller.move(SCHEDULE_ID,
                    moveBody("2026-09-03T14:30:00Z", "NEXT"), request("MEMBER"));

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(bodyOf(response)).containsEntry("reason", "SCHEDULE_REJECTED");
            assertThat(bodyOf(response)).containsEntry("success", false);
        }

        @Test
        @DisplayName("run-now surfaces it too - both writes reach the same service")
        void runNowSurfacesTheRefusal() {
            when(agendaActionService.runNow(any(), anyBoolean(), any(), any()))
                    .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND, "gone"));

            ResponseEntity<?> response = controller.runNow(SCHEDULE_ID, Map.of(), request("MEMBER"));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(bodyOf(response)).containsEntry("reason", "SCHEDULE_REJECTED");
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> bodyOf(ResponseEntity<?> response) {
            return (Map<String, Object>) response.getBody();
        }
    }

    @Nested
    @DisplayName("request shape")
    class Requests {

        @Test
        @DisplayName("400s an unparseable or inverted window instead of 500ing")
        void rejectsBadWindow() {
            assertThat(controller.getAgenda("tomorrow", "later", true, request("MEMBER"))
                    .getStatusCode().value()).isEqualTo(400);
            assertThat(controller.getAgenda("2026-09-05T00:00:00Z", "2026-09-01T00:00:00Z", true,
                    request("MEMBER")).getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(agendaService);
        }

        @Test
        @DisplayName("400s a missing or unparseable move target, and an unknown scope")
        void rejectsBadMoveBody() {
            assertThat(controller.move(SCHEDULE_ID, Map.of(), request("MEMBER"))
                    .getStatusCode().value()).isEqualTo(400);
            assertThat(controller.move(SCHEDULE_ID, moveBody("tomorrow", "NEXT"), request("MEMBER"))
                    .getStatusCode().value()).isEqualTo(400);
            assertThat(controller.move(SCHEDULE_ID, moveBody("2026-09-03T14:30:00Z", "SOMETIMES"),
                    request("MEMBER")).getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(agendaActionService);
        }

        @Test
        @DisplayName("400s an unparseable occurrenceAt rather than ignoring it")
        void rejectsBadOccurrenceAt() {
            // Ignoring it would drop the guard that stops a later occurrence's move from
            // cancelling the runs before it - the request would succeed unguarded.
            Map<String, Object> body = moveBody("2026-09-03T14:30:00Z", "NEXT");
            body.put("occurrenceAt", "the 3rd");

            assertThat(controller.move(SCHEDULE_ID, body, request("MEMBER"))
                    .getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(agendaActionService);
        }

        @Test
        @DisplayName("forwards occurrenceAt to the guard when it is supplied")
        void forwardsOccurrenceAt() {
            Map<String, Object> body = moveBody("2026-09-03T14:30:00Z", "NEXT");
            body.put("occurrenceAt", "2026-09-25T09:00:00Z");
            when(agendaActionService.moveNextOccurrence(any(), any(), any(), any(), any()))
                    .thenReturn(new ActionResult(null, null, null));

            controller.move(SCHEDULE_ID, body, request("MEMBER"));

            verify(agendaActionService).moveNextOccurrence(SCHEDULE_ID,
                    Instant.parse("2026-09-03T14:30:00Z"),
                    Instant.parse("2026-09-25T09:00:00Z"), TENANT, ORG);
        }

        @Test
        @DisplayName("keeps the scheduled occurrence unless the caller says false explicitly")
        void keepNextOccurrenceDefaultsToKeeping() {
            when(agendaActionService.runNow(any(), any(Boolean.class), any(), any()))
                    .thenReturn(new ActionResult(null, null, null));

            controller.runNow(SCHEDULE_ID, null, request("MEMBER"));
            controller.runNow(SCHEDULE_ID, Map.of(), request("MEMBER"));
            // A value that is neither true nor false must not silently consume a run.
            controller.runNow(SCHEDULE_ID, Map.of("keepNextOccurrence", 1), request("MEMBER"));
            verify(agendaActionService, org.mockito.Mockito.times(3))
                    .runNow(eq(SCHEDULE_ID), eq(true), any(), any());

            controller.runNow(SCHEDULE_ID, Map.of("keepNextOccurrence", false), request("MEMBER"));
            controller.runNow(SCHEDULE_ID, Map.of("keepNextOccurrence", "false"), request("MEMBER"));
            verify(agendaActionService, org.mockito.Mockito.times(2))
                    .runNow(eq(SCHEDULE_ID), eq(false), any(), any());
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> runNowBody(boolean keepNextOccurrence) {
            Object body = controller.runNow(SCHEDULE_ID,
                    Map.of("keepNextOccurrence", keepNextOccurrence), request("MEMBER")).getBody();
            return (Map<String, Object>) body;
        }

        @Test
        @DisplayName("puts the consumption answer on the wire, and OMITS it when it does not apply")
        void carriesOccurrenceConsumedOnlyWhenItMeansSomething() {
            // The seam between the two layers, and the whole reason the field is a Boolean
            // rather than a boolean. A `false` on an action that never had an occurrence to
            // consume reads as a refusal, and the bell would then be choosing its sentence
            // from a claim nobody made. So: present and true, present and false, or absent.
            when(agendaActionService.runNow(any(), any(Boolean.class), any(), any()))
                    .thenReturn(new ActionResult(null, null, null, true));
            assertThat(runNowBody(false)).containsEntry("occurrenceConsumed", true);

            when(agendaActionService.runNow(any(), any(Boolean.class), any(), any()))
                    .thenReturn(new ActionResult(null, null, null, false));
            assertThat(runNowBody(false)).containsEntry("occurrenceConsumed", false);

            // The keeping branch, and every action that is not a run, answer null.
            when(agendaActionService.runNow(any(), any(Boolean.class), any(), any()))
                    .thenReturn(new ActionResult(null, null, null));
            assertThat(runNowBody(true)).doesNotContainKey("occurrenceConsumed");
        }

        @Test
        @DisplayName("routes ALL and NEXT to different actions")
        void routesByScope() {
            when(agendaActionService.moveAllOccurrences(any(), any(), any(), any()))
                    .thenReturn(new ActionResult(null, null, null));

            controller.move(SCHEDULE_ID, moveBody("2026-09-03T14:30:00Z", "all"), request("MEMBER"));

            verify(agendaActionService).moveAllOccurrences(any(), any(), any(), any());
            verify(agendaActionService, never()).moveNextOccurrence(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("refusal mapping")
    class Refusals {

        private int statusFor(Failure failure) {
            when(agendaActionService.moveNextOccurrence(any(), any(), any(), any(), any()))
                    .thenReturn(new ActionResult(null, failure, "why"));
            return controller.move(SCHEDULE_ID, moveBody("2026-09-03T14:30:00Z", "NEXT"),
                    request("MEMBER")).getStatusCode().value();
        }

        @Test
        @DisplayName("gives each refusal a status the page can branch on")
        void mapsEachFailure() {
            // 404 gone, 409 cannot act on it as it stands, 422 the move is not expressible.
            assertThat(statusFor(Failure.NOT_FOUND)).isEqualTo(404);
            assertThat(statusFor(Failure.NOT_ARMED)).isEqualTo(409);
            assertThat(statusFor(Failure.EXECUTION_REFUSED)).isEqualTo(409);
            assertThat(statusFor(Failure.PATTERN_NOT_SHIFTABLE)).isEqualTo(422);
            assertThat(statusFor(Failure.WEEKDAY_SET_NOT_MATCHED)).isEqualTo(422);
            assertThat(statusFor(Failure.DAY_OF_MONTH_UNSAFE)).isEqualTo(422);
            assertThat(statusFor(Failure.NOT_THE_NEXT_OCCURRENCE)).isEqualTo(422);
        }

        @Test
        @DisplayName("carries the machine-readable reason and the detail, not just a status")
        void carriesTheReason() {
            // apiClient throws on any non-2xx and the page reads the reason off the error
            // body; a bare status would leave it showing "HTTP 422".
            when(agendaActionService.moveNextOccurrence(any(), any(), any(), any(), any()))
                    .thenReturn(new ActionResult(null, Failure.PATTERN_NOT_SHIFTABLE, "*/15 * * * *"));

            ResponseEntity<?> response = controller.move(SCHEDULE_ID,
                    moveBody("2026-09-03T14:30:00Z", "NEXT"), request("MEMBER"));

            Map<?, ?> body = (Map<?, ?>) response.getBody();
            assertThat(body.get("success")).isEqualTo(false);
            assertThat(body.get("reason")).isEqualTo("PATTERN_NOT_SHIFTABLE");
            assertThat(body.get("detail")).isEqualTo("*/15 * * * *");
        }
    }
}
