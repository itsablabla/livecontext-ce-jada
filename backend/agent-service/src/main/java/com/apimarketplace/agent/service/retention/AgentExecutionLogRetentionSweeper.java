package com.apimarketplace.agent.service.retention;

import com.apimarketplace.agent.repository.ExecutionLogAgentRetentionRepository;
import com.apimarketplace.agent.repository.ExecutionLogAgentRetentionRepository.Scope;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.storage.retention.ExecutionLogStoragePurger;
import com.apimarketplace.common.storage.retention.ExecutionLogStoragePurger.PurgeOutcome;
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
 * Deletes agent transcripts past each workspace's retention window: the message
 * and tool-call journal of terminated agent runs, plus the out-of-row text
 * payloads they point at.
 *
 * <p>Sibling of the orchestrator's {@code ExecutionLogRetentionSweeper}, with the
 * same three switches, the same grandfathering floor, the same fail-long plan
 * resolution, the same allow-listed payload classes and the same sweep unit: a
 * scope of one workspace and one tenant, the window being the WORKSPACE's (its
 * owner's plan) and the quota credit the tenant's. Two differences, both forced
 * by the data:
 *
 * <ul>
 *   <li><b>The unit is an EXECUTION, not a row.</b> Half a transcript is worse
 *       than none, so a run's messages, tool calls and iterations go together.</li>
 *   <li><b>Payload ids are collected BEFORE the journal is deleted.</b> Nothing
 *       else records the link, so a payload whose referrer is already gone is
 *       unreachable and would sit in the table forever. This reverses the
 *       orchestrator's order, where the referrer carries the id and can be read
 *       from the same batch.</li>
 * </ul>
 *
 * <p>The {@code agent_executions} row itself is deliberately KEPT. It is 23 MB
 * against the journal's 113 MB, and it holds the status, model, token counts,
 * cost and timings that metrics and billing read back. Retention is about the
 * bulky transcript, not about erasing that a run happened.
 */
