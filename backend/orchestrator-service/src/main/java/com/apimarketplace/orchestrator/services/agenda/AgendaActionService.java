package com.apimarketplace.orchestrator.services.agenda;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.schedule.CronOccurrences;
import com.apimarketplace.common.schedule.CronShifter;
import com.apimarketplace.orchestrator.schedule.ScheduleExecutorService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The three things the agenda lets a user DO to a schedule: move one occurrence, move
 * every occurrence, and run one early.
 *
 * <p>Each one is a small write with a large way to get it wrong, so the rules are stated
 * once here rather than spread across the controller.
 */
@Service
public class AgendaActionService {

    private static final Logger logger = LoggerFactory.getLogger(AgendaActionService.class);

    private final TriggerClient triggerClient;
    private final ScheduleExecutorService scheduleExecutorService;

    public AgendaActionService(TriggerClient triggerClient,
                               ScheduleExecutorService scheduleExecutorService) {
        this.triggerClient = triggerClient;
        this.scheduleExecutorService = scheduleExecutorService;
    }

    /** Why an action was refused, so the page can say something specific. */
    public enum Failure {
        NOT_FOUND,
        /** The cron has no single time of day to rewrite - see {@link CronShifter}. */
        PATTERN_NOT_SHIFTABLE,
        /** The cron fires on a fixed weekday set the target date is not part of. */
        WEEKDAY_SET_NOT_MATCHED,
        /** A monthly move past the 28th would skip the months that lack that day. */
        DAY_OF_MONTH_UNSAFE,
        /** The schedule is paused or archived, so there is nothing to run or move. */
        NOT_ARMED,
        /**
         * A "this occurrence only" move was aimed at an occurrence that is not the one the
         * schedule is pointing at. Honouring it would write that later time into the single
         * pending fire and silently skip every run in between.
         */
        NOT_THE_NEXT_OCCURRENCE,
        /** The trigger refused the execution (queue down, workflow unpinned, ...). */
        EXECUTION_REFUSED
    }

    /**
     * Outcome of an agenda action.
     *
     * @param schedule the schedule as it now stands, on success
     * @param failure  null on success
     * @param detail   a message worth showing the user, when the platform produced one
     * @param occurrenceConsumed whether an early run gave the scheduled occurrence up, and
     *                 null when the question does not apply (every action but a run asked to
     *                 replace the occurrence). Three-state on purpose: a false here means
     *                 "asked, and it did not happen", which is a different thing to say to
     *                 the user from "never asked"
     */
    public record ActionResult(ScheduledExecutionDto schedule, Failure failure, String detail,
                               Boolean occurrenceConsumed) {

        /**
         * The form every action but the early run uses: there is no occurrence to consume,
         * so the question does not apply and the field stays null.
         */
        public ActionResult(ScheduledExecutionDto schedule, Failure failure, String detail) {
            this(schedule, failure, detail, null);
        }

        public boolean isSuccess() { return failure == null; }

        static ActionResult ok(ScheduledExecutionDto schedule) {
            return new ActionResult(schedule, null, null);
        }

        static ActionResult failed(Failure failure, String detail) {
            return new ActionResult(null, failure, detail);
        }
    }

    /**
     * Move ONLY the next occurrence: write the new fire time, leave the cron alone.
     *
     * <p>No override table is involved and none is needed: the override IS the pending
     * {@code next_execution_at}, and it expires by being consumed. Once this fire happens
     * the daemon recomputes the following one from the untouched cron, so every later
     * occurrence returns to its normal slot - which is the promise the dialog makes.
     *
     * <p><b>Only the pending occurrence can be moved this way</b>, and {@code occurrenceAt}
     * is how the caller proves that is the one it meant. A schedule holds exactly ONE
     * pending fire: writing the 25th's new time into it when the schedule is currently
     * pointing at the 2nd does not move the 25th, it cancels the 2nd through the 24th. The
     * calendar happily draws two hundred identical-looking chips, so without this check the
     * dialog's promise is false for all but the first of them.
     *
     * @param occurrenceAt the occurrence the user acted on; null skips the check, for
     *                     callers that have no occurrence in hand (the bell acts on the
     *                     next fire by construction)
     */
    public ActionResult moveNextOccurrence(UUID scheduleId, Instant startAt, Instant occurrenceAt,
                                           String tenantId, String orgId) {
        ScheduledExecutionDto schedule = resolveInScope(scheduleId, tenantId, orgId);
        if (schedule == null) return ActionResult.failed(Failure.NOT_FOUND, null);
        if (!isArmed(schedule)) return ActionResult.failed(Failure.NOT_ARMED, null);
        if (occurrenceAt != null && !occurrenceAt.equals(schedule.getNextExecutionAt())) {
            return ActionResult.failed(Failure.NOT_THE_NEXT_OCCURRENCE,
                    schedule.getNextExecutionAt() != null
                            ? schedule.getNextExecutionAt().toString()
                            : null);
        }

        ScheduledExecutionDto updated =
                // Pass the fire time the guard above checked, so trigger-service refuses
                // the write if the row moved on in between. The check and the write live in
                // different services, and a schedule about to fire closes that gap on its
                // own.
                triggerClient.setScheduleNextFire(scheduleId, startAt, occurrenceAt, orgId, tenantId);
        logger.info("[Agenda] Moved next occurrence of schedule {} to {}", scheduleId, startAt);
        return ActionResult.ok(updated);
    }

