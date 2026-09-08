package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.badge.UserBadgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface UserBadgeRepository extends JpaRepository<UserBadgeEntity, Long> {

    /** Every badge one user has unlocked, newest first (the profile grid order). */
    List<UserBadgeEntity> findByTenantIdOrderByUnlockedAtDesc(String tenantId);

    /**
     * Idempotent unlock. Returns the number of rows actually inserted, so the
     * caller emits a notification ONLY for a genuine first unlock.
     *
     * <p>{@code ON CONFLICT DO NOTHING} on {@code uq_user_badges_tenant_code} is
     * what makes concurrent evaluators safe: the periodic sweep and an on-demand
     * page load can race on the same user, and without it both would see "not
     * unlocked yet" and both would notify. A plain {@code existsBy} + {@code save}
     * has that exact race - the check and the insert are two statements.
     *
     * <p>Native because JPQL has no upsert. Writes only to
     * {@code orchestrator.user_badges}, which no other component mutates.
     */
    // @Transactional is MANDATORY here, not decorative. Spring Data runs a
    // declared query method under SimpleJpaRepository's class-level
    // `@Transactional(readOnly = true)` unless the method overrides it, and
    // Postgres refuses an INSERT in a read-only transaction. The caller cannot
    // be relied on to supply one either: BadgeService reaches evaluate() by
    // self-invocation from the read path, which bypasses its own proxy.
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO orchestrator.user_badges (tenant_id, badge_code, progress_value, unlocked_at)
            VALUES (:tenantId, :badgeCode, :progressValue, :unlockedAt)
            ON CONFLICT (tenant_id, badge_code) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("tenantId") String tenantId,
                       @Param("badgeCode") String badgeCode,
                       @Param("progressValue") long progressValue,
                       @Param("unlockedAt") Instant unlockedAt);
}
