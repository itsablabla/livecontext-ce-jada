package com.apimarketplace.datasource.events;

/**
 * Published when a VECTOR column is added to a datasource (table create or
 * add_columns), AFTER the column lands in the mappingSpec. Consumed post-commit
 * by {@link VectorColumnCreatedListener} to build the per-datasource HNSW
 * index.
 *
 * <p><b>Why an event instead of an inline call:</b> {@code CREATE INDEX
 * CONCURRENTLY} cannot run while the creating transaction is still open - it
 * waits for every transaction with an older snapshot, including the caller's
 * own CRUD transaction, which would self-deadlock until timeout. The
 * AFTER_COMMIT listener that hands the build to a bounded pool (same pattern as {@link DatasourceRowEvent})
 * runs once the transaction is gone.
 *
 * @param dataSourceId the datasource that received the vector column
 * @param dimension    embedding size from the column's {@code display.dimension}
 * @param metric       similarity metric from {@code display.metric} (cosine/l2/dot)
 */
public record VectorColumnCreatedEvent(Long dataSourceId, int dimension, String metric) {

    /**
     * The event for a datasource's vector column, read off the column's display
     * config, or empty when no usable dimension is declared.
     *
     * <p>The single place this derivation lives. It was previously copied
     * verbatim at the three publish sites (table creation, column addition, the
     * CRUD create-column path); the snapshot clone, which never published the
     * event at all, is the fourth caller. One copy means the next site cannot
     * drift from the others.
     *
     * @param display the VECTOR column's {@code display} map, may be null
     */
    public static java.util.Optional<VectorColumnCreatedEvent> forColumn(Long dataSourceId,
                                                                         java.util.Map<String, Object> display) {
        java.util.Map<String, Object> d = display != null ? display : java.util.Map.of();
        Object dimRaw = d.get("dimension");
        int dimension = dimRaw instanceof Number n ? n.intValue() : 0;
        if (dimension <= 0 && dimRaw instanceof String str) {
            try {
                dimension = Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                // not a number: treated as undeclared below
            }
        }
        if (dimension <= 0) {
            return java.util.Optional.empty();
        }
        String metric = d.get("metric") instanceof String m ? m : "cosine";
        return java.util.Optional.of(new VectorColumnCreatedEvent(dataSourceId, dimension, metric));
    }
}