    /**
     * Move EVERY occurrence by rewriting the cron expression.
     *
     * <p>The rewrite is refused for any pattern whose time of day is ambiguous, and the
     * refusal is the feature: silently turning "every 2 hours" into "every day at 14:00"
     * would change how often a job the user depends on runs, and they would find out only
     * when a run they expected did not happen. The caller turns each refusal into a
     * message offering the exact alternative - move this occurrence only.
     */
    public ActionResult moveAllOccurrences(UUID scheduleId, Instant startAt, String tenantId, String orgId) {
        ScheduledExecutionDto schedule = resolveInScope(scheduleId, tenantId, orgId);
        if (schedule == null) return ActionResult.failed(Failure.NOT_FOUND, null);
        if (!isArmed(schedule)) return ActionResult.failed(Failure.NOT_ARMED, null);

        CronShifter.ShiftResult shift =
                CronShifter.shiftAll(schedule.getCronExpression(), schedule.getTimezone(), startAt);
        if (!shift.isSuccess()) {
            return ActionResult.failed(switch (shift.reason()) {
                case WEEKDAY_SET_NOT_MATCHED -> Failure.WEEKDAY_SET_NOT_MATCHED;
                case DAY_OF_MONTH_UNSAFE -> Failure.DAY_OF_MONTH_UNSAFE;
                default -> Failure.PATTERN_NOT_SHIFTABLE;
            }, schedule.getCronExpression());
        }

        ScheduledExecutionDto updated = triggerClient.updateScheduleCron(
                scheduleId, shift.cron(), schedule.getTimezone(), orgId, tenantId);
        logger.info("[Agenda] Rewrote schedule {} cron '{}' -> '{}' for a move-all to {}",
                scheduleId, schedule.getCronExpression(), shift.cron(), startAt);
        return ActionResult.ok(updated);
    }

