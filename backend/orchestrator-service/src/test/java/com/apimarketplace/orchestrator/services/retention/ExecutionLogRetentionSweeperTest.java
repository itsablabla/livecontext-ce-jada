package com.apimarketplace.orchestrator.services.retention;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.storage.retention.ExecutionLogStoragePurger;
import com.apimarketplace.common.storage.retention.ExecutionLogStoragePurger.PurgeOutcome;
import com.apimarketplace.orchestrator.persistence.ExecutionLogStepDataRetentionRepository;
import com.apimarketplace.orchestrator.persistence.ExecutionLogStepDataRetentionRepository.Candidate;
import com.apimarketplace.orchestrator.persistence.ExecutionLogStepDataRetentionRepository.Scope;
import com.apimarketplace.orchestrator.services.retention.ExecutionLogRetentionSweeper.SweepReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ExecutionLogRetentionSweeper")
class ExecutionLogRetentionSweeperTest {

    private static final String GO_LIVE = "2026-09-01T00:00:00Z";
    private static final String ORG = "org-1";
    private static final String OTHER_ORG = "org-2";
    private static final String TENANT = "t-1";
    private static final String OTHER_TENANT = "t-2";

    private ExecutionLogStepDataRetentionRepository stepData;
    private ExecutionLogStoragePurger storagePurger;
    private AuthClient authClient;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stepData = mock(ExecutionLogStepDataRetentionRepository.class);
        storagePurger = mock(ExecutionLogStoragePurger.class);
        authClient = mock(AuthClient.class);
        transactionTemplate = mock(TransactionTemplate.class);
        // Run the callback inline so the ordering assertions see the real calls.
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
    }

    private ExecutionLogRetentionSweeper sweeper(boolean enabled, boolean dryRun,
                                                 String enforceFrom, String selfHostedDays) {
        return new ExecutionLogRetentionSweeper(stepData, storagePurger, authClient,
                transactionTemplate, enabled, dryRun, enforceFrom, selfHostedDays, 500, 200);
    }

    private record TestCandidate(Long id, UUID outputStorageId) implements Candidate {
        @Override public Long getId() { return id; }
        @Override public UUID getOutputStorageId() { return outputStorageId; }
    }

    private record TestScope(String organizationId, String tenantId) implements Scope {
        @Override public String getOrganizationId() { return organizationId; }
        @Override public String getTenantId() { return tenantId; }
    }

    /** A single page holding {@code scopes}, then the end of the scan. */
    private void pageOf(Scope... scopes) {
        when(stepData.findScopesWithCandidates(any(), any(), eq(""), eq(""), anyInt()))
                .thenReturn(List.of(scopes));
        Scope last = scopes[scopes.length - 1];
        when(stepData.findScopesWithCandidates(any(), any(),
                eq(last.getOrganizationId()), eq(last.getTenantId()), anyInt()))
                .thenReturn(List.of());
    }

    private void oneScopeWithOneBatch(Candidate... rows) {
        pageOf(new TestScope(ORG, TENANT));
        when(stepData.findPurgeCandidates(eq(ORG), eq(TENANT), any(), any(), anyInt()))
                .thenReturn(List.of(rows));
    }

    @Nested
    @DisplayName("The three switches")
    class Switches {

        @Test
        @DisplayName("Disabled by default: it does not even look for candidates")
        void disabledDoesNothing() {
            SweepReport report = sweeper(false, false, GO_LIVE, "").sweep();

            assertEquals(0, report.scopesConsidered());
            verifyNoInteractions(stepData);
            verifyNoInteractions(storagePurger);
        }

        /**
         * Without a grandfathering floor the first run would retroactively delete
         * months of history users could see the day before, including free-tier
         * accounts never told their window is seven days. Refusing is the only safe
         * reading of "enabled but not told when to start".
         */
        @Test
        @DisplayName("Enabled with no enforce-from refuses to sweep rather than sweeping everything")
        void missingEnforceFromRefuses() {
            SweepReport report = sweeper(true, false, "", "").sweep();

            assertEquals(0, report.scopesConsidered());
            verifyNoInteractions(stepData);
        }

        @Test
        @DisplayName("An unparseable enforce-from is treated as unset, not as the epoch")
        void unparseableEnforceFromRefuses() {
            SweepReport report = sweeper(true, false, "last tuesday", "").sweep();

            assertEquals(0, report.scopesConsidered());
            verifyNoInteractions(stepData);
        }

        @Test
        @DisplayName("Dry-run is the default and deletes nothing")
        void dryRunDeletesNothing() {
            oneScopeWithOneBatch(new TestCandidate(1L, UUID.randomUUID()));
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 90));
            when(storagePurger.purgePayloads(anyString(), anyCollection(), eq(true)))
                    .thenReturn(new PurgeOutcome(1, 42L, 0));

            SweepReport report = sweeper(true, true, GO_LIVE, "").sweep();

            assertEquals(1, report.stepRowsDeleted(), "the report says what it would do");
            verify(stepData, never()).deleteByIds(anyCollection());
            verify(storagePurger).purgePayloads(eq(TENANT), anyCollection(), eq(true));
        }
    }

    @Nested
    @DisplayName("Workspace keying")
    class WorkspaceKeying {

        /**
         * The regression behind the rewrite. The window belongs to the WORKSPACE
         * (its owner's plan), and the quota credit belongs to the TENANT. Production
         * 2026-09-02: a STARTER user's 6,852 rows inside a TEAM owner's workspace,
         * and 10 in their own; per-tenant resolution gave all of them one window.
         * Here two tenants share one workspace: auth is asked ONCE, about the
         * workspace, and both scopes are swept under its window, each against its
         * own tenant's ledger.
         */
        @Test
        @DisplayName("Two tenants in one workspace share its window; auth is asked about the workspace, the purger about each tenant")
        void windowIsTheWorkspacesAndQuotaIsTheTenants() {
            pageOf(new TestScope(ORG, TENANT), new TestScope(ORG, OTHER_TENANT));
            when(stepData.findPurgeCandidates(eq(ORG), eq(TENANT), any(), any(), anyInt()))
                    .thenReturn(List.of(new TestCandidate(1L, null)));
            when(stepData.findPurgeCandidates(eq(ORG), eq(OTHER_TENANT), any(), any(), anyInt()))
                    .thenReturn(List.of(new TestCandidate(2L, null)));
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 30));
            when(stepData.deleteByIds(anyCollection())).thenReturn(1);
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            SweepReport report = sweeper(true, false, GO_LIVE, "").sweep();

            assertEquals(2, report.scopesSwept());
            verify(authClient).getLogRetentionDays(List.of(ORG));
            verify(authClient, never()).getLogRetentionDays(argThatContains(TENANT));
            verify(storagePurger).purgePayloads(eq(TENANT), anyCollection(), eq(false));
            verify(storagePurger).purgePayloads(eq(OTHER_TENANT), anyCollection(), eq(false));
        }

        /**
         * The same user active in two workspaces is two scopes, and each takes ITS
         * workspace's window: the one auth answered for is swept, the other is
         * retained, and nothing about the user's own plan or memberships enters
         * into it.
         */
        @Test
        @DisplayName("One tenant across two workspaces gets each workspace's own window, not the widest")
        void eachWorkspaceKeepsItsOwnWindow() {
            pageOf(new TestScope(ORG, TENANT), new TestScope(OTHER_ORG, TENANT));
            when(stepData.findPurgeCandidates(eq(ORG), eq(TENANT), any(), any(), anyInt()))
                    .thenReturn(List.of(new TestCandidate(1L, null)));
            // Only the first workspace has a finite window (say TEAM); the second is
            // enterprise or unresolved and is absent from the answer.
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 90));
            when(stepData.deleteByIds(anyCollection())).thenReturn(1);
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            SweepReport report = sweeper(true, false, GO_LIVE, "").sweep();

            assertEquals(2, report.scopesConsidered());
            assertEquals(1, report.scopesSwept());
            verify(authClient).getLogRetentionDays(List.of(ORG, OTHER_ORG));
            verify(stepData).findPurgeCandidates(eq(ORG), eq(TENANT), any(), any(), anyInt());
            verify(stepData, never()).findPurgeCandidates(eq(OTHER_ORG), anyString(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("The candidate query is asked for the scope's workspace AND tenant, never one without the other")
        void candidatesAreScopedByBoth() {
            oneScopeWithOneBatch(new TestCandidate(1L, null));
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 30));
            when(stepData.deleteByIds(anyCollection())).thenReturn(1);
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            sweeper(true, false, GO_LIVE, "").sweep();

            verify(stepData).findPurgeCandidates(eq(ORG), eq(TENANT), any(), any(), anyInt());
        }

        private static java.util.Collection<String> argThatContains(String value) {
            return org.mockito.ArgumentMatchers.argThat(c -> c != null && c.contains(value));
        }
    }

    @Nested
    @DisplayName("Deleting")
    class Deleting {

        /**
         * output_storage_id has no foreign key, so the order decides what a crash
         * leaves behind. Referrer first can only orphan a payload (invisible,
         * reclaimable); the reverse leaves a step in the run view pointing at nothing.
         */
        @Test
        @DisplayName("Step rows are deleted BEFORE the payloads they point at")
        void referrerIsDeletedFirst() {
            UUID payload = UUID.randomUUID();
            oneScopeWithOneBatch(new TestCandidate(1L, payload));
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 90));
            when(stepData.deleteByIds(anyCollection())).thenReturn(1);
            when(storagePurger.purgePayloads(anyString(), anyCollection(), eq(false)))
                    .thenReturn(new PurgeOutcome(1, 10L, 0));

            sweeper(true, false, GO_LIVE, "").sweep();

            InOrder order = inOrder(stepData, storagePurger);
            order.verify(stepData).deleteByIds(List.of(1L));
            order.verify(storagePurger).purgePayloads(eq(TENANT), anyCollection(), eq(false));
        }

        @Test
        @DisplayName("A step row with no payload still gets deleted")
        void stepWithoutPayloadIsStillDeleted() {
            oneScopeWithOneBatch(new TestCandidate(7L, null));
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 30));
            when(stepData.deleteByIds(anyCollection())).thenReturn(1);
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            SweepReport report = sweeper(true, false, GO_LIVE, "").sweep();

            assertEquals(1, report.stepRowsDeleted());
            verify(stepData).deleteByIds(List.of(7L));
        }
    }

    @Nested
    @DisplayName("Scope paging")
    class ScopePaging {

        /**
         * Keyset on the (workspace, tenant) pair, not offset. A live sweep removes
         * every candidate row of the scopes it just handled, so those scopes leave
         * the result set between pages; {@code OFFSET n} would then skip n scopes
         * that were never looked at. The bug only appears past the first page,
         * converges over later nights, and is invisible to a single-page test,
         * which is precisely why this one spans two.
         */
        @Test
        @DisplayName("The second page continues after the last (workspace, tenant) seen, not at an offset")
        void secondPageIsKeyedOnTheLastScope() {
            when(stepData.findScopesWithCandidates(any(), any(), eq(""), eq(""), anyInt()))
                    .thenReturn(List.of(new TestScope("org-a", "t-1"), new TestScope("org-a", "t-2")));
            when(stepData.findScopesWithCandidates(any(), any(), eq("org-a"), eq("t-2"), anyInt()))
                    .thenReturn(List.of(new TestScope("org-b", "t-1")));
            when(stepData.findScopesWithCandidates(any(), any(), eq("org-b"), eq("t-1"), anyInt()))
                    .thenReturn(List.of());
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of());

            SweepReport report = sweeper(true, false, GO_LIVE, "").sweep();

            assertEquals(3, report.scopesConsidered(), "every scope on both pages must be seen");
            verify(stepData).findScopesWithCandidates(any(), any(), eq("org-a"), eq("t-2"), anyInt());
            verify(stepData).findScopesWithCandidates(any(), any(), eq("org-b"), eq("t-1"), anyInt());
        }
    }

    @Nested
    @DisplayName("Failing long")
    class FailingLong {

        @Test
        @DisplayName("A workspace auth returned no window for is skipped, never defaulted")
        void workspaceWithoutWindowIsSkipped() {
            oneScopeWithOneBatch(new TestCandidate(1L, UUID.randomUUID()));
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of());

            SweepReport report = sweeper(true, false, GO_LIVE, "").sweep();

            assertEquals(1, report.scopesConsidered());
            assertEquals(0, report.scopesSwept());
            verify(stepData, never()).findPurgeCandidates(anyString(), anyString(), any(), any(), anyInt());
            verify(stepData, never()).deleteByIds(anyCollection());
        }

        /**
         * A window answered under the TENANT's key must not be honoured: the wire
         * contract is keyed by workspace, and a caller reading a tenant-keyed answer
         * would be the old per-tenant design back through a side door.
         */
        @Test
        @DisplayName("A window keyed by the tenant instead of the workspace is not a window")
        void tenantKeyedAnswerIsIgnored() {
            oneScopeWithOneBatch(new TestCandidate(1L, null));
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(TENANT, 7));
            when(stepData.deleteByIds(anyCollection())).thenReturn(1);
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            SweepReport report = sweeper(true, false, GO_LIVE, "").sweep();

            assertEquals(0, report.scopesSwept(), "a tenant-keyed answer must not be read as the workspace's window");
            verify(stepData, never()).deleteByIds(anyCollection());
        }

        /**
         * An auth-service outage yields an empty map, which is indistinguishable
         * from "nobody has a finite window". The sweep therefore does nothing,
         * which is the whole point of that wire format.
         */
        @Test
        @DisplayName("An auth outage sweeps nobody rather than everybody")
        void authOutageSweepsNobody() {
            pageOf(new TestScope("org-a", "a"), new TestScope("org-b", "b"), new TestScope("org-c", "c"));
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of());

            SweepReport report = sweeper(true, false, GO_LIVE, "").sweep();

            assertEquals(3, report.scopesConsidered());
            assertEquals(0, report.scopesSwept());
            verify(stepData, never()).deleteByIds(anyCollection());
        }
    }

    @Nested
    @DisplayName("Self-hosted")
    class SelfHosted {

        @Test
        @DisplayName("A configured window applies to every workspace and auth-service is never called")
        void configuredWindowSkipsAuth() {
            oneScopeWithOneBatch(new TestCandidate(1L, UUID.randomUUID()));
            when(stepData.deleteByIds(anyCollection())).thenReturn(1);
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            SweepReport report = sweeper(true, false, GO_LIVE, "45").sweep();

            assertEquals(1, report.stepRowsDeleted());
            verifyNoInteractions(authClient);
        }

        @Test
        @DisplayName("A non-numeric or non-positive window is treated as unset, so cloud rules apply")
        void invalidWindowFallsBackToAuth() {
            oneScopeWithOneBatch(new TestCandidate(1L, UUID.randomUUID()));
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of());

            sweeper(true, false, GO_LIVE, "nonsense").sweep();
            sweeper(true, false, GO_LIVE, "0").sweep();

            verify(authClient, org.mockito.Mockito.times(2)).getLogRetentionDays(any());
        }
    }
}
