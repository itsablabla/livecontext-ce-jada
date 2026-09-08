package com.apimarketplace.orchestrator.services.badge;

import java.time.Instant;

/**
 * One badge as the UI sees it: its definition plus this user's standing
 * against it.
 *
 * @param code       stable catalog code - also the frontend's i18n + artwork key
 * @param family     thematic group (drives grouping and artwork)
 * @param tier       visual rank
 * @param metric     what is being measured
 * @param threshold  value at which it unlocks
 * @param value      the user's current value for {@code metric}. For an unlocked
 *                   badge this is the value FROZEN at unlock time, so a trophy
 *                   never displays a number lower than the one that earned it
 *                   after the user deletes a workflow.
 * @param unlocked   whether the user holds it
 * @param unlockedAt when it was earned; null while locked
 */
public record BadgeView(
        String code,
        BadgeFamily family,
        BadgeTier tier,
        BadgeMetric metric,
        long threshold,
        long value,
        boolean unlocked,
        Instant unlockedAt
) {}
