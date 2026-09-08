package com.apimarketplace.orchestrator.services.retention;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.storage.retention.ExecutionLogStoragePurger;
import com.apimarketplace.common.storage.retention.ExecutionLogStoragePurger.PurgeOutcome;
import com.apimarketplace.orchestrator.persistence.ExecutionLogStepDataRetentionRepository;
import com.apimarketplace.orchestrator.persistence.ExecutionLogStepDataRetentionRepository.Candidate;
import com.apimarketplace.orchestrator.persistence.ExecutionLogStepDataRetentionRepository.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deletes execution journal past each workspace's retention window.
 *
 * <p><b>The window is the WORKSPACE's, and the sweep unit is a scope: one
 * workspace and one tenant within it.</b> Journal rows carry both
 * {@code organization_id} and {@code tenant_id}. The retention window follows
 * the workspace (auth-service answers per organization id, from the plan of the
 * workspace's OWNER), while the storage quota that payload deletion credits back
 * is the tenant's. So the sweeper pages over {@code (organization, tenant)}
 * pairs, asks auth for the distinct workspaces on the page, applies each
 * workspace's window to every scope inside it, and purges each scope's payloads
 * against its own tenant. The first version was keyed by tenant and widened a
 * user's window by the workspaces they were a member of; production showed a
 * STARTER user's own workspace inheriting 90 days from a TEAM workspace they
 * worked in, and the design also shortened a paying workspace's journal the day
 * a member left. Neither can happen when the key is the data's workspace.
 *
 * <p><b>Three independent switches, all of which must be thrown.</b> The feature
 * is off by default, dry-run by default, and does nothing at all until
 * {@code enforce-from} names a date. Any one left at its default means no row is
 * ever deleted. That is deliberate for a job whose mistakes are not recoverable:
 * turning it on should be a decision someone makes three times, not a deploy that
 * happens to pick up a new bean.
 *
 * <p><b>enforce-from is the grandfathering floor.</b> Only journal created at or
 * after that instant is ever swept automatically. Without it the first run would
 * retroactively delete up to four months of history that users could see the day
 * before, including free-tier accounts that have never been told their window is
 * seven days. Clearing the backlog is a separate, announced decision, not
 * something a scheduler does at 3am.
 *
 * <p><b>Order within a batch: step rows first, payloads second.</b>
 * {@code workflow_step_data.output_storage_id} is a reference with no foreign
 * key, so the order decides what a crash leaves behind. Deleting the referrer
 * first can only orphan a payload, which is invisible and reclaimable. The
 * reverse leaves a step in the run view pointing at nothing. Both deletes share
 * one transaction, so the ordinary failure rolls back cleanly and the next sweep
 * retries.
 *
 * <p><b>A workspace with no window is skipped, never defaulted.</b>
 * {@code AuthClient.getLogRetentionDays} answers with the workspaces that HAVE a
 * finite window, so an auth-service outage yields an empty map and the sweep
 * does nothing. See that method for why this direction is the opposite of every
 * other gate in the codebase.
 *
 * <p>Epochs, runs, chat history, the credit ledger and anything backed by object
 * storage are all out of scope. See {@code ExecutionLogRowClasses}.
 */
@Service
public class ExecutionLogRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLogRetentionSweeper.class);

    /**
     * Shortest window any plan can have, used to find candidate scopes before
     * their workspaces' windows are known. Widening the search costs a query;
     * narrowing it would silently skip the workspaces that matter most.
     */
    private static final int SHORTEST_POSSIBLE_WINDOW_DAYS = 7;

    private final ExecutionLogStepDataRetentionRepository stepDataRepository;
    private final ExecutionLogStoragePurger storagePurger;
    private final AuthClient authClient;
    private final TransactionTemplate transactionTemplate;

    private final boolean enabled;
    private final boolean dryRun;
    private final Instant enforceFrom;
    private final Integer selfHostedDays;
    private final int batchSize;
    private final int tenantPageSize;

    public ExecutionLogRetentionSweeper(
            ExecutionLogStepDataRetentionRepository stepDataRepository,
            ExecutionLogStoragePurger storagePurger,
            AuthClient authClient,
            TransactionTemplate transactionTemplate,
            @Value("${retention.execution-logs.enabled:false}") boolean enabled,
            @Value("${retention.execution-logs.dry-run:true}") boolean dryRun,
            @Value("${retention.execution-logs.enforce-from:}") String enforceFrom,
            @Value("${retention.execution-logs.days:}") String selfHostedDays,
            @Value("${retention.execution-logs.batch-size:500}") int batchSize,
            @Value("${retention.execution-logs.tenant-page-size:200}") int tenantPageSize) {
        this.stepDataRepository = stepDataRepository;
        this.storagePurger = storagePurger;
        this.authClient = authClient;
        this.transactionTemplate = transactionTemplate;
        this.enabled = enabled;
        this.dryRun = dryRun;
        this.enforceFrom = parseInstant(enforceFrom);
        this.selfHostedDays = parsePositiveInt(selfHostedDays);
        this.batchSize = Math.max(1, batchSize);
        this.tenantPageSize = Math.max(1, tenantPageSize);
    }

    /**
     * What one whole sweep did, or would have done.
     *
     * @param scopesConsidered (workspace, tenant) pairs holding candidate rows
     * @param scopesSwept      those among them whose workspace had a window and
     *                         that lost at least one row
     */
    public record SweepReport(int scopesConsidered, int scopesSwept, int stepRowsDeleted,
                              int payloadRowsDeleted, long bytesFreed, int payloadRowsRefused,
                              boolean dryRun) {
        static SweepReport skipped(boolean dryRun) {
            return new SweepReport(0, 0, 0, 0, 0L, 0, dryRun);
        }
    }

    /**
     * Nightly sweep, off-peak.
     *
     * <p>03:40 UTC, deliberately not on the hour and not at 03:00 where the
     * database backup runs: a bulk delete competing with {@code pg_dump} would
     * lengthen both. Every guard lives in {@link #sweep()}, so this method is safe
     * to leave scheduled on an install where the feature is off.
     *
     * <p><b>ShedLock is not optional here.</b> The orchestrator runs several pods
     * and they would otherwise all sweep at 03:40: besides the duplicated work,
     * two pods resolving the same payload batch both reach the storage quota
     * ledger, whose decrement is unconditional, so the loser debits bytes it did
     * not delete and {@code used_bytes} drifts below the truth on a sold plan
     * dimension. Same convention as {@code FlagFlipAuditPurgeService}.
     */
    @Scheduled(cron = "0 40 3 * * *", zone = "UTC")
    @SchedulerLock(name = "execution_log_retention_sweep", lockAtMostFor = "PT2H")
    public void scheduledSweep() {
        try {
            sweep();
        } catch (Exception e) {
            // A scheduled method that throws is silently unscheduled by some
            // executors. Retention failing is not worth losing the job over.
            log.error("[ExecutionLogRetention] sweep failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Run one sweep.
     *
     * <p>Public so it can be triggered deliberately (a dry run to read the
     * numbers) rather than only on a schedule.
     */
    public SweepReport sweep() {
        if (!enabled) {
            log.debug("[ExecutionLogRetention] disabled");
            return SweepReport.skipped(dryRun);
        }
        if (enforceFrom == null) {
            log.warn("[ExecutionLogRetention] enabled but retention.execution-logs.enforce-from is "
                    + "unset - refusing to sweep. Set it to the instant the feature goes live; "
                    + "journal older than that is only ever cleared by an announced backlog run.");
            return SweepReport.skipped(dryRun);
        }

        Instant now = Instant.now();
        Instant widestCutoff = now.minus(Duration.ofDays(SHORTEST_POSSIBLE_WINDOW_DAYS));

        int considered = 0;
        int swept = 0;
        int stepRows = 0;
        PurgeOutcome payloads = PurgeOutcome.EMPTY;

        String afterOrganizationId = "";
        String afterTenantId = "";
        while (true) {
            List<Scope> scopes = stepDataRepository.findScopesWithCandidates(
                    widestCutoff, enforceFrom, afterOrganizationId, afterTenantId, tenantPageSize);
            if (scopes.isEmpty()) {
                break;
            }
            considered += scopes.size();

            // One question per WORKSPACE on the page, not per scope: the window is
            // the workspace's, and two tenants inside it share it.
            Map<String, Integer> windows = resolveWindows(workspacesOf(scopes));
            for (Scope scope : scopes) {
                Integer days = windows.get(scope.getOrganizationId());
                if (days == null) {
                    // No finite window for this workspace, or auth could not say. Retain.
                    continue;
                }
                TenantOutcome outcome = sweepScope(scope, now.minus(Duration.ofDays(days)));
                if (outcome.stepRowsDeleted() > 0 || outcome.payloads().rowsDeleted() > 0) {
                    swept++;
                }
                stepRows += outcome.stepRowsDeleted();
                payloads = payloads.plus(outcome.payloads());
            }
            // Keyset on the pair, not offset: a live sweep empties the scopes it
            // just handled out of the candidate set, so an offset would skip an
            // equal number of never-examined scopes on the next page.
            Scope last = scopes.get(scopes.size() - 1);
            afterOrganizationId = last.getOrganizationId();
            afterTenantId = last.getTenantId();
        }

        SweepReport report = new SweepReport(considered, swept, stepRows,
                payloads.rowsDeleted(), payloads.bytesFreed(), payloads.rowsRefused(), dryRun);
        log.info("[ExecutionLogRetention] {} sweep: {} scope(s) (workspace x tenant) considered, {} swept, "
                        + "{} step row(s), {} payload row(s), {} bytes, {} payload(s) refused by the allow-list",
                dryRun ? "DRY-RUN" : "LIVE", considered, swept, stepRows,
                payloads.rowsDeleted(), payloads.bytesFreed(), payloads.rowsRefused());
        return report;
    }

    private record TenantOutcome(int stepRowsDeleted, PurgeOutcome payloads) {
    }

    /** Distinct workspace ids on a page, in page order. */
    private static List<String> workspacesOf(List<Scope> scopes) {
        Set<String> seen = new LinkedHashSet<>();
        for (Scope scope : scopes) {
            seen.add(scope.getOrganizationId());
        }
        return new ArrayList<>(seen);
    }

    private TenantOutcome sweepScope(Scope scope, Instant cutoff) {
        int stepRows = 0;
        PurgeOutcome payloads = PurgeOutcome.EMPTY;

        while (true) {
            List<Candidate> batch = stepDataRepository.findPurgeCandidates(
                    scope.getOrganizationId(), scope.getTenantId(), cutoff, enforceFrom, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            // Payloads are purged against the TENANT: that is whose quota ledger
            // they were booked to, whatever workspace the run happened in.
            TenantOutcome outcome = deleteBatch(scope.getTenantId(), batch);
            stepRows += outcome.stepRowsDeleted();
            payloads = payloads.plus(outcome.payloads());

            if (dryRun) {
                // Nothing was removed, so the same batch would be returned forever.
                // One page is enough to report the shape; the totals below are per
                // batch by design and a dry run is a sample, not a census.
                break;
            }
            if (batch.size() < batchSize) {
                break;
            }
        }
        return new TenantOutcome(stepRows, payloads);
    }

    /**
     * One batch, one transaction. Step rows go first (see class javadoc), then
     * the payloads they pointed at.
     */
    private TenantOutcome deleteBatch(String tenantId, List<Candidate> batch) {
        List<Long> stepIds = new ArrayList<>(batch.size());
        Set<UUID> payloadIds = new LinkedHashSet<>();
        for (Candidate candidate : batch) {
            stepIds.add(candidate.getId());
            if (candidate.getOutputStorageId() != null) {
                payloadIds.add(candidate.getOutputStorageId());
            }
        }
        if (dryRun) {
            return new TenantOutcome(stepIds.size(),
                    storagePurger.purgePayloads(tenantId, payloadIds, true));
        }
        return transactionTemplate.execute(status -> {
            int deletedSteps = stepDataRepository.deleteByIds(stepIds);
            PurgeOutcome outcome = storagePurger.purgePayloads(tenantId, payloadIds, false);
            return new TenantOutcome(deletedSteps, outcome);
        });
    }

    /**
     * Retention windows for the workspaces on a page, keyed by organization id.
     *
     * <p>A self-hosted install has no plans, so a single configured window
     * applies to every workspace and auth-service is never called. With no
     * window configured there, nothing is swept.
     */
    private Map<String, Integer> resolveWindows(List<String> workspaces) {
        if (selfHostedDays != null) {
            return workspaces.stream().collect(
                    java.util.stream.Collectors.toMap(w -> w, w -> selfHostedDays, (a, b) -> a));
        }
        return authClient.getLogRetentionDays(workspaces);
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            log.error("[ExecutionLogRetention] retention.execution-logs.enforce-from is not an ISO "
                    + "instant ('{}') - treating as unset, so nothing will be swept", raw);
            return null;
        }
    }

    private static Integer parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            log.error("[ExecutionLogRetention] retention.execution-logs.days is not a number ('{}') "
                    + "- treating as unset", raw);
            return null;
        }
    }
}
