package com.apimarketplace.orchestrator.services.badge;

import com.apimarketplace.orchestrator.repository.UserBadgeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both badge WRITES fail SILENTLY without their transaction annotation, which
 * is exactly why they need a test rather than a comment.
 *
 * <ul>
 *   <li>{@code UserBadgeRepository.insertIfAbsent} - a declared Spring Data
 *       query runs under {@code SimpleJpaRepository}'s class-level
 *       {@code @Transactional(readOnly = true)}, and Postgres refuses an INSERT
 *       in a read-only transaction. Nobody would hold a badge, ever.</li>
 *   <li>{@code BadgeNotificationEmitter.emitUnlocked} - {@code EntityManager}
 *       DML with no active transaction throws
 *       {@code TransactionRequiredException}, which IS a
 *       {@code PersistenceException} and is therefore caught and logged by the
 *       emitter's own failure isolation. Every unlock would be trophied but
 *       never announced.</li>
 * </ul>
 *
 * <p>Neither caller can supply the transaction: {@code BadgeService} reaches
 * {@code evaluate} by self-invocation from its read path, which bypasses the
 * Spring proxy and its {@code REQUIRES_NEW}. The annotations have to be at the
 * leaves.
 */
@DisplayName("badge write transaction invariants")
class BadgeWriteTransactionInvariantTest {

    @Test
    @DisplayName("the unlock insert declares its own transaction, overriding the read-only default")
    void unlockInsertIsTransactional() throws NoSuchMethodException {
        Method insert = UserBadgeRepository.class.getMethod(
                "insertIfAbsent", String.class, String.class, long.class, Instant.class);

        Transactional annotation = insert.getAnnotation(Transactional.class);
        assertThat(annotation)
                .as("insertIfAbsent without @Transactional runs read-only: no badge is ever awarded")
                .isNotNull();
        assertThat(annotation.readOnly()).isFalse();
    }

    @Test
    @DisplayName("the unlock notification runs in its own transaction, so it can write and cannot roll back the unlock")
    void unlockNotificationRunsInItsOwnTransaction() throws NoSuchMethodException {
        Method emit = BadgeNotificationEmitter.class.getMethod(
                "emitUnlocked", String.class, String.class, BadgeDefinition.class, long.class);

        Transactional annotation = emit.getAnnotation(Transactional.class);
        assertThat(annotation)
                .as("emitUnlocked without @Transactional throws TransactionRequiredException, "
                        + "which its own catch block swallows - unlocks would never reach the bell")
                .isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
