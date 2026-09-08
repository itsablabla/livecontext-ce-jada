package com.apimarketplace.common.schedule;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Rewrites a cron expression so that a schedule fires at a new time, for the agenda's
 * "move ALL occurrences" action.
 *
 * <p><b>Why this refuses more than it accepts.</b> "Move every occurrence to 14:00" has
 * one honest answer only when the expression names a single time of day. For
 * {@code 0 star/2 star star star} ("every 2 hours") there is no such thing as "the" time
 * to move: any rewrite silently invents a different frequency, and the user would
 * discover it days later when a run they were counting on did not happen. So the shapes
 * below are rewritten and everything else is REFUSED with a reason the UI can explain,
 * leaving the user the "this occurrence only" path, which is always exact.
 *
 * <p>The accepted shapes are exactly the ones the schedule inspector produces:
 * <ul>
 *   <li><b>daily</b> {@code m h * * *} - time is rewritten.</li>
 *   <li><b>weekly</b> {@code m h * * D} - time is rewritten; a SINGLE weekday moves to
 *       the target's weekday, while a set ({@code 1-5}, {@code 0,6}) is kept and the
 *       target must already fall inside it.</li>
 *   <li><b>monthly</b> {@code m h D * *} - time and day-of-month are rewritten.</li>
 * </ul>
 *
 * <p>All fields are derived in the SCHEDULE's timezone, never the viewer's: the cron is
 * interpreted in {@code scheduled_executions.timezone}, so a Paris user dragging a
 * UTC-scheduled job must not stamp Paris wall-clock fields into it.
 */
public final class CronShifter {

    private CronShifter() {}

    /**
     * Why a rewrite was refused, or {@link #OK} when it succeeded. These map 1:1 to a
     * user-facing explanation; keep them specific enough to act on.
     */
    public enum Reason {
        /** Rewrite succeeded. */
        OK,
        /** The expression is not a cron this platform honours. */
        INVALID_CRON,
        /**
         * The expression does not name a single time of day (steps, wildcards or lists
         * in the minute/hour fields, or a month constraint), so "every occurrence" has
         * no unambiguous new time.
         */
        UNSUPPORTED_PATTERN,
        /**
         * The expression fires on a fixed SET of weekdays and the target date is not one
         * of them. Moving it would have to add or drop a weekday, which changes how often
         * the schedule runs.
         */
        WEEKDAY_SET_NOT_MATCHED,
        /**
         * A monthly schedule would land after the 28th, a day that does not exist in
         * every month - the schedule would silently skip those months.
         */
        DAY_OF_MONTH_UNSAFE
    }

    /**
     * Outcome of a rewrite attempt.
     *
     * @param cron   the new expression, or null when {@code reason != OK}
     * @param reason why it succeeded or was refused
     */
    public record ShiftResult(String cron, Reason reason) {
        public boolean isSuccess() { return reason == Reason.OK && cron != null; }

        static ShiftResult refused(Reason reason) { return new ShiftResult(null, reason); }
    }

    /**
     * Whether this expression's SHAPE can be rewritten at all, independent of any target
     * date. Drives whether the agenda offers the "all occurrences" choice; a shape that
     * passes here can still be refused for a specific target (a weekday set that does not
     * contain it, a day-of-month past the 28th).
     */
    public static boolean supportsShiftAll(String cron) {
        Fields fields = parse(cron);
        return fields != null && fields.shape() != Shape.UNSUPPORTED;
    }

    /**
     * Rewrite {@code cron} so the schedule fires at {@code target} instead of its current
     * time, for every future occurrence.
     *
     * @param cron     the schedule's current expression (5-field, or 6-field with a zero
     *                 seconds field)
     * @param timezone the schedule's timezone - the zone the cron is interpreted in
     * @param target   the instant the user dropped the occurrence on
     */
    public static ShiftResult shiftAll(String cron, String timezone, Instant target) {
        if (target == null) return ShiftResult.refused(Reason.UNSUPPORTED_PATTERN);
        Fields fields = parse(cron);
        if (fields == null) return ShiftResult.refused(Reason.INVALID_CRON);

        ZonedDateTime local = target.atZone(CronOccurrences.zoneOrUtc(timezone));
        int minute = local.getMinute();
        int hour = local.getHour();

        switch (fields.shape()) {
            case DAILY:
                return build(minute, hour, "*", "*");
            case WEEKLY_SINGLE_DAY:
                // Sunday is 0 in cron; java.time DayOfWeek is MONDAY(1)..SUNDAY(7).
                return build(minute, hour, "*", String.valueOf(local.getDayOfWeek().getValue() % 7));
            case WEEKLY_DAY_SET: {
                int targetDow = local.getDayOfWeek().getValue() % 7;
                if (!fields.weekdays().contains(targetDow)) {
                    return ShiftResult.refused(Reason.WEEKDAY_SET_NOT_MATCHED);
                }
                return build(minute, hour, "*", fields.dayOfWeek());
            }
            case MONTHLY: {
                int dayOfMonth = local.getDayOfMonth();
                if (dayOfMonth > 28) {
                    return ShiftResult.refused(Reason.DAY_OF_MONTH_UNSAFE);
                }
                return build(minute, hour, String.valueOf(dayOfMonth), "*");
            }
            default:
                return ShiftResult.refused(Reason.UNSUPPORTED_PATTERN);
        }
    }

