package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository pour la gestion des workflows
 */
@Repository
public interface WorkflowRepository extends JpaRepository<WorkflowEntity, UUID> {
    
    
    /**
     * Lightweight projection of the workflow's organization_id only.
     * Used by {@code WorkflowOwnershipResolver} to answer
     * {@code /api/internal/orchestrator/owner/workflow/{id}} without
     * hydrating the full entity (no plan JSONB load).
     *
     * <p>Post-V261 (2026-05-19): every workflow row carries a non-null
     * {@code organization_id} (personal workspaces use the user's default
     * personal org). The legacy {@code IS NOT NULL} guard against pre-V261
     * NULL rows is dropped - the projection now returns the org id for any
     * existing workflow.
     */
    @Query("SELECT w.organizationId FROM WorkflowEntity w WHERE w.id = :id")
    Optional<String> findOrganizationIdById(@Param("id") UUID id);

    /**
     * Scalar projection of the pin, for the execution-time version resolver.
     *
     * <p>{@code findById} would hydrate the whole {@link WorkflowEntity}, including
     * several {@code @JdbcTypeCode(SqlTypes.JSON)} columns (the full plan among
     * them). On the resolver's {@code REQUIRES_NEW} call sites that is a real JSONB
     * fetch + deserialize per fire, epoch and step, with no L1 cache to absorb it.
     * On the call sites that already hold a managed entity this projection is
     * instead one extra round-trip; only the pin number is ever needed.
     *
     * <p>Returns empty both when the workflow does not exist and when it is
     * unpinned; the resolver treats the two identically (no pin to honour).
     */
    @Query("SELECT w.pinnedVersion FROM WorkflowEntity w WHERE w.id = :id")
    Optional<Integer> findPinnedVersionById(@Param("id") UUID id);

    /**
     * Phase 2b - batch (id, name) pairs for a set of workflow ids. Used by the Storage Explorer
     * controller to resolve the display name of VIRTUAL workflow folders without hydrating the full
     * entity (no plan JSONB load). Each row is an {@code Object[]} of {@code [UUID id, String name]}.
     * Workflow name resolution lives in the orchestrator (the storage boundary must not query the
     * workflows table).
     */
    @Query("SELECT w.id, w.name FROM WorkflowEntity w WHERE w.id IN :ids")
    List<Object[]> findIdNamePairs(@Param("ids") Collection<UUID> ids);

    /**
     * Every {@code core:sub_workflow} edge declared by the workspace's plans, as
     * {@code [UUID parentId, String childWorkflowId]} rows.
     *
     * <p>A sub-workflow call is stored in the plan as
     * {@code cores[] {type: "sub_workflow", subWorkflow: {workflowId: "<uuid>"}}}, which is the ONLY
     * shape this reads - the same one the publication snapshot walker collects
     * ({@code WorkflowPublicationService#collectCoreSubWorkflowIds}). There is no relation column and
     * no relation table: the plan is the source of truth, so the edges are derived from it on read
     * and a plan save can never leave a stale relation behind.
     *
     * <p>Both workflow types are returned (an APPLICATION-type row is a workflow too and can both
     * call and be called), and the whole workspace is scanned because the PARENT direction is not
     * knowable from the child's own plan - only from every other plan that names it.
     *
     * <p>The {@code @>} containment test is a pre-filter, not a duplicate of the per-element
     * {@code type} test below it: it discards a whole plan before the lateral expands its cores, and
     * is the one predicate here that a GIN index on {@code plan} could serve. Containment against a
     * missing or non-array {@code cores} is false/unknown, never an error.
     *
     * <p>{@code jsonb_typeof} guards the lateral: {@code jsonb_array_elements} raises on a non-array,
     * so a plan whose {@code cores} is absent, null or (malformed) an object degrades to zero rows
     * instead of failing the whole query for the workspace.
     *
     * <p>Scope is the strict org match used by {@code WorkflowManagementService#listWorkflows}; the
     * caller still has to subtract its own per-member restrictions
     * ({@code OrgAccessGuard#getRestrictedResourceIds}), which this query knows nothing about.
     */
    @Query(value = "SELECT w.id AS parent_id, c->'subWorkflow'->>'workflowId' AS child_id "
            + "FROM workflows w "
            + "CROSS JOIN LATERAL jsonb_array_elements("
            + "  CASE WHEN jsonb_typeof(w.plan->'cores') = 'array' THEN w.plan->'cores' ELSE '[]'::jsonb END) AS c "
            + "WHERE w.is_active = true "
            + "  AND w.organization_id = :orgId "
            + "  AND w.plan->'cores' @> '[{\"type\": \"sub_workflow\"}]'::jsonb "
            + "  AND c->>'type' = 'sub_workflow' "
            + "  AND c->'subWorkflow'->>'workflowId' IS NOT NULL "
            + "  AND c->'subWorkflow'->>'workflowId' <> '__self__'", nativeQuery = true)
    List<Object[]> findSubWorkflowEdgesByOrganization(@Param("orgId") String orgId);

