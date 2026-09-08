package com.apimarketplace.common.storage.retention;

import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.repository.ExecutionLogStorageRetentionRepository;
import com.apimarketplace.common.storage.repository.ExecutionLogStorageRetentionRepository.Candidate;
import com.apimarketplace.common.storage.retention.ExecutionLogStoragePurger.PurgeOutcome;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ExecutionLogStoragePurger")
class ExecutionLogStoragePurgerTest {

    private static final String TENANT = "t-1";
    private static final String ORG = "org-1";

    private ExecutionLogStorageRetentionRepository repository;
    private StorageBreakdownService breakdownService;
    private QuotaOperations quotaService;
    private ExecutionLogStoragePurger purger;

    @BeforeEach
    void setUp() {
        repository = mock(ExecutionLogStorageRetentionRepository.class);
        breakdownService = mock(StorageBreakdownService.class);
        quotaService = mock(QuotaOperations.class);
        purger = new ExecutionLogStoragePurger(repository, breakdownService, quotaService);
    }

    /**
     * Plain implementation rather than a mock: building a mock inside a
     * {@code thenReturn(...)} argument is nested stubbing, which Mockito rejects
     * with "unfinished stubbing" at an unrelated line.
     */
    private record TestCandidate(UUID id, String sourceType, String storageType, String fileName,
                                 String s3Key, Integer sizeBytes, String status, String tenantId,
                                 String organizationId) implements Candidate {
        @Override public UUID getId() { return id; }
        @Override public String getSourceType() { return sourceType; }
        @Override public String getStorageType() { return storageType; }
        @Override public String getFileName() { return fileName; }
        @Override public String getS3Key() { return s3Key; }
        @Override public Integer getSizeBytes() { return sizeBytes; }
        @Override public String getStatus() { return status; }
        @Override public String getTenantId() { return tenantId; }
        @Override public String getOrganizationId() { return organizationId; }
    }

    private static Candidate row(UUID id, String sourceType, String storageType, String fileName,
                                 String s3Key, Integer size, StorageStatus status) {
        return new TestCandidate(id, sourceType, storageType, fileName, s3Key, size,
                status.name(), TENANT, ORG);
    }

    private static Candidate journalRow(UUID id, Integer size, StorageStatus status) {
        return row(id, "STEP_OUTPUT", "JSON", null, null, size, status);
    }

    @Nested
    @DisplayName("Quota accounting")
    class QuotaAccounting {

        @Test
        @DisplayName("An ACTIVE row is debited once, in the STEP_OUTPUTS bucket it was credited to")
        void activeRowIsDebitedOnce() {
            UUID id = UUID.randomUUID();
            when(repository.findCandidatesByIds(eq(TENANT), anyCollection()))
                    .thenReturn(List.of(journalRow(id, 2048, StorageStatus.ACTIVE)));
            when(repository.deleteByIds(anyCollection())).thenReturn(1);

            PurgeOutcome outcome = purger.purgePayloads(TENANT, Set.of(id), false);

            assertEquals(1, outcome.rowsDeleted());
            assertEquals(2048L, outcome.bytesFreed());
            verify(breakdownService).trackDelete(TENANT, "STEP_OUTPUTS", 2048L, ORG);
            verify(quotaService).updateOrganizationUsage(ORG);
        }

        /**
         * StorageService.deleteById pairs every trackDelete with a TENANT usage
         * refresh. Refreshing only the organization left tenant_storage_quota
         * .used_bytes stale-high forever, and a row with no organization refreshed
         * nothing at all.
         */
        @Test
        @DisplayName("Both counters are refreshed, tenant as well as organization")
        void tenantQuotaIsRefreshedToo() {
            UUID id = UUID.randomUUID();
            when(repository.findCandidatesByIds(eq(TENANT), anyCollection()))
                    .thenReturn(List.of(journalRow(id, 64, StorageStatus.ACTIVE)));
            when(repository.deleteByIds(anyCollection())).thenReturn(1);

            purger.purgePayloads(TENANT, Set.of(id), false);

            verify(quotaService).updateUsage(TENANT);
            verify(quotaService).updateOrganizationUsage(ORG);
        }

        /**
         * The ledger decrement is unconditional, so debiting before the delete let a
         * caller that removed nothing still move the counter. The sweeps hold a
         * ShedLock, but the ordering means that is no longer what keeps the counter
         * honest.
         */
        @Test
        @DisplayName("A delete that removed nothing debits nothing")
        void noDeleteMeansNoDebit() {
            UUID id = UUID.randomUUID();
            when(repository.findCandidatesByIds(eq(TENANT), anyCollection()))
                    .thenReturn(List.of(journalRow(id, 1234, StorageStatus.ACTIVE)));
            when(repository.deleteByIds(anyCollection())).thenReturn(0);

            PurgeOutcome outcome = purger.purgePayloads(TENANT, Set.of(id), false);

            assertEquals(0, outcome.rowsDeleted());
            assertEquals(0L, outcome.bytesFreed(), "bytes must not be claimed for rows still present");
            verifyNoInteractions(breakdownService);
            verifyNoInteractions(quotaService);
        }

