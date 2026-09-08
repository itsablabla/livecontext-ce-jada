package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.ApplicationTemplateResetService;
import com.apimarketplace.publication.service.LandingInterfaceSnapshotter;
import com.apimarketplace.publication.service.OnboardingCategoryMapper;
import com.apimarketplace.publication.service.PublicationListQueryService;
import com.apimarketplace.publication.service.PublicationReviewService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseFileRefRewriter;
import com.apimarketplace.publication.service.ShowcaseSnapshotBackfillService;
import com.apimarketplace.publication.service.ShowcaseSnapshotReader;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The studio axis on the three response maps that describe a publication to somebody.
 *
 * <p>Each of these is a single {@code put("studio", …)} line, and each one failing is invisible:
 * the request succeeds, the body is well-formed, and the value simply reads {@code false}
 * everywhere. There is no error to notice and no log line to find. What a reader sees instead is a
 * Studio shelf that is permanently empty, or an edit form whose checkbox is unticked on an
 * application that is on the shelf - and since that form posts what it displays, the NEXT
 * unrelated save (a retitle, a new description) writes the unticked value back and really does
 * remove the application. A display bug becomes a data loss one save later.
 *
 * <p>All three lines were deleted at once during a mutation run and 1611 tests stayed green, so
 * this is the suite that makes them load-bearing. Each surface is asserted in BOTH directions: a
 * mapper hardcoded to {@code false} passes any test that only looks at an ordinary publication,
 * and one hardcoded to {@code true} passes any test that only looks at a studio one.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("The studio axis survives every response map that describes a publication")
class StudioAxisResponseMapTest {

    private static WorkflowPublicationEntity publication(boolean studio) {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity(
                UUID.randomUUID(), "Clip Studio", Map.of(), "publisher-1");
        pub.setId(UUID.randomUUID());
        pub.setStatus(PublicationStatus.ACTIVE);
        pub.setVisibility(PublicationVisibility.PUBLIC);
        pub.setStudio(studio);
        return pub;
    }

    @Nested
    @DisplayName("the by-id detail response (what the publish/edit form reads back)")
    class DetailResponse {

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

        private WorkflowPublicationController controller() {
            return new WorkflowPublicationController(publicationService, agentPublicationService,
                    listQueryService, reviewService, resourcePublicationService, orchestratorClient,
                    landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                    onboardingCategoryMapper, orgAccessGuard, mock(ApplicationTemplateResetService.class));
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> detailBodyFor(boolean studio) {
            WorkflowPublicationEntity pub = publication(studio);
            when(publicationService.getPublicationById(pub.getId())).thenReturn(Optional.of(pub));
            ResponseEntity<?> response = controller().getPublicationByIdPublic(
                    pub.getId().toString(), null, null);
            return (Map<String, Object>) response.getBody();
        }

        @Test
        @DisplayName("reports a studio application as one, so the form re-renders it ticked")
        void reportsTrue() {
            assertThat(detailBodyFor(true)).containsEntry("studio", true);
        }

        @Test
        @DisplayName("reports an ordinary application as not one")
        void reportsFalse() {
            assertThat(detailBodyFor(false)).containsEntry("studio", false);
        }

        @Test
        @DisplayName("states the axis explicitly rather than omitting it")
        void isNeverAbsent() {
            // This payload feeds a form, not an agent: an absent key would render the control
            // unticked with no way to tell "not a studio app" from "this build knows nothing about
            // the axis" - and the form would then post that guess back as the truth.
            assertThat(detailBodyFor(true)).containsKey("studio");
        }
    }

    @Nested
    @DisplayName("the internal summary map (what a self-hosted install re-fetches its favourites through)")
    class SummaryMap {

        @Mock private WorkflowPublicationRepository publicationRepository;
        @Mock private WorkflowPublicationService publicationService;
        @Mock private AgentPublicationService agentPublicationService;
        @Mock private ResourcePublicationService resourcePublicationService;
        @Mock private OrchestratorInternalClient orchestratorClient;
        @Mock private ShowcaseSnapshotBackfillService backfillService;

        @SuppressWarnings("unchecked")
        private Map<String, Object> summaryFor(boolean studio) {
            UUID projectId = UUID.randomUUID();
            when(publicationRepository.findByProjectId(projectId))
                    .thenReturn(List.of(publication(studio)));
            InternalPublicationController controller = new InternalPublicationController(
                    publicationRepository, publicationService, agentPublicationService,
                    resourcePublicationService, orchestratorClient, backfillService,
                    mock(com.apimarketplace.publication.service.ShowcaseFileNamespaceRepairService.class),
                    mock(org.springframework.beans.factory.ObjectProvider.class));
            return controller.findByProjectId(projectId, null, null).getBody().get(0);
        }

        @Test
        @DisplayName("carries the axis, or a cloud-acquired studio app vanishes from the install's studio row")
        void carriesTrue() {
            assertThat(summaryFor(true)).containsEntry("studio", true);
        }

        @Test
        @DisplayName("carries it for an ordinary publication too, rather than only when set")
        void carriesFalse() {
            // Unlike ceExclusive on this same map, the axis is always emitted: the enrichment path
            // OVERWRITES a locally-known row with what comes back, so an omitted key would silently
            // demote an application the install already had on its studio shelf.
            assertThat(summaryFor(false)).containsEntry("studio", false);
        }
    }

    /**
     * The CE snapshot payload, which DESCRIBES the publication rather than driving the acquire.
     *
     * <p>Nothing on the acquiring side reads the axis yet: the acquire path takes the snapshot
     * apart for the fields it clones and creates no local publication row. These two tests pin the
     * payload's honesty - it says what the publication IS - not a shelf placement that happens
     * downstream. Written down because a test that looks like it proves the second is worse than
     * no test: it would let a reader believe the acquiring install files the app on its studio
     * shelf, which it does not.
     */
    @Nested
    @DisplayName("the CE snapshot download (what an acquiring install is told)")
    class SnapshotResponse {

        @Mock private WorkflowPublicationRepository publicationRepository;
        @Mock private PublicationReceiptRepository receiptRepository;
        @Mock private CreditConsumptionClient creditClient;
        @Mock private AuthClient authClient;

        private Map<String, Object> snapshotFor(boolean studio) {
            WorkflowPublicationEntity pub = publication(studio);
            pub.setCreditsPerUse(0);
            pub.setPlanSnapshot(Map.of("triggers", "raw"));
            when(publicationRepository.findById(pub.getId())).thenReturn(Optional.of(pub));
            CeDownloadController controller = new CeDownloadController(
                    publicationRepository, receiptRepository, creditClient, authClient);
            return controller.getSnapshot(pub.getId()).getBody();
        }

        @Test
        @DisplayName("describes a studio application as one")
        void shipsTrue() {
            assertThat(snapshotFor(true)).containsEntry("studio", true);
        }

        @Test
        @DisplayName("ships it as false for an ordinary application")
        void shipsFalse() {
            assertThat(snapshotFor(false)).containsEntry("studio", false);
        }
    }
}
