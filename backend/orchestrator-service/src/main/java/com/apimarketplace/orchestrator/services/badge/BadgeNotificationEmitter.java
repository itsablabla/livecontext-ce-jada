package com.apimarketplace.orchestrator.services.badge;

import com.apimarketplace.orchestrator.services.notification.SubjectNameResolver;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the bell row for a freshly unlocked badge.
 *
 * <p>Same contract as the run-failure emitter: native
 * {@code INSERT … ON CONFLICT (tenant_id, category, source_id) DO NOTHING
 * RETURNING id}, and the Redis push fires ONLY when a row was really inserted,
 * so two evaluators racing on the same unlock cannot double-notify. The
 * {@code source_id} is the badge code, which makes the dedup key exactly "this
 * user, this badge" - a re-evaluation after a restart is a silent no-op.
 *
 * <p>{@code subject_id} is a deterministic UUID derived from the badge code
 * rather than a random one: the notifications table keys on a UUID, badges do
 * not have one, and a stable derivation keeps the bell's per-subject
 * aggregation (and the user's "delete this row" action) pointing at the same
 * bucket across restarts.
 *
 * <p>Failures are logged and swallowed. The unlock row is already committed;
 * losing its bell entry must never fail the evaluation, and the badge still
 * shows up on the trophies page.
 */
@Component
public class BadgeNotificationEmitter {

    private static final Logger log = LoggerFactory.getLogger(BadgeNotificationEmitter.class);

    /** Bell category. Mirrored by the frontend's category label + row icon. */
    public static final String CATEGORY_BADGE_UNLOCKED = "BADGE_UNLOCKED";

    private static final String SEVERITY_INFO = "info";
    /** Namespace prefix so a badge code can never collide with another UUID source. */
    private static final String SUBJECT_ID_NAMESPACE = "livecontext:badge:";

    private final WorkflowRedisPublisher redisPublisher;
    private final MeterRegistry meterRegistry;

    @PersistenceContext
    private EntityManager entityManager;

    public BadgeNotificationEmitter(WorkflowRedisPublisher redisPublisher, MeterRegistry meterRegistry) {
        this.redisPublisher = redisPublisher;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Stable subject id for a badge code. Package-visible so the tests can assert
     * the same code always maps to the same UUID.
     */
    static UUID subjectIdFor(String badgeCode) {
        return UUID.nameUUIDFromBytes(
                (SUBJECT_ID_NAMESPACE + badgeCode).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Emit one "badge unlocked" bell row.
     *
     * <p>{@code REQUIRES_NEW} is load-bearing: {@code EntityManager} DML throws
     * {@code TransactionRequiredException} with no active transaction, that
     * exception is a {@code PersistenceException}, and the catch below swallows
     * it - so without this annotation every unlock would silently produce no
     * bell row at all. Its own transaction also keeps a failed notification from
     * rolling back the unlock that has already been written.
     *
     * @param tenantId       the user who unlocked it
     * @param organizationId workspace stamped on the row. The column is NOT NULL
     *                       (V263), and the bell reads strictly by it, so the
     *                       caller passes the org the user is actually browsing
     *                       when there is one and their default personal
     *                       workspace otherwise - a personal trophy has no other
     *                       natural home.
     * @param definition     the unlocked badge
     * @param value          metric value at unlock time, shown in the row
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void emitUnlocked(String tenantId, String organizationId,
                             BadgeDefinition definition, long value) {
        if (tenantId == null || tenantId.isBlank() || organizationId == null
                || organizationId.isBlank() || definition == null) {
            // No org means the row cannot be inserted at all (NOT NULL) and would
            // be invisible if it could. Skip rather than throw: the badge itself
            // is already unlocked and the page will show it.
            log.debug("[badges] skipping unlock notification - tenant={} org={}", tenantId, organizationId);
            return;
        }
        Instant now = Instant.now();
        // The bell renders payload.subjectName as the row title. The CODE is
        // sent, not an English label: the frontend owns the translated name for
        // the same code, so a French user reads a French trophy name.
        String payloadJson = "{\"status\":\"unlocked\""
                + ",\"subjectName\":\"" + definition.code() + "\""
                + ",\"badgeCode\":\"" + definition.code() + "\""
                + ",\"badgeFamily\":\"" + definition.family().name() + "\""
                + ",\"badgeTier\":\"" + definition.tier().name() + "\""
                + ",\"badgeValue\":" + value
                + "}";

        try {
            @SuppressWarnings("unchecked")
            List<Object> inserted = entityManager.createNativeQuery(
                    "INSERT INTO orchestrator.notifications "
                            + "(tenant_id, organization_id, category, severity, subject_type, subject_id, "
                            + " source_id, payload, occurred_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?) "
                            + "ON CONFLICT (tenant_id, category, source_id) DO NOTHING RETURNING id")
                    .setParameter(1, tenantId)
                    .setParameter(2, organizationId)
                    .setParameter(3, CATEGORY_BADGE_UNLOCKED)
                    .setParameter(4, SEVERITY_INFO)
                    .setParameter(5, SubjectNameResolver.BADGE)
                    .setParameter(6, subjectIdFor(definition.code()))
                    .setParameter(7, definition.code())
                    .setParameter(8, payloadJson)
                    .setParameter(9, now)
                    .getResultList();

            if (inserted.isEmpty()) {
                return;
            }
            try {
                redisPublisher.publishNotification(tenantId, "notification.created",
                        Map.of("category", CATEGORY_BADGE_UNLOCKED, "severity", SEVERITY_INFO));
            } catch (RedisConnectionFailureException ex) {
                // Row is committed; the bell's polling fallback surfaces it.
                meterRegistry.counter("notification.emitter.errors", "type", "RedisPublish").increment();
            }
        } catch (DataAccessException | PersistenceException ex) {
            meterRegistry.counter("notification.emitter.errors",
                    "type", ex.getClass().getSimpleName()).increment();
            log.warn("[badges] unlock notification failed for tenant {} badge {}: {}",
                    tenantId, definition.code(), ex.getMessage());
        }
    }
}