    /**
     * Batch (id, name) pairs restricted to ONE organization - the scoped sibling of
     * {@link #findIdNamePairs}, which resolves any id it is handed.
     *
     * <p>Relation names go over the wire, so a child id that a plan names but that lives in another
     * workspace must resolve to NOTHING rather than to its real name. Filtering in the query (not in
     * Java) is what makes that a non-decision at the call site.
     */
    @Query("SELECT w.id, w.name FROM WorkflowEntity w "
            + "WHERE w.id IN :ids AND w.organizationId = :orgId AND w.isActive = true")
    List<Object[]> findIdNamePairsInOrganization(@Param("ids") Collection<UUID> ids,
                                                 @Param("orgId") String orgId);

    /**
     * Batch (id, pinnedVersion) pairs for a set of workflow ids - the Applications page reads each
     * card's pinned version to draw the Live/Active badge, and used to fire one
     * {@code /v2/workflows/dag/{id}/versions} request PER card (an N+1 over ~200). Each row is an
     * {@code Object[]} of {@code [UUID id, Integer pinnedVersion]}; {@code pinnedVersion} is null for
     * an unpinned (Inactive) workflow, and a workflow id absent from the result reads as "load failed"
     * on the client (badge hidden). Lightweight scalar projection - no plan JSONB hydration.
     *
     * <p>Returns the scope columns ({@code tenantId, organizationId}) alongside the version so the
     * caller can apply {@code ScopeGuard.isInStrictScope} - the SAME strict-workspace predicate the
     * per-card {@code /versions} endpoint this replaces used ({@code WorkflowVersionController#verifyOwnership}).
     * Each row is {@code [UUID id, Integer pinnedVersion, String tenantId, String organizationId,
     * BigDecimal budgetCredits, String budgetPeriodMode, BigDecimal budgetPeriodSpent,
     * Instant budgetPeriodStartedAt]}.
     * Filtering in Java (not SQL) reuses the one canonical scope helper instead of duplicating its
     * two-branch predicate, and keeps the projection trivially testable.
     *
     * <p>The budget columns ride along so an application card can show what it is
     * costing without a second request: this batch already resolves one row per
     * card, and the alternative was an N+1 the page exists to avoid.
     */
    @Query("SELECT w.id, w.pinnedVersion, w.tenantId, w.organizationId, "
            + "w.budgetCredits, w.budgetPeriodMode, w.budgetPeriodSpent, w.budgetPeriodStartedAt "
            + "FROM WorkflowEntity w WHERE w.id IN :ids")
    List<Object[]> findPinnedVersionScopeRows(@Param("ids") Collection<UUID> ids);

    /**
     * BATCH-B (2026-05-20) - strict-org listing of every workflow in an org workspace.
     * Pairs with the orphaned tenant-only {@link #findByTenantId(String)} which leaked
     * cross-org rows when a user belonged to multiple orgs sharing the same userId.
     */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId")
    List<WorkflowEntity> findByOrganizationIdStrict(@Param("orgId") String orgId);

    /**
     * @deprecated Use {@link #findByOrganizationIdStrict(String)}. Kept only because
     * sibling services still reference it via the legacy method name.
     * <p>BATCH-B (2026-05-20): all orchestrator-service callers rerouted.
     */
    @Deprecated
    List<WorkflowEntity> findByTenantId(String tenantId);

