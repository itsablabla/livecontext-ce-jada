package com.apimarketplace.orchestrator.services.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persistence for the platform-wide node-usage ledger ({@code orchestrator.node_usage_stats}).
 *
 * <p>Writes are ADDITIVE upserts, never assignments: several orchestrator replicas flush
 * their own windows concurrently and each one contributes its delta, so the row is the sum
 * of every pod's contribution rather than whichever pod wrote last.
 */
@Repository
public class NodeUsageRepository {

    private static final Logger log = LoggerFactory.getLogger(NodeUsageRepository.class);

    private static final String UPSERT = """
            INSERT INTO orchestrator.node_usage_stats (node_key, run_count, updated_at)
            VALUES (?, ?, now())
            ON CONFLICT (node_key) DO UPDATE
            SET run_count  = orchestrator.node_usage_stats.run_count + EXCLUDED.run_count,
                updated_at = now()
            """;

    /**
     * The ORDER, never the numbers. The counters are platform-wide sums that no tenant owns,
     * and the ranking is the only thing any reader needs; serving the raw volume would leak
     * how much the platform runs to every signed-in builder for no benefit to the feature.
     */
    private static final String RANKING = """
            SELECT node_key
            FROM orchestrator.node_usage_stats
            WHERE run_count > 0
            ORDER BY run_count DESC, node_key ASC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public NodeUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Add one flush window's deltas. One batched statement for the whole window, which is
     * the point of accumulating in memory: a node launch never touches the database.
     *
     * @param deltas node key -> how many launches to add (callers pass only positive deltas)
     */
    public void addCounts(Map<String, Long> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return;
        }
        // Locked in key order: several pods flush overlapping windows at once, and two
        // transactions touching the same rows in different orders deadlock - one gets
        // aborted and its window is lost for a minute. A total order removes the cycle.
        List<Object[]> batch = new ArrayList<>(deltas.size());
        deltas.keySet().stream().sorted().forEach(key -> batch.add(new Object[]{key, deltas.get(key)}));
        jdbcTemplate.batchUpdate(UPSERT, batch);
        log.debug("Node usage flushed: {} key(s)", batch.size());
    }

    /** Node keys, most-run first. Never returns counts - see {@link #RANKING}. */
    public List<String> findRanking(int limit) {
        return jdbcTemplate.queryForList(RANKING, String.class, limit);
    }
}
