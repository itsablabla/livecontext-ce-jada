package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentMemoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Long-term memory reads, all keyed on {@code organization_id}.
 *
 * <p>Every finder here is {@code *Strict}: the workspace is the isolation
 * boundary and a row is visible iff its {@code organization_id} equals the
 * caller's active workspace. There is deliberately no {@code tenant_id}-based
 * or {@code tenantId OR organizationId} finder - that shape is the
 * cross-workspace read the platform removed in the V263 migration arc, and
 * re-introducing it here would let a member still see their personally-created
 * rows after switching into a team workspace.
 *
 * <p>The {@code agentId} axis is expressed as {@code (m.agentId IS NULL OR
 * m.agentId = :agentId)} rather than two finders: a run must see the shared
 * workspace memory AND its own private memory in one ordered result, and the
 * caps have to apply across both. A {@code null} {@code agentId} argument (a
 * chat with no agent bound) collapses the predicate to workspace-only rows,
 * which is correct: nobody's private memory leaks into a general chat.
 *
 * <p>The injection read IS split in two, for a different reason: the index needs
 * four small columns per row and the pinned bodies need whole entities, so one
 * query for each keeps forty full bodies off the hot path of every execution.
 */
@Repository
public interface AgentMemoryRepository extends JpaRepository<AgentMemoryEntity, UUID> {

    /** Single-row read in the caller's workspace. Out of scope returns empty, which the callers map to 404. */
    @Query("SELECT m FROM AgentMemoryEntity m WHERE m.id = :id AND m.organizationId = :orgId")
    Optional<AgentMemoryEntity> findByIdAndOrganizationIdStrict(@Param("id") UUID id,
                                                                @Param("orgId") String orgId);

    /**
     * The index lines injected into every run, as a PROJECTION.
     *
     * <p>Only four small columns, deliberately: this runs at the start of every
     * agent execution in the workspace, and selecting whole entities would pull up
     * to forty 8000-character bodies across the wire to render forty one-line
     * summaries. The bodies belong to {@link #findPinnedForInjectionStrict}, which
     * is capped at a handful of rows.
     */
    @Query("""
        SELECT new com.apimarketplace.agent.repository.AgentMemoryRepository$IndexRow(
            m.id, m.slug, m.summary, m.type, m.pinned)
        FROM AgentMemoryEntity m
        WHERE m.organizationId = :orgId
          AND m.isActive = true
          AND (m.agentId IS NULL OR m.agentId = :agentId)
        ORDER BY m.pinned DESC, m.updatedAt DESC
        """)
    List<IndexRow> findIndexForInjectionStrict(@Param("orgId") String orgId,
                                               @Param("agentId") UUID agentId,
                                               Pageable pageable);

    /** The few pinned entries whose full body is injected. Entities, because the body is the point. */
    @Query("""
        SELECT m FROM AgentMemoryEntity m
        WHERE m.organizationId = :orgId
          AND m.isActive = true
          AND m.pinned = true
          AND (m.agentId IS NULL OR m.agentId = :agentId)
        ORDER BY m.updatedAt DESC
        """)
    List<AgentMemoryEntity> findPinnedForInjectionStrict(@Param("orgId") String orgId,
                                                         @Param("agentId") UUID agentId,
                                                         Pageable pageable);

    /**
     * Every pinned, active row in the workspace, both scopes, for the write-time
     * pin check.
     *
     * <p>A WORKSPACE pin is rendered for every agent, so whether it fits has to be
     * answered against each agent's own pinned set, not against the workspace set
     * alone. One unfiltered read grouped in Java is cheaper than the per-scope
     * query it replaces and is bounded by the per-scope caps: at most
     * {@code agents x maxPinnedEntries + maxPinnedEntries} rows.
     */
    @Query("""
        SELECT m FROM AgentMemoryEntity m
        WHERE m.organizationId = :orgId
          AND m.isActive = true
          AND m.pinned = true
        ORDER BY m.updatedAt DESC
        """)
    List<AgentMemoryEntity> findAllPinnedInWorkspaceStrict(@Param("orgId") String orgId);

    /** One rendered index line, without the body. */
    record IndexRow(UUID id, String slug, String summary,
                    AgentMemoryEntity.MemoryType type, Boolean pinned) {}

    /** Browse/list read. Includes inactive rows so the UI can show and revive them. */
    @Query("""
        SELECT m FROM AgentMemoryEntity m
        WHERE m.organizationId = :orgId
          AND (m.agentId IS NULL OR m.agentId = :agentId)
        ORDER BY m.pinned DESC, m.updatedAt DESC
        """)
    List<AgentMemoryEntity> findVisibleStrict(@Param("orgId") String orgId,
                                              @Param("agentId") UUID agentId);

    /** Every row in the workspace, both scopes, for the Memory tab's unfiltered list. */
    @Query("""
        SELECT m FROM AgentMemoryEntity m
        WHERE m.organizationId = :orgId
        ORDER BY m.pinned DESC, m.updatedAt DESC
        """)
    List<AgentMemoryEntity> findAllInWorkspaceStrict(@Param("orgId") String orgId);