    /**
     * Run a scheduled resource NOW, ahead of its next slot.
     *
     * <p><b>The pending occurrence survives.</b> {@code executeNow} performs an optimistic
     * advance - it moves {@code next_execution_at} on to the following cron slot, which is
     * right for the daemon (the fire it is claiming has been used) but wrong here: the
     * user asked to run the job early, not to skip its scheduled run. So the pending fire
     * time is captured first and written back afterwards, and the occurrence the user can
     * see on the calendar still happens.
     *
     * <p>Two things are deliberately NOT restored. {@code execution_count} stays
     * incremented and {@code last_execution_at} stays at now, because a run genuinely
     * happened; rolling those back would under-report usage and, where a max-executions
     * cap exists, hand out a free run.
     *
     * <p>The write-back is skipped when the captured fire time is already due, because
     * restoring an overdue time would make the daemon fire again within the minute - the
     * user would get two runs from one click.
     *
     * <p><b>It also happens when the run FAILED.</b> The advance is optimistic: it lands
     * before the work is attempted, and only one failure mode ({@code executeNow}'s
     * queue-unavailable path) rolls it back. Every other failure - a provider that is not
     * configured, a workflow that is not pinned, conversation-service unreachable - left
     * the schedule advanced past an occurrence that never ran, so the user saw "could not
     * run it" and then quietly lost the run they were counting on. Nothing ran, so nothing
     * should have been consumed.
     *
     * <p><b>And "consume it" has to be WRITTEN - the advance does not do it.</b> That was
     * the whole of "Run instead of the scheduled run" doing nothing: the advance recomputes
     * the next slot FROM NOW, and an early run happens before the occurrence it replaces,
     * so for a 15:00 job run at 14:23 "the next slot from now" IS 15:00. The row came back
     * carrying exactly the occurrence the user had asked to give up, the calendar redrew it
     * unchanged, and the run they had just paid for was followed by the scheduled one
     * anyway. Consuming is therefore its own write: the first slot strictly AFTER the
     * occurrence being given up. See {@link #consumePendingFire}.
     */
    public ActionResult runNow(UUID scheduleId, boolean keepNextOccurrence, String tenantId, String orgId) {
        ScheduledExecutionDto schedule = resolveInScope(scheduleId, tenantId, orgId);
        if (schedule == null) return ActionResult.failed(Failure.NOT_FOUND, null);
        // "Armed" has one meaning across this feature: enabled, not archived, AND still
        // under its max-executions cap. The calendar greys an exhausted schedule out, so
        // leaving the cap out here let the endpoint hand out a run the UI says is over.
        if (!isArmed(schedule)) return ActionResult.failed(Failure.NOT_ARMED, null);

        Instant pendingFire = schedule.getNextExecutionAt();
        TriggerExecutionResult result = scheduleExecutorService.executeNow(schedule);

        if (result == null || !result.success()) {
            // No restore here on purpose. A failed manual run is undone at the layer that
            // performed the advance ({@code ScheduleExecutorService}), which puts back ALL
            // THREE dispatch markers - execution_count and last_execution_at as well as the
            // fire time - under a compare-and-set, on BOTH the workflow and the agent
            // branches. This method could only ever write the fire time back, so doing it
            // here too left the count inflated while looking like the problem was handled,
            // and gave the column a second writer with different rules.
            String detail = result != null ? result.message() : null;
            return ActionResult.failed(Failure.EXECUTION_REFUSED, detail);
        }

        // The run HAS started. From here nothing may turn this into a reported failure:
        // setScheduleNextFire propagates its errors by design, so a 409 (the schedule was
        // archived in the interval) or a timeout would escape, be caught by the controller's
        // pass-through and answer SCHEDULE_REJECTED - telling the user their run did not
        // happen and inviting them to click again, on the one call this feature marks
        // explicitly non-idempotent. Losing the occurrence-restore is the lesser failure,
        // and it is visible on the calendar; a false negative on a run that happened is not.
        ScheduledExecutionDto after = null;
        boolean consumed = false;
        try {
            if (keepNextOccurrence) {
                after = restorePendingFire(scheduleId, pendingFire, tenantId, orgId, "it ran early");
            } else {
                Consumption consumption = consumePendingFire(scheduleId, pendingFire, tenantId, orgId);
                after = consumption.schedule();
                consumed = consumption.consumed();
            }
        } catch (RuntimeException e) {
            // Both writes are best-effort for the same reason, so they share one catch: the
            // run has started, and no bookkeeping failure after it may be reported as the
            // run failing. What that costs is one occurrence drawn wrongly on a calendar the
            // user can look at; what the alternative costs is a second click on the one call
            // this feature marks non-idempotent.
            //
            // Logged WITH the exception, not with `e.toString()`. The catch covers a whole
            // method rather than a single HTTP call, so a programming error inside it lands
            // here too, and a one-line warning with no stack is how such a thing stays
            // invisible for months on a path nobody is watching.
            logger.warn("[Agenda] Schedule {} ran early but its pending occurrence could not be "
                    + "{}. Reporting the run as the success it was.",
                    scheduleId, keepNextOccurrence ? "restored" : "consumed", e);
        }
        return new ActionResult(
                after != null ? after : triggerClient.getSchedule(scheduleId), null, null,
                // Only meaningful for the consuming branch. The caller says "ran instead of
                // the scheduled run" ONLY on a true here: every refusal below leaves the
                // occurrence standing, and announcing a replacement that did not happen is
                // the same defect this whole change exists to remove, moved into the copy.
                keepNextOccurrence ? null : consumed);
    }

    /**
     * Put back a pending fire time the optimistic advance moved past, and return the
     * schedule as it then stands ({@code null} when nothing was written).
     *
     * <p>Only a time still in the FUTURE is restored. An overdue one is already claimable,
     * so writing it back would have the daemon fire within the minute - two runs from one
     * click, which is the opposite failure to the one this exists to fix.
     */
    private ScheduledExecutionDto restorePendingFire(UUID scheduleId, Instant pendingFire,
                                                     String tenantId, String orgId, String why) {
        if (pendingFire == null || !pendingFire.isAfter(Instant.now())) {
            logger.info("[Agenda] Schedule {}: pending occurrence {} not restored after {} "
                    + "(absent or already due)", scheduleId, pendingFire, why);
            return null;
        }
        ScheduledExecutionDto restored =
                triggerClient.setScheduleNextFire(scheduleId, pendingFire, orgId, tenantId);
        logger.info("[Agenda] Schedule {}: scheduled occurrence at {} preserved after {}",
                scheduleId, pendingFire, why);
        return restored;
    }