        /**
         * The category is NOT uniform across the allowed classes, which the first
         * version of this class assumed. {@code StorageService.save} picks by source
         * type, so a JSON step output books STEP_OUTPUTS; {@code saveText}, which
         * writes the agent payloads, hardcodes FILES. Debiting one flat bucket left
         * the other credit standing, and used_bytes is recomputed FROM the
         * breakdown, so the inflation would have been permanent and billed.
         */
        @Test
        @DisplayName("Each row is debited from the bucket it was credited to, not from one flat category")
        void debitFollowsTheCreditingWritePath() {
            UUID json = UUID.randomUUID();
            UUID text = UUID.randomUUID();
            when(repository.findCandidatesByIds(eq(TENANT), anyCollection())).thenReturn(List.of(
                    journalRow(json, 100, StorageStatus.ACTIVE),
                    row(text, null, "TEXT", "agent_message.txt", null, 250, StorageStatus.ACTIVE)));
            when(repository.deleteByIds(anyCollection())).thenReturn(2);

            purger.purgePayloads(TENANT, Set.of(json, text), false);

            verify(breakdownService).trackDelete(TENANT, "STEP_OUTPUTS", 100L, ORG);
            verify(breakdownService).trackDelete(TENANT, "FILES", 250L, ORG);
        }

        /**
         * The counter-drift trap. A row already at DELETED was soft-deleted through
         * StorageService, which debited it then; production held 4,465 such
         * STEP_OUTPUT rows. Debiting again would push used_bytes below the truth,
         * and storage quota is a sold plan dimension.
         */
        @Test
        @DisplayName("An already soft-deleted row frees disk without being debited a second time")
        void softDeletedRowIsNotDebitedAgain() {
            UUID id = UUID.randomUUID();
            when(repository.findCandidatesByIds(eq(TENANT), anyCollection()))
                    .thenReturn(List.of(journalRow(id, 4096, StorageStatus.DELETED)));
            when(repository.deleteByIds(anyCollection())).thenReturn(1);

            PurgeOutcome outcome = purger.purgePayloads(TENANT, Set.of(id), false);

            assertEquals(1, outcome.rowsDeleted(), "the row must still be removed");
            assertEquals(4096L, outcome.bytesFreed(), "the disk is still freed");
            verifyNoInteractions(breakdownService);
            verifyNoInteractions(quotaService);
        }

        @Test
        @DisplayName("A mixed batch debits only the active rows and refreshes the org once")
        void mixedBatchDebitsOnlyActiveRows() {
            UUID active = UUID.randomUUID();
            UUID alreadyGone = UUID.randomUUID();
            when(repository.findCandidatesByIds(eq(TENANT), anyCollection())).thenReturn(List.of(
                    journalRow(active, 100, StorageStatus.ACTIVE),
                    journalRow(alreadyGone, 900, StorageStatus.DELETED)));
            when(repository.deleteByIds(anyCollection())).thenReturn(2);

            PurgeOutcome outcome = purger.purgePayloads(TENANT, Set.of(active, alreadyGone), false);

            assertEquals(2, outcome.rowsDeleted());
            assertEquals(1000L, outcome.bytesFreed());
            verify(breakdownService, times(1)).trackDelete(anyString(), anyString(), anyLong(), anyString());
            verify(breakdownService).trackDelete(TENANT, "STEP_OUTPUTS", 100L, ORG);
            verify(quotaService, times(1)).updateOrganizationUsage(ORG);
        }
    }

    @Nested
    @DisplayName("The allow-list still rules here")
    class AllowListRules {

        /**
         * output_storage_id has no foreign key, so a step row can point at anything.
         * The class check is repeated on every row the query returns rather than
         * trusted from the referrer.
         */
        @Test
        @DisplayName("A row the allow-list refuses is counted, never deleted, never debited")
        void refusedRowIsKept() {
            UUID id = UUID.randomUUID();
            when(repository.findCandidatesByIds(eq(TENANT), anyCollection()))
                    .thenReturn(List.of(row(id, "CHAT_ATTACHMENT", "BINARY", "photo.png", null,
                            5000, StorageStatus.ACTIVE)));

            PurgeOutcome outcome = purger.purgePayloads(TENANT, Set.of(id), false);

            assertEquals(0, outcome.rowsDeleted());
            assertEquals(1, outcome.rowsRefused());
            verify(repository, never()).deleteByIds(anyCollection());
            verifyNoInteractions(breakdownService);
        }