    /**
     * Slug lookup inside one scope. Mirrors the two partial unique indexes, so
     * {@code save} can resolve "does this fact already exist here" before
     * deciding between insert and update. The {@code agentId IS NULL} branch is
     * written out rather than passed as a parameter because a JPQL {@code =}
     * against {@code null} never matches, which would silently turn every
     * workspace-scope save into an insert and trip the unique index instead.
     */
    @Query("""
        SELECT m FROM AgentMemoryEntity m
        WHERE m.organizationId = :orgId AND m.slug = :slug AND m.agentId IS NULL
        """)
    Optional<AgentMemoryEntity> findWorkspaceSlugStrict(@Param("orgId") String orgId,
                                                        @Param("slug") String slug);

    @Query("""
        SELECT m FROM AgentMemoryEntity m
        WHERE m.organizationId = :orgId AND m.slug = :slug AND m.agentId = :agentId
        """)
    Optional<AgentMemoryEntity> findAgentSlugStrict(@Param("orgId") String orgId,
                                                    @Param("agentId") UUID agentId,
                                                    @Param("slug") String slug);

    /**
     * Full-text search over title + summary + content, ranked by relevance.
     *
     * <p>Native because the generated {@code search_vector} column and the
     * {@code @@} / {@code plainto_tsquery} operators have no JPQL equivalent.
     * {@code plainto_tsquery} rather than {@code to_tsquery} on purpose: the
     * query string comes from an LLM or a UI search box and may contain any
     * punctuation, and {@code to_tsquery} raises a syntax error on input it
     * cannot parse - a search box that 500s on an apostrophe is not a search box.
     *
     * <p>Deactivated rows are excluded, matching the injection finders: this is
     * the AGENT's search, and an agent that can still find a deactivated entry can
     * still act on it, which is the whole thing deactivating is for. The person's
     * search ({@link #searchWorkspaceStrict}) keeps them, or the rows they turned
     * off would be the rows they can no longer find to turn back on.
     *
     * <p>{@code 'simple'} matches the generated column's configuration; using a
     * different one here would silently return nothing.
     */
    @Query(value = """
        SELECT * FROM agent.agent_memories m
        WHERE m.organization_id = :orgId
          AND (m.agent_id IS NULL OR m.agent_id = CAST(:agentId AS uuid))
          AND m.is_active = true
          AND m.search_vector @@ plainto_tsquery('simple', :query)
        ORDER BY ts_rank(m.search_vector, plainto_tsquery('simple', :query)) DESC,
                 m.updated_at DESC
        LIMIT :maxResults
        """, nativeQuery = true)
    List<AgentMemoryEntity> searchStrict(@Param("orgId") String orgId,
                                         @Param("agentId") String agentId,
                                         @Param("query") String query,
                                         @Param("maxResults") int maxResults);

    /**
     * Search every row in the workspace, both scopes. The human surface: a person
     * auditing memory must be able to FIND an agent-private entry, not merely see
     * it in a list, or the rows they cannot search are the ones they cannot fix.
     * The agent's own search stays scoped by {@link #searchStrict}.
     */
    @Query(value = """
        SELECT * FROM agent.agent_memories m
        WHERE m.organization_id = :orgId
          AND m.search_vector @@ plainto_tsquery('simple', :query)
        ORDER BY ts_rank(m.search_vector, plainto_tsquery('simple', :query)) DESC,
                 m.updated_at DESC
        LIMIT :maxResults
        """, nativeQuery = true)
    List<AgentMemoryEntity> searchWorkspaceStrict(@Param("orgId") String orgId,
                                                  @Param("query") String query,
                                                  @Param("maxResults") int maxResults);

    /**
     * Record one recall WITHOUT touching {@code updated_at}.
     *
     * <p>A targeted update rather than an entity save, because {@code @PreUpdate}
     * would stamp {@code updated_at} and the tab's "updated" line would then show
     * the last time an agent READ the entry rather than the last time anyone
     * changed it. The injection order is {@code updated_at DESC} too, so a recall
     * would also reshuffle which pinned entries make the cut. {@code last_recalled_at}
     * already carries the read timestamp.
     *
     * <p><b>Scope warning:</b> {@code clearAutomatically} detaches EVERY managed
     * entity in the persistence context, not only this row - that is all Spring
     * Data offers. It is safe for the current callers, which read one memory and
     * return, but a future caller that joins an outer transaction with pending
     * changes would lose them silently. If that day comes, move the recall into its
     * own transaction rather than dropping the clear, because the clear is what
     * stops updated_at being stamped (below).
     *
     * <p><b>{@code clearAutomatically} is load-bearing, not tidiness.</b> The
     * caller still holds the managed entity and updates its counter fields in
     * memory afterwards. Without the clear that entity stays managed, the dirty
     * check flushes an entity UPDATE at commit, {@code @PreUpdate} fires, and
     * {@code updated_at} is stamped after all - reintroducing exactly the bug this
     * query exists to avoid. {@code flushAutomatically} is its partner: it writes
     * any pending change BEFORE the clear discards it.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentMemoryEntity m
        SET m.recallCount = m.recallCount + 1, m.lastRecalledAt = :now
        WHERE m.id = :id
        """)
    void recordRecall(@Param("id") UUID id, @Param("now") java.time.Instant now);

    /** Workspace-scope entry count, for the per-workspace cap. */
    long countByOrganizationIdAndAgentIdIsNull(String organizationId);

    /** Agent-scope entry count, for the per-agent cap. */
    long countByOrganizationIdAndAgentId(String organizationId, UUID agentId);
}
