package com.apimarketplace.orchestrator.controllers.dto;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Summary DTO for workflow list responses.
 */
public record WorkflowSummary(
    UUID id,
    String name,
    String description,
    String tenantId,
    WorkflowEntity.WorkflowStatus status,
    Instant createdAt,
    Instant updatedAt,
    Instant lastExecutedAt,
    long runCount,
    Map<String, Object> metadata,
    Map<String, Object> plan,
    Map<String, Object> schedule,
    Map<String, String> webhookTokens,
    List<Map<String, Object>> nodeIcons,
    UUID sourcePublicationId,
    Instant acquiredAt,
    boolean isPublished,
    /**
     * Moderation state of this workflow's publication, when one exists and is
     * still shared: {@code "ACTIVE"} | {@code "PENDING_REVIEW"} | {@code "REJECTED"}.
     * {@code null} when the workflow is not shared (or its publication is
     * INACTIVE). The frontend list renders a distinct badge per state - e.g.
     * an orange "shared · in review" chip for {@code PENDING_REVIEW}. Note
     * {@code isPublished} stays the ACTIVE-only boolean for backward compat.
     */
    String publicationStatus,
    UUID projectId,
    WorkflowEntity.WorkflowType workflowType,
    Integer pinnedVersion,
    boolean hasActiveRun,
    String boardColumn,
    /**
     * Optional spending cap in credits (1 credit = $0.001), or null when none is
     * set (the default: leave it empty and nothing is capped, exactly like an
     * agent's budget). Applies to the agent spend of every run except a
     * builder test fire, in one
     * {@code budgetPeriodMode} period. The frontend renders it as dollars in CE
     * and raw credits in cloud.
     */
    java.math.BigDecimal budgetCredits,
    /** How the cap resets: {@code monthly} (default), {@code weekly}, {@code cumulative}. */
    String budgetPeriodMode,
    /**
     * What the governed runs have spent in the period open RIGHT NOW, already
     * rolled over server-side: a stored figure from an expired period reads as
     * zero here, so the card never shows last month's spend against this
     * month's cap. Always present (0 when nothing was spent), even when there
     * is no cap, so the list can show the running cost either way.
     */
    java.math.BigDecimal budgetPeriodSpent,
    /**
     * When the open period rolls over and the allowance starts again, or
     * {@code null} for a cap that never resets.
     *
     * <p>Computed server-side from the same rule the counter resets on. The
     * client could derive it from the cadence alone, and that is exactly why it
     * is sent instead: a second implementation of the calendar rule would agree
     * until one of them was edited.
     */
    java.time.Instant budgetPeriodResetsAt,
    /**
     * Folder this workflow is filed under on the list page (V448), or {@code null} when it
     * sits at the top level. Lets the list show where a row lives without a second call -
     * used by the "move to..." dialog to preselect the current folder.
     */
    UUID folderId
) {}