    private static ShiftResult build(int minute, int hour, String dayOfMonth, String dayOfWeek) {
        String cron = minute + " " + hour + " " + dayOfMonth + " * " + dayOfWeek;
        // Belt and braces: never hand back an expression we would ourselves reject.
        return CronOccurrences.isValid(cron)
                ? new ShiftResult(cron, Reason.OK)
                : ShiftResult.refused(Reason.INVALID_CRON);
    }

    private enum Shape { DAILY, WEEKLY_SINGLE_DAY, WEEKLY_DAY_SET, MONTHLY, UNSUPPORTED }

    /**
     * The classified 5-field view of an expression.
     *
     * @param dayOfWeek the raw day-of-week field, preserved verbatim so a matched set is
     *                  written back exactly as the user wrote it
     * @param weekdays  the day-of-week field expanded to cron numbers, empty unless the
     *                  shape is {@link Shape#WEEKLY_DAY_SET}
     */
    private record Fields(int minute, int hour, String dayOfMonth, String dayOfWeek,
                          Set<Integer> weekdays, Shape shape) {}

    /**
     * Parse and classify. Returns null when the expression is not one this platform
     * honours; a {@link Shape#UNSUPPORTED} result means "valid cron, but not a single
     * named time of day".
     */
    private static Fields parse(String cron) {
        if (!CronOccurrences.isValid(cron)) return null;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length == 6) {
            // Only a zero seconds field maps onto the 5-field user-facing form; a
            // sub-minute schedule has no single "time of day" to move.
            if (!"0".equals(parts[0])) {
                return new Fields(0, 0, "*", "*", Set.of(), Shape.UNSUPPORTED);
            }
            parts = new String[] { parts[1], parts[2], parts[3], parts[4], parts[5] };
        }
        if (parts.length != 5) return null;

        String minuteField = parts[0];
        String hourField = parts[1];
        String dayOfMonth = parts[2];
        String month = parts[3];
        String dayOfWeek = parts[4];

        Integer minute = singleNumber(minuteField);
        Integer hour = singleNumber(hourField);
        // A month constraint (or a non-literal minute/hour) means there is no single
        // recurring time this move could rewrite.
        if (minute == null || hour == null || !"*".equals(month)) {
            return new Fields(0, 0, "*", "*", Set.of(), Shape.UNSUPPORTED);
        }

        boolean anyDayOfMonth = "*".equals(dayOfMonth);
        boolean anyDayOfWeek = "*".equals(dayOfWeek) || "?".equals(dayOfWeek);

        if (anyDayOfMonth && anyDayOfWeek) {
            return new Fields(minute, hour, dayOfMonth, dayOfWeek, Set.of(), Shape.DAILY);
        }
        if (anyDayOfMonth) {
            Integer single = singleNumber(dayOfWeek);
            if (single != null) {
                return new Fields(minute, hour, dayOfMonth, dayOfWeek, Set.of(), Shape.WEEKLY_SINGLE_DAY);
            }
            Set<Integer> set = expandWeekdays(dayOfWeek);
            return set == null
                    ? new Fields(0, 0, "*", "*", Set.of(), Shape.UNSUPPORTED)
                    : new Fields(minute, hour, dayOfMonth, dayOfWeek, set, Shape.WEEKLY_DAY_SET);
        }
        if (anyDayOfWeek && singleNumber(dayOfMonth) != null) {
            return new Fields(minute, hour, dayOfMonth, dayOfWeek, Set.of(), Shape.MONTHLY);
        }
        // Both day fields constrained, or a day-of-month list: cron ORs them, and which
        // one the user meant to move is a guess.
        return new Fields(0, 0, "*", "*", Set.of(), Shape.UNSUPPORTED);
    }

    /** The field as a single non-negative integer, or null when it is anything else. */
    private static Integer singleNumber(String field) {
        if (field == null || field.isEmpty()) return null;
        for (int i = 0; i < field.length(); i++) {
            if (!Character.isDigit(field.charAt(i))) return null;
        }
        try {
            return Integer.parseInt(field);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Expand a numeric weekday list/range ({@code 1-5}, {@code 0,6}, {@code 1,3,5}) to
     * cron numbers, normalising 7 to 0 (both mean Sunday). Returns null for anything
     * non-numeric (named days, steps), which the caller treats as unsupported rather
     * than guessing.
     */
    private static Set<Integer> expandWeekdays(String field) {
        Set<Integer> days = new LinkedHashSet<>();
        for (String token : field.split(",")) {
            String trimmed = token.trim();
            int dash = trimmed.indexOf('-');
            if (dash > 0) {
                Integer start = singleNumber(trimmed.substring(0, dash));
                Integer end = singleNumber(trimmed.substring(dash + 1));
                if (start == null || end == null || start > end || end > 7) return null;
                for (int d = start; d <= end; d++) days.add(d % 7);
                continue;
            }
            Integer single = singleNumber(trimmed);
            if (single == null || single > 7) return null;
            days.add(single % 7);
        }
        return days.isEmpty() ? null : days;
    }
}
