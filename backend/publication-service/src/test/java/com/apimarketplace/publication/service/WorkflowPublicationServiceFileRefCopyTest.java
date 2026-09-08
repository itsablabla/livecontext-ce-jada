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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the FileRef copy logic in {@code walkAndCopyFileRefs} /
 * {@code copyFileRefsInRunState}, exercised through the {@code publishWorkflow}
 * entry point. Covers cross-tenant copy, same-tenant copy, idempotency,
 * graceful degradation, and sourceTenantId omission.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("walkAndCopyFileRefs: showcase snapshot file copy")
class WorkflowPublicationServiceFileRefCopyTest {

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
    private static final UUID INTERFACE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String PUBLISHER_TENANT = "8";
    /** Same secret the resolver handed to the service under test uses, so a URL signed here verifies there. */
    private static final com.apimarketplace.common.storage.signing.ShowcaseUrlSigner SIGNER =
            new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner("test-secret-32-bytes-long-enough-for-hmac");

    /** An absolute public link exactly as a core:public_link node mints it, signed for real. */
    private static String publicLink(String key) {
        return "https://livecontext.ai/api/files/proxy-signed?key="
                + java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8)
                + "&exp=1787778662&disposition=inline&sig=" + SIGNER.sign(key, 1787778662L, "inline");
    }

    private static final String FILE_OWNER_TENANT = "1";

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository,
                snapshotVersionRepository,
                receiptRepository,
                reviewRepository,
                orchestratorClient,
                agentClient,
                interfaceClient,
                dataSourceClient,
                breakdownService,
                new ObjectMapper(),
                snapshotCloneService,
                entitlementGuard,
                authClient,
                new com.apimarketplace.publication.service.PublicationFileUrlResolver(new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner("test-secret-32-bytes-long-enough-for-hmac")));
        // Server-side publisher identity snapshot - every (re)publish path
        // calls AuthClient.getPublisherProfile. Lenient so non-publish tests
        // don't trip strict-stubbing.
        lenient().when(authClient.getPublisherProfile(any()))
                .thenReturn(new PublisherProfileDto(PUBLISHER_TENANT, "Test Publisher", "test@publisher.com", "test-avatar-uuid", null));
    }

    // ========================================================================
    // Cross-tenant copy (the primary bug fix)
    // ========================================================================

    @Test
    @DisplayName("cross-tenant FileRef in runState is copied to _publications/ namespace")
    void crossTenantFileRefInRunStateIsCopied() {
        String foreignPath = FILE_OWNER_TENANT + "/general/catalog-binary/image.jpg";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-abc/image.jpg";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(foreignPath);

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());

        Map<String, Object> req = captor.getValue();
        assertThat(req).containsEntry("sourcePath", foreignPath);
        assertThat(req).containsEntry("tenantId", "_publications");
        assertThat(req).doesNotContainKey("sourceTenantId");
    }

    @Test
    @DisplayName("cross-tenant FileRef path is rewritten to new _publications/ path in snapshot")
    void crossTenantFileRefPathIsRewrittenInSnapshot() {
        String foreignPath = FILE_OWNER_TENANT + "/general/catalog-binary/image.jpg";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-abc/image.jpg";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(foreignPath);

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        WorkflowPublicationEntity pub = service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        // The snapshot stored on the entity should have the rewritten path
        Map<String, Object> stored = pub.getShowcaseSnapshot();
        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) stored.get("runState");
        @SuppressWarnings("unchecked")
        Map<String, Object> fileRef = (Map<String, Object>) runState.get("profilePic");
        assertThat(fileRef.get("path")).isEqualTo(newPath);
    }

    // ========================================================================
    // Opaque `id` rewrite (the opaque-URL cutover bug fix)
    //
    // The opaque by-id file URL is built from the FileRef's `id`, not its
    // `path`. The re-uploaded file is a NEW storage row in the _publications
    // tenant, so the snapshot MUST adopt the new id; a FileRef left with the
    // SOURCE tenant's id renders 403/404 cross-tenant for the authenticated
    // snapshot preview.
    // ========================================================================

    @Test
    @DisplayName("FileRef opaque id is rewritten to the new storage-row id returned by copyFile")
    void crossTenantFileRefIdIsRewrittenToNewStorageRow() {
        String foreignPath = FILE_OWNER_TENANT + "/general/catalog-binary/image.jpg";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-abc/image.jpg";
        String newId = "9aaaaaaa-0000-0000-0000-000000000001";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(foreignPath);
        // Stamp the source storage-row id on the incoming FileRef - it MUST be replaced.
        @SuppressWarnings("unchecked")
        Map<String, Object> incomingRef =
                (Map<String, Object>) ((Map<String, Object>) snapshot.get("runState")).get("profilePic");
        incomingRef.put("id", "1bbbbbbb-0000-0000-0000-000000000099");

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath, "newId", newId));

        WorkflowPublicationEntity pub = service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) pub.getShowcaseSnapshot().get("runState");
        @SuppressWarnings("unchecked")
        Map<String, Object> fileRef = (Map<String, Object>) runState.get("profilePic");
        assertThat(fileRef.get("path")).isEqualTo(newPath);
        assertThat(fileRef.get("id")).isEqualTo(newId);
    }

    @Test
    @DisplayName("stale source id is dropped when copyFile returns no newId")
    void staleSourceIdIsDroppedWhenCopyReturnsNoNewId() {
        String foreignPath = FILE_OWNER_TENANT + "/general/catalog-binary/image.jpg";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-abc/image.jpg";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(foreignPath);
        @SuppressWarnings("unchecked")
        Map<String, Object> incomingRef =
                (Map<String, Object>) ((Map<String, Object>) snapshot.get("runState")).get("profilePic");
        incomingRef.put("id", "1bbbbbbb-0000-0000-0000-000000000099");

        stubPublishWorkflow(snapshot);
        // Legacy orchestrator response: newPath only, no newId.
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        WorkflowPublicationEntity pub = service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) pub.getShowcaseSnapshot().get("runState");
        @SuppressWarnings("unchecked")
        Map<String, Object> fileRef = (Map<String, Object>) runState.get("profilePic");
        assertThat(fileRef.get("path")).isEqualTo(newPath);
        // The stale source id would 403/404 cross-tenant - it must be gone, not kept.
        assertThat(fileRef).doesNotContainKey("id");
    }

    // ========================================================================
    // Same-tenant copy (regression check)
    // ========================================================================

    @Test
    @DisplayName("same-tenant FileRef is still copied (no regression)")
    void sameTenantFileRefIsCopied() {
        String sameTenantPath = PUBLISHER_TENANT + "/workflow/run/photo.png";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-xyz/photo.png";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(sameTenantPath);

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        verify(orchestratorClient, atLeastOnce()).copyFile(any(), any());
    }

    // ========================================================================
    // Already-copied FileRef skipped (idempotency)
    // ========================================================================

    @Test
    @DisplayName("FileRef already in _publications/ namespace is not re-copied")
    void alreadyCopiedFileRefIsSkipped() {
        String alreadyCopiedPath = "_publications/" + PUBLICATION_ID + "/snapshot/file.jpg";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(alreadyCopiedPath);

        stubPublishWorkflow(snapshot);

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    // ========================================================================
    // Copy failure graceful degradation
    // ========================================================================

    @Test
    @DisplayName("copy failure preserves original path and does not abort publish")
    void copyFailurePreservesOriginalPath() {
        String originalPath = FILE_OWNER_TENANT + "/general/image.jpg";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(originalPath);

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenThrow(new RuntimeException("S3 timeout"));

        // Should not throw - publish completes despite file copy failure
        WorkflowPublicationEntity pub = service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        // Original path preserved (not nulled, not replaced)
        Map<String, Object> stored = pub.getShowcaseSnapshot();
        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) stored.get("runState");
        @SuppressWarnings("unchecked")
        Map<String, Object> fileRef = (Map<String, Object>) runState.get("profilePic");
        assertThat(fileRef.get("path")).isEqualTo(originalPath);
    }

    // ========================================================================
    // sourceTenantId omission
    // ========================================================================

    @Test
    @DisplayName("copy request does not include sourceTenantId (inferred server-side)")
    void copyRequestOmitsSourceTenantId() {
        String path = FILE_OWNER_TENANT + "/general/photo.png";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(path);

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", "_publications/x/photo.png"));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());

        for (Map<String, Object> req : captor.getAllValues()) {
            assertThat(req).doesNotContainKey("sourceTenantId");
        }
    }

    // ========================================================================
    // interfaceRenders subtree walking
    // ========================================================================

    @Test
    @DisplayName("FileRef inside interfaceRenders items[].data is copied")
    void fileRefInInterfaceRenderItemsIsCopied() {
        String filePath = FILE_OWNER_TENANT + "/general/render-img.png";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-def/render-img.png";

        Map<String, Object> fileRef = new HashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", filePath);
        fileRef.put("name", "render-img.png");
        fileRef.put("mimeType", "image/png");

        Map<String, Object> data = new HashMap<>();
        data.put("image", fileRef);
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);

        Map<String, Object> epochRender = new HashMap<>();
        epochRender.put("items", List.of(item));
        epochRender.put("htmlTemplate", "<img src='{{image}}'>");

        Map<String, Object> byEpoch = new HashMap<>();
        byEpoch.put("0", epochRender);

        Map<String, Object> ifaceEntry = new HashMap<>();
        ifaceEntry.put("byEpoch", byEpoch);

        Map<String, Object> snapshot = new HashMap<>();
        // Stated by the orchestrator, which validated the caller's scope against it. It is
        // what makes a cross-org copy legitimate instead of a path the publisher just named.
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", Map.of("status", "COMPLETED"));
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), ifaceEntry));

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("sourcePath", filePath);

        // The item's data map is REPLACED by the rewritten copy, not mutated in place (the
        // pass must not write through a subtree it does not own), so read it back from the map
        // the fixture handed to the service.
        assertThat(renderItemPath(item)).isEqualTo(newPath);
    }

    @Test
    @DisplayName("FileRef inside interfaceRenders defaultRender items[].data is copied")
    void fileRefInDefaultRenderItemsIsCopied() {
        String filePath = FILE_OWNER_TENANT + "/general/default-render-img.png";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-default/default-render-img.png";

        Map<String, Object> fileRef = new HashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", filePath);
        fileRef.put("name", "default-render-img.png");
        fileRef.put("mimeType", "image/png");

        Map<String, Object> data = new HashMap<>();
        data.put("image", fileRef);
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);

        Map<String, Object> defaultRender = new HashMap<>();
        defaultRender.put("items", List.of(item));
        defaultRender.put("htmlTemplate", "<img src='{{image}}'>");

        Map<String, Object> ifaceEntry = new HashMap<>();
        ifaceEntry.put("defaultRender", defaultRender);

        Map<String, Object> snapshot = new HashMap<>();
        // Stated by the orchestrator, which validated the caller's scope against it. It is
        // what makes a cross-org copy legitimate instead of a path the publisher just named.
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", Map.of("status", "COMPLETED"));
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), ifaceEntry));

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("sourcePath", filePath);
        assertThat(renderItemPath(item)).isEqualTo(newPath);
    }

    @Test
    @DisplayName("AI screening replacement image is copied to _publications namespace and stored in snapshot")
    void aiReplacementImageIsCopiedAndStoredInSnapshot() {
        String externalUrl = "https://images.example.com/hotel.jpg";
        String generatedPath = PUBLISHER_TENANT + "/ai-generated/replacement.png";
        String publicationPath = "_publications/" + PUBLICATION_ID + "/snapshot/ai-replace/replacement.png";
        Map<String, Object> snapshot = new HashMap<>();
        // Stated by the orchestrator, which validated the caller's scope against it. It is
        // what makes a cross-org copy legitimate instead of a path the publisher just named.
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", Map.of("status", "COMPLETED"));

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", publicationPath));

        WorkflowPublicationEntity pub = service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, true,
                Map.of(externalUrl, generatedPath), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient).copyFile(captor.capture(), any());
        assertThat(captor.getValue())
                .containsEntry("sourcePath", generatedPath)
                .containsEntry("tenantId", "_publications")
                .containsEntry("workflowId", publishedPublicationId())
                .containsEntry("runId", "snapshot")
                .containsEntry("fileName", "replacement.png")
                .containsEntry("mimeType", "image/png");

        @SuppressWarnings("unchecked")
        Map<String, String> replacements = (Map<String, String>) pub.getShowcaseSnapshot().get("imageReplacements");
        assertThat(replacements).containsEntry(externalUrl, publicationPath);
    }

    // ========================================================================
    // Multiple FileRefs in same snapshot
    // ========================================================================

    @Test
    @DisplayName("copies the publisher's own file and the captured run owner's, and REFUSES a third tenant's - a snapshot can legitimately carry two owners, never an arbitrary one")
    void copiesTheTwoOwnersInScopeAndRefusesAnyOther() {
        String ownFile = PUBLISHER_TENANT + "/general/own.jpg";
        String runOwnerFile = FILE_OWNER_TENANT + "/general/from-the-run.jpg";
        String strangerFile = "42/general/somebody-elses.jpg";

        Map<String, Object> runState = new HashMap<>();
        runState.put("status", "COMPLETED");
        runState.put("own", fileRef(ownFile));
        runState.put("fromRun", fileRef(runOwnerFile));
        runState.put("stranger", fileRef(strangerFile));

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", runState);

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/x/copied.jpg"));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, times(2)).copyFile(captor.capture(), any());
        assertThat(captor.getAllValues())
                .extracting(req -> req.get("sourcePath"))
                .containsExactlyInAnyOrder(ownFile, runOwnerFile);
        // The stranger's path is left exactly as it was, so nothing downstream can sign it:
        // the render-time guard only accepts the publisher's tenant or this publication's
        // namespace, and it is in neither.
        assertThat(runState.get("stranger")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> stranger = (Map<String, Object>) runState.get("stranger");
        assertThat(stranger).containsEntry("path", strangerFile);
    }

    // ========================================================================
    // Proxy URL → FileRef normalization
    // ========================================================================

    @Test
    @DisplayName("proxy URL string in interfaceRenders data is normalized to FileRef and copied")
    void proxyUrlInInterfaceRenderDataIsNormalizedAndCopied() {
        // Simulates the Instagram Profile Scraper scenario:
        // profilePic is stored as "/api/files/proxy?key=1%2F...%2Ffile.jpg&disposition=inline"
        // Publisher-owned key: a URL STRING naming another tenant is refused outright now
        // (see foreignTenantUrlStringIsRefused), unlike a FileRef map, which may legitimately
        // name the workflow owner's tenant in a cross-org publish.
        String proxyUrl = "/api/files/proxy?key=8%2Fd1c0e41a%2Frun_123%2Fcore%3Adownload_avatar%2Fphoto.jpg&disposition=inline";
        String decodedKey = "8/d1c0e41a/run_123/core:download_avatar/photo.jpg";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-abc/photo.jpg";

        Map<String, Object> data = new HashMap<>();
        data.put("profilePic", proxyUrl);
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);

        Map<String, Object> epochRender = new HashMap<>();
        epochRender.put("items", List.of(item));
        epochRender.put("htmlTemplate", "<img src='{{profilePic}}'>");

        Map<String, Object> byEpoch = new HashMap<>();
        byEpoch.put("0", epochRender);
        Map<String, Object> ifaceEntry = new HashMap<>();
        ifaceEntry.put("byEpoch", byEpoch);

        Map<String, Object> snapshot = new HashMap<>();
        // Stated by the orchestrator, which validated the caller's scope against it. It is
        // what makes a cross-org copy legitimate instead of a path the publisher just named.
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", Map.of("status", "COMPLETED"));
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), ifaceEntry));

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        // Verify the proxy URL was first normalized to FileRef, then copyFile was invoked
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("sourcePath", decodedKey);
    }

    @Test
    @DisplayName("JSON-encoded string field with embedded proxy URLs is normalized")
    void jsonEncodedStringWithProxyUrlsIsNormalized() {
        // postsJson field: a JSON array stored as a String, containing proxy URLs
        String postsJson = "[{\"image\":\"/api/files/proxy?key=8%2Frun%2Fstep%2Fpost1.jpg&disposition=inline\",\"caption\":\"Hello\"},"
                + "{\"image\":\"/api/files/proxy?key=8%2Frun%2Fstep%2Fpost2.jpg&disposition=inline\",\"caption\":\"World\"}]";
        String newPath1 = "_publications/" + PUBLICATION_ID + "/snapshot/runout-1/post1.jpg";
        String newPath2 = "_publications/" + PUBLICATION_ID + "/snapshot/runout-2/post2.jpg";

        Map<String, Object> data = new HashMap<>();
        data.put("postsJson", postsJson);
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);

        Map<String, Object> epochRender = new HashMap<>();
        epochRender.put("items", List.of(item));
        epochRender.put("htmlTemplate", "<div>{{postsJson}}</div>");

        Map<String, Object> byEpoch = new HashMap<>();
        byEpoch.put("0", epochRender);
        Map<String, Object> ifaceEntry = new HashMap<>();
        ifaceEntry.put("byEpoch", byEpoch);

        Map<String, Object> snapshot = new HashMap<>();
        // Stated by the orchestrator, which validated the caller's scope against it. It is
        // what makes a cross-org copy legitimate instead of a path the publisher just named.
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", Map.of("status", "COMPLETED"));
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), ifaceEntry));

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", newPath1))
                .thenReturn(Map.of("newPath", newPath2));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        // Both embedded proxy URLs should trigger file copies
        verify(orchestratorClient, times(2)).copyFile(any(), any());
    }

    @Test
    @DisplayName("non-proxy URL strings are NOT modified during normalization")
    void nonProxyUrlStringsAreNotModified() {
        Map<String, Object> data = new HashMap<>();
        data.put("username", "@instagram_user");
        data.put("bio", "Hello world");
        data.put("url", "https://example.com/profile");
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);

        Map<String, Object> epochRender = new HashMap<>();
        epochRender.put("items", List.of(item));
        epochRender.put("htmlTemplate", "<p>{{username}}</p>");

        Map<String, Object> byEpoch = new HashMap<>();
        byEpoch.put("0", epochRender);
        Map<String, Object> ifaceEntry = new HashMap<>();
        ifaceEntry.put("byEpoch", byEpoch);

        Map<String, Object> snapshot = new HashMap<>();
        // Stated by the orchestrator, which validated the caller's scope against it. It is
        // what makes a cross-org copy legitimate instead of a path the publisher just named.
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", Map.of("status", "COMPLETED"));
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), ifaceEntry));

        stubPublishWorkflow(snapshot);

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        // No file copy should be triggered - no proxy URLs and no FileRefs
        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    @Test
    @DisplayName("ABSOLUTE core:public_link URL in interfaceRenders data is normalized to a FileRef and copied into the publication namespace")
    void absolutePublicLinkUrlIsNormalizedAndCopied() {
        // The shape a `core:public_link` node writes into interface data: absolute
        // (it carries the install's public origin) and already signed. It was invisible
        // to the normalizer, so the bytes were never copied into the publication and the
        // frozen `exp` 403'd a few hours after publish with nothing able to refresh it.
        String decodedKey = "8/d1c0e41a/run_123/core:watermark/clip.mp4";
        String publicLink = publicLink(decodedKey);
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-abc/clip.mp4";

        Map<String, Object> data = new HashMap<>();
        data.put("final_video", publicLink);

        stubPublishWorkflow(snapshotWithInterfaceRenderData(data));
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("sourcePath", decodedKey);
        // The mime type must survive the URL round-trip, or the browser gets
        // application/octet-stream and refuses to play the video.
        assertThat(captor.getValue()).containsEntry("mimeType", "video/mp4");
        assertThat(captor.getValue()).containsEntry("fileName", "clip.mp4");
    }

    @Test
    @DisplayName("public_link URLs nested in a JSON-encoded string field are normalized and copied")
    void publicLinkUrlsInsideJsonStringAreNormalizedAndCopied() {
        String postsJson = "[{\"clip\":\"" + publicLink("8/run/p1.mp4") + "\"}]";
        Map<String, Object> data = new HashMap<>();
        data.put("postsJson", postsJson);

        stubPublishWorkflow(snapshotWithInterfaceRenderData(data));
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/" + PUBLICATION_ID + "/snapshot/x/p1.mp4"));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("sourcePath", "8/run/p1.mp4");
    }

    @Test
    @DisplayName("a caption that merely QUOTES a public link is left alone - no copy, no data loss")
    void proseQuotingAPublicLinkIsNotNormalized() {
        Map<String, Object> data = new HashMap<>();
        data.put("caption", "Watch it here: " + publicLink("8/a.mp4"));

        stubPublishWorkflow(snapshotWithInterfaceRenderData(data));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    // ========================================================================
    // Landing-interface files (AGENT / TABLE / SKILL / WORKFLOW landing page)
    // ========================================================================

    @Test
    @DisplayName("a FileRef in agentSnapshot.landingInterface.data is copied into the publication namespace - the landing is rendered for every visitor, so it must not read the publisher's storage")
    void agentLandingFileRefIsCopied() {
        WorkflowPublicationEntity pub = savedPublicationWithAgentLanding(
                fileRef(FILE_OWNER_TENANT + "/run/hero.png"));
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/" + PUBLICATION_ID + "/snapshot/x/hero.png"));

        int copied = service.materializeLandingFiles(pub, PUBLISHER_TENANT);

        assertThat(copied).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient).copyFile(captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("sourcePath", FILE_OWNER_TENANT + "/run/hero.png");
        assertThat(landingPath(pub.getAgentSnapshot()))
                .isEqualTo("_publications/" + PUBLICATION_ID + "/snapshot/x/hero.png");
    }

    @Test
    @DisplayName("a core:public_link URL in planSnapshot.landingInterface.data is normalized and copied too")
    void planLandingPublicLinkUrlIsCopied() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity(
                WORKFLOW_ID, "My App", landingSnapshot(publicLink("8/run/clip.mp4")), PUBLISHER_TENANT);
        pub.setId(PUBLICATION_ID);
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/" + PUBLICATION_ID + "/snapshot/x/clip.mp4"));

        int copied = service.materializeLandingFiles(pub, PUBLISHER_TENANT);

        assertThat(copied).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient).copyFile(captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("sourcePath", "8/run/clip.mp4");
    }

    @Test
    @DisplayName("called BEFORE the publication is saved it copies nothing - the id is assigned by @PrePersist, so there is no namespace yet")
    void doesNothingWhenCalledBeforeTheEntityHasAnId() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity(
                WORKFLOW_ID, "My App", landingSnapshot(fileRef(FILE_OWNER_TENANT + "/run/hero.png")),
                PUBLISHER_TENANT);
        // No setId: exactly the state of a first publish before publicationRepository.save.

        int copied = service.materializeLandingFiles(pub, PUBLISHER_TENANT);

        assertThat(copied).isZero();
        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    @Test
    @DisplayName("a landing file already inside the publication namespace is not re-copied - a re-publish must be free")
    void alreadyNamespacedLandingFileIsNotRecopied() {
        WorkflowPublicationEntity pub = savedPublicationWithAgentLanding(
                fileRef("_publications/" + PUBLICATION_ID + "/snapshot/x/hero.png"));

        int copied = service.materializeLandingFiles(pub, PUBLISHER_TENANT);

        assertThat(copied).isZero();
        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    @Test
    @DisplayName("a publication with no landing interface is left alone")
    void noLandingIsANoOp() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity(
                WORKFLOW_ID, "My App", new HashMap<>(), PUBLISHER_TENANT);
        pub.setId(PUBLICATION_ID);

        assertThat(service.materializeLandingFiles(pub, PUBLISHER_TENANT)).isZero();
        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    // ========================================================================
    // repairSnapshotFileNamespace - the sweep's actual worker, exercised for real
    // ========================================================================

    @Test
    @DisplayName("repair copies a showcase FileRef that still points at the publisher and rewrites the entity snapshot in memory - persistence is the sweep job, pinned by theWorkersSuspendTheirCallersTransaction")
    void repairCopiesAndPersists() {
        WorkflowPublicationEntity pub = publicationWithShowcase(fileRef(FILE_OWNER_TENANT + "/run/clip.mp4"));
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/x/clip.mp4";
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        int copied = service.repairSnapshotFileNamespace(pub);

        assertThat(copied).isEqualTo(1);
        assertThat(showcasePath(pub)).isEqualTo(newPath);
        // Mutates, does not persist: the sweep saves once after both passes, so two merges of
        // the same entity cannot race each other's version.
        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("repair is free the second time - a path already in the publication namespace is not re-copied and the entity is not re-saved")
    void repairIsIdempotent() {
        WorkflowPublicationEntity pub = publicationWithShowcase(
                fileRef("_publications/" + PUBLICATION_ID + "/snapshot/x/clip.mp4"));

        assertThat(service.repairSnapshotFileNamespace(pub)).isZero();
        verify(orchestratorClient, never()).copyFile(any(), any());
        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("when every copy FAILS the stored snapshot is left byte-identical - reporting 0 while still rewriting it would silently degrade a page the operator was told was untouched")
    void failedCopyLeavesTheStoredSnapshotAlone() {
        // A trusted relative proxy URL: the pass normalizes it into a FileRef map BEFORE
        // attempting any copy, so an implementation that walks the entity's own map mutates
        // it and the transaction flushes that rewrite even though nothing was copied. On an
        // install with no signing key the rewriter then leaves the FileRef raw and the
        // template renders a serialized Map where a working URL used to be.
        WorkflowPublicationEntity pub = publicationWithShowcase("/api/files/proxy?key=8%2Frun%2Fclip.mp4");
        when(orchestratorClient.copyFile(any(), any())).thenReturn(null); // copy endpoint refused

        int copied = service.repairSnapshotFileNamespace(pub);

        assertThat(copied).isZero();
        assertThat(showcaseValue(pub)).isEqualTo("/api/files/proxy?key=8%2Frun%2Fclip.mp4");
        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("repair refuses a forged signed URL - the copy pass has no ownership guard of its own, so the trust decision has to happen before it")
    void repairRefusesForgedSignedUrl() {
        String forged = "https://livecontext.ai/api/files/proxy-signed?key=3%2Fprivate%2Fcontract.pdf"
                + "&exp=99999999999&disposition=inline&sig=bm90LW91cnMtYXQtYWxs";
        WorkflowPublicationEntity pub = publicationWithShowcase(forged);

        assertThat(service.repairSnapshotFileNamespace(pub)).isZero();
        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    @Test
    @DisplayName("an INTERFACE publication keeps its landing payload at planSnapshot.data, not under landingInterface - the one type whose landing IS the interface")
    void interfaceLandingDataIsCopied() {
        Map<String, Object> data = new HashMap<>();
        // An INTERFACE publication's landing is planSnapshot.data at top level: there is no
        // landingInterface wrapper to state an owner, so only the publisher's own files are
        // in scope there.
        data.put("hero", fileRef(PUBLISHER_TENANT + "/run/hero.png"));
        Map<String, Object> plan = new HashMap<>();
        plan.put("data", data);
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity(WORKFLOW_ID, "My Page", plan, PUBLISHER_TENANT);
        pub.setId(PUBLICATION_ID);
        pub.setPublicationType(WorkflowPublicationEntity.PublicationType.INTERFACE);
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/" + PUBLICATION_ID + "/snapshot/x/hero.png"));

        assertThat(service.materializeLandingFiles(pub, PUBLISHER_TENANT)).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient).copyFile(captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("sourcePath", PUBLISHER_TENANT + "/run/hero.png");
    }

    @Test
    @DisplayName("re-homing a landing file recomputes the capability label - a deliberate side effect on the repair path, where an old publication is re-labelled with today's rules")
    void materializeLandingRecomputesCeExclusiveLabel() {
        // A table carrying a vector column is labelled VECTOR_SEARCH. This publication is stored
        // with no label at all, as one published before the detector knew that rule would be.
        Map<String, Object> agentSnapshot = landingSnapshot(fileRef(FILE_OWNER_TENANT + "/run/hero.png"));
        // In an AGENT snapshot a standalone table carries its spec under `mappingSpec`
        // (the `_snapshot_ds_` prefix is the PLAN-side shape).
        agentSnapshot.put("datasources", List.of(Map.of(
                "mappingSpec", Map.of("embedding", Map.of("path", "embedding", "type", "vector")))));
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity(
                WORKFLOW_ID, "My Agent", new HashMap<>(), PUBLISHER_TENANT);
        pub.setId(PUBLICATION_ID);
        pub.setAgentSnapshot(agentSnapshot);
        assertThat(pub.isCeExclusive()).isFalse();
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/" + PUBLICATION_ID + "/snapshot/x/hero.png"));

        service.materializeLandingFiles(pub, PUBLISHER_TENANT);

        assertThat(pub.getCeExclusiveFeatures()).contains("VECTOR_SEARCH");
        // The label is recomputed with TODAY's rules, and today embeddings are priced rather than
        // impossible: the row records the capability without becoming un-installable.
        assertThat(pub.isCeExclusive()).isFalse();
    }

    @Test
    @DisplayName("a URL STRING naming another tenant is refused - the copy pass has no ownership test, so after a copy the key sits in the publication namespace and the render-time guard signs it happily")
    void foreignTenantUrlStringIsRefused() {
        // The publisher is tenant 8; this names tenant 3. A FileRef MAP naming tenant 3 is
        // still copied (cross-org publishing), but a URL string is a value the publisher's
        // own workflow wrote, so it gets the stricter rule.
        WorkflowPublicationEntity pub = publicationWithShowcase(
                "/api/files/proxy?key=3%2Fprivate%2Fcontract.pdf&disposition=inline");

        assertThat(service.repairSnapshotFileNamespace(pub)).isZero();
        verify(orchestratorClient, never()).copyFile(any(), any());
        assertThat(showcaseValue(pub)).isEqualTo("/api/files/proxy?key=3%2Fprivate%2Fcontract.pdf&disposition=inline");
    }

    @Test
    @DisplayName("materializeLandingFiles also leaves the entity untouched when every copy fails - same invariant as the showcase repair, same deep-copy defence")
    void failedLandingCopyLeavesTheEntityAlone() {
        WorkflowPublicationEntity pub = savedPublicationWithAgentLanding(
                "/api/files/proxy?key=8%2Frun%2Fhero.png&disposition=inline");
        when(orchestratorClient.copyFile(any(), any())).thenReturn(null);

        assertThat(service.materializeLandingFiles(pub, PUBLISHER_TENANT)).isZero();
        @SuppressWarnings("unchecked")
        Map<String, Object> landing = (Map<String, Object>) pub.getAgentSnapshot().get("landingInterface");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) landing.get("data");
        assertThat(data.get("hero")).isEqualTo("/api/files/proxy?key=8%2Frun%2Fhero.png&disposition=inline");
        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("the landing pass uses the PUBLISHER of record, not the caller - an ORG publication can be re-published by another member, and the files are still the original publisher's")
    void landingUsesThePublisherOfRecordNotTheCaller() {
        WorkflowPublicationEntity pub = savedPublicationWithAgentLanding(
                "/api/files/proxy?key=8%2Frun%2Fhero.png&disposition=inline");
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/" + PUBLICATION_ID + "/snapshot/x/hero.png"));

        // Called by a DIFFERENT member of the org. Keyed on the caller, the publisher's own
        // file would read as foreign, nothing would be copied, and the publication would go
        // on reading live files - the invariant this whole change exists to establish.
        int copied = service.materializeLandingFiles(pub, "99");

        assertThat(copied).isEqualTo(1);
    }

    /** The path as it now stands in the item, which the pass replaces rather than mutates. */
    @SuppressWarnings("unchecked")
    private static String renderItemPath(Map<String, Object> item) {
        Map<String, Object> data = (Map<String, Object>) item.get("data");
        Object first = data.values().iterator().next();
        return (String) ((Map<String, Object>) first).get("path");
    }

    /** Showcase snapshot shaped like the real one: interfaceRenders to byEpoch to items[].data. */
    private WorkflowPublicationEntity publicationWithShowcase(Object dataValue) {
        Map<String, Object> data = new HashMap<>();
        data.put("final_video", dataValue);
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);
        Map<String, Object> render = new HashMap<>();
        render.put("items", List.of(item));
        Map<String, Object> byEpoch = new HashMap<>();
        byEpoch.put("1", render);
        Map<String, Object> entry = new HashMap<>();
        entry.put("byEpoch", byEpoch);
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), entry));
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity(
                WORKFLOW_ID, "My App", new HashMap<>(), PUBLISHER_TENANT);
        pub.setId(PUBLICATION_ID);
        pub.setShowcaseSnapshot(snapshot);
        return pub;
    }

    @SuppressWarnings("unchecked")
    private static Object showcaseValue(WorkflowPublicationEntity pub) {
        Map<String, Object> renders = (Map<String, Object>) pub.getShowcaseSnapshot().get("interfaceRenders");
        Map<String, Object> entry = (Map<String, Object>) renders.values().iterator().next();
        Map<String, Object> byEpoch = (Map<String, Object>) entry.get("byEpoch");
        Map<String, Object> render = (Map<String, Object>) byEpoch.get("1");
        Map<String, Object> item = (Map<String, Object>) ((List<?>) render.get("items")).get(0);
        return ((Map<String, Object>) item.get("data")).get("final_video");
    }

    @SuppressWarnings("unchecked")
    private static String showcasePath(WorkflowPublicationEntity pub) {
        return (String) ((Map<String, Object>) showcaseValue(pub)).get("path");
    }

    private WorkflowPublicationEntity savedPublicationWithAgentLanding(Object dataValue) {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity(
                WORKFLOW_ID, "My Agent", new HashMap<>(), PUBLISHER_TENANT);
        pub.setId(PUBLICATION_ID);
        pub.setAgentSnapshot(landingSnapshot(dataValue));
        return pub;
    }

    /** {@code {landingInterface: {data: {hero: <value>}}}} - the shape the snapshotter embeds. */
    private static Map<String, Object> landingSnapshot(Object dataValue) {
        Map<String, Object> data = new HashMap<>();
        data.put("hero", dataValue);
        Map<String, Object> landing = new HashMap<>();
        landing.put("data", data);
        // Stated by LandingInterfaceSnapshotter, under the internal (underscore) key that
        // every public exit strips.
        landing.put(com.apimarketplace.publication.service.LandingInterfaceSnapshotter
                .INTERNAL_SOURCE_TENANT_KEY, FILE_OWNER_TENANT);
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("landingInterface", landing);
        return snapshot;
    }

    private static Map<String, Object> fileRef(String path) {
        Map<String, Object> ref = new HashMap<>();
        ref.put("_type", "file");
        ref.put("path", path);
        ref.put("name", path.substring(path.lastIndexOf('/') + 1));
        ref.put("mimeType", "image/png");
        return ref;
    }

    @SuppressWarnings("unchecked")
    private static String landingPath(Map<String, Object> snapshot) {
        Map<String, Object> landing = (Map<String, Object>) snapshot.get("landingInterface");
        Map<String, Object> data = (Map<String, Object>) landing.get("data");
        return (String) ((Map<String, Object>) data.get("hero")).get("path");
    }

    /** Snapshot carrying one interface render whose single item holds {@code data}. */
    private Map<String, Object> snapshotWithInterfaceRenderData(Map<String, Object> data) {
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);
        Map<String, Object> epochRender = new HashMap<>();
        epochRender.put("items", List.of(item));
        Map<String, Object> byEpoch = new HashMap<>();
        byEpoch.put("0", epochRender);
        Map<String, Object> ifaceEntry = new HashMap<>();
        ifaceEntry.put("byEpoch", byEpoch);
        Map<String, Object> snapshot = new HashMap<>();
        // Stated by the orchestrator, which validated the caller's scope against it. It is
        // what makes a cross-org copy legitimate instead of a path the publisher just named.
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", Map.of("status", "COMPLETED"));
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), ifaceEntry));
        return snapshot;
    }

    @Test
    @DisplayName("proxy URL without key param is not normalized (graceful)")
    void proxyUrlWithoutKeyParamIsIgnored() {
        String badProxyUrl = "/api/files/proxy?disposition=inline";

        Map<String, Object> data = new HashMap<>();
        data.put("image", badProxyUrl);
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);

        Map<String, Object> epochRender = new HashMap<>();
        epochRender.put("items", List.of(item));
        epochRender.put("htmlTemplate", "<img src='{{image}}'>");

        Map<String, Object> byEpoch = new HashMap<>();
        byEpoch.put("0", epochRender);
        Map<String, Object> ifaceEntry = new HashMap<>();
        ifaceEntry.put("byEpoch", byEpoch);

        Map<String, Object> snapshot = new HashMap<>();
        // Stated by the orchestrator, which validated the caller's scope against it. It is
        // what makes a cross-org copy legitimate instead of a path the publisher just named.
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", Map.of("status", "COMPLETED"));
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), ifaceEntry));

        stubPublishWorkflow(snapshot);

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        // Malformed proxy URL → no normalization → no copy
        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    // ========================================================================
    // Non-FileRef maps are not copied
    // ========================================================================

    @Test
    @DisplayName("map without _type=file is NOT treated as FileRef")
    void nonFileRefMapIsIgnored() {
        Map<String, Object> regularMap = new HashMap<>();
        regularMap.put("type", "file");  // wrong key: "type" not "_type"
        regularMap.put("path", FILE_OWNER_TENANT + "/general/not-a-file.txt");

        Map<String, Object> runState = new HashMap<>();
        runState.put("status", "COMPLETED");
        runState.put("someData", regularMap);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("runState", runState);

        stubPublishWorkflow(snapshot);

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    // ========================================================================
    // Signing and normalization guards
    // ========================================================================

    @Test
    @DisplayName("a publish that copies NOTHING leaves the data exactly as it was - on an install with no signing key the rewriter leaves a FileRef alone, so replacing a working URL with one is an immediate, permanent regression")
    void aPublishThatCopiesNothingDoesNotRewriteTheData() {
        // The copy is refused (foreign tenant), so the normalization that turned the URL into
        // a FileRef must not stick either. Same rule the repair pass applies to the stored
        // snapshot; the publish path had no equivalent.
        String url = "/api/files/proxy?key=3%2Fprivate%2Fcontract.pdf&disposition=inline";
        Map<String, Object> data = new HashMap<>();
        data.put("doc", url);

        stubPublishWorkflow(snapshotWithInterfaceRenderData(data));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        verify(orchestratorClient, never()).copyFile(any(), any());
        assertThat(data.get("doc")).isEqualTo(url);
    }

    // ========================================================================
    // AI-screening replacement images - a CALLER-supplied storage key
    // ========================================================================

    @Test
    @DisplayName("a replacement image naming another tenant's file is REFUSED - imageReplacements is a request field, so its storageKey is whatever the publisher sent, and the copy endpoint reads the owner off the path and downloads as them")
    void replacementImageOutsideTheScopeIsRefused() {
        // Left ungated, this was a straight cross-tenant read from a public endpoint: the
        // bytes land in the publication namespace, the render-time guard then accepts them
        // (they are in the namespace now), and ShowcaseSnapshotReader substitutes a freshly
        // signed URL into the public template.
        stubPublishWorkflow(snapshotWithRunStateFileRef(FILE_OWNER_TENANT + "/general/img.jpg"));
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/x/snapshot/img.jpg"));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false,
                Map.of("__flagged__", "3/private/contract.pdf"), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());
        assertThat(captor.getAllValues())
                .extracting(req -> req.get("sourcePath"))
                .doesNotContain("3/private/contract.pdf");
    }

    @Test
    @DisplayName("a replacement image the publisher owns is still copied - the guard must not break AI screening")
    void replacementImageInsideTheScopeIsCopied() {
        stubPublishWorkflow(new HashMap<>(Map.of("runState", Map.of("status", "COMPLETED"))));
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/x/snapshot/ai-replace/clean.png"));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false,
                Map.of("__flagged__", PUBLISHER_TENANT + "/screening/clean.png"), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());
        assertThat(captor.getAllValues())
                .extracting(req -> req.get("sourcePath"))
                .contains(PUBLISHER_TENANT + "/screening/clean.png");
    }

    // ========================================================================
    // First publish: the id has to exist before the plan is enriched
    // ========================================================================

    @Test
    @DisplayName("a FIRST publish copies the files embedded in the plan - the id is the storage namespace, and while it was assigned only at save these passes read null and returned at their first line, silently, on the one path where it mattered")
    void firstPublishCopiesPlanEmbeddedFileRefs() {
        // No existing publication: this is a first publish, the case that used to no-op.
        Map<String, Object> ifaceData = new HashMap<>();
        ifaceData.put("hero", fileRef(PUBLISHER_TENANT + "/run/hero.png"));
        Map<String, Object> iface = new HashMap<>();
        iface.put("_snapshot_data", ifaceData);

        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", List.of());
        plan.put("interfaces", List.of(iface));
        plan.put("cores", List.of());
        plan.put("edges", List.of());

        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", PUBLISHER_TENANT);
        workflowData.put("workflowType", "WORKFLOW");
        workflowData.put("plan", plan);

        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, PUBLISHER_TENANT, null))
                .thenReturn(workflowData);
        when(orchestratorClient.validateShowcaseRun("run-1", PUBLISHER_TENANT, null))
                .thenReturn(Map.of("isStepByStep", false, "publishable", true, "status", "COMPLETED"));
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.empty());
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotVersionRepository.getMaxVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(orchestratorClient.getLatestPlanVersion(WORKFLOW_ID, PUBLISHER_TENANT)).thenReturn(1);
        when(orchestratorClient.captureShowcaseSnapshot("run-1", PUBLISHER_TENANT, null, null))
                .thenReturn(Map.of("runState", Map.of("status", "COMPLETED")));
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/x/snapshot/hero.png"));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());
        assertThat(captor.getAllValues())
                .extracting(req -> req.get("sourcePath"))
                .contains(PUBLISHER_TENANT + "/run/hero.png");
    }

    @Test
    @DisplayName("a core:public_link URL in interfaces[]._snapshot_data is copied too - that subtree is what an ACQUIRER clones, and a reference left there is refused by the clone allowlist and renders a dead link")
    void publicLinkUrlInPlanInterfaceSnapshotDataIsCopied() {
        Map<String, Object> ifaceData = new HashMap<>();
        ifaceData.put("clip", publicLink(PUBLISHER_TENANT + "/run/clip.mp4"));
        Map<String, Object> iface = new HashMap<>();
        // List.of on purpose: a plan is partly assembled in Java by the enrichers, so the
        // pass must not rewrite it in place.
        iface.put("_snapshot_data", ifaceData);
        Map<String, Object> plan = new HashMap<>();
        plan.put("interfaces", List.of(iface));

        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/" + PUBLICATION_ID + "/snapshot/x/clip.mp4"));

        service.snapshotPlanEmbeddedFileRefs(plan, PUBLICATION_ID, PUBLISHER_TENANT, PUBLISHER_TENANT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient).copyFile(captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("sourcePath", PUBLISHER_TENANT + "/run/clip.mp4");
    }

    @Test
    @DisplayName("an IMMUTABLE collection inside the plan is not rewritten in place - doing so throws inside the publish transaction, turning a file-tidying step into a failed publish")
    void immutablePlanSubtreeIsNotMutatedInPlace() {
        Map<String, Object> iface = new HashMap<>();
        // A URL STRING inside immutable containers. A FileRef map would not prove anything:
        // walkAndCopyFileRefs only writes INSIDE the ref (a mutable HashMap), never through
        // the container. It is normalizeProxyUrlsInMap/-List, which replace the entry itself,
        // that would throw here - and that is the pass deepCopyValue exists to protect.
        iface.put("_snapshot_data", Map.of(
                "gallery", List.of("/api/files/proxy?key=" + PUBLISHER_TENANT + "%2Frun%2Fa.png")));
        Map<String, Object> plan = new HashMap<>();
        plan.put("interfaces", List.of(iface));

        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/" + PUBLICATION_ID + "/snapshot/x/a.png"));

        // Would throw UnsupportedOperationException if the pass wrote through the Map.of.
        service.snapshotPlanEmbeddedFileRefs(plan, PUBLICATION_ID, PUBLISHER_TENANT, PUBLISHER_TENANT);

        verify(orchestratorClient).copyFile(any(), any());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** The id the service assigned; captured from what it saved, not dictated by the test. */
    private String publishedPublicationId() {
        ArgumentCaptor<WorkflowPublicationEntity> captor =
                ArgumentCaptor.forClass(WorkflowPublicationEntity.class);
        verify(publicationRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue().getId().toString();
    }

    private void stubPublishWorkflow(Map<String, Object> capturedSnapshot) {
        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", PUBLISHER_TENANT);
        workflowData.put("workflowType", "WORKFLOW");
        workflowData.put("plan", new HashMap<>(Map.of(
                "triggers", List.of(),
                "interfaces", List.of(),
                "cores", List.of(),
                "edges", List.of())));

        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, PUBLISHER_TENANT, null))
                .thenReturn(workflowData);
        when(orchestratorClient.validateShowcaseRun("run-1", PUBLISHER_TENANT, null))
                .thenReturn(Map.of("isStepByStep", false, "publishable", true, "status", "COMPLETED"));
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.empty());
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // The id is assigned by the entity before the plan is enriched, because it IS the
        // storage namespace the plan's files are copied into. A test that pins it through the
        // save stub would be pinning the bug that made those copies no-op on a first publish.
        when(snapshotVersionRepository.getMaxVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(orchestratorClient.getLatestPlanVersion(WORKFLOW_ID, PUBLISHER_TENANT)).thenReturn(1);
        when(orchestratorClient.captureShowcaseSnapshot("run-1", PUBLISHER_TENANT, null, null))
                .thenReturn(capturedSnapshot);
    }

    // ========================================================================
    // stepFiles - the canvas node file pills
    //
    // The section the marketplace canvas reads to hang a file pill under a node.
    // It is READ by every visitor, so a path left in the publisher's namespace
    // makes the preview depend on the publisher never deleting that file - the
    // exact failure the copy pass exists to prevent everywhere else.
    // ========================================================================

    @Test
    @DisplayName("a FileRef in stepFiles is copied into the publication namespace and its path rewritten")
    void stepFilesFileRefIsCopiedAndRewritten() {
        String runOwnerFile = FILE_OWNER_TENANT + "/general/catalog-binary/clip.mp4";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-abc/clip.mp4";

        Map<String, Object> perAlias = new HashMap<>();
        perAlias.put("download_file", fileRef(runOwnerFile));
        Map<String, Object> stepFiles = new HashMap<>();
        stepFiles.put("1", perAlias);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", new HashMap<>(Map.of("status", "COMPLETED")));
        snapshot.put("stepFiles", stepFiles);

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());
        assertThat(captor.getAllValues()).extracting(req -> req.get("sourcePath")).contains(runOwnerFile);

        @SuppressWarnings("unchecked")
        Map<String, Object> copied = (Map<String, Object>) perAlias.get("download_file");
        assertThat(copied).containsEntry("path", newPath);
    }

    @Test
    @DisplayName("a stepFiles path owned by a third tenant is refused, exactly as everywhere else in the snapshot")
    void stepFilesForeignTenantIsRefused() {
        // stepFiles is built from a node's own output, and a core:code node can author a
        // FileRef pointing anywhere. It gets the same ownership gate as items[].data.
        String strangerFile = "42/general/somebody-elses.jpg";

        Map<String, Object> perAlias = new HashMap<>();
        perAlias.put("code", fileRef(strangerFile));
        Map<String, Object> stepFiles = new HashMap<>();
        stepFiles.put("1", perAlias);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", new HashMap<>(Map.of("status", "COMPLETED")));
        snapshot.put("stepFiles", stepFiles);

        stubPublishWorkflow(snapshot);

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        verify(orchestratorClient, never()).copyFile(any(), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> untouched = (Map<String, Object>) perAlias.get("code");
        assertThat(untouched).containsEntry("path", strangerFile);
    }

    @Test
    @DisplayName("a file reachable from two branches is transferred ONCE, and every occurrence still lands on the copy")
    void aFileReachableTwiceIsCopiedOnce() {
        // On an unpinned capture a step's FileRef sits in runState.steps[].output AND in
        // stepFiles - two distinct map instances of the same file. Without a per-pass memo the
        // bytes are downloaded and re-uploaded once per occurrence, paying the transfer twice
        // and leaving two identical objects in the publication's namespace.
        String sharedPath = FILE_OWNER_TENANT + "/general/catalog-binary/clip.mp4";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-abc/clip.mp4";

        Map<String, Object> inRunState = fileRef(sharedPath);
        Map<String, Object> inStepFiles = fileRef(sharedPath);
        Map<String, Object> runState = new HashMap<>();
        runState.put("status", "COMPLETED");
        runState.put("stepOutput", inRunState);
        Map<String, Object> perAlias = new HashMap<>();
        perAlias.put("download_file", inStepFiles);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", runState);
        snapshot.put("stepFiles", new HashMap<>(Map.of("1", perAlias)));

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", newPath, "newId", "st-new"));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        verify(orchestratorClient, times(1)).copyFile(any(), any());
        assertThat(inRunState).containsEntry("path", newPath).containsEntry("id", "st-new");
        assertThat(inStepFiles)
                .as("the second occurrence must adopt the copy, not keep pointing at the publisher")
                .containsEntry("path", newPath).containsEntry("id", "st-new");
    }

    @Test
    @DisplayName("a memo hit is still COUNTED - the repair sweep tallies occurrences, and a count short of them makes it report work it did as work it skipped")
    void aMemoHitIsStillCounted() {
        // repairSnapshotFileNamespace discards its rewritten snapshot when the count is 0, and
        // ShowcaseFileNamespaceRepairService.countPending counts every OCCURRENCE. If the memo
        // hit stopped incrementing, a two-occurrence snapshot would repair and report 1 where
        // the dry run promised 2 - and a snapshot whose only file was a repeat would be
        // rewritten and then thrown away.
        String sharedPath = FILE_OWNER_TENANT + "/general/catalog-binary/clip.mp4";
        Map<String, Object> runState = new HashMap<>();
        runState.put("status", "COMPLETED");
        runState.put("stepOutput", fileRef(sharedPath));
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", runState);
        snapshot.put("stepFiles", new HashMap<>(Map.of("1",
                new HashMap<>(Map.of("download_file", fileRef(sharedPath))))));

        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUBLICATION_ID);
        pub.setPublisherId(PUBLISHER_TENANT);
        pub.setShowcaseSnapshot(snapshot);
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/" + PUBLICATION_ID + "/clip.mp4"));

        int copied = service.repairSnapshotFileNamespace(pub);

        assertThat(copied).as("two occurrences, one transfer, still two rewrites").isEqualTo(2);
        verify(orchestratorClient, times(1)).copyFile(any(), any());
    }

    @Test
    @DisplayName("a reused copy drops the stale source id too, never leaves one pointing at the publisher's row")
    void reusedCopyDropsAStaleId() {
        // The by-id URL is built from the id, so a source id surviving on the second occurrence
        // would 403 cross-tenant exactly like it would on the first.
        String sharedPath = FILE_OWNER_TENANT + "/general/catalog-binary/clip.mp4";
        Map<String, Object> first = fileRef(sharedPath);
        first.put("id", "st-source");
        Map<String, Object> second = fileRef(sharedPath);
        second.put("id", "st-source");

        Map<String, Object> runState = new HashMap<>();
        runState.put("status", "COMPLETED");
        runState.put("stepOutput", first);
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", runState);
        snapshot.put("stepFiles", new HashMap<>(Map.of("1", new HashMap<>(Map.of("download_file", second)))));

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/x/clip.mp4"));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of(), null);

        assertThat(first).doesNotContainKey("id");
        assertThat(second).doesNotContainKey("id");
    }

    private Map<String, Object> snapshotWithRunStateFileRef(String filePath) {
        Map<String, Object> fileRef = new HashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", filePath);
        fileRef.put("name", extractFileName(filePath));
        fileRef.put("mimeType", "image/jpeg");

        Map<String, Object> runState = new HashMap<>();
        runState.put("status", "COMPLETED");
        runState.put("profilePic", fileRef);

        Map<String, Object> snapshot = new HashMap<>();
        // Stated by the orchestrator, which validated the caller's scope against it. It is
        // what makes a cross-org copy legitimate rather than a path the publisher just named.
        snapshot.put("_sourceTenantId", FILE_OWNER_TENANT);
        snapshot.put("runState", runState);
        return snapshot;
    }

    private static String extractFileName(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 && idx < path.length() - 1 ? path.substring(idx + 1) : path;
    }
}
