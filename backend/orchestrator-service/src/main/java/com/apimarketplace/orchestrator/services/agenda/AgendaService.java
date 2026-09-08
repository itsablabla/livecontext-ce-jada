package com.apimarketplace.orchestrator.services.agenda;

import com.apimarketplace.common.schedule.CronOccurrences;
import com.apimarketplace.common.schedule.CronShifter;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.ResourceType;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto.Marker;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto.Occurrence;
import com.apimarketplace.orchestrator.controllers.dto.AgendaDto.OccurrenceKind;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowType;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.services.ActiveAutomationsService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the agenda page's view of a time window: what is going to run, what already
 * ran, and what is armed but has no date.
 *
 * <p><b>Where the future comes from, and why it is not just the cron.</b> A schedule row
 * carries both a cron expression AND a {@code next_execution_at} column, and they can
 * legitimately disagree: moving a single occurrence writes the new time into that column
 * and leaves the cron alone (that is exactly what "this occurrence only" means). The
 * daemon fires on the column, so the FIRST projected occurrence is read from it and the
 * cron is only expanded for the ones AFTER it. Expanding the cron from the window start
 * instead would redraw the moved occurrence back in its old slot - the calendar would
 * contradict the move the user just made, and then contradict the daemon when the fire
 * happened somewhere else.
 *
 * <p><b>Where the past comes from.</b> Not from the schedule row, which remembers only
 * its last fire and a running total. One {@code EPOCH_HEADER} is written per trigger
 * fire, so the epoch table is the only thing that can answer "what ran on the 12th". It
 * is read once for the whole window, batched over the workspace's runs.
 *
 * <p><b>Known gap, stated rather than papered over</b>: past fires exist for workflows
 * and applications, which execute as runs with epochs. An AGENT schedule fires a
 * conversation turn instead, which writes no epoch, so agents contribute projected
 * occurrences and their last-run time, but no per-day history. The page must not imply
 * an agent had a quiet week when it simply has no history source here.
 */
@Service
public class AgendaService {

    private static final Logger logger = LoggerFactory.getLogger(AgendaService.class);

    private final ActiveAutomationsService activeAutomationsService;
    private final WorkflowEpochRepository epochRepository;
    private final ObjectMapper objectMapper;

    /**
     * Occurrences drawn per schedule per window. A once-a-minute cron over a month is
     * 43 200 fires: past a couple of hundred the calendar is unreadable and the payload
     * is waste, so the expansion stops here and the schedule is reported as truncated.
     */
    @Value("${agenda.max-occurrences-per-schedule:200}")
    private int maxOccurrencesPerSchedule;

    /** Past fires read per window, across all runs. Same honesty contract as above. */
    @Value("${agenda.max-past-fires:2000}")
    private int maxPastFires;

    /**
     * Longest window the page may ask for. Bounds both the cron expansion and the epoch
     * scan; a request for a decade is clamped rather than refused, so the page still
     * renders something correct for the start of what it asked for.
     */
    @Value("${agenda.max-window-days:120}")
    private int maxWindowDays;

