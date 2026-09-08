package com.apimarketplace.agent.service.retention;

import com.apimarketplace.agent.repository.ExecutionLogAgentRetentionRepository;
import com.apimarketplace.agent.repository.ExecutionLogAgentRetentionRepository.Scope;
import com.apimarketplace.agent.service.retention.AgentExecutionLogRetentionSweeper.SweepReport;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.storage.retention.ExecutionLogStoragePurger;
import com.apimarketplace.common.storage.retention.ExecutionLogStoragePurger.PurgeOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AgentExecutionLogRetentionSweeper")
class AgentExecutionLogRetentionSweeperTest {

    private static final String GO_LIVE = "2026-09-01T00:00:00Z";
    private static final String ORG = "org-1";
    private static final String OTHER_ORG = "org-2";
    private static final String TENANT = "t-1";
    private static final String OTHER_TENANT = "t-2";
    private static final int BATCH_SIZE = 200;

    private ExecutionLogAgentRetentionRepository repository;
    private ExecutionLogStoragePurger storagePurger;
    private AuthClient authClient;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ExecutionLogAgentRetentionRepository.class);
        storagePurger = mock(ExecutionLogStoragePurger.class);
        authClient = mock(AuthClient.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
    }

    private AgentExecutionLogRetentionSweeper sweeper(boolean enabled, boolean dryRun,
                                                      String enforceFrom, String selfHostedDays) {
        return new AgentExecutionLogRetentionSweeper(repository, storagePurger, authClient,
                transactionTemplate, enabled, dryRun, enforceFrom, selfHostedDays, BATCH_SIZE, 200);
    }

    private record TestScope(String organizationId, String tenantId) implements Scope {
        @Override public String getOrganizationId() { return organizationId; }
        @Override public String getTenantId() { return tenantId; }
    }

    /** A single page holding {@code scopes}, then the end of the scan. */
    private void pageOf(Scope... scopes) {
        when(repository.findScopesWithCandidates(any(), any(), any(), eq(""), eq(""), anyInt()))
                .thenReturn(List.of(scopes));
        Scope last = scopes[scopes.length - 1];
        when(repository.findScopesWithCandidates(any(), any(), any(),
                eq(last.getOrganizationId()), eq(last.getTenantId()), anyInt()))
                .thenReturn(List.of());
    }

    private void oneScopeOneExecution(UUID executionId, List<UUID> payloadIds) {
        pageOf(new TestScope(ORG, TENANT));
        when(repository.findPurgeableExecutionIds(eq(ORG), eq(TENANT), any(), any(), any(), anyInt()))
                .thenReturn(List.of(executionId));
        when(repository.findPayloadIds(anyCollection())).thenReturn(payloadIds);
    }

    @Nested
    @DisplayName("The three switches")
    class Switches {

        @Test
        @DisplayName("Disabled by default: it does not even look for candidates")
        void disabledDoesNothing() {
            SweepReport report = sweeper(false, false, GO_LIVE, "").sweep();

            assertEquals(0, report.scopesConsidered());
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("Enabled with no enforce-from refuses to sweep")
        void missingEnforceFromRefuses() {
            assertEquals(0, sweeper(true, false, "", "").sweep().scopesConsidered());
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("Dry-run reports and deletes nothing from any of the three journal tables")
        void dryRunDeletesNothing() {
            oneScopeOneExecution(UUID.randomUUID(), List.of(UUID.randomUUID()));
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 90));
            when(storagePurger.purgePayloads(anyString(), anyCollection(), eq(true)))
                    .thenReturn(new PurgeOutcome(1, 500L, 0));

            SweepReport report = sweeper(true, true, GO_LIVE, "").sweep();

            assertEquals(1, report.executionsSwept());
            assertTrue(report.dryRun());
            verify(repository, never()).deleteMessages(anyCollection());
            verify(repository, never()).deleteToolCalls(anyCollection());
            verify(repository, never()).deleteIterations(anyCollection());
        }
    }

    @Nested
    @DisplayName("Workspace keying")
    class WorkspaceKeying {

        /**
         * The window is the WORKSPACE's (its owner's plan) and the quota credit is
         * the TENANT's. Two tenants in one workspace: auth is asked once, about
         * the workspace; both scopes are swept under that window; each scope's
         * payloads are purged against its own tenant.
         */
        @Test
        @DisplayName("Two tenants in one workspace share its window; auth is asked about the workspace, the purger about each tenant")
        void windowIsTheWorkspacesAndQuotaIsTheTenants() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            pageOf(new TestScope(ORG, TENANT), new TestScope(ORG, OTHER_TENANT));
            when(repository.findPurgeableExecutionIds(eq(ORG), eq(TENANT), any(), any(), any(), anyInt()))
                    .thenReturn(List.of(first));
            when(repository.findPurgeableExecutionIds(eq(ORG), eq(OTHER_TENANT), any(), any(), any(), anyInt()))
                    .thenReturn(List.of(second));
            when(repository.findPayloadIds(anyCollection())).thenReturn(List.of());
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 30));
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            SweepReport report = sweeper(true, false, GO_LIVE, "").sweep();

            assertEquals(2, report.scopesSwept());
            verify(authClient).getLogRetentionDays(List.of(ORG));
            verify(storagePurger).purgePayloads(eq(TENANT), anyCollection(), eq(false));
            verify(storagePurger).purgePayloads(eq(OTHER_TENANT), anyCollection(), eq(false));
        }

        @Test
        @DisplayName("One tenant across two workspaces gets each workspace's own window, not the widest")
        void eachWorkspaceKeepsItsOwnWindow() {
            pageOf(new TestScope(ORG, TENANT), new TestScope(OTHER_ORG, TENANT));
            when(repository.findPurgeableExecutionIds(eq(ORG), eq(TENANT), any(), any(), any(), anyInt()))
                    .thenReturn(List.of(UUID.randomUUID()));
            when(repository.findPayloadIds(anyCollection())).thenReturn(List.of());
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 90));
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            SweepReport report = sweeper(true, false, GO_LIVE, "").sweep();

            assertEquals(2, report.scopesConsidered());
            assertEquals(1, report.scopesSwept());
            verify(authClient).getLogRetentionDays(List.of(ORG, OTHER_ORG));
            verify(repository, never()).findPurgeableExecutionIds(
                    eq(OTHER_ORG), anyString(), any(), any(), any(), anyInt());
        }

        /**
         * A window answered under the TENANT's key must not be honoured: the wire
         * contract is keyed by workspace, and reading a tenant-keyed answer would be
         * the old per-tenant design back through a side door.
         */
        @Test
        @DisplayName("A window keyed by the tenant instead of the workspace is not a window")
        void tenantKeyedAnswerIsIgnored() {
            oneScopeOneExecution(UUID.randomUUID(), List.of());
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(TENANT, 7));
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            SweepReport report = sweeper(true, false, GO_LIVE, "").sweep();

            assertEquals(0, report.scopesSwept(), "a tenant-keyed answer must not be read as the workspace's window");
            verify(repository, never()).deleteMessages(anyCollection());
        }
    }

    @Nested
    @DisplayName("Deleting a transcript")
    class Deleting {

        /**
         * Nothing else records which storage row a message pointed at, so reading
         * the links after the journal is gone would leave those payloads
         * unreachable in the table forever. This is the reverse of the
         * orchestrator's order, where the referrer is read from the same batch.
         */
        @Test
        @DisplayName("Payload links are read BEFORE the journal that carries them is deleted")
        void payloadIdsAreReadFirst() {
            UUID execution = UUID.randomUUID();
            UUID payload = UUID.randomUUID();
            oneScopeOneExecution(execution, List.of(payload));
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 30));
            when(storagePurger.purgePayloads(anyString(), anyCollection(), eq(false)))
                    .thenReturn(new PurgeOutcome(1, 10L, 0));

            sweeper(true, false, GO_LIVE, "").sweep();

            InOrder order = inOrder(repository, storagePurger);
            order.verify(repository).findPayloadIds(List.of(execution));
            order.verify(repository).deleteMessages(List.of(execution));
            order.verify(storagePurger).purgePayloads(eq(TENANT), eq(List.of(payload)), eq(false));
        }

        @Test
        @DisplayName("All three journal tables go together: a half-deleted transcript is worse than none")
        void allThreeChildTablesAreDeleted() {
            UUID execution = UUID.randomUUID();
            oneScopeOneExecution(execution, List.of());
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 30));
            when(repository.deleteMessages(anyCollection())).thenReturn(12);
            when(repository.deleteToolCalls(anyCollection())).thenReturn(7);
            when(repository.deleteIterations(anyCollection())).thenReturn(3);
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            SweepReport report = sweeper(true, false, GO_LIVE, "").sweep();

            assertEquals(12, report.messagesDeleted());
            assertEquals(7, report.toolCallsDeleted());
            assertEquals(3, report.iterationsDeleted());
        }

        /**
         * The execution row carries status, model, token counts and cost, which
         * metrics and billing read back. Retention drops the bulky transcript, it
         * does not erase that a run happened.
         */
        @Test
        @DisplayName("The agent_executions row itself is never deleted")
        void executionRowSurvives() {
            UUID execution = UUID.randomUUID();
            oneScopeOneExecution(execution, List.of());
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 30));
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            sweeper(true, false, GO_LIVE, "").sweep();

            verify(repository, never()).deleteById(any());
            verify(repository, never()).delete(any());
            verify(repository, never()).deleteAllById(anyCollection());
        }
    }

    @Nested
    @DisplayName("Terminal statuses")
    class TerminalStatuses {

        /**
         * An allow-list, so a status shipped next year counts as still running and
         * its transcript is kept. A deny-list of RUNNING would sweep it by default.
         */
        @Test
        @DisplayName("Only terminal statuses are asked for, and RUNNING is not one of them")
        void onlyTerminalStatusesAreQueried() {
            oneScopeOneExecution(UUID.randomUUID(), List.of());
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 30));
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            sweeper(true, false, GO_LIVE, "").sweep();

            verify(repository).findPurgeableExecutionIds(
                    eq(ORG), eq(TENANT),
                    eq(ExecutionLogAgentRetentionRepository.TERMINAL_STATUSES),
                    any(), any(), anyInt());
            assertTrue(ExecutionLogAgentRetentionRepository.TERMINAL_STATUSES
                    .containsAll(List.of("COMPLETED", "FAILED", "CANCELLED")));
            assertTrue(!ExecutionLogAgentRetentionRepository.TERMINAL_STATUSES.contains("RUNNING"),
                    "a running agent's transcript must never be swept");
        }
    }

    @Nested
    @DisplayName("Wiring")
    class Wiring {

        /**
         * Sibling of the orchestrator's pin, here because agent-service's application
         * class is not on that module's classpath. Both services inject the same
         * purger, and its repository dependency is only instantiated if it sits in a
         * package this app registers with @EnableJpaRepositories; anywhere else the
         * service does not start, while every Mockito assertion stays green.
         */
        @Test
        @DisplayName("agent-service registers the package the retention repository lives in")
        void repositoryPackageIsRegistered() {
            String repositoryPackage = com.apimarketplace.common.storage.repository
                    .ExecutionLogStorageRetentionRepository.class.getPackageName();
            var annotation = com.apimarketplace.agent.AgentServiceApplication.class
                    .getAnnotation(org.springframework.data.jpa.repository.config.EnableJpaRepositories.class);

            assertTrue(annotation != null && java.util.Arrays.stream(annotation.basePackages())
                            .anyMatch(b -> repositoryPackage.equals(b) || repositoryPackage.startsWith(b + ".")),
                    "agent-service would fail to start: nothing instantiates " + repositoryPackage);
        }

        @Test
        @DisplayName("The scheduled sweep holds a named, bounded ShedLock")
        void scheduledSweepIsLocked() throws NoSuchMethodException {
            var scheduled = AgentExecutionLogRetentionSweeper.class.getMethod("scheduledSweep");
            var lock = scheduled.getAnnotation(
                    net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);

            assertTrue(scheduled.getAnnotation(
                    org.springframework.scheduling.annotation.Scheduled.class) != null,
                    "the sweep must stay scheduled");
            assertTrue(lock != null && !lock.name().isBlank() && !lock.lockAtMostFor().isBlank(),
                    "several pods run this service: the sweep needs a named, bounded lock");
        }
    }

    @Nested
    @DisplayName("Termination")
    class Termination {

        /**
         * The sweep KEEPS the agent_executions row, and the candidate query selects
         * on that row. So termination depends entirely on the journal EXISTS clause:
         * without it, a scope holding a full batch is handed the same page forever
         * and the sweeper loops until the pod dies, while a smaller scope is
         * re-swept from scratch every night, issuing no-op deletes and counting them
         * as work. The orchestrator sibling cannot have this bug, because it deletes
         * its own candidate rows.
         *
         * <p>The earlier tests could not see it: they stub a ONE-element batch
         * against a batch size of 200, and a mock repository has no rows to lose. So
         * this one models a full page, and models the repository actually forgetting
         * what was deleted.
         */
        @Test
        @DisplayName("A full batch terminates once its journal is gone, instead of looping forever")
        void fullBatchTerminatesWhenTheJournalDisappears() {
            List<UUID> firstPage = IntStream.range(0, BATCH_SIZE)
                    .mapToObj(i -> UUID.randomUUID()).toList();
            Set<UUID> stillHasJournal = new HashSet<>(firstPage);

            pageOf(new TestScope(ORG, TENANT));
            // The real query only returns executions that still have journal rows.
            when(repository.findPurgeableExecutionIds(eq(ORG), eq(TENANT), any(), any(), any(), anyInt()))
                    .thenAnswer(inv -> firstPage.stream().filter(stillHasJournal::contains).toList());
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of(ORG, 30));
            when(repository.findPayloadIds(anyCollection())).thenReturn(List.of());
            when(repository.deleteMessages(anyCollection())).thenAnswer(inv -> {
                stillHasJournal.removeAll((Collection<?>) inv.getArgument(0));
                return BATCH_SIZE;
            });
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            SweepReport report = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> sweeper(true, false, GO_LIVE, "").sweep(),
                    "the sweep did not terminate: the candidate query must stop returning "
                            + "executions whose journal is already deleted");

            assertEquals(BATCH_SIZE, report.executionsSwept(),
                    "each execution must be swept exactly once, not re-counted every pass");
            verify(repository, times(1)).deleteMessages(anyCollection());
        }
    }

    @Nested
    @DisplayName("Failing long")
    class FailingLong {

        @Test
        @DisplayName("A workspace auth returned no window for is skipped, never defaulted")
        void workspaceWithoutWindowIsSkipped() {
            oneScopeOneExecution(UUID.randomUUID(), List.of());
            when(authClient.getLogRetentionDays(any())).thenReturn(Map.of());

            SweepReport report = sweeper(true, false, GO_LIVE, "").sweep();

            assertEquals(1, report.scopesConsidered());
            assertEquals(0, report.scopesSwept());
            verify(repository, never()).findPurgeableExecutionIds(
                    anyString(), anyString(), any(), any(), any(), anyInt());
            verify(repository, never()).deleteMessages(anyCollection());
        }

        @Test
        @DisplayName("A self-hosted window applies to every workspace and auth-service is never called")
        void selfHostedWindowSkipsAuth() {
            oneScopeOneExecution(UUID.randomUUID(), List.of());
            when(storagePurger.purgePayloads(anyString(), anyCollection(), anyBoolean()))
                    .thenReturn(PurgeOutcome.EMPTY);

            SweepReport report = sweeper(true, false, GO_LIVE, "45").sweep();

            assertEquals(1, report.executionsSwept());
            verifyNoInteractions(authClient);
        }
    }
}
