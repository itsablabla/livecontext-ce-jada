package com.apimarketplace.datasource.metrics;

import com.apimarketplace.datasource.DatasourceServiceApplication;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The reporter must compute lazily. Its first version was @Scheduled, which
 * needed @EnableScheduling on a service that never had it, and enabling it would
 * have woken StorageConfig.cleanupExpiredStorages: a scanned hourly job writing
 * to storage.storage under a @SchedulerLock this service has no lock provider
 * for. An unlocked cross-schema write on every replica, as a side effect of
 * adding a metric. These pins keep that door shut.
 */
@DisplayName("VectorCapacityReporter wiring")
class VectorCapacityReporterWiringTest {

    @Test
    @DisplayName("datasource-service does NOT enable scheduling: it would wake the storage cleanup job unlocked")
    void serviceDoesNotEnableScheduling() {
        assertThat(DatasourceServiceApplication.class.getAnnotation(EnableScheduling.class))
                .as("@EnableScheduling on datasource-service starts StorageConfig.cleanupExpiredStorages "
                        + "with an inert @SchedulerLock; the reporter must not need it")
                .isNull();
    }

    @Test
    @DisplayName("the reporter has no @Scheduled method at all")
    void reporterIsNotScheduled() {
        boolean anyScheduled = Arrays.stream(VectorCapacityReporter.class.getDeclaredMethods())
                .anyMatch(m -> m.getAnnotation(Scheduled.class) != null);
        assertThat(anyScheduled).isFalse();
    }

    @Test
    @DisplayName("gauges register, read lazily through a memo, and keep the last values when a read fails")
    void gaugesAreLazyAndKeepLastValuesOnError() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        VectorCapacityReporter reporter = new VectorCapacityReporter(jdbc, registry);

        assertThat(registry.find("datasource_vector_hnsw_indexes").gauge()).isNotNull();
        assertThat(registry.find("datasource_vector_hnsw_indexes_invalid").gauge()).isNotNull();
        assertThat(registry.find("datasource_vector_hnsw_indexes_orphan").gauge()).isNotNull();
        assertThat(registry.find("datasource_vector_hnsw_indexes").gauge().value()).isEqualTo(3.0);

        // The database goes away: the gauge must not drop to zero and read as a purge.
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenThrow(new IllegalStateException("db down"));
        reporter.invalidate();
        assertThat(registry.find("datasource_vector_hnsw_indexes").gauge().value()).isEqualTo(3.0);
    }

    /**
     * Before the first successful read the gauges must be NaN, not zero: zero
     * reads as "healthy and empty" on a database the reporter has not reached.
     */
    @Test
    @DisplayName("gauges are NaN until the first successful read, never a reassuring zero")
    void gaugesAreNaNBeforeFirstSuccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenThrow(new IllegalStateException("db down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new VectorCapacityReporter(jdbc, registry);

        assertThat(registry.find("datasource_vector_hnsw_indexes").gauge().value()).isNaN();
    }

    /**
     * THE regression. Gauge.builder(name, obj, fn) holds obj in a WEAK reference.
     * The first version passed a Supplier lambda as obj; nothing else referenced it
     * once the constructor returned, the next minor GC collected it, and every
     * gauge read NaN forever. A value read before any GC cannot see that, so this
     * test forces collection first: it drops a sentinel weak reference, loops
     * System.gc() until the sentinel clears (proof that a collection actually ran),
     * and only then reads the gauge.
     */
    @Test
    @DisplayName("gauges survive garbage collection: they anchor on the reporter, not on a collectable lambda")
    void gaugesSurviveGarbageCollection() throws InterruptedException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(5L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // Held only in a local so it stays reachable through the test, like the
        // Spring singleton would be.
        VectorCapacityReporter reporter = new VectorCapacityReporter(jdbc, registry);
        assertThat(registry.find("datasource_vector_hnsw_indexes").gauge().value()).isEqualTo(5.0);

        java.lang.ref.WeakReference<Object> sentinel = new java.lang.ref.WeakReference<>(new Object());
        for (int i = 0; i < 50 && sentinel.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertThat(sentinel.get()).as("a garbage collection must actually have happened").isNull();

        assertThat(registry.find("datasource_vector_hnsw_indexes").gauge().value())
                .as("a gauge anchored on a collectable lambda reads NaN here")
                .isEqualTo(5.0);
        assertThat(reporter).isNotNull();
    }
}