        @Test
        @DisplayName("Refused rows do not stop the journal rows in the same batch")
        void refusedRowDoesNotBlockTheBatch() {
            UUID journal = UUID.randomUUID();
            UUID avatar = UUID.randomUUID();
            when(repository.findCandidatesByIds(eq(TENANT), anyCollection())).thenReturn(List.of(
                    journalRow(journal, 10, StorageStatus.ACTIVE),
                    row(avatar, "USER_AVATAR", "BINARY", null, null, 999, StorageStatus.ACTIVE)));
            when(repository.deleteByIds(anyCollection())).thenReturn(1);

            PurgeOutcome outcome = purger.purgePayloads(TENANT, Set.of(journal, avatar), false);

            assertEquals(1, outcome.rowsDeleted());
            assertEquals(1, outcome.rowsRefused());
            assertEquals(10L, outcome.bytesFreed(), "the refused row's bytes must not be counted");
        }
    }

    @Nested
    @DisplayName("Dry run")
    class DryRun {

        @Test
        @DisplayName("Reports what it would delete and writes nothing at all")
        void dryRunWritesNothing() {
            UUID id = UUID.randomUUID();
            when(repository.findCandidatesByIds(eq(TENANT), anyCollection()))
                    .thenReturn(List.of(journalRow(id, 777, StorageStatus.ACTIVE)));

            PurgeOutcome outcome = purger.purgePayloads(TENANT, Set.of(id), true);

            assertEquals(1, outcome.rowsDeleted());
            assertEquals(777L, outcome.bytesFreed());
            verify(repository, never()).deleteByIds(anyCollection());
            verifyNoInteractions(breakdownService);
            verifyNoInteractions(quotaService);
        }
    }

    @Nested
    @DisplayName("Chunking")
    class Chunking {

        /**
         * The id list is not bounded by the caller's batch size: one agent execution
         * contributes an id per oversized message and tool result, so a chatty batch
         * of 200 runs can carry thousands. Past 32767 bind parameters the driver
         * refuses the statement outright.
         */
        @Test
        @DisplayName("An oversized id list is split, and every chunk's outcome is accumulated")
        void oversizedListIsSplitAndAccumulated() {
            Set<UUID> ids = IntStream.range(0, 4500)
                    .mapToObj(i -> UUID.randomUUID())
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            // One journal row per chunk, so the totals must add up across chunks.
            when(repository.findCandidatesByIds(eq(TENANT), anyCollection()))
                    .thenAnswer(inv -> List.of(journalRow(UUID.randomUUID(), 10, StorageStatus.ACTIVE)));
            when(repository.deleteByIds(anyCollection())).thenReturn(1);

            PurgeOutcome outcome = purger.purgePayloads(TENANT, ids, false);

            // ceil(4500 / 2000) = 3 chunks
            verify(repository, times(3)).findCandidatesByIds(eq(TENANT), anyCollection());
            assertEquals(3, outcome.rowsDeleted());
            assertEquals(30L, outcome.bytesFreed());
        }

        @Test
        @DisplayName("No chunk exceeds the bind-parameter budget")
        void noChunkExceedsTheBudget() {
            Set<UUID> ids = IntStream.range(0, 4500)
                    .mapToObj(i -> UUID.randomUUID())
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            when(repository.findCandidatesByIds(eq(TENANT), anyCollection())).thenReturn(List.of());

            purger.purgePayloads(TENANT, ids, true);

            ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(repository, times(3)).findCandidatesByIds(eq(TENANT), captor.capture());
            for (Collection<UUID> chunk : captor.getAllValues()) {
                assertTrue(chunk.size() <= 2000, "a chunk of " + chunk.size() + " can exceed the JDBC cap");
            }
        }
    }

    @Nested
    @DisplayName("Degenerate input")
    class DegenerateInput {

        @Test
        @DisplayName("A blank tenant or an empty id set never reaches the database")
        void nothingToDoNeverQueries() {
            assertEquals(PurgeOutcome.EMPTY, purger.purgePayloads("  ", Set.of(UUID.randomUUID()), false));
            assertEquals(PurgeOutcome.EMPTY, purger.purgePayloads(TENANT, Set.of(), false));
            assertEquals(PurgeOutcome.EMPTY, purger.purgePayloads(TENANT, null, false));
            verify(repository, never()).findCandidatesByIds(anyString(), anyCollection());
        }

        @Test
        @DisplayName("A null size counts as zero rather than failing the batch")
        void nullSizeIsZero() {
            UUID id = UUID.randomUUID();
            Candidate c = journalRow(id, null, StorageStatus.ACTIVE);
            when(repository.findCandidatesByIds(eq(TENANT), anyCollection())).thenReturn(List.of(c));
            when(repository.deleteByIds(anyCollection())).thenReturn(1);

            PurgeOutcome outcome = purger.purgePayloads(TENANT, Set.of(id), false);

            assertEquals(1, outcome.rowsDeleted());
            assertEquals(0L, outcome.bytesFreed());
            verify(breakdownService).trackDelete(TENANT, "STEP_OUTPUTS", 0L, ORG);
        }
    }
}