@Service
public class AgentExecutionLogRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionLogRetentionSweeper.class);

    /** Shortest window any plan can have; see the orchestrator sibling. */
    private static final int SHORTEST_POSSIBLE_WINDOW_DAYS = 7;

    private final ExecutionLogAgentRetentionRepository repository;
    private final ExecutionLogStoragePurger storagePurger;
    private final AuthClient authClient;
    private final TransactionTemplate transactionTemplate;

    private final boolean enabled;
    private final boolean dryRun;
    private final Instant enforceFrom;
    private final Integer selfHostedDays;
    private final int batchSize;
    private final int tenantPageSize;

    public AgentExecutionLogRetentionSweeper(
            ExecutionLogAgentRetentionRepository repository,
            ExecutionLogStoragePurger storagePurger,
            AuthClient authClient,
            TransactionTemplate transactionTemplate,
            @Value("${retention.execution-logs.enabled:false}") boolean enabled,
            @Value("${retention.execution-logs.dry-run:true}") boolean dryRun,
            @Value("${retention.execution-logs.enforce-from:}") String enforceFrom,
            @Value("${retention.execution-logs.days:}") String selfHostedDays,
            @Value("${retention.execution-logs.batch-size:200}") int batchSize,
            @Value("${retention.execution-logs.tenant-page-size:200}") int tenantPageSize) {
        this.repository = repository;
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

    /** What one whole sweep did, or would have done; scopes are (workspace, tenant) pairs. */
    public record SweepReport(int scopesConsidered, int scopesSwept, int executionsSwept,
                              int messagesDeleted, int toolCallsDeleted, int iterationsDeleted,
                              int payloadRowsDeleted, long bytesFreed, boolean dryRun) {
        static SweepReport skipped(boolean dryRun) {
            return new SweepReport(0, 0, 0, 0, 0, 0, 0, 0L, dryRun);
        }
    }

    /**
     * Nightly, 03:50 UTC.
     *
     * <p>Ten minutes after the orchestrator's sweep rather than alongside it: both
     * delete from {@code storage.storage}, and there is no reason to have them
     * contending on the same table on a four-core database box.
     */
    @Scheduled(cron = "0 50 3 * * *", zone = "UTC")
    @SchedulerLock(name = "agent_execution_log_retention_sweep", lockAtMostFor = "PT2H")
    public void scheduledSweep() {
        try {
            sweep();
        } catch (Exception e) {
            log.error("[AgentLogRetention] sweep failed: {}", e.getMessage(), e);
        }
    }

    public SweepReport sweep() {
        if (!enabled) {
            log.debug("[AgentLogRetention] disabled");
            return SweepReport.skipped(dryRun);
        }
        if (enforceFrom == null) {
            log.warn("[AgentLogRetention] enabled but retention.execution-logs.enforce-from is unset "
                    + "- refusing to sweep.");
            return SweepReport.skipped(dryRun);
        }

        Instant now = Instant.now();
        Instant widestCutoff = now.minus(Duration.ofDays(SHORTEST_POSSIBLE_WINDOW_DAYS));
        var terminal = ExecutionLogAgentRetentionRepository.TERMINAL_STATUSES;

        int considered = 0;
        int swept = 0;
        Totals totals = Totals.EMPTY;

        String afterOrganizationId = "";
        String afterTenantId = "";
        while (true) {
            List<Scope> scopes = repository.findScopesWithCandidates(
                    terminal, widestCutoff, enforceFrom, afterOrganizationId, afterTenantId, tenantPageSize);
            if (scopes.isEmpty()) {
                break;
            }
            considered += scopes.size();

            // One question per WORKSPACE on the page: the window is the workspace's.
            Map<String, Integer> windows = resolveWindows(workspacesOf(scopes));
            for (Scope scope : scopes) {
                Integer days = windows.get(scope.getOrganizationId());
                if (days == null) {
                    continue;
                }
                Totals scopeTotals = sweepScope(scope, now.minus(Duration.ofDays(days)), terminal);
                if (scopeTotals.executions() > 0) {
                    swept++;
                }
                totals = totals.plus(scopeTotals);
            }
            // Keyset on the pair, not offset: see the orchestrator sibling.
            Scope last = scopes.get(scopes.size() - 1);
            afterOrganizationId = last.getOrganizationId();
            afterTenantId = last.getTenantId();
        }

        SweepReport report = new SweepReport(considered, swept, totals.executions(),
                totals.messages(), totals.toolCalls(), totals.iterations(),
                totals.payloads(), totals.bytes(), dryRun);
        log.info("[AgentLogRetention] {} sweep: {} scope(s) (workspace x tenant) considered, {} swept, {} execution(s), "
                        + "{} message(s), {} tool call(s), {} iteration(s), {} payload(s), {} bytes",
                dryRun ? "DRY-RUN" : "LIVE", considered, swept, totals.executions(),
                totals.messages(), totals.toolCalls(), totals.iterations(),
                totals.payloads(), totals.bytes());
        return report;
    }

    private record Totals(int executions, int messages, int toolCalls, int iterations,
                          int payloads, long bytes) {
        static final Totals EMPTY = new Totals(0, 0, 0, 0, 0, 0L);

        Totals plus(Totals o) {
            return new Totals(executions + o.executions, messages + o.messages,
                    toolCalls + o.toolCalls, iterations + o.iterations,
                    payloads + o.payloads, bytes + o.bytes);
        }
    }

    /** Distinct workspace ids on a page, in page order. */
    private static List<String> workspacesOf(List<Scope> scopes) {
        Set<String> seen = new LinkedHashSet<>();
        for (Scope scope : scopes) {
            seen.add(scope.getOrganizationId());
        }
        return new ArrayList<>(seen);
    }

    private Totals sweepScope(Scope scope, Instant cutoff, Set<String> terminal) {
        Totals totals = Totals.EMPTY;
        while (true) {
            List<UUID> executionIds = repository.findPurgeableExecutionIds(
                    scope.getOrganizationId(), scope.getTenantId(), terminal, cutoff, enforceFrom, batchSize);
            if (executionIds.isEmpty()) {
                break;
            }
            // Payloads are purged against the TENANT whose quota booked them.
            totals = totals.plus(deleteBatch(scope.getTenantId(), executionIds));

            if (dryRun) {
                // Nothing was removed, so the same batch would come back forever.
                break;
            }
            if (executionIds.size() < batchSize) {
                break;
            }
        }
        return totals;
    }

    private Totals deleteBatch(String tenantId, List<UUID> executionIds) {
        // Read the payload links first, while the rows that carry them still exist.
        List<UUID> payloadIds = repository.findPayloadIds(executionIds);

        if (dryRun) {
            PurgeOutcome outcome = storagePurger.purgePayloads(tenantId, payloadIds, true);
            return new Totals(executionIds.size(), 0, 0, 0,
                    outcome.rowsDeleted(), outcome.bytesFreed());
        }
        return transactionTemplate.execute(status -> {
            int messages = repository.deleteMessages(executionIds);
            int toolCalls = repository.deleteToolCalls(executionIds);
            int iterations = repository.deleteIterations(executionIds);
            PurgeOutcome outcome = storagePurger.purgePayloads(tenantId, payloadIds, false);
            return new Totals(executionIds.size(), messages, toolCalls, iterations,
                    outcome.rowsDeleted(), outcome.bytesFreed());
        });
    }

    /** Windows for the workspaces on a page, keyed by organization id. */
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
            log.error("[AgentLogRetention] retention.execution-logs.enforce-from is not an ISO instant "
                    + "('{}') - treating as unset, so nothing will be swept", raw);
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
            log.error("[AgentLogRetention] retention.execution-logs.days is not a number ('{}') "
                    + "- treating as unset", raw);
            return null;
        }
    }
}
