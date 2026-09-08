package com.apimarketplace.datasource.events;

import com.apimarketplace.datasource.crud.repository.VectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

/**
 * Builds the per-datasource HNSW index when a vector column is created.
 * Without it, every similarity search sequential-scans all of the
 * datasource's vectors (the index-creation code existed since V75 but was
 * never wired to any caller - searches were O(N) distance computations).
 *
 * <p>AFTER_COMMIT (the column must be durable before the index references its
 * config), then handed to a bounded two-thread pool (CREATE INDEX CONCURRENTLY
 * can wait on other transactions; a request thread should not park on it,
 * except in the documented saturated-queue case where the caller runs the
 * task). Failures are logged and swallowed: similarity search works without
 * the index, just slower.
 *
 * <p>Build and drop for the SAME datasource serialize on the table's SHARE
 * UPDATE EXCLUSIVE lock. The one bad interleaving, a drop that runs before a
 * still-queued build registers its index, leaks one empty index for a table
 * created and deleted within seconds; the capacity gauges will show it and the
 * next deletion-free rebuild will not, so it is accepted.
 *
 * <p>Known limit, accepted: the partial index covers ALL of the datasource's
 * vectors with one dimension cast. A second vector column with a DIFFERENT
 * dimension on the same datasource makes the build fail (cast error on the
 * other column's rows) - logged, search falls back to seq scan. One dimension
 * per datasource is the supported layout.
 */
@Component
public class VectorColumnCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(VectorColumnCreatedListener.class);

    private final VectorRepository vectorRepository;
    private final VectorIndexExecutorConfig.VectorIndexExecutor executor;

    public VectorColumnCreatedListener(VectorRepository vectorRepository,
                                       VectorIndexExecutorConfig.VectorIndexExecutor executor) {
        this.vectorRepository = vectorRepository;
        this.executor = executor;
    }

    // fallbackExecution: the add_columns path publishes from inside the
    // @Transactional CRUD executor (AFTER_COMMIT applies), but table creation
    // (DataSourceService.createDataSource) runs WITHOUT a transaction - the
    // default fallbackExecution=false silently DROPS the event there (caught
    // live by the CE e2e: table created, index never built). Out of a
    // transaction the column row is already committed (autocommit), so
    // immediate execution is safe.
    // Not @Async: the pool is deliberately not an Executor bean (see
    // VectorIndexExecutorConfig), so the handler hands the work over itself. The
    // after-commit callback returns immediately; the build runs on the bounded pool,
    // and if that pool is saturated the caller-runs policy makes THIS thread build,
    // which is the one documented case where a request thread does wait on it.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVectorColumnCreated(VectorColumnCreatedEvent event) {
        executor.submit("HNSW build for datasource " + event.dataSourceId(), () -> buildIndex(event));
    }

    void buildIndex(VectorColumnCreatedEvent event) {
        try {
            vectorRepository.createHnswIndex(event.dataSourceId(), event.dimension(), event.metric());
        } catch (Exception e) {
            log.warn("[VectorColumnCreatedListener] HNSW index build skipped for datasource {} (dim={}): {} - similarity search will seq-scan",
                    event.dataSourceId(), event.dimension(), e.getMessage());
        }
    }

    /**
     * Drops the datasource's partial HNSW index once the datasource is gone.
     *
     * <p>Same shape as the build: AFTER_COMMIT so the rows are already deleted
     * (the drop must never race a live table), and on the bounded executor so a
     * burst of deletions queues rather than fans out. The drop is CONCURRENTLY
     * and runs outside any transaction (see {@code VectorRepository.dropHnswIndex});
     * a failure is logged and swallowed, because a leaked empty index is a
     * capacity concern, not a correctness one, and the next sweep of
     * {@code countHnswIndexes} will show it.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVectorDataSourceDeleted(VectorDataSourceDeletedEvent event) {
        executor.submit("HNSW drop for deleted datasource " + event.dataSourceId(), () -> dropIndex(event));
    }

    void dropIndex(VectorDataSourceDeletedEvent event) {
        try {
            vectorRepository.dropHnswIndex(event.dataSourceId());
        } catch (Exception e) {
            log.warn("[VectorColumnCreatedListener] HNSW index drop failed for deleted datasource {}: {} "
                    + "- an empty index remains on data_source_vectors", event.dataSourceId(), e.getMessage());
        }
    }
}