    /**
     * PR30 - strict-org workflow list. WorkflowEntity.organization_id was added by
     * V209 (PR15 chain). Pre-PR30 the list endpoint used the tenant-only finder
     * and returned cross-scope workflows when a tenant belonged to multiple orgs.
     *
     * <p>Post-V261 (2026-05-19): every user-scoped workflow row carries a non-null
     * {@code organization_id} (gateway always injects {@code X-Organization-ID},
     * personal workspaces use the user's default personal org). The companion
     * {@code findByTenantIdAndOrganizationIdIsNullOrderByCreatedAtDesc} was removed
     * - callers in personal scope now resolve the personal org id and route through
     * this strict-org finder.
     */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId ORDER BY w.createdAt DESC")
    List<WorkflowEntity> findByOrganizationIdStrictOrderByCreatedAtDesc(@Param("orgId") String orgId);

    /** PR30 - strict-org active workflow list. */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId AND w.isActive = true ORDER BY w.createdAt DESC")
    List<WorkflowEntity> findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(@Param("orgId") String orgId);
    
    /**
     * Trouve tous les workflows actifs
     */
    List<WorkflowEntity> findByIsActiveTrue();

    // ===== Recent-activity aggregator (V234 partial indexes back these) =====

    /**
     * Top-N workflows (including APPLICATION subtype) in an org workspace
     * ordered by last edit time. Used by the orchestrator's
     * {@code RecentActivityAggregatorService} own-DB branch covering BOTH
     * WORKFLOW and APPLICATION row kinds via the {@code WorkflowType}
     * discriminator on the result. Backed by the V234 partial index
     * {@code idx_workflows_org_updated_at}.
     */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId ORDER BY w.updatedAt DESC")
    List<WorkflowEntity> findRecentByOrganizationIdStrict(@Param("orgId") String orgId, Pageable pageable);

    /**
     * Stamp {@code lastExecutedAt} for a single workflow without loading the entity.
     *
     * <p>{@link com.apimarketplace.orchestrator.trigger.ReusableTriggerService} stamps
     * this on every reusable-trigger fire, but used to call
     * {@code run.getWorkflow().setLastExecutedAt(...); workflowRepository.save(entity)}
     * - which forced lazy proxy initialization on {@code WorkflowRunEntity.workflow}
     * ({@code FetchType.LAZY}). When the surrounding Hibernate session was already
     * closed (schedule-spread async path), the proxy initialization threw
     * {@code LazyInitializationException: Could not initialize proxy ... - no session}
     * and {@code lastExecutedAt} was silently lost - observed 13×/day in prod for
     * scheduled workflows (regression introduced 2026-05-05 commit 2a083618b7).
     *
     * <p>The bulk-update form sidesteps the proxy entirely by issuing a direct
     * {@code UPDATE} keyed by id. {@code @Transactional} ensures the JPA executor
     * has a session for the {@code @Modifying} query even when the caller is not in
     * an active transaction (the original failure mode).
     */
    @Modifying
    @Transactional
    // JPQL bulk @Modifying bypasses @PreUpdate on WorkflowEntity, so the
    // Activity bell tab (orders by workflows.updated_at DESC) won't see this
    // fire unless we explicitly SET it here too. Mirror of the agent JPQL
    // fix in AgentExecutionRepository.incrementCounters.
    @Query("UPDATE WorkflowEntity w SET w.lastExecutedAt = :ts, w.updatedAt = :ts WHERE w.id = :id")
    int updateLastExecutedAt(@Param("id") UUID id, @Param("ts") Instant ts);
    
    /**
     * Compte le nombre de workflows d'un tenant.
     * <p>Only the integration test references this in-tree; the strict-org variant
     * {@link #countByOrganizationIdStrict(String)} is the runtime path. Kept as
     * the test fixture builder for tenant-only assertions but no production caller.
     */
    long countByTenantId(String tenantId);

    /** PR30 - strict-org workflow count for quota display. */
    @Query("SELECT COUNT(w) FROM WorkflowEntity w WHERE w.organizationId = :orgId")
    long countByOrganizationIdStrict(@Param("orgId") String orgId);

    /** PR30 - strict-org active workflow count. */
    @Query("SELECT COUNT(w) FROM WorkflowEntity w WHERE w.organizationId = :orgId AND w.isActive = true")
    long countByOrganizationIdStrictAndIsActiveTrue(@Param("orgId") String orgId);
    
    
    /**
     * Trouve les workflows par nom et tenant (recherche partielle)
     */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.tenantId = :tenantId AND w.name ILIKE %:name%")
    List<WorkflowEntity> findByTenantIdAndNameContainingIgnoreCase(@Param("tenantId") String tenantId,
                                                                  @Param("name") String name);

