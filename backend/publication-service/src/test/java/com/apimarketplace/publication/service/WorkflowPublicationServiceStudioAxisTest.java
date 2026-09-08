package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.OwnerType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The studio axis on the two paths that write it, and specifically the three-valued contract:
 * {@code true} puts an application on the studio shelf, {@code false} takes it off, and
 * <b>absent (null) expresses no opinion and leaves the stored value alone</b>.
 *
 * <p><b>Why the null case is the one that matters.</b> The axis is a {@code Boolean}, not a
 * {@code boolean}, and every pre-existing client sends no value for it at all: the agent publish
 * path passes {@code null} by design, and so does any edit form that does not render the control.
 * Collapse the guard to {@code setStudio(Boolean.TRUE.equals(studio))} - the obvious
 * "simplification", since it type-checks and reads as equivalent - and every one of those saves
 * silently takes the application OFF the studio shelf. The publisher renames their app and it
 * vanishes from Studio, with a 200, a correct-looking edit form, and nothing in any log.
 *
 * <p>This suite exists because that mutation was applied to both write sites and the module's
 * other 1321 tests stayed green: the axis was reached only through its absent state, so no
 * assertion could tell "left alone" apart from "written false".
 *
 * <p>Each path is asserted in all three states. A guard hardcoded to skip the write passes any
 * test that only checks preservation, and one hardcoded to always write passes any test that only
 * checks that an explicit value lands.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("The studio axis: true sets, false clears, absent leaves alone")
class WorkflowPublicationServiceStudioAxisTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationSnapshotVersionRepository snapshotVersionRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private PublicationReviewRepository reviewRepository;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private AuthClient authClient;

    private WorkflowPublicationService service;

    private static final UUID PUBLICATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TENANT_ID = "tenant-001";
    private static final UUID CATEGORY_ID = UUID.fromString("a0000000-0000-4000-8000-000000000006");

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository, snapshotVersionRepository, receiptRepository, reviewRepository,
                orchestratorClient, agentClient, interfaceClient, dataSourceClient, breakdownService,
                new ObjectMapper(), snapshotCloneService, entitlementGuard, authClient,
                new com.apimarketplace.publication.service.PublicationFileUrlResolver(
                        new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner(
                                "test-secret-32-bytes-long-enough-for-hmac")));
        lenient().when(authClient.getPublisherProfile(any())).thenReturn(
                new PublisherProfileDto(TENANT_ID, "Test Publisher", "test@publisher.com", "avatar-uuid", null));
        lenient().when(orchestratorClient.getCategoryById(any())).thenReturn(Map.of(
                "slug", "marketing", "name", "Marketing", "iconSlug", "megaphone", "color", "#ec4899"));
    }

    @Nested
    @DisplayName("publishWorkflow")
    class Publish {

        @Test
        @DisplayName("puts a brand-new application on the studio shelf when asked to")
        void explicitTrueOnFirstPublish() {
            stubFirstPublish();

            assertThat(publish(true).isStudio()).isTrue();
        }

        @Test
        @DisplayName("leaves a brand-new application off the shelf when the axis is absent")
        void absentOnFirstPublishKeepsTheColumnDefault() {
            // Not an accident of the entity default: a first publish has nobody to ask, so "no
            // opinion" has to mean "not a studio app" rather than an unset column.
            stubFirstPublish();

            assertThat(publish(null).isStudio()).isFalse();
        }

        @Test
        @DisplayName("a re-share that says nothing about the axis KEEPS a studio application on the shelf")
        void absentOnRepublishPreservesTheFlag() {
            // The defect this is written for. A republish reuses the existing row, so writing
            // `Boolean.TRUE.equals(null)` here is a silent removal from Studio on an edit that had
            // nothing to do with the axis.
            WorkflowPublicationEntity existing = existingPublication(true);
            stubRepublish(existing);

            assertThat(publish(null).isStudio()).isTrue();
        }

        @Test
        @DisplayName("a re-share CAN take an application off the shelf when it says so explicitly")
        void explicitFalseOnRepublishClearsTheFlag() {
            // The other direction, so "preserve" cannot be implemented as "never write on
            // republish": an author who unticks the box must be obeyed.
            WorkflowPublicationEntity existing = existingPublication(true);
            stubRepublish(existing);

            assertThat(publish(false).isStudio()).isFalse();
        }

        private WorkflowPublicationEntity publish(Boolean studio) {
            return service.publishWorkflow(
                    WORKFLOW_ID, TENANT_ID, null, "Title", "Description",
                    null, null, CATEGORY_ID, 0,
                    PublicationVisibility.PRIVATE, null, DisplayMode.WORKFLOW, null, true, Map.of(), studio);
        }
    }

    @Nested
    @DisplayName("updatePublicationInfo")
    class Update {

        @Test
        @DisplayName("an edit that says nothing about the axis KEEPS a studio application on the shelf")
        void absentPreservesTheFlag() {
            // The same defect on the path a publisher actually uses to rename or re-describe an
            // application. A client predating the axis sends no value at all.
            WorkflowPublicationEntity existing = existingPublication(true);
            stubUpdate(existing);

            assertThat(update(null).isStudio()).isTrue();
        }

        @Test
        @DisplayName("an edit that says nothing KEEPS an ordinary application off the shelf")
        void absentDoesNotPromote() {
            WorkflowPublicationEntity existing = existingPublication(false);
            stubUpdate(existing);

            assertThat(update(null).isStudio()).isFalse();
        }

        @Test
        @DisplayName("ticking the box puts an existing application on the shelf")
        void explicitTruePromotes() {
            WorkflowPublicationEntity existing = existingPublication(false);
            stubUpdate(existing);

            assertThat(update(true).isStudio()).isTrue();
        }

        @Test
        @DisplayName("unticking the box takes it back off")
        void explicitFalseDemotes() {
            WorkflowPublicationEntity existing = existingPublication(true);
            stubUpdate(existing);

            assertThat(update(false).isStudio()).isFalse();
        }

        private WorkflowPublicationEntity update(Boolean studio) {
            return service.updatePublicationInfo(
                    PUBLICATION_ID, TENANT_ID, null, "New title", "New description",
                    null, null, CATEGORY_ID, 0,
                    PublicationVisibility.PRIVATE, DisplayMode.WORKFLOW, null, true, true, Map.of(), studio);
        }
    }

    // ---- helpers ----

    private WorkflowPublicationEntity existingPublication(boolean studio) {
        WorkflowPublicationEntity p = new WorkflowPublicationEntity();
        p.setId(PUBLICATION_ID);
        p.setWorkflowId(WORKFLOW_ID);
        p.setPublisherId(TENANT_ID);
        p.setOwnerType(OwnerType.USER);
        p.setOwnerId(TENANT_ID);
        p.setStatus(PublicationStatus.ACTIVE);
        p.setVisibility(PublicationVisibility.PRIVATE);
        p.setDisplayMode(DisplayMode.WORKFLOW);
        p.setTitle("Original title");
        p.setDescription("Original description");
        p.setCreditsPerUse(0);
        p.setCategoryId(CATEGORY_ID);
        p.setCategorySlug("marketing");
        p.setStudio(studio);
        return p;
    }

    private Map<String, Object> workflowData() {
        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", TENANT_ID);
        workflowData.put("workflowType", "WORKFLOW");
        workflowData.put("plan", new HashMap<>(Map.of(
                "triggers", List.of(), "interfaces", List.of(), "cores", List.of(), "edges", List.of())));
        return workflowData;
    }

    private void stubPublishCommon() {
        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, null)).thenReturn(workflowData());
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> {
                    WorkflowPublicationEntity p = invocation.getArgument(0);
                    if (p.getId() == null) p.setId(PUBLICATION_ID);
                    return p;
                });
        when(snapshotVersionRepository.getMaxVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(orchestratorClient.getLatestPlanVersion(WORKFLOW_ID, TENANT_ID)).thenReturn(1);
        lenient().when(orchestratorClient.createApplicationWorkflow(any(), eq(TENANT_ID)))
                .thenReturn(Map.of("id", UUID.randomUUID().toString()));
    }

    private void stubFirstPublish() {
        stubPublishCommon();
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.empty());
    }

    private void stubRepublish(WorkflowPublicationEntity existing) {
        stubPublishCommon();
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.of(existing));
    }

    private void stubUpdate(WorkflowPublicationEntity publication) {
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, null))
                .thenReturn(Map.of("plan", Map.of()));
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }
}
