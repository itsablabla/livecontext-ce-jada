package com.apimarketplace.agent.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One curated long-term memory: a single declarative fact the agents in a
 * workspace should still know in a conversation that has not happened yet.
 *
 * <p>Deliberately separate from {@link SkillEntity}. A skill is PROCEDURAL and
 * authored ("here is how to run a release"); a memory is DECLARATIVE and
 * accumulated ("this team ships on Thursdays"). They have different write
 * paths, different lifecycles and different caps, and folding them into one
 * table behind a discriminator would leak memories into every existing skill
 * finder, into the chat skills tree, into the CE skill bundles and into
 * marketplace snapshots.
 *
 * <p><b>Two-tier by construction.</b> {@code summary} is the index line and is
 * injected into the system prompt of every agent in scope on every execution;
 * {@code content} is the body and is never injected, the agent fetches it with
 * {@code memory(action='get', slug=...)}. Keeping them in separate columns is
 * what lets the index query project only the cheap half.
 *
 * <p><b>No {@code @Lob} on {@code content}</b> - same trap as
 * {@link SkillEntity#getInstructions()}: PostgreSQL streams a LOB as a large
 * object that is only readable inside the transaction that opened it, and the
 * prompt-section build runs outside one. Plain {@code TEXT} instead.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "agent_memories", schema = "agent")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AgentMemoryEntity implements OrgScopedEntity {

    /** Claude Code's four buckets, rendered as the {@code [type]} tag on each index line. */
    public enum MemoryType { USER, FEEDBACK, PROJECT, REFERENCE }

    /** Who wrote the row. Surfaced as a badge so a human can audit what an agent decided to keep. */
    public enum MemorySource { AGENT, USER }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /**
     * Creating user. Attribution and the storage-usage rollup only: workspace
     * isolation is {@link #organizationId} alone, via
     * {@code ScopeGuard.isInStrictScope}. Matching on tenantId as well would
     * re-open the cross-workspace read that the strict predicate exists to close.
     */
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "organization_id", nullable = false)
    private String organizationId;

    /**
     * {@code null} = the memory belongs to the whole workspace and every agent
     * and chat in it sees the index line. Non-null = private to that one agent,
     * so a fleet of specialised agents does not drown in its siblings' facts.
     * The FK cascades on agent delete: an agent-private fact must die with its
     * agent rather than be demoted to workspace visibility.
     */
    @Column(name = "agent_id")
    private UUID agentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private MemoryType type = MemoryType.PROJECT;

    /**
     * The agent-facing handle. A readable slug rather than the UUID, because the
     * agent reads and writes it in prompts on every turn and an opaque id there
     * is token spend with no recall value. Unique per scope, which is what makes
     * {@code save} an idempotent upsert instead of a duplicate generator.
     */
    @Column(name = "slug", nullable = false, length = 80)
    private String slug;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    /** The one line that is paid for on every execution of every agent in scope. */
    @Column(name = "summary", nullable = false, length = 240)
    private String summary;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb")
    private List<String> tags = new ArrayList<>();

    /** Pinned entries inject their full {@link #content}, not just the summary. */
    @Column(name = "pinned", nullable = false)
    private Boolean pinned = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 8)
    private MemorySource source = MemorySource.AGENT;

    @Column(name = "created_by_agent_id")
    private UUID createdByAgentId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "recall_count", nullable = false)
    private Integer recallCount = 0;

    @Column(name = "last_recalled_at")
    private Instant lastRecalledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    private void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (content == null) content = "";
        if (tags == null) tags = new ArrayList<>();
        if (pinned == null) pinned = false;
        if (isActive == null) isActive = true;
        if (recallCount == null) recallCount = 0;
        if (type == null) type = MemoryType.PROJECT;
        if (source == null) source = MemorySource.AGENT;
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = Instant.now();
        if (content == null) content = "";
        if (tags == null) tags = new ArrayList<>();
        if (pinned == null) pinned = false;
        if (isActive == null) isActive = true;
        if (recallCount == null) recallCount = 0;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    @Override
    public String getOrganizationId() { return organizationId; }

    @Override
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }

    public MemoryType getType() { return type; }
    public void setType(MemoryType type) { this.type = type; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public Boolean getPinned() { return pinned; }
    public void setPinned(Boolean pinned) { this.pinned = pinned; }

    public MemorySource getSource() { return source; }
    public void setSource(MemorySource source) { this.source = source; }

    public UUID getCreatedByAgentId() { return createdByAgentId; }
    public void setCreatedByAgentId(UUID createdByAgentId) { this.createdByAgentId = createdByAgentId; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Integer getRecallCount() { return recallCount; }
    public void setRecallCount(Integer recallCount) { this.recallCount = recallCount; }

    public Instant getLastRecalledAt() { return lastRecalledAt; }
    public void setLastRecalledAt(Instant lastRecalledAt) { this.lastRecalledAt = lastRecalledAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