    /**
     * BATCH-B (2026-05-20) - strict-org partial name search. Replaces
     * {@link #findByTenantIdAndNameContainingIgnoreCase(String, String)} for the MCP
     * agent workflow builder (used by load-by-name). The tenant-only finder leaked
     * workflow names across orgs when a single user belonged to multiple workspaces.
     */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId AND w.name ILIKE %:name%")
    List<WorkflowEntity> findByOrganizationIdAndNameContainingIgnoreCaseStrict(@Param("orgId") String orgId,
                                                                              @Param("name") String name);
    
    
    /**
     * Debug: Trouve les workflows avec un tenantId similaire (pour debug)
     * Utilise une recherche avec LIKE pour trouver des correspondances partielles.
     * <p>Debug-only helper invoked by {@code WorkflowListController.logDebugInfo}
     * when an empty-result query triggers diagnostic logging. Retained because
     * removing it would lose the on-prod tenantId-encoding troubleshooting path
     * (URL-encoded vs raw tenantId mismatch) it was built for. No production code
     * path consumes it.
     */
    @Query(value = "SELECT * FROM workflows WHERE tenant_id LIKE CONCAT('%', :pattern, '%') ORDER BY created_at DESC", nativeQuery = true)
    List<WorkflowEntity> findByTenantIdContaining(@Param("pattern") String pattern);
    
    /**
     * Debug: Trouve tous les tenantIds uniques (pour debug)
     */
    @Query(value = "SELECT DISTINCT tenant_id FROM workflows ORDER BY tenant_id", nativeQuery = true)
    List<String> findAllDistinctTenantIds();

    /**
     * Find workflows that have a workflow trigger referencing a specific parent workflow.
     * This is used to find downstream workflows that should be triggered when a parent workflow completes.
     *
     * The query searches for workflows where the plan contains a trigger with:
     * - type = "workflow"
     * - id = parentWorkflowId
     *
     * @param parentWorkflowId The ID of the parent workflow (as string UUID)
     * @return List of workflows that are triggered by the parent workflow
     */
    @Query(value = "SELECT * FROM workflows w WHERE w.is_active = true AND EXISTS (" +
           "SELECT 1 FROM jsonb_array_elements(w.plan->'triggers') AS t " +
           "WHERE t->>'type' = 'workflow' AND t->>'id' = :parentWorkflowId)", nativeQuery = true)
    List<WorkflowEntity> findByWorkflowTrigger(@Param("parentWorkflowId") String parentWorkflowId);

    /**
     * Find workflows that have an error trigger referencing a specific parent workflow.
     * This is used to find error handler workflows that should be triggered when a parent workflow fails.
     *
     * The query searches for workflows where the plan contains a trigger with:
     * - type = "error"
     * - id = parentWorkflowId
     *
     * @param parentWorkflowId The ID of the parent workflow (as string UUID)
     * @return List of workflows that handle errors from the parent workflow
     */
    @Query(value = "SELECT * FROM workflows w WHERE w.is_active = true AND EXISTS (" +
           "SELECT 1 FROM jsonb_array_elements(w.plan->'triggers') AS t " +
           "WHERE t->>'type' = 'error' AND t->>'id' = :parentWorkflowId)", nativeQuery = true)
    List<WorkflowEntity> findByErrorTrigger(@Param("parentWorkflowId") String parentWorkflowId);

    /**
     * Find acquired APPLICATION roots (from marketplace) for an organization
     * workspace. Typed to APPLICATION on purpose: since the V268 invariant
     * (one APPLICATION root per (org, publication)), acquire also clones
     * sub-workflow children as standard WORKFLOW rows that carry the same
     * sourcePublicationId - listing them here would surface one Applications
     * card per child and let "Remove" target a child instead of the root.
     */
    @Query("""
            SELECT w FROM WorkflowEntity w
            WHERE w.organizationId = :organizationId
              AND w.sourcePublicationId IS NOT NULL
              AND w.workflowType = :type
            ORDER BY w.acquiredAt DESC
            """)
    List<WorkflowEntity> findAcquiredByOrganizationId(
            @Param("organizationId") String organizationId,
            @Param("type") WorkflowEntity.WorkflowType type);

