package com.apimarketplace.orchestrator.controllers.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Batched per-workflow metadata for one card on the Applications page: the application-dedicated run
 * id ({@code applicationRunId}, drives the live preview), its last-executed timestamp
 * ({@code lastExecutedAt} = lastFireAt of the most recent epoch, else the run's startedAt - drives the
 * execution sort), and the workflow's pinned version ({@code pinnedVersion}, drives the Live/Active
 * badge).
 *
 * <p>Returned in the {@code Map<workflowId, ApplicationRunVersionSummary>} of
 * {@code POST /api/workflows/applications/run-version-batch}, which replaces the per-card
 * {@code /runs/application} + {@code /versions} N+1 (two HTTP calls PER application, ~200 cards). Any
 * field may be null: {@code applicationRunId}/{@code lastExecutedAt} are null when the workflow has no
 * application run yet; {@code pinnedVersion} is null when the workflow is unpinned (Inactive). A
 * workflow id absent from the response map reads as "load failed / no data" on the client.
 */
public record ApplicationRunVersionSummary(
        String applicationRunId,
        Instant lastExecutedAt,
        Integer pinnedVersion,
        /** Spending cap in credits, or null when the app is uncapped (the default). */
        BigDecimal budgetCredits,
        /** How the cap resets: monthly | weekly | cumulative. */
        String budgetPeriodMode,
        /**
         * Spent by the governed runs in the period open right now, already rolled
         * over server-side, so a card never shows last period's spend against
         * this period's cap. Present even without a cap: an app that costs
         * something should say so whether or not it is capped.
         */
        BigDecimal budgetPeriodSpent,
        /**
         * When the open period rolls over and the allowance starts again, or
         * {@code null} for a cap that never resets.
         *
         * <p>Computed server-side from the same rule the counter resets on. The
         * client could derive it from the cadence alone, and that is exactly why it
         * is sent instead: a second implementation of the calendar rule would agree
         * until one of them was edited.
         */
        java.time.Instant budgetPeriodResetsAt
) {}
