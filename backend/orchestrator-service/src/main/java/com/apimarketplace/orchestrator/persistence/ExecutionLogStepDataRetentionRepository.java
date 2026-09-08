package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The {@code orchestrator.workflow_step_data} half of execution-log retention.
 *
 * <p><b>The sweep unit is a SCOPE: one workspace ({@code organization_id}) and
 * one tenant within it.</b> The retention window belongs to the workspace (it is
 * the workspace owner's plan, resolved by auth-service), while the storage quota
 * that payload deletion credits back is kept per tenant. Grouping by the pair
 * lets one query serve both: every row in a scope shares the window, and every
 * payload it points at is debited to the right ledger. A workspace with two
 * active members is two scopes with the same window; a user active in two
 * workspaces is two scopes with possibly different windows.
 *
 * <p><b>Rows with a NULL {@code organization_id} are never candidates.</b> The
 * column is NOT NULL in production (0 such rows on 2026-09-02), so this is a
 * guard for older or self-hosted schemas: a row that names no workspace has no
 * window, and no window means retain. In the scope query the guard is enforced
 * twice, by the explicit {@code IS NOT NULL} and by the row-value keyset bound,
 * which a NULL can never satisfy; the explicit predicate is there for the
 * reader, and the real-Postgres test pins the BEHAVIOUR (no scope is formed),
 * not which of the two predicates did it. In the candidate query the equality
 * on {@code :organizationId} excludes NULL on its own.
 *
 * <p><b>A step row is only swept once its epoch is closed.</b> Age alone is the
 * wrong key here: a trigger-driven run sits in {@code WAITING_TRIGGER}
 * indefinitely (production held 853 runs against 80,520 epochs when this was
 * written), so a perfectly live run can carry step rows older than any window,
 * and its pending approvals and signal waits resume against them. Sweeping on age
 * would break the next resume of a workflow whose only fault was being idle.
 *
 * <p><b>The epoch join is by {@code run_id}, not by {@code workflow_run_id}.</b>
 * {@code workflow_epochs} is keyed by the public varchar run id and carries
 * neither a tenant nor an organization column, which is also why epochs
 * themselves are out of scope for this feature.
 *
 * <p><b>{@code entry_type = 'EPOCH_HEADER'} is mandatory in every epoch subquery,
 * and omitting it is silent.</b> That table holds three kinds of row and only the
 * header carries epoch STATE. Measured in production 2026-09-01: 2,933 headers
 * (one per run and epoch, 2,927 of them closed) against 40,247 {@code NODE} and
 * 38,012 {@code EDGE} rows, all of which are structural and sit permanently at
 * {@code is_active = true} with a null {@code closed_at}. A predicate that asks
 * "is any row for this epoch still active" therefore answers YES for every epoch
 * ever created once the filter is dropped, and the sweep quietly becomes a no-op
 * that reports success. It fails in the safe direction, which is exactly why
 * nothing else would ever surface it.
 *
 * <p><b>{@code is_active IS NOT FALSE}, not {@code = true}.</b> The column is
 * NULLABLE in production. Under {@code = true} a header carrying a NULL
 * {@code is_active} and an old {@code closed_at} satisfies none of the
 * disqualifying conditions and its epoch is swept, so an UNKNOWN state would be
 * read as "finished". {@code IS NOT FALSE} keeps NULL on the retaining side,
 * which is where an unknown belongs in a job that deletes.
 */
@Repository
public interface ExecutionLogStepDataRetentionRepository
        extends JpaRepository<WorkflowStepDataEntity, Long> {

    /** Id pair the sweeper needs: the row to delete, and the payload it points at. */
    interface Candidate {
        Long getId();

        UUID getOutputStorageId();
    }

    /** One sweep unit: the workspace whose window applies, and the tenant whose quota is credited. */
    interface Scope {
        String getOrganizationId();

        String getTenantId();
    }

    /**
     * Step rows of one scope whose epoch closed before the cutoff, oldest first.
     *
     * @param organizationId the workspace the rows live in (its window)
     * @param tenantId       the tenant within it (its quota ledger)
     * @param closedBefore   the workspace's retention cutoff
     * @param enforceFrom    grandfathering floor
     */
    @Query(nativeQuery = true, value = """
            SELECT d.id AS id, d.output_storage_id AS outputStorageId
            FROM orchestrator.workflow_step_data d
            WHERE d.organization_id = :organizationId
              AND d.tenant_id = :tenantId
              AND d.start_time >= :enforceFrom
              AND EXISTS (
                    SELECT 1 FROM orchestrator.workflow_epochs k
                    WHERE k.run_id = d.run_id AND k.epoch = d.epoch
                      AND k.entry_type = 'EPOCH_HEADER')
              AND NOT EXISTS (
                    SELECT 1 FROM orchestrator.workflow_epochs a
                    WHERE a.run_id = d.run_id AND a.epoch = d.epoch
                      AND a.entry_type = 'EPOCH_HEADER'
                      AND (a.is_active IS NOT FALSE
                           OR a.closed_at IS NULL
                           OR a.closed_at >= :closedBefore))
            ORDER BY d.id
            LIMIT :batchSize
            """)
    List<Candidate> findPurgeCandidates(@Param("organizationId") String organizationId,
                                        @Param("tenantId") String tenantId,
                                        @Param("closedBefore") Instant closedBefore,
                                        @Param("enforceFrom") Instant enforceFrom,
                                        @Param("batchSize") int batchSize);

    /**
     * Hard delete by explicit id list.
     *
     * <p>Must run BEFORE the storage payloads these rows point at.
     * {@code output_storage_id} is a reference with no foreign key, so the order
     * decides what a crash mid-sweep leaves behind: deleting the referrer first
     * can only orphan a payload (invisible, reclaimed by the next sweep), while
     * the reverse leaves a step visible in the run view pointing at nothing.
     */
    @Modifying
    @Query(nativeQuery = true,
            value = "DELETE FROM orchestrator.workflow_step_data WHERE id IN (:ids)")
    int deleteByIds(@Param("ids") Collection<Long> ids);

    /**
     * Scopes (workspace, tenant) holding any candidate row, paged by KEYSET on the
     * pair, not by OFFSET.
     *
     * <p>A live sweep removes every candidate row of the scopes it just handled,
     * so those scopes drop out of this result set between pages. With
     * {@code OFFSET n} the next page would then skip n scopes that were never
     * looked at: an under-delete that only appears past the first page, converges
     * over later nights, and is invisible to any single-page test.
     *
     * <p>The bound is a row-value comparison on {@code (organization_id,
     * tenant_id)}, which is the same ordering the query sorts by, so the page
     * after {@code (org, tenant)} starts at the next pair exactly. A row with a
     * NULL {@code organization_id} never satisfies the comparison, which is one
     * of two ways such rows are kept out (the explicit {@code IS NOT NULL} is the
     * other, and the one a reader sees).
     *
     * @param afterOrganizationId exclusive lower bound, first component; empty
     *                            string starts the scan
     * @param afterTenantId       exclusive lower bound, second component
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT d.organization_id AS organizationId, d.tenant_id AS tenantId
            FROM orchestrator.workflow_step_data d
            WHERE d.organization_id IS NOT NULL
              AND d.start_time >= :enforceFrom
              AND EXISTS (
                    SELECT 1 FROM orchestrator.workflow_epochs k
                    WHERE k.run_id = d.run_id AND k.epoch = d.epoch
                      AND k.entry_type = 'EPOCH_HEADER')
              AND NOT EXISTS (
                    SELECT 1 FROM orchestrator.workflow_epochs a
                    WHERE a.run_id = d.run_id AND a.epoch = d.epoch
                      AND a.entry_type = 'EPOCH_HEADER'
                      AND (a.is_active IS NOT FALSE
                           OR a.closed_at IS NULL
                           OR a.closed_at >= :closedBefore))
              AND (d.organization_id, d.tenant_id) > (:afterOrganizationId, :afterTenantId)
            ORDER BY d.organization_id, d.tenant_id
            LIMIT :limit
            """)
    List<Scope> findScopesWithCandidates(@Param("closedBefore") Instant closedBefore,
                                         @Param("enforceFrom") Instant enforceFrom,
                                         @Param("afterOrganizationId") String afterOrganizationId,
                                         @Param("afterTenantId") String afterTenantId,
                                         @Param("limit") int limit);
}
