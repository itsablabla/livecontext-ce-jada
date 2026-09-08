package com.apimarketplace.common.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Cron validation and occurrence maths, shared by every service that has to reason
 * about when a schedule fires.
 *
 * <p>Two services need the same answers and must never drift: trigger-service owns
 * {@code scheduled_executions.next_execution_at} (it computes the ONE next fire when a
 * schedule is created, toggled, or recorded), and orchestrator's agenda projects the
 * SAME expression over a date window to draw the calendar. A second implementation
 * would let the calendar disagree with the daemon, which is the one bug this feature
 * cannot afford: the user would drag an occurrence that never existed.
 *
 * <p>{@code ScheduleCronParser} (trigger-service) delegates here and keeps only the
 * human-readable description, which is presentation, not maths.
 *
 * <p><b>Input format</b>: the canonical user-facing expression is 5-field Unix cron.
 * {@link #toSpringCron(String)} prepends the seconds field Spring requires. A 6-field
 * expression is accepted unchanged.
 *
 * <p><b>Strict step validation</b>: Spring's {@code CronExpression} silently accepts a
 * step value larger than its field maximum and collapses it to "start of range only",
 * so "every 120 minutes" quietly becomes "every hour at :00". Such expressions are
 * rejected here so the error is honest.
 */
public final class CronOccurrences {

    private CronOccurrences() {}

    /** Maximum value per 5-field cron position (minute, hour, day, month, weekday). */
    private static final int[] FIELD_MAX_5 = { 59, 23, 31, 12, 7 };

    /** Minimum value per 5-field cron position. day/month start at 1; everything else at 0. */
    private static final int[] FIELD_MIN_5 = { 0, 0, 1, 1, 0 };

    /** Maximum value per 6-field Spring cron position (second, minute, hour, day, month, weekday). */
    private static final int[] FIELD_MAX_6 = { 59, 59, 23, 31, 12, 7 };

    /** Minimum value per 6-field Spring cron position. */
    private static final int[] FIELD_MIN_6 = { 0, 0, 0, 1, 1, 0 };

    /**
     * Convert a standard 5-field cron to the 6-field Spring form by prepending a
     * {@code "0"} seconds field. Returns a 6-field input unchanged, {@code null} for
     * blank input.
     */
    public static String toSpringCron(String cron) {
        if (cron == null || cron.isBlank()) return null;
        String trimmed = cron.trim();
        String[] parts = trimmed.split("\\s+");
        return parts.length == 5 ? "0 " + trimmed : trimmed;
    }

    /**
     * Whether the expression can produce occurrences at all, i.e. Spring can parse it.
     *
     * <p><b>Deliberately permissive, and it must stay that way.</b> This is the predicate
     * the occurrence walk and the schedule reapers use, and in the reapers a {@code false}
     * is <i>destructive</i>: the row is archived with {@code INVALID_CRON_LEGACY}, which is
     * permanent. So the only thing it may refuse is an expression that genuinely cannot
     * fire - the case those reapers were written for, where {@code getNextExecution}
     * returns null and the caller falls back to "in 60 seconds", forever.
     *
     * <p>Use {@link #isAcceptableInput(String)} to vet a cron a caller is submitting. The
     * two were one method until a degenerate-but-firing expression (see that method) showed
     * what conflating them costs: tightening the input rule also silently emptied the
     * occurrence walk for every stored row matching it, so those schedules stopped firing
     * and were then archived beyond recovery on their next tick.
     */
    public static boolean isValid(String cron) {
        if (cron == null || cron.isBlank()) return false;
        try {
            CronExpression.parse(toSpringCron(cron.trim()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether an expression is acceptable as NEW input: parseable, and free of the
     * silently-collapsing step values described in the class Javadoc.
     *
     * <p>{@code 1-5/10} is the shape at issue. It parses, and it fires - on minute 1 only,
     * because a step of 10 over a five-value range keeps just the first. Nobody writing it
     * meant that, so it is refused at the door. What must NOT follow is treating an
     * already stored row carrying such an expression as broken: it has been firing,
     * narrowly but correctly, and destroying it to enforce a rule introduced afterwards is
     * not a fix.
     */
    public static boolean isAcceptableInput(String cron) {
        if (!isValid(cron)) return false;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length == 5) {
            return stepValuesWithinFieldRange(parts, FIELD_MIN_5, FIELD_MAX_5);
        }
        if (parts.length == 6) {
            return stepValuesWithinFieldRange(parts, FIELD_MIN_6, FIELD_MAX_6);
        }
        return true;
    }

    /**
     * Resolve a timezone id, falling back to UTC for a null, blank, or unknown zone
     * rather than throwing. A schedule row carrying a corrupt timezone must still draw
     * on the calendar (in UTC) instead of taking the whole window's response down.
     */
    public static ZoneId zoneOrUtc(String timezone) {
        if (timezone == null || timezone.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }

    /**
     * The next {@code count} fire times strictly after now, ascending. Empty when the
     * expression is invalid or has no future firing.
     *
     * <p><b>This is the daemon's arming value, not a display helper.</b> trigger-service
     * writes the first element into {@code scheduled_executions.next_execution_at}, and the
     * claim query is {@code WHERE next_execution_at <= now}. An instant at or before now is
     * therefore immediately due again: the schedule is re-claimed on the next minute tick
     * and the job runs a SECOND time. The per-pod in-flight guard does not catch it.
     *
     * <p>Hence the strictly-after cursor here, against {@link #between}'s inclusive one.
     * The daemon asks this question milliseconds after firing the slot, so the two
     * boundaries are not interchangeable: sharing the inclusive walk re-answers with the
     * slot that just fired.
     */
    public static List<Instant> next(String cron, String timezone, int count) {
        // A zone this platform cannot resolve makes the answer UNKNOWN, not UTC. The
        // calendar can afford to draw a corrupt row in UTC rather than fail a whole month;
        // the daemon cannot, because its answer is written to next_execution_at and the row
        // would then start firing at a wall-clock time nobody chose.
        //
        // Returning empty is NOT by itself enough to make the row inert, and believing it
        // was is how this stayed open: every caller that ARMS a row falls back to "in 60
        // seconds" on a null, and record-execution recomputes the same null and falls back
        // again - so an unresolvable zone made a schedule fire every minute forever. The
        // write doors refuse such a zone (see isResolvableTimezone's callers) so it cannot
        // be stored. This is the second line, not the only one.
        if (!isResolvableTimezone(timezone)) {
            return List.of();
        }
        return expand(cron, timezone, Instant.now(), null, count).occurrences();
    }

    /** Whether a timezone id resolves; blank means UTC, which always does. */
    public static boolean isResolvableTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) return true;
        try {
            ZoneId.of(timezone);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Every fire time of {@code cron} inside {@code [from, to]}, ascending.
     *
     * <p>{@code from} is INCLUSIVE: an occurrence landing exactly on the window start is
     * returned, because a day view opening at 09:00 has to draw the 09:00 job. Spring's
     * {@code CronExpression.next} is strictly-after, so the walk starts one NANOsecond
     * early - just enough to admit {@code from} itself and nothing before it. Backing off a
     * whole second instead would also admit the entire preceding second, which is how a
     * window can return an occurrence outside itself. {@code to} may be null for an
     * open-ended window, in which case {@code cap} is the only bound.
     *
     * <p>For "the next fire from now", use {@link #next} - it walks strictly after its
     * start and is the value the scheduling daemon arms on.
     *
     * <p><b>The cap is an answer, not a safety net.</b> A once-a-minute cron over a month
     * is 43 200 occurrences: drawing them is neither useful nor affordable, so the
     * expansion stops at {@code cap} and reports {@link Window#truncated()}. Callers MUST
     * surface that, because silently showing the first N would misrepresent the schedule
     * as stopping mid-window.
     *
     * @param cap maximum occurrences to return; a value below 1 yields an empty window
     */
    public static Window between(String cron, String timezone, Instant from, Instant to, int cap) {
        if (from == null) {
            return new Window(List.of(), false);
        }
        // One nanosecond, not one second: enough for `from` itself to survive the
        // strictly-after walk, too little to reach anything before it.
        return expand(cron, timezone, from.minusNanos(1), to, cap);
    }

    /**
     * The shared walk. {@code after} is EXCLUSIVE - the first occurrence returned is the
     * first one strictly later than it. Every caller states its own boundary by choosing
     * what to pass, which is the whole reason this is separate: the calendar wants the
     * window start included, the daemon wants now excluded, and one shared "roughly here"
     * cursor silently gives one of them the wrong answer.
     */
    private static Window expand(String cron, String timezone, Instant after, Instant to, int cap) {
        if (cap < 1 || after == null || !isValid(cron)) {
            return new Window(List.of(), false);
        }
        if (to != null && to.isBefore(after)) {
            return new Window(List.of(), false);
        }
        ZoneId zone = zoneOrUtc(timezone);
        List<Instant> result = new ArrayList<>();
        try {
            CronExpression expression = CronExpression.parse(toSpringCron(cron));
            LocalDateTime cursor = LocalDateTime.ofInstant(after, zone);
            while (result.size() < cap) {
                LocalDateTime nextLocal = expression.next(cursor);
                if (nextLocal == null) break;                       // expression exhausted
                Instant nextInstant = nextLocal.atZone(zone).toInstant();
                // Compared as an INSTANT, not as a local date-time. The window is an
                // interval of instants, and on a spring-forward day the two disagree: a
                // 02:30 slot does not exist, so Spring answers 03:30 local, whose instant is
                // an hour later than the local comparison believes. The walk then returned a
                // fire 30 minutes past the window end - a chip drawn on a day the caller
                // never asked about, and one the next window would draw again.
                if (to != null && nextInstant.isAfter(to)) break;
                result.add(nextInstant);
                cursor = nextLocal;
            }
            // Truncated only when the cap is what stopped us AND a further occurrence
            // still exists inside the window.
            boolean truncated = false;
            if (result.size() == cap) {
                LocalDateTime beyond = expression.next(
                        LocalDateTime.ofInstant(result.get(result.size() - 1), zone));
                // Same comparison as the loop, for the same reason: asking it in local time
                // here would report a capped window as complete, or a complete one as
                // capped, on exactly the days the loop was getting wrong.
                truncated = beyond != null
                        && (to == null || !beyond.atZone(zone).toInstant().isAfter(to));
            }
            return new Window(List.copyOf(result), truncated);
        } catch (Exception e) {
            // A zone/expression combination Spring refuses mid-walk (never observed for
            // validated input) degrades to "nothing to draw" rather than a 500.
            return new Window(List.copyOf(result), false);
        }
    }

    /**
     * Result of expanding one cron over one window.
     *
     * @param occurrences ascending fire instants inside the window
     * @param truncated   true when the cap stopped the expansion before the window end,
     *                    meaning more occurrences exist than are listed
     */
    public record Window(List<Instant> occurrences, boolean truncated) {}

    /**
     * Validate that every step value stays within the range it steps over. Comma lists are
     * checked element by element, because an embedded oversized step collapses just as
     * silently as a standalone one.
     *
     * <p>The range is the one the token EXPRESSES, not the field's maximum: {@code 1-5/10}
     * steps over five values, so a step of 10 collapses it to minute 1 alone - exactly the
     * silent collapse this guard exists to reject, and one that passes if the step is only
     * compared against the field's own 0-59.
     */
    private static boolean stepValuesWithinFieldRange(String[] fieldParts, int[] fieldMin, int[] fieldMax) {
        for (int position = 0; position < fieldParts.length; position++) {
            String field = fieldParts[position];
            int max = fieldMax[position];
            int min = fieldMin[position];
            for (String part : field.split(",")) {
                int slash = part.indexOf('/');
                if (slash < 0) continue;
                int step;
                try {
                    step = Integer.parseInt(part.substring(slash + 1));
                } catch (NumberFormatException e) {
                    return false;
                }
                if (step <= 0) return false;

                // How many values the token actually spans: the whole field for `*` or a
                // bare start, or the explicit range for `a-b`.
                String head = part.substring(0, slash);
                int span = max - min + 1;
                boolean explicitRange = false;
                int dash = head.indexOf('-');
                if (dash > 0) {
                    try {
                        int from = Integer.parseInt(head.substring(0, dash));
                        int to = Integer.parseInt(head.substring(dash + 1));
                        if (from > to) return false;
                        span = to - from + 1;
                        explicitRange = true;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }
                // The boundary depends on whether the author WROTE the range, and that is
                // not a nicety: it separates an idiom from a mistake.
                //
                // Over an implicit range the rule stays step > span. A step equal to the
                // whole field keeps only its first value, but that is exactly what it says:
                // every 60 minutes IS hourly, intent and behaviour agree, and it is the
                // ordinary way to write it. A step the field cannot traverse even once
                // (*/120 on minutes) is still refused.
                //
                // Over an explicit range the rule is step >= span. Bounding the range and
                // then choosing a step that cannot reach a second value inside it is the
                // same disagreement as 1-5/10: nobody writes 1-5 to mean "minute 1".
                if (explicitRange ? step >= span : step > span) {
                    return false;
                }
            }
        }
        return true;
    }
}