    /**
     * Find acquired workflows by tenant for the legacy PERSONAL scope only
     * ({@code organization_id IS NULL}).
     *
     * <p>2026-05-21 fix - InternalPublicationSupportController.getAcquiredWorkflows
     * declared {@code organizationId} as required=true. When the frontend was on a
     * scope where no org header propagated (legacy paths / direct API access),
     * Spring 404'd and the Applications page showed zero acquired apps.
     *
     * <p>2026-05-21 audit-A MEDIUM follow-up - earlier version of this finder
     * filtered by {@code tenantId} only, which leaked acquisitions across all
     * orgs the user belongs to (multi-org user in personal fallback saw the
     * union). Tightened to {@code organization_id IS NULL} so the personal
     * fallback returns ONLY legacy personal acquisitions, never org-scoped
     * rows. Multi-org users now see an empty list in personal fallback
     * (correct - they have no personal-scope acquisitions, only org-scoped
     * ones which require the org header).
     */
    @Query("""
            SELECT w FROM WorkflowEntity w
            WHERE w.tenantId = :tenantId
              AND w.organizationId IS NULL
              AND w.sourcePublicationId IS NOT NULL
              AND w.workflowType = :type
            ORDER BY w.acquiredAt DESC
            """)
    List<WorkflowEntity> findAcquiredByTenantId(
            @Param("tenantId") String tenantId,
            @Param("type") WorkflowEntity.WorkflowType type);

    /**
     * Check if a tenant already acquired a specific publication.
     */
    boolean existsByTenantIdAndSourcePublicationId(String tenantId, UUID sourcePublicationId);

    /**
     * Check if an organization workspace already acquired a specific publication.
     */
    boolean existsByOrganizationIdAndSourcePublicationId(String organizationId, UUID sourcePublicationId);

    /**
     * Typed "already acquired" probes - an acquisition exists iff its
     * APPLICATION root row does. The untyped variants above also match
     * sub-workflow children (WORKFLOW rows tagged with the same publication),
     * which would permanently block re-acquisition after a failed acquire
     * left orphan clones behind.
     */
    boolean existsByTenantIdAndSourcePublicationIdAndWorkflowType(
            String tenantId, UUID sourcePublicationId, WorkflowEntity.WorkflowType workflowType);

    boolean existsByOrganizationIdAndSourcePublicationIdAndWorkflowType(
            String organizationId, UUID sourcePublicationId, WorkflowEntity.WorkflowType workflowType);

    /**
     * Find the decoupled editable WORKFLOW twin previously created from an acquired
     * APPLICATION, if any. The lineage lives in {@code metadata} because a twin
     * deliberately carries {@code source_publication_id = NULL} (that is what keeps it out
     * of every My-Applications / execute / uninstall lookup and exempt from the V268
     * unique index).
     *
     * <p>Used to make "create my editable copy" IDEMPOTENT: a second request returns the
     * existing twin instead of cloning a second full set of interfaces / tables / agents.
     * Matches EITHER lineage key: the application clone id (exact, same install) OR the
     * publication id, because uninstalling and reinstalling gives the application a NEW
     * clone id while the user's existing copy is still theirs - keying on the clone alone
     * would hand them a second resource set after a reinstall.
     *
     * <p>Native (the lineage keys are inside a jsonb column) and scoped to the org
     * workspace (post-V261 every row carries one), so another workspace's copy is never
     * handed back.
     */
    @Query(value = "SELECT * FROM workflows w "
            + "WHERE w.organization_id = :organizationId "
            + "  AND w.workflow_type = 'WORKFLOW' "
            + "  AND w.source_publication_id IS NULL "
            + "  AND (w.metadata->>'duplicatedFromApplicationId' = :applicationWorkflowId "
            + "       OR (CAST(:publicationId AS text) IS NOT NULL "
            + "           AND w.metadata->>'duplicatedFromPublicationId' = CAST(:publicationId AS text))) "
            + "ORDER BY w.created_at DESC LIMIT 1", nativeQuery = true)
    Optional<WorkflowEntity> findEditableDuplicateOfApplication(
            @Param("organizationId") String organizationId,
            @Param("applicationWorkflowId") String applicationWorkflowId,
            @Param("publicationId") String publicationId);

    /**
     * Find workflows by tenant filtered by workflow type, ordered by update date descending.
     * <p>Retained only as the personal-scope fallback path for
     * {@link com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderLoader}.
     * All other callers must use the strict-org variant
     * {@link #findByOrganizationIdAndWorkflowTypeOrderByUpdatedAtDescStrict(String, WorkflowEntity.WorkflowType)}.
     */
    List<WorkflowEntity> findByTenantIdAndWorkflowTypeOrderByUpdatedAtDesc(
            String tenantId, WorkflowEntity.WorkflowType workflowType);

