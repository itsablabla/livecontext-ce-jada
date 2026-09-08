package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The headline promise of the CE-exclusive label is that it is RE-DERIVED on
 * every update, so it can never drift from what the publication contains.
 *
 * <p>{@code CeExclusiveApplyCallsiteInvariantTest} only proves the call EXISTS in
 * the file; it cannot see ordering or scoping. These tests exercise the real
 * update path end to end, so hoisting the recompute above the snapshot write, or
 * dropping it from the update branch specifically, fails here.
 *
 * <p>Both directions matter: an app that GAINS a self-hosted-only feature must
 * become uninstallable on cloud, and one that DROPS its last such feature must
 * stop being refused. The second is the one nothing else covers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CE-exclusive - recomputed on the workflow UPDATE path")
class CeExclusiveUpdatePathTest {

    private static final String TENANT_ID = "publisher-1";
    private static final String ORG_ID = "org-1";

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private AuthClient authClient;

    private WorkflowPublicationService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository,
                mock(PublicationSnapshotVersionRepository.class),
                mock(PublicationReceiptRepository.class),
                mock(PublicationReviewRepository.class),
                orchestratorClient,
                agentClient,
                interfaceClient,
                dataSourceClient,
                mock(StorageBreakdownService.class),
                new ObjectMapper(),
                mock(SnapshotCloneService.class),
                null,
                authClient,
                new com.apimarketplace.publication.service.PublicationFileUrlResolver(new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner("test-secret-32-bytes-long-enough-for-hmac")));
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** A PRIVATE publication: no showcase run required, so the update reaches the re-snapshot. */
    private WorkflowPublicationEntity existingPublication(boolean currentlyExclusive) {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(UUID.randomUUID());
        publication.setWorkflowId(UUID.randomUUID());
        publication.setTitle("RAG Assistant");
        publication.setPublisherId(TENANT_ID);
        publication.setOwnerType(OwnerType.USER);
        publication.setOwnerId(TENANT_ID);
        publication.setStatus(PublicationStatus.ACTIVE);
        publication.setVisibility(PublicationVisibility.PRIVATE);
        publication.setDisplayMode(DisplayMode.WORKFLOW);
        publication.setCreditsPerUse(0);
        publication.setCeExclusive(currentlyExclusive);
        publication.setCeExclusiveFeatures(currentlyExclusive ? List.of("CLI_AGENT") : List.of());
        return publication;
    }

    private WorkflowPublicationEntity update(WorkflowPublicationEntity publication,
                                             Map<String, Object> newPlan) {
        when(publicationRepository.findById(publication.getId())).thenReturn(Optional.of(publication));
        when(orchestratorClient.getWorkflowForPublication(publication.getWorkflowId(), TENANT_ID, ORG_ID))
                .thenReturn(Map.of("plan", newPlan));

        return service.updatePublicationInfo(
                publication.getId(), TENANT_ID, ORG_ID,
                "RAG Assistant", "desc",
                null, null, null, 0,
                PublicationVisibility.PRIVATE, DisplayMode.WORKFLOW,
                null, false, false, Map.of(),
                null);
    }

    @Test
    @DisplayName("an update that DROPS the last CLI agent clears the label")
    void updateClearsTheLabelWhenTheFeatureIsGone() {
        // Nothing else covers this direction: the publication stays refused on
        // managed cloud forever if the update path does not recompute.
        WorkflowPublicationEntity publication = existingPublication(true);

        WorkflowPublicationEntity updated = update(publication, Map.of(
                "agents", List.of(Map.of("agentConfigId", "a-1", "provider", "anthropic"))));

        assertThat(updated.isCeExclusive()).isFalse();
        assertThat(updated.getCeExclusiveFeatures()).isEmpty();
    }

    @Test
    @DisplayName("an update that ADDS a CLI agent sets the label")
    void updateSetsTheLabelWhenTheFeatureAppears() {
        WorkflowPublicationEntity publication = existingPublication(false);

        WorkflowPublicationEntity updated = update(publication, Map.of(
                "agents", List.of(Map.of("provider", "claude-code"))));

        assertThat(updated.isCeExclusive()).isTrue();
        assertThat(updated.getCeExclusiveFeatures()).containsExactly("CLI_AGENT");
    }

    @Test
    @DisplayName("the label is derived from the NEW snapshot, not the one the row already had")
    void labelFollowsTheNewSnapshotNotTheOld() {
        // Recomputing BEFORE the snapshot write would read the previous plan and
        // keep answering "vector", which is exactly the ordering bug the
        // call-site invariant test cannot see.
        WorkflowPublicationEntity publication = existingPublication(false);
        publication.setPlanSnapshot(Map.of("tables", List.of(Map.of(
                "_snapshot_ds_mappingSpec", Map.of("e", Map.of("type", "vector"))))));
        publication.setCeExclusive(true);
        publication.setCeExclusiveFeatures(List.of("VECTOR_SEARCH"));

        WorkflowPublicationEntity updated = update(publication, Map.of(
                "agents", List.of(Map.of("provider", "codex"))));

        assertThat(updated.getCeExclusiveFeatures())
                .as("the new plan has a CLI agent and NO vector column")
                .containsExactly("CLI_AGENT");
    }
}