    public AgendaService(ActiveAutomationsService activeAutomationsService,
                         WorkflowEpochRepository epochRepository,
                         ObjectMapper objectMapper) {
        this.activeAutomationsService = activeAutomationsService;
        this.epochRepository = epochRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param includePast when false, only projections are returned. The page turns this
     *                    off for a window entirely in the future, where the epoch scan
     *                    could only ever return nothing.
     */
    public AgendaDto getAgenda(String tenantId, String orgId, String orgRole,
                               Instant from, Instant to, boolean includePast) {
        Instant windowStart = from;
        Instant windowEnd = clampWindowEnd(from, to);
        Instant now = Instant.now();

        // Disabled schedules are pulled in deliberately: they become greyed markers, so a
        // user whose job stopped running finds it paused instead of finding a hole.
        List<ActiveAutomationDto> automations =
                activeAutomationsService.getActiveAutomations(tenantId, orgId, orgRole, true);

        List<Occurrence> occurrences = new ArrayList<>();
        List<Marker> markers = new ArrayList<>();
        List<UUID> truncatedScheduleIds = new ArrayList<>();

        for (ActiveAutomationDto automation : automations) {
            ActiveAutomationDto.ScheduleInfo schedule = automation.schedule();
            if (schedule == null) {
                // Webhook, chat, form, table, workflow or manual: armed, but with no date
                // it can be drawn at. It belongs on the unscheduled rail, not on a day.
                markers.add(toMarker(automation, null));
                continue;
            }
            if (!schedule.armed()) {
                // Paused or capped out: it will not fire, so projecting occurrences for it
                // would draw runs that are never going to happen.
                markers.add(toMarker(automation, schedule));
                continue;
            }
            boolean truncated = appendProjectedOccurrences(
                    automation, schedule, windowStart, windowEnd, now, occurrences);
            if (truncated) {
                truncatedScheduleIds.add(schedule.scheduleId());
            }
        }

        boolean pastTruncated = false;
        if (includePast && windowStart.isBefore(now)) {
            pastTruncated = appendPastFires(
                    orgId, windowStart, min(windowEnd, now), occurrences);
        }

        occurrences.sort((a, b) -> {
            int byTime = a.startAt().compareTo(b.startAt());
            if (byTime != 0) return byTime;
            // Same instant: keep the order stable across refetches so a re-render does not
            // reshuffle a day cell under the user's cursor.
            return a.id().compareTo(b.id());
        });

        return new AgendaDto(windowStart, windowEnd, occurrences, markers,
                // Deduplicated: a standalone schedule can be declared by two workflows and
                // is emitted under each, so the raw list counted one schedule twice and the
                // banner said "2 schedules fire too often" - sending the user to look for a
                // second job that does not exist.
                truncatedScheduleIds.stream().distinct().toList(), pastTruncated);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Projections
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Project one armed schedule onto the window.
     *
     * <p>Order matters here. The pending fire from {@code next_execution_at} is emitted
     * FIRST and verbatim, because it is what the daemon will actually do - including when
     * the user has moved it away from its cron slot. Only then is the cron expanded, and
     * only strictly after that pending fire, so the two sources can never produce the
     * same occurrence twice or contradict each other.
     *
     * @return true when the per-schedule cap cut the expansion short
     */
    private boolean appendProjectedOccurrences(ActiveAutomationDto automation,
                                               ActiveAutomationDto.ScheduleInfo schedule,
                                               Instant windowStart, Instant windowEnd, Instant now,
                                               List<Occurrence> out) {
        String cron = schedule.cronExpression();
        String timezone = schedule.timezone();
        boolean moveAllSupported = CronShifter.supportsShiftAll(cron);
        Instant pendingFire = schedule.nextFireAt();
        int emitted = 0;

        Instant expandFrom = maxInstant(windowStart, now);
        if (pendingFire != null) {
            // An overdue pending fire (daemon behind, or the user moved it into the past)
            // still belongs on the calendar at its stored time: it is what happens next.
            boolean insideWindow = !pendingFire.isBefore(windowStart) && !pendingFire.isAfter(windowEnd);
            if (insideWindow) {
                out.add(toPlannedOccurrence(automation, schedule, pendingFire, cron, timezone,
                        true, isOffCronSlot(cron, timezone, pendingFire), moveAllSupported));
                emitted++;
            }
            // Whether or not it was inside the window, the cron walk must resume after it,
            // or a pending fire moved LATER would be shadowed by the cron slot it skipped.
            expandFrom = maxInstant(expandFrom, pendingFire.plusSeconds(1));
        }

        if (!expandFrom.isAfter(windowEnd)) {
            CronOccurrences.Window window = CronOccurrences.between(
                    cron, timezone, expandFrom, windowEnd, maxOccurrencesPerSchedule - emitted);
            for (Instant fire : window.occurrences()) {
                out.add(toPlannedOccurrence(automation, schedule, fire, cron, timezone,
                        false, false, moveAllSupported));
            }
            return window.truncated();
        }
        return false;
    }

    /**
     * Whether an instant is NOT a slot this cron would produce, i.e. the user moved this
     * single occurrence. Asked by expanding the cron over the zero-width window
     * {@code [fire, fire]}: a hit means the instant is a natural slot.
     */
    private static boolean isOffCronSlot(String cron, String timezone, Instant fire) {
        // Cheap check first: does the expression produce this exact instant?
        if (!CronOccurrences.between(cron, timezone, fire, fire, 1).occurrences().isEmpty()) {
            return false;
        }
        // It said no, which on ONE kind of day is a lie. The walk cursors in local time, so
        // on a spring-forward day a slot whose wall-clock time does not exist comes back an
        // hour later than the cursor believes, and a one-instant window cannot see it. The
        // fire is natural; badging it "moved" would tell the user their schedule had been
        // dragged when nobody touched it.
        //
        // So re-ask over a window wide enough to contain any zone's transition (two hours
        // covers every current one) and look for the instant itself rather than for
        // emptiness. Only reached when the cheap check already said "moved", so an ordinary
        // day pays nothing, and the cap bounds the walk for a frequent cron.
        return !CronOccurrences.between(cron, timezone, fire.minusSeconds(7200), fire, 200)
                .occurrences().contains(fire);
    }

    private Occurrence toPlannedOccurrence(ActiveAutomationDto automation,
                                           ActiveAutomationDto.ScheduleInfo schedule,
                                           Instant startAt, String cron, String timezone,
                                           boolean isNextFire, boolean overridden,
                                           boolean moveAllSupported) {
        return new Occurrence(
                // Keyed by RESOURCE as well as schedule: one standalone schedule can be
                // declared by two pinned workflows, and ActiveAutomationsService emits it
                // under each (its de-dup is per-workflow). Without the resource id the two
                // projections collide, React sees duplicate keys and dnd-kit registers two
                // draggables under one id, so a drag lands on the wrong chip.
                automation.resourceId() + ":" + schedule.scheduleId() + "@" + startAt.toEpochMilli(),
                OccurrenceKind.PLANNED,
                startAt,
                null,
                automation.resourceType(),
                automation.resourceId(),
                automation.name(),
                automation.avatarUrl(),
                schedule.scheduleId(),
                null,                       // the plan-level trigger label is not on the bell DTO
                cron,
                timezone,
                // Not a constant any more. A workflow over its SPENDING cap keeps
                // an armed schedule with a live cron - the fires are simply
                // refused until the allowance starts again - so the occurrences
                // inside that window are drawn as not-going-to-happen and the
                // ones after it are drawn normally, because they will happen. A
                // null "until" while blocked means the cap never resets, so the
                // whole projected future is refused.
                !(schedule.budgetBlocked()
                        && (schedule.budgetBlockedUntil() == null
                            || startAt.isBefore(schedule.budgetBlockedUntil()))),
                isNextFire,
                overridden,
                moveAllSupported,
                "PLANNED",
                automation.productionRunIdPublic(),
                null,   // a projection has not fired, so it has no epoch
                automation.publicationId());
    }

    private Marker toMarker(ActiveAutomationDto automation, ActiveAutomationDto.ScheduleInfo schedule) {
        return new Marker(
                automation.resourceType(),
                automation.resourceId(),
                automation.name(),
                automation.avatarUrl(),
                automation.triggerType(),
                schedule != null ? schedule.scheduleId() : null,
                schedule != null ? schedule.cronExpression() : null,
                schedule != null ? schedule.timezone() : null,
                schedule != null ? schedule.nextFireAt() : null,
                automation.lastRunAt(),
                schedule == null || schedule.armed(),
                schedule != null ? schedule.pausedReason() : null,
                automation.productionRunIdPublic(),
                automation.publicationId());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // History
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Add every trigger fire that actually happened inside the window.
     *
     * <p>Read straight from the epoch headers, joined to their run and workflow, with NO pin
     * predicate: an automation row exists only while a workflow is pinned, but a run that
     * happened last Tuesday happened whether or not the workflow is pinned today. Resolving
     * history through the production-run lookup (as this first did) made past runs disappear
     * on unpin and on every re-pin - the calendar quietly rewriting history.
     *
     * @return true when the row cap cut the scan short
     */
    private boolean appendPastFires(String orgId, Instant from, Instant to, List<Occurrence> out) {
        if (orgId == null || !from.isBefore(to)) return false;

        // Newest-first from the query so a cap keeps the days the user is looking at; the
        // caller sorts the whole window chronologically afterwards.
        List<WorkflowEpochRepository.WorkspaceFireRow> fires =
                epochRepository.findWorkspaceFiresBetween(orgId, from, to, maxPastFires);

        Set<String> seen = new HashSet<>();
        for (WorkflowEpochRepository.WorkspaceFireRow row : fires) {
            WorkflowEpochRepository.EpochFireRow fire = row.fire();
            if (fire.startedAt() == null || row.workflowId() == null) continue;
            // A run can carry several trigger DAGs, each with its own epoch numbering, so
            // the identity has to include the trigger or two same-numbered epochs collide.
            String id = fire.runId() + "#" + fire.triggerId() + "#" + fire.epoch();
            if (!seen.add(id)) continue;

            ResourceType type = WorkflowType.APPLICATION.name().equals(row.workflowType())
                    ? ResourceType.APPLICATION
                    : ResourceType.WORKFLOW;
            out.add(new Occurrence(
                    id,
                    OccurrenceKind.PAST,
                    fire.startedAt(),
                    fire.closedAt(),
                    type,
                    row.workflowId(),
                    row.workflowName(),
                    null,
                    null,
                    fire.triggerId(),
                    null,
                    null,
                    true,
                    false,
                    false,
                    false,
                    pastStatus(fire),
                    fire.runId(),
                    fire.epoch(),
                    type == ResourceType.APPLICATION ? row.sourcePublicationId() : null));
        }
        return fires.size() >= maxPastFires;
    }

    /**
     * What a past fire achieved.
     *
     * <p>An open epoch is RUNNING. A closed one is asked of
     * {@link WorkflowEpochService#deriveEpochOutcome}, the same function the run history
     * badge uses, so the calendar cannot contradict the run page for the same cycle. That
     * function answers null when the epoch closed without executing anything past the
     * trigger, which is not "nothing happened": the trigger did fire. That case is FIRED.
     */
    private String pastStatus(WorkflowEpochRepository.EpochFireRow fire) {
        if (fire.isActive()) return "RUNNING";
        EpochState state = deserializeEpochState(fire.epochStateJson());
        String outcome = WorkflowEpochService.deriveEpochOutcome(state, false);
        return outcome != null ? outcome : "FIRED";
    }

    /** Parse a stored epoch state; null (never a throw) when absent or unreadable. */
    private EpochState deserializeEpochState(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, EpochState.class);
        } catch (Exception e) {
            // One unreadable epoch must not take the month down; it degrades to FIRED.
            logger.debug("[Agenda] Unreadable epoch_state, falling back to FIRED: {}", e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Window helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private Instant clampWindowEnd(Instant from, Instant to) {
        Instant maxEnd = from.plus(Duration.ofDays(maxWindowDays));
        if (to == null || to.isBefore(from)) return maxEnd;
        return to.isAfter(maxEnd) ? maxEnd : to;
    }

    private static Instant maxInstant(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** Unmodifiable empty agenda, for callers with no workspace resolved. */
    public static AgendaDto empty(Instant from, Instant to) {
        return new AgendaDto(from, to, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), false);
    }
}
