package com.apimarketplace.common.storage.retention;

import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.repository.ExecutionLogStorageRetentionRepository;
import com.apimarketplace.common.storage.repository.ExecutionLogStorageRetentionRepository.Candidate;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Deletes journal payload rows from {@code storage.storage} and keeps the storage
 * quota honest while doing it.
 *
 * <p><b>Only rows that are currently ACTIVE are debited.</b> A row already at
 * {@code status = DELETED} was soft-deleted through {@code StorageService}, which
 * debited the breakdown at that moment; production held 4,465 such STEP_OUTPUT
 * rows on 2026-09-01. Debiting again on the hard delete would drive
 * {@code used_bytes} below the truth, and storage quota is a paid plan dimension,
 * so the drift is billing-adjacent rather than cosmetic. Hard-deleting an
 * already-soft-deleted row must therefore free the disk and touch no counter.
 *
 * <p><b>The debit category is per row, from
 * {@link ExecutionLogRowClasses#breakdownCategoryFor}.</b> An earlier version
 * hardcoded STEP_OUTPUTS on the belief that every allowed class booked there. It
 * does not: {@code StorageService.saveText}, which writes the agent payloads,
 * books FILES unconditionally. Debiting a bucket the row was never credited to
 * leaves the original credit standing, and {@code QuotaService.updateUsage}
 * recomputes the tenant's billed {@code used_bytes} from that breakdown, so the
 * inflation is permanent and silent.
 */
@Service
public class ExecutionLogStoragePurger {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLogStoragePurger.class);

    /** Well under the JDBC 32767 bind-parameter ceiling, with room for the rest. */
    private static final int MAX_IDS_PER_STATEMENT = 2000;

    private final ExecutionLogStorageRetentionRepository repository;
    private final StorageBreakdownService breakdownService;
    private final QuotaOperations quotaService;

    public ExecutionLogStoragePurger(ExecutionLogStorageRetentionRepository repository,
                                     StorageBreakdownService breakdownService,
                                     QuotaOperations quotaService) {
        this.repository = repository;
        this.breakdownService = breakdownService;
        this.quotaService = quotaService;
    }

    /**
     * What a purge did, or would do.
     *
     * @param rowsDeleted rows removed (zero in dry-run, where it is rows matched)
     * @param bytesFreed  their declared size, whether or not the quota was debited
     * @param rowsRefused candidates the allow-list kept, worth watching: a number
     *                    that is not near zero means the referrer is pointing at
     *                    row classes this feature was never meant to reach
     */
    public record PurgeOutcome(int rowsDeleted, long bytesFreed, int rowsRefused) {
        public static final PurgeOutcome EMPTY = new PurgeOutcome(0, 0L, 0);

        public PurgeOutcome plus(PurgeOutcome other) {
            return new PurgeOutcome(rowsDeleted + other.rowsDeleted,
                    bytesFreed + other.bytesFreed,
                    rowsRefused + other.rowsRefused);
        }
    }

    /**
     * Delete the journal payloads among {@code storageIds}, for one tenant.
     *
     * @param dryRun when true, everything is resolved and counted and nothing is
     *               written; the returned counts are what a real run would do
     */
    @Transactional
    public PurgeOutcome purgePayloads(String tenantId, Collection<UUID> storageIds, boolean dryRun) {
        if (tenantId == null || tenantId.isBlank() || storageIds == null || storageIds.isEmpty()) {
            return PurgeOutcome.EMPTY;
        }
        // Chunked because the id list is not bounded by the caller's batch size: one
        // agent execution contributes an id per oversized message and tool result,
        // so a chatty batch of 200 runs can carry thousands. Past 32767 bind
        // parameters the JDBC driver simply refuses the statement.
        if (storageIds.size() > MAX_IDS_PER_STATEMENT) {
            List<UUID> all = new ArrayList<>(storageIds);
            PurgeOutcome total = PurgeOutcome.EMPTY;
            for (int start = 0; start < all.size(); start += MAX_IDS_PER_STATEMENT) {
                List<UUID> chunk = all.subList(start, Math.min(start + MAX_IDS_PER_STATEMENT, all.size()));
                total = total.plus(purgePayloads(tenantId, chunk, dryRun));
            }
            return total;
        }
        List<Candidate> candidates = repository.findCandidatesByIds(tenantId, storageIds);
        if (candidates.isEmpty()) {
            return PurgeOutcome.EMPTY;
        }

        List<UUID> toDelete = new ArrayList<>(candidates.size());
        List<Candidate> toDebit = new ArrayList<>();
        long bytes = 0L;
        int refused = 0;

        for (Candidate row : candidates) {
            if (!ExecutionLogRowClasses.isPurgeableExecutionLog(
                    row.getSourceType(), row.getStorageType(), row.getFileName(), row.getS3Key())) {
                refused++;
                continue;
            }
            toDelete.add(row.getId());
            bytes += row.getSizeBytes() != null ? row.getSizeBytes() : 0;
            if (isActive(row)) {
                toDebit.add(row);
            }
        }

        if (refused > 0) {
            log.info("[ExecutionLogRetention] tenant={} kept {} payload row(s) the allow-list refused",
                    tenantId, refused);
        }
        if (toDelete.isEmpty()) {
            return new PurgeOutcome(0, 0L, refused);
        }
        if (dryRun) {
            return new PurgeOutcome(toDelete.size(), bytes, refused);
        }

        // Delete FIRST, debit second. The ledger decrement is unconditional, so
        // debiting before the delete means a caller that removed nothing still
        // moves the counter. The sweeps hold a ShedLock, so this should not be
        // reachable; it costs one reordering to stop being load-bearing on that.
        int deleted = repository.deleteByIds(toDelete);
        if (deleted == 0) {
            return new PurgeOutcome(0, 0L, refused);
        }

        for (Candidate row : toDebit) {
            int size = row.getSizeBytes() != null ? row.getSizeBytes() : 0;
            // Per row, not one flat category: saveText books FILES and save books
            // STEP_OUTPUTS, and debiting the wrong bucket leaves the original credit
            // standing forever on a billed number.
            String category = ExecutionLogRowClasses.breakdownCategoryFor(
                    row.getSourceType(), row.getStorageType());
            if (category == null) {
                continue;
            }
            breakdownService.trackDelete(row.getTenantId(), category, size,
                    row.getOrganizationId());
        }

        // Both counters, matching StorageService.deleteById, which pairs every
        // trackDelete with a usage refresh. Refreshing only the organization left
        // tenant_storage_quota.used_bytes stale-high forever, and a row with no
        // organization refreshed nothing at all.
        if (!toDebit.isEmpty()) {
            quotaService.updateUsage(tenantId);
            toDebit.stream()
                    .map(Candidate::getOrganizationId)
                    .filter(orgId -> orgId != null && !orgId.isBlank())
                    .distinct()
                    .forEach(quotaService::updateOrganizationUsage);
        }

        return new PurgeOutcome(deleted, bytes, refused);
    }

    /**
     * A row already soft-deleted has been debited once. Anything that is not
     * explicitly ACTIVE is treated as already accounted for, so an unrecognised
     * status errs towards leaving the counter alone rather than debiting twice.
     */
    private boolean isActive(Candidate row) {
        return StorageStatus.ACTIVE.name().equals(row.getStatus());
    }
}
