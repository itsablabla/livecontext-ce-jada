package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code agent} half of execution-log retention: an agent run's message and
 * tool-call journal, and the out-of-row payloads it points at.
 *
 * <p><b>The sweep unit is a SCOPE: one workspace ({@code organization_id}) and
 * one tenant within it.</b> The window is the workspace's (its owner's plan);
 * the storage quota credited back by payload deletion is the tenant's. See the
 * orchestrator sibling for why the pair, and why a NULL {@code organization_id}
 * is never a candidate (the explicit {@code IS NOT NULL} in the scope query is
 * redundant with the row-value bound and kept for the reader; the Postgres test
 * pins the behaviour, not the predicate).
 *
 * <p><b>Only executions in a TERMINAL status are swept, by allow-list.</b>
 * {@code agent_executions.status} is a plain string defaulting to
 * {@code RUNNING}, so there is no enum to lean on. Listing the terminal values
 * means a status invented later is treated as still-running and its journal is
 * kept, which is the safe direction; a deny-list of {@code RUNNING} would sweep
 * anything new by default. Production held only COMPLETED, FAILED and CANCELLED
 * on 2026-09-01, but the guard is about what ships next year.
 *
 * <p><b>{@code ended_at} is the age key, not {@code created_at}.</b> A long agent
 * run started before the window and finished inside it is still recent work.
 *
 * <p>Deletion is by EXECUTION id rather than by row age: messages and tool calls
 * of one run are a single unit, and half a transcript is worse than none.
 */
@Repository
public interface ExecutionLogAgentRetentionRepository extends JpaRepository<AgentExecutionEntity, UUID> {

    /**
     * Statuses an execution can be in and never resume from.
     *
     * <p>Anything else, including a value added after this was written, counts as
     * live and is retained.
     */
    Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED");

    /** One sweep unit: the workspace whose window applies, and the tenant whose quota is credited. */
    interface Scope {
        String getOrganizationId();

        String getTenantId();
    }

    /**
     * Executions of one scope whose journal may be deleted, oldest completion first.
     *
     * <p><b>The journal EXISTS check is what terminates the sweep.</b> This query
     * selects on the {@code agent_executions} row, which the sweep deliberately
     * KEEPS, so without it nothing the sweep deletes ever leaves the candidate set:
     * a scope with at least a full batch of purgeable executions would be handed
     * the same page forever and the sweeper would loop until the pod died, while a
     * smaller scope would be re-swept from scratch every night, issuing no-op
     * deletes and reporting them as work. The orchestrator sibling has no such
     * clause because it deletes its own candidate rows.
     */
    @Query(nativeQuery = true, value = """
            SELECT e.id
            FROM agent.agent_executions e
            WHERE e.organization_id = :organizationId
              AND e.tenant_id = :tenantId
              AND e.status IN (:terminalStatuses)
              AND e.ended_at IS NOT NULL
              AND e.ended_at < :endedBefore
              AND e.created_at >= :enforceFrom
              AND EXISTS (
                    SELECT 1 FROM agent.agent_execution_messages m WHERE m.execution_id = e.id
                    UNION ALL
                    SELECT 1 FROM agent.agent_execution_tool_calls c WHERE c.execution_id = e.id
                    UNION ALL
                    SELECT 1 FROM agent.agent_execution_iterations i WHERE i.execution_id = e.id)
            ORDER BY e.ended_at
            LIMIT :batchSize
            """)
    List<UUID> findPurgeableExecutionIds(@Param("organizationId") String organizationId,
                                         @Param("tenantId") String tenantId,
                                         @Param("terminalStatuses") Collection<String> terminalStatuses,
                                         @Param("endedBefore") Instant endedBefore,
                                         @Param("enforceFrom") Instant enforceFrom,
                                         @Param("batchSize") int batchSize);

    /**
     * Scopes (workspace, tenant) holding any purgeable execution, paged by KEYSET
     * on the pair, not by OFFSET.
     *
     * <p>A live sweep empties the scopes it handles out of this result set, so an
     * OFFSET would skip an equal number of never-examined scopes on the next page.
     * The bound is a row-value comparison on the same pair the query orders by.
     *
     * @param afterOrganizationId exclusive lower bound, first component; empty
     *                            string starts the scan
     * @param afterTenantId       exclusive lower bound, second component
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT e.organization_id AS organizationId, e.tenant_id AS tenantId
            FROM agent.agent_executions e
            WHERE e.organization_id IS NOT NULL
              AND e.status IN (:terminalStatuses)
              AND e.ended_at IS NOT NULL
              AND e.ended_at < :endedBefore
              AND e.created_at >= :enforceFrom
              AND (e.organization_id, e.tenant_id) > (:afterOrganizationId, :afterTenantId)
              AND EXISTS (
                    SELECT 1 FROM agent.agent_execution_messages m WHERE m.execution_id = e.id
                    UNION ALL
                    SELECT 1 FROM agent.agent_execution_tool_calls c WHERE c.execution_id = e.id
                    UNION ALL
                    SELECT 1 FROM agent.agent_execution_iterations i WHERE i.execution_id = e.id)
            ORDER BY e.organization_id, e.tenant_id
            LIMIT :limit
            """)
    List<Scope> findScopesWithCandidates(@Param("terminalStatuses") Collection<String> terminalStatuses,
                                         @Param("endedBefore") Instant endedBefore,
                                         @Param("enforceFrom") Instant enforceFrom,
                                         @Param("afterOrganizationId") String afterOrganizationId,
                                         @Param("afterTenantId") String afterTenantId,
                                         @Param("limit") int limit);

    /**
     * Out-of-row payload ids referenced by these executions, from both journal
     * tables at once.
     *
     * <p>Collected BEFORE the journal rows are deleted: nothing else records the
     * link, so a payload whose referrer is already gone is unreachable and would
     * sit in the table forever.
     */
    @Query(nativeQuery = true, value = """
            SELECT m.content_storage_id FROM agent.agent_execution_messages m
             WHERE m.execution_id IN (:executionIds) AND m.content_storage_id IS NOT NULL
            UNION
            SELECT t.content_storage_id FROM agent.agent_execution_tool_calls t
             WHERE t.execution_id IN (:executionIds) AND t.content_storage_id IS NOT NULL
            """)
    List<UUID> findPayloadIds(@Param("executionIds") Collection<UUID> executionIds);

    @Modifying
    @Query(nativeQuery = true,
            value = "DELETE FROM agent.agent_execution_messages WHERE execution_id IN (:executionIds)")
    int deleteMessages(@Param("executionIds") Collection<UUID> executionIds);

    @Modifying
    @Query(nativeQuery = true,
            value = "DELETE FROM agent.agent_execution_tool_calls WHERE execution_id IN (:executionIds)")
    int deleteToolCalls(@Param("executionIds") Collection<UUID> executionIds);

    /**
     * The third child table, easy to miss and pointless to leave behind.
     *
     * <p>Only 4 MB against the journal's 113 MB, so it is not why this feature
     * exists, but skipping it would strand 22,473 rows whose parent transcript no
     * longer exists. {@code deleteExecutionsByAgent} in
     * {@code AgentMetricsAggregationRepository} deletes the same three tables, and
     * is the reason to expect a fourth one day: check that method when adding
     * anything here.
     */
    @Modifying
    @Query(nativeQuery = true,
            value = "DELETE FROM agent.agent_execution_iterations WHERE execution_id IN (:executionIds)")
    int deleteIterations(@Param("executionIds") Collection<UUID> executionIds);
}
