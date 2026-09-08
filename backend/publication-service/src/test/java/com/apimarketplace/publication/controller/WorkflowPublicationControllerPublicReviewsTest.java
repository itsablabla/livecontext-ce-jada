package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.PublicationReviewEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.ApplicationTemplateResetService;
import com.apimarketplace.publication.service.LandingInterfaceSnapshotter;
import com.apimarketplace.publication.service.OnboardingCategoryMapper;
import com.apimarketplace.publication.service.PublicationListQueryService;
import com.apimarketplace.publication.service.PublicationReviewService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseFileRefRewriter;
import com.apimarketplace.publication.service.ShowcaseSnapshotReader;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@code GET /api/publications/by-id/{id}/reviews}, the anonymous read
 * that lets the crawlable marketplace page show a listing's reviews.
 *
 * <p>Two of these are the reason the alias exists rather than the authenticated
 * handler simply being allowlisted: that handler applies NO visibility check and
 * returns the reviewer's internal user id. Both are harmless behind the JWT
 * filter and neither is harmless on a public route.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkflowPublicationController: public reviews read")
class WorkflowPublicationControllerPublicReviewsTest {

    @Mock private WorkflowPublicationService publicationService;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private PublicationListQueryService listQueryService;
    @Mock private PublicationReviewService reviewService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    @Mock private ShowcaseSnapshotReader showcaseSnapshotReader;
    @Mock private ShowcaseFileRefRewriter fileRefRewriter;
    @Mock private OnboardingCategoryMapper onboardingCategoryMapper;
    @Mock private OrgAccessGuard orgAccessGuard;

    private WorkflowPublicationController controller;

    private static final UUID PUB_ID = UUID.fromString("0189d3c2-7f4a-4c11-9b3e-2a5d6e7f8a9b");
    private static final UUID REVIEW_ID = UUID.fromString("0189d3c2-7f4a-4c11-9b3e-2a5d6e7f8aaa");

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(publicationService, agentPublicationService,
                listQueryService, reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                onboardingCategoryMapper, orgAccessGuard,
                org.mockito.Mockito.mock(ApplicationTemplateResetService.class));
    }

    @Test
    @DisplayName("Public publication -> 200 with its reviews")
    void publicPublicationExposesItsReviews() {
        givenPublication(anonymouslyReadablePublication());
        givenOneReview();

        ResponseEntity<?> response = controller.getReviewsPublic(PUB_ID.toString(), 0, 20, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reviewsOf(response)).hasSize(1);
        assertThat(reviewsOf(response).get(0).get("comment")).isEqualTo("Saved me a week.");
    }

    @Test
    @DisplayName("The internal reviewer id never reaches an anonymous reader")
    void reviewerIdIsScrubbed() {
        givenPublication(anonymouslyReadablePublication());
        givenOneReview();

        ResponseEntity<?> response = controller.getReviewsPublic(PUB_ID.toString(), 0, 20, false);

        // The app needs it to recognise "my review"; a stranger reading a public
        // page has no business receiving another user's internal id.
        assertThat(reviewsOf(response).get(0)).doesNotContainKey("reviewerId");
        // What IS public stays: these are already displayed to every visitor.
        assertThat(reviewsOf(response).get(0)).containsKey("reviewerName");
        assertThat(reviewsOf(response).get(0)).containsKey("rating");
    }

    @Test
    @DisplayName("PRIVATE publication -> 404, and its reviews are never read")
    void privatePublicationLeaksNothing() {
        WorkflowPublicationEntity pub = anonymouslyReadablePublication();
        pub.setVisibility(WorkflowPublicationEntity.PublicationVisibility.PRIVATE);
        givenPublication(pub);

        ResponseEntity<?> response = controller.getReviewsPublic(PUB_ID.toString(), 0, 20, false);

        // The authenticated handler applies no visibility check at all, so
        // without this gate anyone guessing a UUID could read the reviews of a
        // private publication through a public route.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(reviewService, never()).getReviews(any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("PENDING_REVIEW publication -> 404: not yet public is not public")
    void preModerationPublicationLeaksNothing() {
        WorkflowPublicationEntity pub = anonymouslyReadablePublication();
        pub.setStatus(WorkflowPublicationEntity.PublicationStatus.PENDING_REVIEW);
        givenPublication(pub);

        ResponseEntity<?> response = controller.getReviewsPublic(PUB_ID.toString(), 0, 20, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(reviewService, never()).getReviews(any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("UNLISTED publication -> 200: reachable by direct link, like its detail page")
    void unlistedPublicationIsReadable() {
        WorkflowPublicationEntity pub = anonymouslyReadablePublication();
        pub.setVisibility(WorkflowPublicationEntity.PublicationVisibility.UNLISTED);
        givenPublication(pub);
        givenOneReview();

        // Same predicate as the detail endpoint, so the reviews of a page a
        // visitor can open are the reviews they can read.
        assertThat(controller.getReviewsPublic(PUB_ID.toString(), 0, 20, false).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Unknown publication -> 404, indistinguishable from a private one")
    void unknownPublicationReturnsNotFound() {
        when(publicationService.getPublicationById(PUB_ID)).thenReturn(Optional.empty());

        assertThat(controller.getReviewsPublic(PUB_ID.toString(), 0, 20, false).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("A path that is not a UUID -> 404, not a 500")
    void malformedIdIsRejectedCleanly() {
        ResponseEntity<?> response = controller.getReviewsPublic("../../secrets", 0, 20, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(publicationService, never()).getPublicationById(any());
    }

    // ── helpers ──

    private void givenPublication(WorkflowPublicationEntity pub) {
        when(publicationService.getPublicationById(PUB_ID)).thenReturn(Optional.of(pub));
    }

    private void givenOneReview() {
        PublicationReviewEntity review = new PublicationReviewEntity();
        review.setId(REVIEW_ID);
        review.setPublicationId(PUB_ID);
        review.setReviewerId("42");
        review.setReviewerName("John Doe");
        review.setRating((short) 5);
        review.setComment("Saved me a week.");
        review.setCreatedAt(Instant.parse("2026-07-01T10:00:00Z"));

        Page<PublicationReviewEntity> page = new PageImpl<>(List.of(review));
        when(reviewService.getReviews(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(page);
        when(reviewService.getReplyCountsBatch(any())).thenReturn(Map.of());
        when(reviewService.resolveAuthorIdentities(any())).thenReturn(Map.of());
    }

    private static WorkflowPublicationEntity anonymouslyReadablePublication() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUB_ID);
        pub.setTitle("Invoice Bot");
        pub.setPublicSlug("invoice-bot");
        pub.setStatus(WorkflowPublicationEntity.PublicationStatus.ACTIVE);
        pub.setVisibility(WorkflowPublicationEntity.PublicationVisibility.PUBLIC);
        pub.setPublicationType(WorkflowPublicationEntity.PublicationType.WORKFLOW);
        return pub;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> reviewsOf(ResponseEntity<?> response) {
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        return (List<Map<String, Object>>) body.get("reviews");
    }
}
