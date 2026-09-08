package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.OwnerType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.resource.ResourcePublicationStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The INLINE edition gates, i.e. the ones that do not go through
 * {@link PublicationAcquisitionHelper}.
 *
 * <p>Both services keep a legacy branch that runs when the helper bean is
 * absent, so the guard is applied at the top of the acquire method as well.
 * Without these tests the two call sites could be deleted and only the helper's
 * own test would still pass, leaving cloud able to install an app it cannot run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CE-exclusive inline acquire gates")
class CeExclusiveInlineGuardTest {

    private static final String TENANT_ID = "7";
    private static final String ORG_ID = "org-1";

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private AuthClient authClient;

    private static AppEditionProvider edition(String value) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", value);
        return new AppEditionProvider(env);
    }

    private static WorkflowPublicationEntity ceExclusive(PublicationType type) {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(UUID.randomUUID());
        publication.setPublicationType(type);
        publication.setPublisherId("someone-else");
        publication.setOwnerType(OwnerType.USER);
        publication.setOwnerId("someone-else");
        publication.setStatus(PublicationStatus.ACTIVE);
        publication.setVisibility(PublicationVisibility.PUBLIC);
        publication.setResourceId("42");
        // CLI_AGENT, deliberately: since 2026-09-03 that is the only feature managed cloud cannot
        // run at any price. A VECTOR_SEARCH publication is installable here from a plan, so using
        // it as the fixture would test the opposite of what this class is named for.
        publication.setCeExclusive(true);
        publication.setCeExclusiveFeatures(List.of("CLI_AGENT"));
        return publication;
    }

    // ------------------------------------------------------------------
    // WorkflowPublicationService.acquirePublication
    // ------------------------------------------------------------------

    private WorkflowPublicationService workflowService(String editionValue) {
        WorkflowPublicationService service = new WorkflowPublicationService(
                publicationRepository,
                mock(PublicationSnapshotVersionRepository.class),
                receiptRepository,
                mock(PublicationReviewRepository.class),
                orchestratorClient,
                mock(AgentClient.class),
                mock(InterfaceClient.class),
                mock(DataSourceClient.class),
                mock(StorageBreakdownService.class),
                new ObjectMapper(),
                mock(SnapshotCloneService.class),
                null,
                authClient,
                new com.apimarketplace.publication.service.PublicationFileUrlResolver(new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner("test-secret-32-bytes-long-enough-for-hmac")));
        ReflectionTestUtils.setField(service, "ceExclusiveGuard",
                new CeExclusiveAcquisitionGuard(edition(editionValue), null));
        return service;
    }

    @Test
    @DisplayName("workflow acquire on managed cloud is refused before any clone work starts")
    void workflowAcquireRefusedOnCloud() {
        WorkflowPublicationEntity publication = ceExclusive(PublicationType.WORKFLOW);
        when(publicationRepository.findById(publication.getId())).thenReturn(Optional.of(publication));

        WorkflowPublicationService service = workflowService("cloud");

        assertThatThrownBy(() -> service.acquirePublication(publication.getId(), TENANT_ID, ORG_ID))
                .isInstanceOf(CeExclusivePublicationException.class);
        // Nothing downstream ran: no ownership lookup, no clone, no receipt.
        verify(orchestratorClient, never()).existsBySourcePublication(any(), any(), any());
        verify(receiptRepository, never()).save(any());
    }

    @Test
    @DisplayName("workflow acquire on a self-hosted install passes the gate and proceeds")
    void workflowAcquireProceedsOnSelfHosted() {
        WorkflowPublicationEntity publication = ceExclusive(PublicationType.WORKFLOW);
        when(publicationRepository.findById(publication.getId())).thenReturn(Optional.of(publication));

        WorkflowPublicationService service = workflowService("ce");

        // It proceeds PAST the gate; what it fails on afterwards is irrelevant
        // here, only that the refusal is not the CE-exclusive one.
        assertThatThrownBy(() -> service.acquirePublication(publication.getId(), TENANT_ID, ORG_ID))
                .isNotInstanceOf(CeExclusivePublicationException.class);
    }

    // ------------------------------------------------------------------
    // ResourcePublicationService.acquireResource
    // ------------------------------------------------------------------

    private ResourcePublicationService resourceService(String editionValue) {
        ResourcePublicationStrategy strategy = mock(ResourcePublicationStrategy.class);
        when(strategy.getPublicationType()).thenReturn(PublicationType.TABLE);
        ResourcePublicationService service = new ResourcePublicationService(
                publicationRepository,
                receiptRepository,
                orchestratorClient,
                null,
                new ObjectMapper(),
                mock(LandingInterfaceSnapshotter.class),
                mock(WorkflowPublicationService.class),
                List.of(strategy),
                authClient);
        ReflectionTestUtils.setField(service, "ceExclusiveGuard",
                new CeExclusiveAcquisitionGuard(edition(editionValue), null));
        return service;
    }

    @Test
    @DisplayName("resource acquire on managed cloud is refused and writes no receipt")
    void resourceAcquireRefusedOnCloud() {
        WorkflowPublicationEntity publication = ceExclusive(PublicationType.TABLE);
        when(publicationRepository.findById(publication.getId())).thenReturn(Optional.of(publication));

        ResourcePublicationService service = resourceService("cloud");

        assertThatThrownBy(() -> service.acquireResource(publication.getId(), TENANT_ID, ORG_ID))
                .isInstanceOf(CeExclusivePublicationException.class);
        verify(receiptRepository, never()).save(any());
    }

    @Test
    @DisplayName("resource acquire on a self-hosted install is not blocked by the edition gate")
    void resourceAcquireNotBlockedOnSelfHosted() {
        WorkflowPublicationEntity publication = ceExclusive(PublicationType.TABLE);
        when(publicationRepository.findById(publication.getId())).thenReturn(Optional.of(publication));
        when(receiptRepository.existsByOrganizationIdAndPublicationId(ORG_ID, publication.getId()))
                .thenReturn(false);

        ResourcePublicationService service = resourceService("ce");

        assertThatThrownBy(() -> service.acquireResource(publication.getId(), TENANT_ID, ORG_ID))
                .isNotInstanceOf(CeExclusivePublicationException.class);
    }

    // ------------------------------------------------------------------
    // AgentPublicationService.acquireAgentPublication
    // ------------------------------------------------------------------

    private AgentPublicationService agentService(String editionValue) {
        AgentPublicationService service = new AgentPublicationService(
                publicationRepository,
                receiptRepository,
                mock(com.apimarketplace.agent.client.AgentClient.class),
                mock(com.apimarketplace.interfaces.client.InterfaceClient.class),
                mock(DataSourceClient.class),
                orchestratorClient,
                mock(StorageBreakdownService.class),
                mock(SnapshotCloneService.class),
                new ObjectMapper(),
                mock(WorkflowPublicationService.class),
                null,
                mock(com.apimarketplace.publication.service.resource.DataSourceFileCloneService.class),
                mock(LandingInterfaceSnapshotter.class),
                authClient);
        ReflectionTestUtils.setField(service, "ceExclusiveGuard",
                new CeExclusiveAcquisitionGuard(edition(editionValue), null));
        return service;
    }

    @Test
    @DisplayName("agent acquire on managed cloud is refused and writes no receipt")
    void agentAcquireRefusedOnCloud() {
        WorkflowPublicationEntity publication = ceExclusive(PublicationType.AGENT);
        when(publicationRepository.findById(publication.getId())).thenReturn(Optional.of(publication));

        AgentPublicationService service = agentService("cloud");

        assertThatThrownBy(() -> service.acquireAgentPublication(publication.getId(), TENANT_ID, ORG_ID))
                .isInstanceOf(CeExclusivePublicationException.class);
        verify(receiptRepository, never()).save(any());
    }

    @Test
    @DisplayName("agent acquire on a self-hosted install is not blocked by the edition gate")
    void agentAcquireNotBlockedOnSelfHosted() {
        WorkflowPublicationEntity publication = ceExclusive(PublicationType.AGENT);
        when(publicationRepository.findById(publication.getId())).thenReturn(Optional.of(publication));

        AgentPublicationService service = agentService("ce");

        assertThatThrownBy(() -> service.acquireAgentPublication(publication.getId(), TENANT_ID, ORG_ID))
                .isNotInstanceOf(CeExclusivePublicationException.class);
    }

    @Test
    @DisplayName("a NON-exclusive publication is never refused by the gate, on either service")
    void nonExclusiveNeverRefused() {
        WorkflowPublicationEntity publication = ceExclusive(PublicationType.WORKFLOW);
        publication.setCeExclusive(false);
        publication.setCeExclusiveFeatures(List.of());
        when(publicationRepository.findById(publication.getId())).thenReturn(Optional.of(publication));

        WorkflowPublicationService service = workflowService("cloud");

        assertThatCode(() -> {
            try {
                service.acquirePublication(publication.getId(), TENANT_ID, ORG_ID);
            } catch (CeExclusivePublicationException e) {
                throw e; // only this one is a failure
            } catch (RuntimeException ignored) {
                // any other downstream failure is out of scope here
            }
        }).doesNotThrowAnyException();
    }
}
