package com.apimarketplace.trigger.service;

import com.apimarketplace.common.schedule.CronOccurrences;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Cron expression parsing and validation.
 *
 * <p>Accepts a 5-field Unix-style cron expression and converts it to the 6-field
 * Spring representation used internally. The 5-field input is the canonical
 * user-facing format.
 *
 * <p><b>The maths lives in {@link CronOccurrences}</b> (common-lib), not here.
 * Orchestrator's agenda projects the same expressions over a date window to draw the
 * calendar, and a second implementation would let the calendar disagree with this
 * daemon about when a schedule fires. This class delegates validation and occurrence
 * computation and keeps only {@link #getDescription(String)}, which is presentation.
 */
@Service
public class ScheduleCronParser {

    /**
     * Convert standard 5-field cron to 6-field Spring cron (prepends a "0" seconds field).
     * Returns the input unchanged if it already has 6 fields.
     */
    public String toSpringCron(String cron) {
        return CronOccurrences.toSpringCron(cron);
    }

    /**
     * Whether the expression can fire at all. Permissive on purpose - the schedule reapers
     * archive a row PERMANENTLY when this is false, so it must not refuse an expression
     * that still produces occurrences. See {@link CronOccurrences#isValid(String)}.
     */
    public boolean isValid(String cron) {
        return CronOccurrences.isValid(cron);
    }

    /**
     * Whether a cron a caller is submitting should be accepted. Stricter than
     * {@link #isValid(String)}: also refuses step values that silently collapse.
     * See {@link CronOccurrences#isAcceptableInput(String)}.
     */
    public boolean isAcceptableInput(String cron) {
        return CronOccurrences.isAcceptableInput(cron);
    }

    /**
     * Calculate the next execution time for a cron expression in a given timezone.
     */
    public Instant getNextExecution(String cron, String timezone) {
        List<Instant> next = getNextExecutions(cron, timezone, 1);
        return next.isEmpty() ? null : next.get(0);
    }

    /**
     * Calculate the next {@code count} execution times for a cron expression in a
     * given timezone. Returned in ascending chronological order. If the expression
     * is invalid or has no future firings, returns an empty list.
     */
    public List<Instant> getNextExecutions(String cron, String timezone, int count) {
        return CronOccurrences.next(cron, timezone, count);
    }

    /**
     * Check if the cron should execute now (preventing double execution within ~1 min).
     */
    public boolean shouldExecuteNow(String cron, String timezone, Instant lastExecutionAt) {
        if (lastExecutionAt == null) return true;
        Instant now = Instant.now();
        return ChronoUnit.SECONDS.between(lastExecutionAt, now) >= 55;
    }

    /**
     * Get a human-readable description of the cron expression.
     *
     * <p>Returns short, deterministic descriptions for the patterns the inspector
     * exposes via the preset dropdown. Falls back to {@code "Custom: <cron>"} for
     * anything not in the known set. The description is consumed by the inspector
     * as the source of truth - the frontend never re-computes it.
     */
    public String getDescription(String cron) {
        if (cron == null || cron.isBlank()) return "Invalid cron";
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) return "Custom: " + cron;

        String minute = parts[0];
        String hour = parts[1];
        String dayOfMonth = parts[2];
        String month = parts[3];
        String dayOfWeek = parts[4];

        // Every minute
        if ("*".equals(minute) && "*".equals(hour) && "*".equals(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            return "Every minute";
        }

        // Every N minutes (only when N is a valid step within [1, 30])
        if (minute.startsWith("*/") && "*".equals(hour) && "*".equals(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            try {
                int n = Integer.parseInt(minute.substring(2));
                if (n >= 1 && n <= 59) return "Every " + n + " minutes";
            } catch (NumberFormatException ignored) { /* fall through */ }
        }

        // Every hour at minute 0
        if ("0".equals(minute) && "*".equals(hour) && "*".equals(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            return "Every hour";
        }

        // Every N hours at minute 0
        if ("0".equals(minute) && hour.startsWith("*/") && "*".equals(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            try {
                int n = Integer.parseInt(hour.substring(2));
                if (n >= 1 && n <= 23) return "Every " + n + " hours";
            } catch (NumberFormatException ignored) { /* fall through */ }
        }

        // Daily at HH:MM (no day/month/weekday constraint)
        if (isNumeric(minute) && isNumeric(hour) && "*".equals(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            return "Every day at " + pad(hour) + ":" + pad(minute);
        }

        // Weekly at HH:MM on specific weekday(s)
        if (isNumeric(minute) && isNumeric(hour) && "*".equals(dayOfMonth)
                && "*".equals(month) && !"*".equals(dayOfWeek)) {
            return "Every " + formatWeekdays(dayOfWeek) + " at " + pad(hour) + ":" + pad(minute);
        }

        // Monthly at HH:MM on a specific day-of-month
        if (isNumeric(minute) && isNumeric(hour) && isNumeric(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            return "On day " + dayOfMonth + " of every month at " + pad(hour) + ":" + pad(minute);
        }

        return "Custom: " + cron;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static String pad(String s) {
        if (s == null) return "00";
        return s.length() == 1 ? "0" + s : s;
    }

    private static final String[] WEEKDAY_NAMES = {
            "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    };

    private static String formatWeekdays(String dayOfWeek) {
        // Special cases for common patterns the inspector exposes.
        if ("1-5".equals(dayOfWeek)) return "weekday";
        if ("0,6".equals(dayOfWeek) || "6,0".equals(dayOfWeek)) return "weekend day";

        // Single day or comma-separated list of single days.
        StringBuilder sb = new StringBuilder();
        String[] tokens = dayOfWeek.split(",");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].trim();
            if (i > 0) sb.append(", ");
            if (isNumeric(token)) {
                int n = Integer.parseInt(token);
                if (n >= 0 && n <= 7) {
                    sb.append(WEEKDAY_NAMES[n]);
                    continue;
                }
            }
            sb.append(token);
        }
        return sb.toString();
    }
}
