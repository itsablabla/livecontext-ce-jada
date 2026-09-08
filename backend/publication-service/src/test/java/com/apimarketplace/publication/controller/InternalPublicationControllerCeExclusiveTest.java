package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseSnapshotBackfillService;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The internal summary map is what the agent-facing {@code application} tool
 * reads (search / my / get all funnel through it via {@code PublicationClient}).
 *
 * <p>If it omits the CE-exclusive flag, the tool's own field
 * ({@code ce_exclusive}) is dead code and its help - which tells the agent that
 * an ABSENT flag means "installable here" - becomes false on managed cloud: the
 * agent calls acquire and eats a PERMISSION_DENIED it was promised would not
 * happen. This pins the flag onto the projection.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalPublicationController - CE-exclusive in the summary map")
class InternalPublicationControllerCeExclusiveTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private WorkflowPublicationService publicationService;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private ShowcaseSnapshotBackfillService backfillService;

    private InternalPublicationController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        controller = new InternalPublicationController(
                publicationRepository, publicationService, agentPublicationService,
                resourcePublicationService, orchestratorClient, backfillService,
                org.mockito.Mockito.mock(com.apimarketplace.publication.service.ShowcaseFileNamespaceRepairService.class),
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    private WorkflowPublicationEntity pub(UUID id, boolean ceExclusive) {
        WorkflowPublicationEntity p = new WorkflowPublicationEntity(
                UUID.randomUUID(), "RAG App", Map.of(), "user-42");
        p.setId(id);
        p.setVisibility(WorkflowPublicationEntity.PublicationVisibility.PUBLIC);
        p.setStatus(WorkflowPublicationEntity.PublicationStatus.ACTIVE);
        p.setCeExclusive(ceExclusive);
        p.setCeExclusiveFeatures(ceExclusive ? List.of("CLI_AGENT") : List.of());
        return p;
    }

    @Test
    @DisplayName("a CE-exclusive publication carries the flag and its features")
    void ceExclusivePublicationCarriesTheFlag() {
        UUID projectId = UUID.randomUUID();
        UUID pubId = UUID.randomUUID();
        when(publicationRepository.findByProjectId(projectId))
                .thenReturn(List.of(pub(pubId, true)));

        ResponseEntity<List<Map<String, Object>>> response =
                controller.findByProjectId(projectId, null, null);

        Map<String, Object> summary = response.getBody().get(0);
        assertThat(summary)
                .containsEntry("ceExclusive", true)
                .containsEntry("ceExclusiveFeatures", List.of("CLI_AGENT"));
    }

    @Test
    @DisplayName("a VECTOR app carries the feature list WITHOUT the blocking flag")
    void vectorPublicationCarriesTheListOnly() {
        // The two fields answer different questions since 2026-09-03, so they are emitted
        // independently. Tying the list to the boolean would hide the one fact the agent needs to
        // see a plan refusal coming, while its help promises the field is there.
        UUID projectId = UUID.randomUUID();
        WorkflowPublicationEntity vectorApp = pub(UUID.randomUUID(), false);
        vectorApp.setCeExclusiveFeatures(List.of("VECTOR_SEARCH"));
        when(publicationRepository.findByProjectId(projectId)).thenReturn(List.of(vectorApp));

        ResponseEntity<List<Map<String, Object>>> response =
                controller.findByProjectId(projectId, null, null);

        assertThat(response.getBody().get(0))
                .doesNotContainKey("ceExclusive")
                .containsEntry("ceExclusiveFeatures", List.of("VECTOR_SEARCH"));
    }

    @Test
    @DisplayName("a normal publication OMITS the keys - absent is the agent's 'installable' signal")
    void normalPublicationOmitsTheKeys() {
        UUID projectId = UUID.randomUUID();
        when(publicationRepository.findByProjectId(projectId))
                .thenReturn(List.of(pub(UUID.randomUUID(), false)));

        ResponseEntity<List<Map<String, Object>>> response =
                controller.findByProjectId(projectId, null, null);

        // Emitting `false` here would bloat every item in a 50-app page for no
        // gain; the tool's contract is "absent means installable".
        assertThat(response.getBody().get(0))
                .doesNotContainKey("ceExclusive")
                .doesNotContainKey("ceExclusiveFeatures");
    }
}