    /**
     * Give up the pending occurrence: point the schedule at the first cron slot strictly
     * AFTER it, and return the schedule as it then stands.
     *
     * <p><b>This has to be an explicit write, and that is the part that was missing.</b> The
     * optimistic advance is not a consumption: it asks the cron for its next slot FROM NOW,
     * and an early run is by definition before the occurrence it replaces, so the answer is
     * that same occurrence. Leaving it there made "Run instead" and "Run now" do exactly the
     * same thing, with a sentence under one of them promising otherwise.
     *
     * <p>The slot comes from {@link CronOccurrences}, the same walk the agenda projects the
     * calendar with. A second implementation here would let the row and the drawing of the
     * row disagree, which on this feature is the one bug that cannot be tolerated.
     *
     * <p><b>The walk starts at the LATER of the pending fire and now</b>, which matters only
     * for an occurrence that is already overdue (the daemon behind, or the user having moved
     * one into the past). Walking from the fire itself could then answer with a slot that is
     * also in the past, and the daemon's query is {@code next_execution_at <= now}: it would
     * claim that slot within the minute, so a user asking to SKIP a run would be handed an
     * extra one. Same rule, and the same reason, as the restore above.
     *
     * <p>Two refusals, each of which would otherwise cost the user a run:
     * <ul>
     *   <li><b>The row no longer points at that occurrence.</b> Written as EQUALITY, not as
     *       "has it moved past it": anything that changed the fire time has already dealt
     *       with the occurrence the user was looking at, and in EITHER direction. The daemon
     *       claiming the fire moves it forward, and its advance IS the real consumption, so
     *       writing again would skip the occurrence AFTER the one being given up. A cron
     *       edited in the interval moves it too, and can move it EARLIER (a rare expression
     *       replaced by a frequent one): the row then points at a fire nobody in this call
     *       has seen, and consuming "the old one" would stamp a far-future slot over it and
     *       skip every run in between. The same value is passed as the compare-and-set, so
     *       trigger-service refuses (409) if the row moves between this read and the
     *       write.</li>
     *   <li><b>The cron has nothing after it.</b> A finite expression can be exhausted, and
     *       there is then no later slot to point at, so the row is left alone rather than
     *       stamped with an invented time.</li>
     * </ul>
     */
    private Consumption consumePendingFire(UUID scheduleId, Instant pendingFire,
                                           String tenantId, String orgId) {
        // The row as it stands AFTER the run, and every input below is taken from that one
        // read. Mixing it with the pre-run one would reason about two different moments;
        // there is no reachable case where they disagree about the cron and agree about the
        // fire time (every cron write in trigger-service recomputes the fire, so the guard
        // below catches it), which is precisely why one read is the whole answer rather than
        // two reads plus a rule for reconciling them.
        ScheduledExecutionDto current = triggerClient.getSchedule(scheduleId);
        // A schedule with no pending fire draws no occurrence, so there is nothing to give
        // up. Load-bearing ahead of the equality below, which would otherwise read two nulls
        // as "still pointing at it".
        if (pendingFire == null || current == null) return Consumption.notDone(current);

        Instant stillPending = current.getNextExecutionAt();
        if (!pendingFire.equals(stillPending)) {
            logger.info("[Agenda] Schedule {}: the row no longer points at occurrence {} "
                    + "(it now points at {}), so something else has already dealt with it",
                    scheduleId, pendingFire, stillPending);
            return Consumption.notDone(current);
        }

        String cron = current.getCronExpression();
        String timezone = current.getTimezone();
        // The zone rule that separates a DRAWING from an ARMING, and this is an arming.
        // `CronOccurrences.between` resolves an unknown zone to UTC, which is right for the
        // calendar (draw one row oddly rather than fail a whole month) and wrong here: the
        // answer goes into next_execution_at, so the schedule would start firing at a
        // wall-clock time nobody chose. `CronOccurrences.next` refuses such a zone for
        // exactly this reason; the write doors refuse to store one, so this is the second
        // line rather than the only one.
        if (!CronOccurrences.isResolvableTimezone(timezone)) {
            logger.warn("[Agenda] Schedule {}: timezone '{}' does not resolve, so the slot after {} "
                    + "cannot be computed. Leaving the occurrence alone rather than arming a UTC "
                    + "time nobody chose.", scheduleId, timezone, pendingFire);
            return Consumption.notDone(current);
        }

        Instant now = Instant.now();
        Instant walkFrom = pendingFire.isAfter(now) ? pendingFire : now;
        // `between` includes its start, so ask from one nanosecond later to get the first
        // slot STRICTLY after the occurrence being given up. Open-ended: the cap of 1 bounds it.
        List<Instant> following =
                CronOccurrences.between(cron, timezone, walkFrom.plusNanos(1), null, 1).occurrences();
        if (following.isEmpty()) {
            logger.info("[Agenda] Schedule {}: cron '{}' has no slot after {}, so the occurrence stands",
                    scheduleId, cron, walkFrom);
            return Consumption.notDone(current);
        }

        Instant target = following.get(0);
        // Unreachable as the walk stands, and kept anyway: `target` is strictly after
        // `walkFrom`, which is at least `pendingFire`, which the guard above proved equal to
        // `stillPending`. It is here for whoever changes where the walk starts - moving a
        // schedule BACKWARDS makes the daemon fire a slot it has already passed, and the
        // guard costs a comparison. Deliberately not covered by a test: driving it would mean
        // stubbing a cron walk into answering something it cannot answer, which pins the mock
        // rather than the rule.
        if (!target.isAfter(stillPending)) {
            logger.info("[Agenda] Schedule {}: next slot {} is not after the pending fire {}, "
                    + "refusing to move the schedule backwards", scheduleId, target, stillPending);
            return Consumption.notDone(current);
        }

        ScheduledExecutionDto updated =
                triggerClient.setScheduleNextFire(scheduleId, target, stillPending, orgId, tenantId);
        logger.info("[Agenda] Schedule {}: occurrence at {} consumed by the early run, next fire "
                + "moved to {}", scheduleId, pendingFire, target);
        return new Consumption(updated, true);
    }

