package com.apimarketplace.publication.dto;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Slim DTO for the anonymous-accessible {@code GET /api/publications/highlights/{displayMode}}
 * endpoint. Excludes all heavy / sensitive jsonb columns ({@code planSnapshot},
 * {@code agentSnapshot}, {@code showcaseSnapshot}) that anonymous callers have
 * no business reading and that would otherwise bloat the cached payload to many
 * KB per row. Only fields actually consumed by {@code HighlightedApps.tsx} are
 * exposed.
 */
public record PublicHighlightItem(
        UUID id,
        String title,
        String description,
        String displayMode,
        Integer creditsPerUse,
        String publisherId,
        String publisherName,
        String publisherAvatarUrl,
        UUID showcaseInterfaceId,
        String showcaseRunId,
        List<Map<String, Object>> nodeIcons,
        Integer agentCount,
        Integer skillCount,
        Integer workflowCount,
        Integer interfaceCount,
        Integer datasourceCount,
        Double averageRating,
        Integer reviewCount,
        // V420 - the "CE exclusive" label. Curated highlights are the most-seen
        // surface (anonymous Home / chat row), so omitting it here would leave the
        // one place a self-hosted-only app is NOT marked.
        Boolean ceExclusive,
        List<String> ceExclusiveFeatures,
        /**
         * True when this publication belongs in the Studio.
         *
         * <p>Carried because a surface can be scoped to the studio AXIS (the studio's application
         * row is), and such a row has to narrow the reader's FAVOURITES the same way it narrows the
         * marketplace. Without it a row headed "My studio apps" either lists every favourite the
         * reader has, or - filtering on a field that is never sent - lists none, and the second
         * reads as "you have none" for apps they favourited themselves.
         */
        Boolean studio
) {
    public static PublicHighlightItem from(WorkflowPublicationEntity p) {
        DisplayMode mode = p.getDisplayMode();
        return new PublicHighlightItem(
                p.getId(),
                p.getTitle(),
                p.getDescription(),
                mode == null ? null : mode.name(),
                p.getCreditsPerUse(),
                p.getPublisherId(),
                p.getPublisherName(),
                p.getPublisherAvatarUrl(),
                p.getShowcaseInterfaceId(),
                p.getShowcaseRunId(),
                p.getNodeIcons(),
                p.getAgentCount(),
                p.getSkillCount(),
                p.getWorkflowCount(),
                p.getInterfaceCount(),
                p.getDatasourceCount(),
                p.getAverageRating(),
                p.getReviewCount(),
                p.isCeExclusive(),
                p.getCeExclusiveFeatures(),
                p.isStudio()
        );
    }
}
