package com.apimarketplace.orchestrator.services.badge;

/**
 * One entry of the badge catalog.
 *
 * @param code      stable identifier persisted in {@code orchestrator.user_badges}
 *                  and used as the frontend's i18n + artwork key. NEVER reuse a
 *                  code for a different badge: existing unlock rows would silently
 *                  re-label themselves.
 * @param family    thematic group, drives grouping + artwork
 * @param tier      visual rank inside the family
 * @param metric    the quantity measured
 * @param threshold value of {@code metric} at which the badge unlocks (inclusive)
 */
public record BadgeDefinition(
        String code,
        BadgeFamily family,
        BadgeTier tier,
        BadgeMetric metric,
        long threshold
) {
    /** True when the supplied metric value has reached this badge's threshold. */
    public boolean isUnlockedBy(long value) {
        return value >= threshold;
    }
}
