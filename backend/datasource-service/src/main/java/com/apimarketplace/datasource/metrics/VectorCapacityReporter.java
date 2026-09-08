package com.apimarketplace.datasource.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.function.ToDoubleFunction;

/**
 * The numbers that decide when the vector feature outgrows the database box,
 * exported as gauges so somebody sees them before it happens.
 *
 * <p><b>Why the index count is the one that matters most.</b> Vectors live in
 * one shared table with one PARTIAL HNSW index per datasource. Four costs scale
 * with the NUMBER of those indexes, not with the row count: every
 * {@code CREATE INDEX CONCURRENTLY} scans the whole table (all tenants' rows)
 * twice per new index; every autovacuum visits every HNSW index on the table;
 * the planner weighs every index at every plan; and {@code pg_dump} replays
 * every build on restore. The comfortable ceiling is in the low hundreds.
 *
 * <p><b>Invalid and orphan indexes are exported separately</b>, because they
 * are the two defects this feature's hygiene work is about, and folding them
 * into the total hides them: an INVALID index is one a failed build left
 * behind (the datasource behind it sequential-scans), and an ORPHAN is one
 * whose datasource no longer exists (pure ceiling waste). Nothing else in the
 * platform repairs what is already there; these numbers are how an operator
 * learns there is something to repair.
 *
 * <p><b>Lazy, not scheduled, and that is deliberate.</b> The gauges compute on
 * scrape through a fifteen-minute memo. The first version used
 * {@code @Scheduled}, which needed {@code @EnableScheduling} on a service that
 * had never enabled it, and enabling it would have woken
 * {@code StorageConfig.cleanupExpiredStorages}, a scanned hourly job that
 * writes to {@code storage.storage} with a {@code @SchedulerLock} this service
 * has no lock provider for. A memo has no such reach. Row count uses
 * {@code pg_class.reltuples} so a scrape never scans the table.
 *
 * <p><b>The gauges reference THIS object, not a lambda, and that is
 * load-bearing.</b> {@code Gauge.builder(name, obj, fn)} holds {@code obj} in
 * a weak reference. The first version passed a {@code Supplier} lambda as
 * {@code obj}; nothing else referenced it once the constructor returned, the
 * next minor GC collected it, and every gauge read {@code NaN} forever. The
 * reporter singleton is strongly held by the context, so it is the anchor.
 *
 * <p><b>Until the first successful read, the gauges report {@code NaN}</b>,
 * not zero. Zero would read as "healthy and empty" on a database the reporter
 * has not managed to reach yet.
 */
@Component
public class VectorCapacityReporter {

    private static final Logger log = LoggerFactory.getLogger(VectorCapacityReporter.class);

    static final Duration MEMO_TTL = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;

    /** Last successful read; null until there has been one. */
    private volatile Snapshot memo;
    private volatile Instant memoAt = Instant.EPOCH;

    /** One read of every number, taken together so the gauges agree with each other. */
    public record Snapshot(long hnswIndexes, long invalidIndexes, long orphanIndexes,
                           long estimatedRows, long tableBytes) {
    }

    public VectorCapacityReporter(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        gauge(registry, "datasource_vector_hnsw_indexes",
                "Per-datasource partial HNSW indexes on data_source_vectors; the operational ceiling is in the low hundreds",
                s -> s.hnswIndexes());
        gauge(registry, "datasource_vector_hnsw_indexes_invalid",
                "HNSW indexes a failed CONCURRENTLY build left INVALID; each one is a datasource that sequential-scans",
                s -> s.invalidIndexes());
        gauge(registry, "datasource_vector_hnsw_indexes_orphan",
                "HNSW indexes whose datasource no longer exists; pure ceiling waste",
                s -> s.orphanIndexes());
        gauge(registry, "datasource_vector_rows",
                "Estimated rows in data_source_vectors (pg_class.reltuples), all tenants",
                s -> s.estimatedRows());
        gauge(registry, "datasource_vector_table_bytes",
                "pg_total_relation_size of data_source_vectors: heap, TOAST and every index",
                s -> s.tableBytes());
    }

    private void gauge(MeterRegistry registry, String name, String description,
                       ToDoubleFunction<Snapshot> field) {
        // The strong reference is `this`; the function reads the memoised snapshot.
        Gauge.builder(name, this, reporter -> {
                    Snapshot s = reporter.snapshot();
                    return s == null ? Double.NaN : field.applyAsDouble(s);
                })
                .description(description)
                .register(registry);
    }

    /** The memoised snapshot, refreshed at most once per {@link #MEMO_TTL}; null before the first success. */
    public Snapshot snapshot() {
        Instant now = Instant.now();
        if (Duration.between(memoAt, now).compareTo(MEMO_TTL) < 0) {
            return memo;
        }
        try {
            memo = read();
        } catch (Exception e) {
            // Keep the last good values (or null): a gauge that dropped to zero on a
            // transient error would read as a purge, and one that read zero before
            // the first success would read as an empty, healthy table.
            // WARN, not DEBUG: five hand-written catalog queries, and a typo in any of
            // them would otherwise read as NaN forever with nothing above DEBUG. The
            // 15-minute memo already rate-limits this line.
            log.warn("[VectorCapacityReporter] refresh failed, keeping last values: {}", e.getMessage());
        }
        memoAt = now;
        return memo;
    }

    /** Force the next {@link #snapshot()} to read. For tests. */
    public void invalidate() {
        memoAt = Instant.EPOCH;
    }

    private Snapshot read() {
        // Every index query is anchored on the table's OID, not on a name pattern
        // alone, so a same-named index in another schema cannot be counted.
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                WHERE i.indrelid = 'data_source_vectors'::regclass AND c.relname LIKE 'idx_vectors_ds_%'
                """, Long.class);
        Long invalid = jdbc.queryForObject("""
                SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                WHERE i.indrelid = 'data_source_vectors'::regclass AND c.relname LIKE 'idx_vectors_ds_%'
                  AND NOT i.indisvalid
                """, Long.class);
        Long orphan = jdbc.queryForObject("""
                SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                WHERE i.indrelid = 'data_source_vectors'::regclass AND c.relname LIKE 'idx_vectors_ds_%'
                  AND NOT EXISTS (
                        SELECT 1 FROM data_sources d
                        WHERE d.id = substring(c.relname FROM 'idx_vectors_ds_([0-9]+)$')::bigint)
                """, Long.class);
        Long rows = jdbc.queryForObject(
                "SELECT greatest(reltuples, 0)::bigint FROM pg_class WHERE oid = 'data_source_vectors'::regclass",
                Long.class);
        Long bytes = jdbc.queryForObject(
                "SELECT pg_total_relation_size('data_source_vectors')", Long.class);
        return new Snapshot(nz(total), nz(invalid), nz(orphan), nz(rows), nz(bytes));
    }

    private static long nz(Long v) {
        return v != null ? v : 0L;
    }
}
