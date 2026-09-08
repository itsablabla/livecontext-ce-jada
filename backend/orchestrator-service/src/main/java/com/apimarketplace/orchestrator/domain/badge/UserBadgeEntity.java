package com.apimarketplace.orchestrator.domain.badge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One badge a user has unlocked (V459).
 *
 * <p>Deliberately NOT an {@code OrgScopedEntity}: a trophy belongs to the
 * person, not to the workspace they happened to be browsing when it unlocked.
 * The same user sees the same badges in every organization, and the public
 * profile shows them with no org context at all.
 *
 * <p>Rows are written once and never updated: {@code progress_value} freezes
 * the metric at unlock time, and an unlock is never revoked when the metric
 * later drops (deleting a workflow must not take a trophy away).
 */
@Entity
@Table(name = "user_badges", schema = "orchestrator")
public class UserBadgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning user (the numeric auth user id the gateway sends as X-User-ID). */
    @Column(name = "tenant_id", nullable = false, length = 255)
    private String tenantId;

    /** Stable {@code BadgeCatalog} code. Unknown codes are ignored on read. */
    @Column(name = "badge_code", nullable = false, length = 64)
    private String badgeCode;

    @Column(name = "progress_value", nullable = false)
    private long progressValue;

    @Column(name = "unlocked_at", nullable = false)
    private Instant unlockedAt;

    public UserBadgeEntity() {}

    public UserBadgeEntity(String tenantId, String badgeCode, long progressValue, Instant unlockedAt) {
        this.tenantId = tenantId;
        this.badgeCode = badgeCode;
        this.progressValue = progressValue;
        this.unlockedAt = unlockedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getBadgeCode() { return badgeCode; }
    public void setBadgeCode(String badgeCode) { this.badgeCode = badgeCode; }
    public long getProgressValue() { return progressValue; }
    public void setProgressValue(long progressValue) { this.progressValue = progressValue; }
    public Instant getUnlockedAt() { return unlockedAt; }
    public void setUnlockedAt(Instant unlockedAt) { this.unlockedAt = unlockedAt; }
}
