package com.apimarketplace.orchestrator.services.badge;

/**
 * Visual rank of a badge. Purely presentational: the tier drives the medal's
 * metal and glow in the UI and the sort order inside a family. It carries no
 * unlock logic - {@link BadgeDefinition#threshold()} alone decides that.
 *
 * <p>Ordinal order is the display order (weakest first), so a family renders
 * as a natural progression.
 */
public enum BadgeTier {
    BRONZE,
    SILVER,
    GOLD,
    PLATINUM,
    DIAMOND
}
