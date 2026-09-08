package com.apimarketplace.orchestrator.controllers.monitoring;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto;
import com.apimarketplace.orchestrator.services.agenda.AgendaActionService;
import com.apimarketplace.orchestrator.services.agenda.AgendaActionService.ActionResult;
import com.apimarketplace.orchestrator.services.agenda.AgendaService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The agenda page: everything scheduled in the active workspace over a date window, and
 * the actions a user can take on it.
 *
 * <p>Reads and writes are one controller on purpose - the page's whole interaction loop
 * is "see the occurrence, act on it, see the result", and every action returns the
 * schedule as it now stands so the calendar can update without refetching the month.
 */
@RestController
@RequestMapping("/api/agenda")
public class AgendaController {

    private static final Logger logger = LoggerFactory.getLogger(AgendaController.class);

    private final AgendaService agendaService;
    private final AgendaActionService agendaActionService;
    private final TenantResolver tenantResolver;

    public AgendaController(AgendaService agendaService,
                            AgendaActionService agendaActionService,
                            TenantResolver tenantResolver) {
        this.agendaService = agendaService;
        this.agendaActionService = agendaActionService;
        this.tenantResolver = tenantResolver;
    }

    /**
     * GET /api/agenda?from=...&to=...&includePast=true
     *
     * <p>{@code from} and {@code to} are ISO-8601 instants: the page computes them from
     * the visible grid in the user's display timezone and sends absolute times, so the
     * server never has to guess which day the user is looking at.
     */
    @GetMapping
    public ResponseEntity<?> getAgenda(
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            @RequestParam(value = "includePast", defaultValue = "true") boolean includePast,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        tenantResolver.validate(tenantId);
        String orgId = request.getHeader("X-Organization-ID");
        String orgRole = request.getHeader("X-Organization-Role");

        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = Instant.parse(from);
            toInstant = Instant.parse(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "from and to must be ISO-8601 instants"));
        }
        if (toInstant.isBefore(fromInstant)) {
            return ResponseEntity.badRequest().body(Map.of("error", "to must not precede from"));
        }

        AgendaDto agenda = agendaService.getAgenda(tenantId, orgId, orgRole,
                fromInstant, toInstant, includePast);
        logger.debug("GET /api/agenda tenant={} window={}..{} -> {} occurrences, {} markers",
                tenantId, fromInstant, toInstant, agenda.occurrences().size(), agenda.markers().size());
        return ResponseEntity.ok(agenda);
    }

    /**
     * POST /api/agenda/schedules/{scheduleId}/move
     * body: {@code { "startAt": "<ISO instant>", "scope": "NEXT" | "ALL" }}
     *
     * <p>{@code NEXT} moves one fire and leaves the schedule's rhythm alone. {@code ALL}
     * rewrites the cron, and is refused (422, with a {@code reason} the page turns into a
     * sentence) whenever the expression has no single time of day to move - the user is
     * then offered {@code NEXT}, which is always exact.
     */
    @PostMapping("/schedules/{scheduleId}/move")
    public ResponseEntity<?> move(@PathVariable("scheduleId") UUID scheduleId,
                                  @RequestBody Map<String, Object> body,
                                  HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        tenantResolver.validate(tenantId);
        String orgId = request.getHeader("X-Organization-ID");
        ResponseEntity<?> denied = refuseViewer(request, orgId, tenantId, "move a schedule");
        if (denied != null) return denied;

        Instant startAt;
        try {
            Object raw = body != null ? body.get("startAt") : null;
            if (raw == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "startAt is required"));
            }
            startAt = Instant.parse(raw.toString());
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "startAt must be an ISO-8601 instant"));
        }

        // The occurrence the user acted on. Optional, but a NEXT move that sends it gets
        // the guard that stops a later occurrence's move from cancelling the runs before it.
        Instant occurrenceAt = null;
        Object rawOccurrence = body.get("occurrenceAt");
        if (rawOccurrence != null && !rawOccurrence.toString().isBlank()) {
            try {
                occurrenceAt = Instant.parse(rawOccurrence.toString());
            } catch (DateTimeParseException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "occurrenceAt must be an ISO-8601 instant"));
            }
        }

        Object rawScope = body.get("scope");
        String scope = rawScope != null ? rawScope.toString().toUpperCase() : "NEXT";
        if (!"NEXT".equals(scope) && !"ALL".equals(scope)) {
            return ResponseEntity.badRequest().body(Map.of("error", "scope must be NEXT or ALL"));
        }

        try {
            ActionResult result = "ALL".equals(scope)
                    ? agendaActionService.moveAllOccurrences(scheduleId, startAt, tenantId, orgId)
                    : agendaActionService.moveNextOccurrence(scheduleId, startAt, occurrenceAt, tenantId, orgId);
            return toResponse(result);
        } catch (HttpStatusCodeException e) {
            return passThrough(e, scheduleId, "move");
        }
    }

    /**
     * POST /api/agenda/schedules/{scheduleId}/run-now
     * body: {@code { "keepNextOccurrence": true }}
     *
     * <p>Defaults to keeping the scheduled occurrence, because that is what "run it early"
     * means on a calendar: the run the user can see still happens. Send false to consume
     * it instead.
     */
    @PostMapping("/schedules/{scheduleId}/run-now")
    public ResponseEntity<?> runNow(@PathVariable("scheduleId") UUID scheduleId,
                                    @RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        tenantResolver.validate(tenantId);
        String orgId = request.getHeader("X-Organization-ID");
        ResponseEntity<?> denied = refuseViewer(request, orgId, tenantId, "run a schedule early");
        if (denied != null) return denied;

        // Keeping the scheduled occurrence is the default and only an explicit `false`
        // gives it up. `Boolean.parseBoolean` treats every unrecognised value as false, so
        // a JSON `1` or a typo would silently consume a run the user expected to keep.
        Object raw = body != null ? body.get("keepNextOccurrence") : null;
        boolean keepNextOccurrence = !(Boolean.FALSE.equals(raw) || "false".equalsIgnoreCase(String.valueOf(raw)));

        try {
            return toResponse(agendaActionService.runNow(scheduleId, keepNextOccurrence, tenantId, orgId));
        } catch (HttpStatusCodeException e) {
            return passThrough(e, scheduleId, "run-now");
        }
    }

    /**
     * Refuse an org VIEWER, returning the 403 body when they must be stopped and null when
     * they may proceed.
     *
     * <p>Both agenda writes are real mutations: one rewrites when a schedule fires, the
     * other starts a run that spends credits. Every other write path in this service gates
     * VIEWER the same way ({@code WorkflowCrudController}, {@code WorkflowListController},
     * {@code StorageExplorerController}), and the frontend's {@code canMutate} is not a
     * substitute - it hides the menu but not the drag, and it is a UI hint, not a boundary.
     *
     * <p>Only applies inside an organization workspace; a personal workspace has no roles.
     */
    private ResponseEntity<?> refuseViewer(HttpServletRequest request, String orgId,
                                           String tenantId, String action) {
        String orgRole = request.getHeader("X-Organization-Role");
        if (orgId != null && orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim())) {
            logger.warn("OrgAccess denied: VIEWER user {} attempted to {} in org {}",
                    tenantId, action, orgId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "reason", "VIEWER_ROLE",
                            "error", "VIEWER role cannot modify schedules"));
        }
        return null;
    }

    /**
     * Map an action outcome onto a status the page can branch on:
     * 404 gone, 409 cannot act on it as it stands, 422 the move itself is not expressible.
     */
    private ResponseEntity<?> toResponse(ActionResult result) {
        if (result.isSuccess()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", true);
            payload.put("schedule", result.schedule());
            // Only the early run answers this, and only when it was asked to give the
            // occurrence up. Absent everywhere else, because "false" would read as a
            // refusal on the actions that never had an occurrence to consume.
            if (result.occurrenceConsumed() != null) {
                payload.put("occurrenceConsumed", result.occurrenceConsumed());
            }
            return ResponseEntity.ok(payload);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("reason", result.failure().name());
        if (result.detail() != null) payload.put("detail", result.detail());

        return switch (result.failure()) {
            case NOT_FOUND -> ResponseEntity.status(404).body(payload);
            case NOT_ARMED, EXECUTION_REFUSED -> ResponseEntity.status(409).body(payload);
            case PATTERN_NOT_SHIFTABLE, WEEKDAY_SET_NOT_MATCHED, DAY_OF_MONTH_UNSAFE,
                 NOT_THE_NEXT_OCCURRENCE -> ResponseEntity.status(422).body(payload);
        };
    }

    /**
     * Re-emit trigger-service's own refusal instead of collapsing it into a 500.
     * It is the component that owns the schedule row, so its 409 (archived) or 400
     * (unparseable instant) is the accurate answer - and the page needs the status to
     * choose a message.
     */
    private ResponseEntity<?> passThrough(HttpStatusCodeException e, UUID scheduleId, String action) {
        logger.warn("[Agenda] trigger-service refused {} on schedule {}: {} {}",
                action, scheduleId, e.getStatusCode(), e.getMessage());
        return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                "success", false,
                "reason", "SCHEDULE_REJECTED"));
    }
}
