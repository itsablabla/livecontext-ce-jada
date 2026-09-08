package com.apimarketplace.orchestrator.services.badge;

import com.apimarketplace.orchestrator.services.notification.SubjectNameResolver;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BadgeNotificationEmitter")
class BadgeNotificationEmitterTest {

    private static final String TENANT = "42";
    private static final String ORG = "org-7";
    private static final BadgeDefinition BADGE = BadgeCatalog.byCode("builder_10");

    @Mock private WorkflowRedisPublisher redisPublisher;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private MeterRegistry meterRegistry;
    private BadgeNotificationEmitter emitter;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        emitter = new BadgeNotificationEmitter(redisPublisher, meterRegistry);
        Field em = BadgeNotificationEmitter.class.getDeclaredField("entityManager");
        em.setAccessible(true);
        em.set(emitter, entityManager);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(anyInt(), any())).thenReturn(query);
    }

    @Test
    @DisplayName("a fresh unlock writes the row and pushes to the recipient")
    void freshUnlockWritesAndPublishes() {
        when(query.getResultList()).thenReturn(List.of(1L));

        emitter.emitUnlocked(TENANT, ORG, BADGE, 12);

        verify(query).setParameter(1, TENANT);
        verify(query).setParameter(2, ORG);
        verify(query).setParameter(3, BadgeNotificationEmitter.CATEGORY_BADGE_UNLOCKED);
        verify(query).setParameter(5, SubjectNameResolver.BADGE);
        // source_id is the badge code, which is what makes the row idempotent
        // per (user, badge).
        verify(query).setParameter(7, BADGE.code());
        verify(redisPublisher).publishNotification(eq(TENANT), eq("notification.created"), any());
    }

    @Test
    @DisplayName("an already-written row publishes nothing - no duplicate bell push across replicas")
    void conflictSkipsThePush() {
        when(query.getResultList()).thenReturn(List.of());

        emitter.emitUnlocked(TENANT, ORG, BADGE, 12);

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("the payload carries the badge CODE as subjectName so the bell can translate it")
    void payloadCarriesTheCodeNotAnEnglishLabel() {
        when(query.getResultList()).thenReturn(List.of(1L));

        emitter.emitUnlocked(TENANT, ORG, BADGE, 12);

        // Parameter 8 is the jsonb payload.
        verify(query).setParameter(eq(8), org.mockito.ArgumentMatchers.argThat((Object json) ->
                json instanceof String s
                        && s.contains("\"status\":\"unlocked\"")
                        && s.contains("\"subjectName\":\"builder_10\"")
                        && s.contains("\"badgeTier\":\"SILVER\"")
                        && s.contains("\"badgeValue\":12")));
    }

    @Test
    @DisplayName("without a workspace the row is skipped rather than attempted - the column is NOT NULL")
    void missingOrganizationSkipsTheWrite() {
        emitter.emitUnlocked(TENANT, "  ", BADGE, 12);
        emitter.emitUnlocked(TENANT, null, BADGE, 12);

        verify(entityManager, never()).createNativeQuery(anyString());
        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("a database failure is swallowed - the badge is already unlocked either way")
    void databaseFailureIsSwallowed() {
        when(query.getResultList()).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> emitter.emitUnlocked(TENANT, ORG, BADGE, 12)).doesNotThrowAnyException();
        assertThat(meterRegistry.find("notification.emitter.errors").counters()).isNotEmpty();
    }

    @Test
    @DisplayName("a Redis failure is swallowed - the row is committed and polling will surface it")
    void redisFailureIsSwallowed() {
        when(query.getResultList()).thenReturn(List.of(1L));
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(redisPublisher).publishNotification(anyString(), anyString(), any());

        assertThatCode(() -> emitter.emitUnlocked(TENANT, ORG, BADGE, 12)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a badge code always maps to the same subject id, so its bell bucket is stable")
    void subjectIdIsDeterministicPerCode() {
        UUID first = BadgeNotificationEmitter.subjectIdFor("builder_10");
        UUID second = BadgeNotificationEmitter.subjectIdFor("builder_10");

        assertThat(first).isEqualTo(second);
        assertThat(BadgeNotificationEmitter.subjectIdFor("builder_25")).isNotEqualTo(first);
    }

    @Test
    @DisplayName("every catalog code maps to a distinct subject id")
    void subjectIdsDoNotCollideAcrossTheCatalog() {
        List<UUID> ids = BadgeCatalog.all().stream()
                .map(d -> BadgeNotificationEmitter.subjectIdFor(d.code()))
                .toList();

        assertThat(ids).doesNotHaveDuplicates();
    }
}
