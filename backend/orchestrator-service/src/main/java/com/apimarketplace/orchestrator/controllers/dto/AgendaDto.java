package com.apimarketplace.orchestrator.controllers.dto;

import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.ResourceType;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.TriggerType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the agenda page draws for one time window.
 *
 * <p>The window holds three different kinds of thing, and keeping them apart is what
 * lets the page be honest:
 * <ul>
 *   <li>{@link Occurrence} with {@code kind = PLANNED} - a fire that WILL happen,
 *       projected from a schedule. It can still be moved or cancelled.</li>
 *   <li>{@link Occurrence} with {@code kind = PAST} - a fire that DID happen, read from
 *       the epoch headers. It is history: nothing about it can be changed.</li>
 *   <li>{@link Marker} - a resource that is armed but has no date: a webhook, a chat or
 *       form endpoint, a manual trigger. These have no place on a day cell, so they are
 *       returned separately for the page's "unscheduled" rail rather than being invented
 *       onto one.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgendaDto(
        Instant from,
        Instant to,
        List<Occurrence> occurrences,
        List<Marker> markers,
        /**
         * Schedule ids whose expansion hit the per-schedule cap inside this window, so
         * the calendar is showing a PREFIX of their occurrences. A once-a-minute cron
         * over a month is 43 200 fires; drawing them all is useless, but silently
         * drawing the first 200 would show the schedule stopping mid-month. The page
         * must tell the user which schedules are abbreviated.
         */
        List<UUID> truncatedScheduleIds,
        /**
         * True when the past-fire lookup hit its row cap, so older fires inside the
         * window are missing. Same contract as {@code truncatedScheduleIds}: say it
         * rather than let the user read an incomplete day as a quiet one.
         */
        boolean pastTruncated
) {

    /** Whether an occurrence is a projection of the future or a record of the past. */
    public enum OccurrenceKind { PLANNED, PAST }

    /**
     * A dated entry on the calendar.
     *
     * @param id           stable within a window and across refetches, so the page can
     *                     key rows and track a drag without the identity changing under
     *                     it. {@code <resourceId>:<scheduleId>@<epochMillis>} for a
     *                     projection, {@code <runId>#<triggerId>#<epoch>} for a past fire.
     *                     Both carry a component the obvious form omits, and both were
     *                     added to fix a real collision: one standalone schedule can be
     *                     declared by two workflows and is emitted under each, and one run
     *                     can fire from several triggers inside one epoch. Without them
     *                     React keyed two distinct rows identically and dropped one.
     * @param startAt      when it fires / fired
     * @param endAt        when the past fire's epoch closed; null for a projection and
     *                     for an epoch still open
     * @param scheduleId   the row to address for a move, a run-now or a pause. Null on
     *                     a past fire whose schedule no longer exists.
     * @param triggerId    the plan-level trigger label ({@code trigger:daily}). Present
     *                     on past fires too, so a resource with several triggers can be
     *                     told apart on the same day.
     * @param armed        will THIS occurrence actually fire? False only on a
     *                     PLANNED one whose workflow is over its spending cap at that
     *                     moment: the schedule itself is still armed and still has a
     *                     cron, so it keeps producing occurrences, but the fires inside
     *                     the block are refused. Occurrences after the allowance resets
     *                     are armed again, which is why this is per-occurrence and not
     *                     per-schedule. A paused or exhausted SCHEDULE is a different
     *                     thing: it produces no occurrence at all and reaches the page
     *                     as a {@link Marker}. Always true on a PAST fire, which has
     *                     already happened and cannot be predicted.
     * @param isNextFire   true for the ONE occurrence the schedule is actually pointing at
     *                     ({@code next_execution_at}). Only this one can be moved on its
     *                     own: the row holds a single pending fire, so writing a later
     *                     occurrence's time into it would skip every run in between. The
     *                     page uses this to offer "this occurrence only" where it is
     *                     truthful and explain itself where it is not.
     * @param overridden   true when this occurrence sits somewhere the cron would not
     *                     have put it, i.e. the user moved this one fire. Lets the page
     *                     badge it and offer "reset to schedule".
     * @param moveAllSupported whether the cron has a single, unambiguous time of day to
     *                     rewrite. False means only "this occurrence" can be offered -
     *                     see {@code CronShifter}.
     * @param status       PLANNED, or the past fire's outcome: RUNNING while its epoch
     *                     is open, then COMPLETED / FAILED, or FIRED when the epoch
     *                     closed without executing anything beyond the trigger.
     * @param runIdPublic  click target: the production run this occurrence belongs to.
     * @param publicationId APPLICATION rows only - the frontend application route is
     *                     keyed by publication id, not by workflow id.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Occurrence(
            String id,
            OccurrenceKind kind,
            Instant startAt,
            Instant endAt,
            ResourceType resourceType,
            UUID resourceId,
            String name,
            String avatarUrl,
            UUID scheduleId,
            String triggerId,
            String cronExpression,
            String timezone,
            boolean armed,
            boolean isNextFire,
            boolean overridden,
            boolean moveAllSupported,
            String status,
            String runIdPublic,
            /**
             * Which fire of the run this was. Present on a PAST occurrence, null on a
             * projection - a run that has not happened has no epoch.
             *
             * <p>Carried so a click can open the run ON this fire. A run is a sequence of
             * fires and its surfaces default to the cumulative view of all of them, which
             * is right when you open a run and wrong when you clicked one dot on a
             * calendar: the user pointed at Tuesday 09:00 and got every Tuesday at once.
             * The id already embeds it, but parsing an id back apart is not an API.
             */
            Integer epoch,
            String publicationId
    ) {}

    /**
     * An armed resource with no date: a webhook, chat, form, table or manual trigger, or
     * a schedule that is currently paused.
     *
     * <p>These are the answer to "show me everything that is armed", which the user asked
     * for alongside the calendar itself. They deliberately carry no {@code startAt}:
     * placing a webhook on a day would state a falsehood about when it runs.
     *
     * @param nextFireAt for a PAUSED schedule marker only: the fire time it would resume
     *                   at. Null for every trigger kind that has no schedule.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Marker(
            ResourceType resourceType,
            UUID resourceId,
            String name,
            String avatarUrl,
            TriggerType triggerType,
            UUID scheduleId,
            String cronExpression,
            String timezone,
            Instant nextFireAt,
            /**
             * When this trigger last fired, straight from {@code ActiveAutomationDto.lastRunAt}.
             *
             * <p>For a SCHEDULE marker that is the schedule's own fire. For the other kinds it is
             * the production run's most recent fire whatever trigger caused it, falling back to
             * the workflow's {@code lastExecutedAt} - the same per-workflow granularity those
             * rows have always carried.
             */
            Instant lastRunAt,
            boolean armed,
            /**
             * Why this schedule is paused, or null when it is armed or has no schedule.
             *
             * <p>Only {@code USER} carries a Resume action. Offering it on the other two
             * produced a success toast for a call that wrote nothing, and withholding it
             * without a reason would leave a row sitting there unexplained - which is the
             * question the rail exists to answer.
             */
            ActiveAutomationDto.PausedReason pausedReason,
            String runIdPublic,
            String publicationId
    ) {}
}
