package com.apimarketplace.datasource.events;

/**
 * A datasource that owned a partial HNSW index has been deleted.
 *
 * <p>The vector ROWS go with the datasource (the {@code item_id} foreign key
 * cascades), but the per-datasource index is a separate catalog object that
 * nothing removed: {@code dropHnswIndex} had no callers, so every deleted
 * vector table left an empty index behind forever. Empty indexes are cheap
 * individually, but the NUMBER of partial HNSW indexes on the shared table is
 * the operational ceiling of the whole design (every build scans the table for
 * all of them, every vacuum visits all of them, every backup replays all of
 * them), so a leak here is a leak of the scarcest resource.
 *
 * @param dataSourceId the deleted datasource, whose index name is derived from it
 */
public record VectorDataSourceDeletedEvent(Long dataSourceId) {
}