    /**
     * What {@link #consumePendingFire} did, which the caller has to know rather than guess.
     *
     * <p>Every refusal in there leaves the occurrence standing, and the two outcomes are
     * indistinguishable from the schedule alone: the row can carry the same fire time
     * because nothing was written, or because the daemon wrote it first. The bell's
     * sentence differs ("in place of the scheduled run" against "the scheduled run still
     * happens"), so the answer travels rather than being re-derived.
     *
     * @param schedule the row as it now stands, or null when it could not be read
     * @param consumed whether THIS call gave the occurrence up, i.e. whether it performed
     *                 the write. Deliberately not "whether the occurrence is gone": on the
     *                 branch where something else moved the fire first, the occurrence may
     *                 well be gone and this is still false, because what the caller has to
     *                 decide is whether IT may claim the replacement.
     */
    private record Consumption(ScheduledExecutionDto schedule, boolean consumed) {
        static Consumption notDone(ScheduledExecutionDto schedule) {
            return new Consumption(schedule, false);
        }
    }

    /**
     * Whether this schedule will ever fire again: enabled, not archived, and under its
     * max-executions cap.
     *
     * <p>Applied to all three actions, not just the run. Rescheduling something that can
     * never run reports a success the user cannot observe: the calendar draws no occurrence
     * for it either way, so "moved" would be a claim about nothing. It is the same
     * definition {@code ScheduleInfo.armed} uses to grey a row out.
     */
    private static boolean isArmed(ScheduledExecutionDto schedule) {
        return schedule.isEnabled() && schedule.getIsActive() && !schedule.hasReachedMaxExecutions();
    }

    /**
     * Load a schedule and prove the caller may act on it.
     *
     * <p>The internal read endpoint is an id lookup with no scope filter of its own, so
     * the check belongs here: without it, knowing a UUID would be enough to move or fire
     * another workspace's schedule. Strict scope, matching the delete paths - a caller in
     * one workspace cannot reach their own schedule from another.
     */
    private ScheduledExecutionDto resolveInScope(UUID scheduleId, String tenantId, String orgId) {
        if (scheduleId == null || tenantId == null || tenantId.isBlank()) return null;
        ScheduledExecutionDto schedule = triggerClient.getSchedule(scheduleId);
        if (schedule == null) return null;
        if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                schedule.getTenantId(), schedule.getOrganizationId())) {
            logger.warn("[Agenda][SCOPE] Refused action on schedule {} for tenantId={} orgId={}",
                    scheduleId, tenantId, orgId);
            return null;
        }
        return schedule;
    }
}