    /**
     * BATCH-B (2026-05-20) - strict-org workflow listing filtered by workflow type.
     * Replaces {@link #findByTenantIdAndWorkflowTypeOrderByUpdatedAtDesc} for the
     * MCP agent builder loader so the agent only sees workflows belonging to the
     * current workspace.
     */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId AND w.workflowType = :workflowType ORDER BY w.updatedAt DESC")
    List<WorkflowEntity> findByOrganizationIdAndWorkflowTypeOrderByUpdatedAtDescStrict(
            @Param("orgId") String orgId,
            @Param("workflowType") WorkflowEntity.WorkflowType workflowType);

    /**
     * Find workflows belonging to an organization or owned by a specific user, filtered by type.
     */
    // Post-V261 (2026-05-19): every workflow row carries a non-null organization_id
    // (personal workspaces resolve to the user's default personal org), so the
    // legacy {@code OR w.tenantId = :userId} branch was dead code that also bled
    // cross-workspace rows owned by the caller across orgs they're in. Strict-org
    // match closes that leak. Signature preserved so callers still pass userId
    // (ignored) - Phase 11 will rename + drop the param.
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId AND w.workflowType = :type ORDER BY w.updatedAt DESC")
    List<WorkflowEntity> findByOrganizationOrOwnerAndType(
            @Param("orgId") String orgId, @Param("userId") String userId,
            @Param("type") WorkflowEntity.WorkflowType type);

