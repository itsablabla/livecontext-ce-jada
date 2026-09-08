package com.apimarketplace.common.retention;

import com.apimarketplace.common.plan.PlanTier;

/**
 * GENERATED FILE - DO NOT EDIT BY HAND.
 * Source: shared/contracts/log-retention.json
 * Regenerate: node shared/contracts/scripts/generate-log-retention.js
 *
 * <p>How long a workspace's EXECUTION JOURNAL is kept, per subscription plan.
 *
 * <p><b>Keyed by plan RANK, not by plan code.</b> {@link PlanTier} ranks
 * {@code PAYG} with {@code STARTER}, {@code CREDIT_PACK} with {@code FREE}, and
 * all four {@code ENTERPRISE_*} SKUs together. Keying this table by literal code
 * would leave those codes unmapped, and an unmapped code must never be guessed
 * into a shorter window.
 *
 * <p><b>Unknown plan CODES retain FOREVER</b>, and so do self-hosted installs and
 * the no-subscription sentinel: {@link #retentionDays(String)} answers {@code null}
 * for all of them, and {@code null} means "keep".
 *
 * <p><b>Blank and null are the exception, and callers must guard them.</b>
 * {@link PlanTier} ranks an empty code as FREE, so this method answers the SEVEN
 * day window for {@code null} and {@code ""}. That is a deliberate reflection of
 * PlanTier rather than a second opinion about it, but it means a blank code is the
 * one input that shortens rather than retains. {@code LogRetentionService} refuses
 * blanks before ever calling here; any new caller must do the same. This is the
 * deliberate mirror image of {@code PlanFeatureGate}, which fails OPEN: that gate
 * protects revenue, so a lookup failure costs a sale; this policy DELETES, so a
 * lookup failure must cost nothing. A purge job that defaulted an unresolvable
 * tenant to the shortest window would erase a paying customer's journal on the
 * strength of a failed HTTP call.
 *
 * <p>Retention covers the execution journal only. It never covers files (any
 * storage row carrying an {@code s3_key}), chat history, or the credit ledger;
 * those exclusions live in the purge jobs, which own the row-class allow-list.
 */
public final class LogRetentionPolicy {

    private LogRetentionPolicy() {
    }

    /**
     * Days of execution-log retention for {@code planCode}, or {@code null} to
     * retain indefinitely.
     *
     * @param planCode the account's effective plan code, as returned by
     *                 auth-service. The no-subscription sentinel RETAINS (it also
     *                 means "no account matched"); {@code null} and blank rank as
     *                 FREE and therefore SHORTEN, so guard them before calling
     */
    public static Integer retentionDays(String planCode) {
        if (isNoSubscriptionSentinel(planCode)) {
            // NOT free. PlanLimitService returns this sentinel both for "account
            // found, no active subscription" and for "account not found", so
            // reading it as FREE would hand the SHORTEST window to a tenant the
            // platform simply failed to identify. The FREE window requires the
            // literal code FREE, i.e. a positive identification.
            return null;
        }
        int rank = PlanTier.userRank(planCode);
        return switch (rank) {
            case 0 -> Integer.valueOf(7); // FREE
            case 1 -> Integer.valueOf(30); // STARTER
            case 2 -> Integer.valueOf(30); // PRO
            case 3 -> Integer.valueOf(90); // TEAM
            case 4 -> null; // ENTERPRISE
            default -> null; // CE and any code PlanTier does not know: retain
        };
    }

    /**
     * The pricing-page feature key describing {@code planCode}'s window, so the
     * advertised copy and the enforced window cannot drift apart.
     */
    public static String featureKey(String planCode) {
        if (isNoSubscriptionSentinel(planCode)) {
            return "logsCustom";
        }
        int rank = PlanTier.userRank(planCode);
        return switch (rank) {
            case 0 -> "logs7"; // FREE
            case 1 -> "logs30"; // STARTER
            case 2 -> "logs30"; // PRO
            case 3 -> "logs90"; // TEAM
            case 4 -> "logsCustom"; // ENTERPRISE
            default -> "logsCustom";
        };
    }

    /**
     * Whether a row created at {@code ageDays} old is past {@code planCode}'s
     * window. Retention-forever plans always answer {@code false}.
     */
    public static boolean isExpired(String planCode, long ageDays) {
        Integer days = retentionDays(planCode);
        return days != null && ageDays > days;
    }

    private static boolean isNoSubscriptionSentinel(String planCode) {
        return planCode != null
                && PlanTier.NO_SUBSCRIPTION.equalsIgnoreCase(planCode.trim());
    }
}