    @Query("""
            SELECT w FROM WorkflowEntity w
            WHERE w.organizationId = :organizationId
              AND w.sourcePublicationId = :pubId
              AND w.workflowType = :type
            """)
    Optional<WorkflowEntity> findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
            @Param("organizationId") String organizationId, @Param("pubId") UUID sourcePublicationId,
            @Param("type") WorkflowEntity.WorkflowType type);

    @Query("""
            SELECT w FROM WorkflowEntity w
            WHERE w.organizationId = :organizationId
              AND w.sourcePublicationId = :pubId
            """)
    List<WorkflowEntity> findAllByOrganizationIdAndSourcePublicationId(
            @Param("organizationId") String organizationId, @Param("pubId") UUID sourcePublicationId);

    /**
     * Find workflows assigned to a specific project.
     */
    List<WorkflowEntity> findByProjectId(UUID projectId);

    List<WorkflowEntity> findByProjectIdAndOrganizationId(UUID projectId, String organizationId);

    /**
     * Count workflows assigned to a specific project.
     */
    long countByProjectId(UUID projectId);

    long countByProjectIdAndOrganizationId(UUID projectId, String organizationId);

    /**
     * Find every workflow whose persisted plan still references a given
     * interface id under {@code plan.interfaces[].id}. Used by the
     * interface-deletion cascade to scrub dangling references before the
     * row in {@code interface.interfaces} is removed.
     */
    @Query(value = "SELECT * FROM workflows w WHERE EXISTS (" +
           "SELECT 1 FROM jsonb_array_elements(COALESCE(w.plan->'interfaces', '[]'::jsonb)) AS i " +
           "WHERE i->>'id' = :interfaceId)", nativeQuery = true)
    List<WorkflowEntity> findByPlanInterfaceId(@Param("interfaceId") String interfaceId);

    // ===================== List folders (V448) =====================

    /**
     * File the given workflows into {@code folderId} ({@code null} = back to the top
     * level) inside ONE workspace. The organization predicate is the scope check itself,
     * so a caller can never re-file a row belonging to another workspace, and the count
     * returned tells the caller how many of the requested ids were actually theirs.
     *
     * <p>Deliberately does NOT touch {@code updatedAt}: filing a workflow is a change to
     * the list's organisation, not to the workflow, and the folder tiles order themselves
     * on the newest change INSIDE them - bumping it would make every re-filed workflow
     * look freshly edited.
     */
    @Modifying
    @Transactional
    @Query("UPDATE WorkflowEntity w SET w.folderId = :folderId "
            + "WHERE w.id IN :ids AND w.organizationId = :organizationId")
    int assignFolderForOrganization(@Param("ids") Collection<UUID> ids,
                                    @Param("folderId") UUID folderId,
                                    @Param("organizationId") String organizationId);

    /** Personal-workspace counterpart of {@link #assignFolderForOrganization}. */
    @Modifying
    @Transactional
    @Query("UPDATE WorkflowEntity w SET w.folderId = :folderId "
            + "WHERE w.id IN :ids AND w.tenantId = :ownerId AND w.organizationId IS NULL")
    int assignFolderForOwner(@Param("ids") Collection<UUID> ids,
                             @Param("folderId") UUID folderId,
                             @Param("ownerId") String ownerId);

    /**
     * Empty the given folders: their workflows go back to the top level. Called when the
     * folders are deleted - a folder never deletes what it holds.
     */
    @Modifying
    @Transactional
    @Query("UPDATE WorkflowEntity w SET w.folderId = null "
            + "WHERE w.folderId IN :folderIds AND w.organizationId = :organizationId")
    int clearFolderForOrganization(@Param("folderIds") Collection<UUID> folderIds,
                                   @Param("organizationId") String organizationId);

    /** Personal-workspace counterpart of {@link #clearFolderForOrganization}. */
    @Modifying
    @Transactional
    @Query("UPDATE WorkflowEntity w SET w.folderId = null "
            + "WHERE w.folderId IN :folderIds AND w.tenantId = :ownerId AND w.organizationId IS NULL")
    int clearFolderForOwner(@Param("folderIds") Collection<UUID> folderIds,
                            @Param("ownerId") String ownerId);

    /**
     * Accumulate what a governed fire spent onto the workflow's period budget,
     * rolling the period over in the same statement when it has expired. For
     * the modes that DO reset (monthly, weekly).
     *
     * <p>{@code periodStart} is the start of the period the caller computed for
     * "now" ({@code WorkflowBudgetPeriod.periodStart}). Passing it in rather
     * than computing it in SQL keeps ONE rollover rule, in Java, unit-testable
     * without a database, and shared by the guard and the epoch gate that read
     * the same columns.
     *
     * <p>Reset and add happen in a single UPDATE on purpose: doing them as a
     * read-then-write in application code would let two agent notifications
     * crossing a period boundary either double-reset (losing the first cost of
     * the new period) or skip the reset entirely.
     *
     * <p>The second CASE only ever moves the marker FORWARD. Two pods whose
     * clocks straddle a period boundary would otherwise take turns resetting
     * each other's period, and the counter would sit near zero for as long as
     * the skew lasts - a cap that never fires. Refusing to move it backwards
     * costs at most a slight over-count in the new period, which errs towards
     * enforcing the cap rather than towards spending.
     *
     * <p>No {@code cast(... as timestamptz)} on the bind, and that is the direct
     * pay-off of the split below: a cast was only ever needed to tell Postgres
     * the type of a NULL, and there is no null here any more. Removing it also
     * makes the statement executable on H2, so the branch logic below can be
     * covered by a real SQL test instead of a mock that agrees with whatever the
     * author assumed (see {@code WorkflowBudgetPeriodRepositoryIntegrationTest}).
     *
     * <p><b>Separate from the cumulative statement on purpose, and NOT one
     * statement with a nullable parameter.</b> Binding a null timestamp is a
     * known production trap here: Postgres cannot infer a null parameter's type
     * in a prepared statement, H2 accepts the same SQL happily, and this repo
     * has already shipped exactly that bug once (see {@code BudgetResolver}) -
     * green unit tests, then a failure on the first real reset in production.
     * The failure would have been invisible too, swallowed by the accumulator's
     * caller, leaving cumulative caps silently inert. Two statements, each with
     * only non-null binds, remove the hazard instead of testing for it.
     *
     * <p>Does NOT touch {@code updated_at}: a cost increment is bookkeeping, not
     * a user edit, and bumping it would reshuffle every list sorted by "last
     * modified" on every agent call.
     *
     * @return rows updated (0 = workflow deleted meanwhile)
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE workflows
        SET budget_period_spent = CASE
                WHEN budget_period_started_at IS NULL
                     OR budget_period_started_at < :periodStart
                    THEN cast(:credits as numeric)
                ELSE budget_period_spent + cast(:credits as numeric)
            END,
            budget_period_started_at = CASE
                WHEN budget_period_started_at IS NOT NULL
                     AND budget_period_started_at > :periodStart
                    THEN budget_period_started_at
                ELSE :periodStart
            END
        WHERE id = cast(:workflowId as uuid)
        """, nativeQuery = true)
    int incrementBudgetPeriodSpendWithReset(@Param("workflowId") UUID workflowId,
                                            @Param("credits") java.math.BigDecimal credits,
                                            @Param("periodStart") Instant periodStart);

    /**
     * Accumulate onto a CUMULATIVE budget, which never resets: a plain add, and
     * the period marker is left exactly as it is.
     *
     * <p>See {@link #incrementBudgetPeriodSpendWithReset} for why this is its
     * own statement rather than the same one with a null period start.
     *
     * @return rows updated (0 = workflow deleted meanwhile)
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE workflows
        SET budget_period_spent = budget_period_spent + cast(:credits as numeric)
        WHERE id = cast(:workflowId as uuid)
        """, nativeQuery = true)
    int incrementBudgetPeriodSpendCumulative(@Param("workflowId") UUID workflowId,
                                             @Param("credits") java.math.BigDecimal credits);

    /**
     * Read back the period spend committed by
     * {@link #incrementBudgetPeriodSpendWithReset} (or its cumulative
     * sibling), from inside the same transaction.
     *
     * <p>Needed because the value cannot be derived in application code. Two
     * settles racing (routine inside a split, where several agent items settle
     * in parallel) both read the same stored figure BEFORE the update, so both
     * compute the same "fresh" total and each of them under-reports. The row is
     * serialised by the UPDATE's lock, so re-reading it afterwards returns the
     * true post-increment value, including any increment that committed while
     * this one waited. That matters twice: it is the figure the run bar shows,
     * and it is what decides whether this settle is the one that crossed the
     * cap. Derived arithmetic makes two racing settles BOTH conclude they are
     * still under, and the crossing notification is then lost for good.
     */
    @Query(value = "SELECT budget_period_spent FROM workflows "
            + "WHERE id = cast(:workflowId as uuid)", nativeQuery = true)
    Optional<java.math.BigDecimal> findBudgetPeriodSpentById(@Param("workflowId") UUID workflowId);

    /**
     * Start the spending cap's period over: zero the counter and stamp it with
     * {@code periodStart}.
     *
     * <p>Called when the user CHANGES the terms of the cap, and only then: when
     * a cap appears on a workflow that had none, and when the reset cadence
     * changes. Both of those would otherwise be traps rather than settings.
     * Setting a first cap would judge it against spend accumulated while no cap
     * existed, so a workflow that had quietly spent 50 credits this month would
     * be refused the instant its owner prudently capped it at 10, with no way to
     * clear the counter and no fire possible until the period rolled over.
     * Changing the cadence would re-file an existing figure under a period it
     * does not belong to: the same stored number means "this month" under
     * monthly and "for all time" under cumulative, so a monthly-to-cumulative
     * switch would silently promote one month's spend to the lifetime total, and
     * the reverse would leave a lifetime total to be spent again every month.
     *
     * <p>Deliberately NOT called on an ordinary save. A rename must never reset
     * anyone's spending counter.
     *
     * <p>A separate statement because the period columns are mapped
     * {@code insertable=false, updatable=false} on the entity - that fence is
     * what stops a stale in-memory copy from clobbering live spend on every
     * unrelated save, so the few writes that ARE legitimate go through explicit
     * SQL like this one.
     *
     * @return rows updated (0 = workflow deleted meanwhile)
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE workflows
        SET budget_period_spent = 0,
            budget_period_started_at = :periodStart
        WHERE id = cast(:workflowId as uuid)
        """, nativeQuery = true)
    int resetBudgetPeriodAt(@Param("workflowId") UUID workflowId,
                            @Param("periodStart") Instant periodStart);

    /**
     * Start a CUMULATIVE cap over: zero the counter and clear the period marker,
     * which a lifetime cap has no use for.
     *
     * <p>Its own statement, with a literal NULL rather than a null bind, for the
     * same reason the increment is split in two: Postgres cannot infer the type
     * of a null parameter, and this repo has already shipped that bug once.
     *
     * @return rows updated (0 = workflow deleted meanwhile)
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE workflows
        SET budget_period_spent = 0,
            budget_period_started_at = NULL
        WHERE id = cast(:workflowId as uuid)
        """, nativeQuery = true)
    int resetBudgetPeriodCumulative(@Param("workflowId") UUID workflowId);

}
