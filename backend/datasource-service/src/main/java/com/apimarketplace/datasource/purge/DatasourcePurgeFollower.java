package com.apimarketplace.datasource.purge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.purge.PurgeFollower;
import com.apimarketplace.datasource.events.VectorDataSourceDeletedEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deletes the datasource schema's rows for a purged workspace, driven by
 * {@code auth.purge_log} (auth-client {@link PurgeFollower}).
 *
 * <p>This is the follower that makes the whole design worth it: {@code datasource.*} (user
 * tables and their vectors) is the schema that moves to its own database when customers
 * arrive, and until 2026-09-02 auth-service's purger reaching into it was the one thing that
 * forbade the move.
 *
 * <p><b>HNSW indexes are dropped explicitly.</b> A bulk DELETE never reaches
 * {@code DataSourceService.deleteDataSource}, so the per-table vector index would survive its
 * table as an orphan (exactly the gap the by-workflow delete had to close). The vector-carrying
 * ids are read BEFORE the delete, because afterwards nothing is left to enumerate.
 */
@Component
public class DatasourcePurgeFollower implements PurgeFollower.Handler, PurgeFollower.Cursor {

    public static final List<String> ORG_TABLES = List.of("datasource.data_sources");

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher eventPublisher;
    private final PurgeFollower follower;
    private final boolean enabled;

    public DatasourcePurgeFollower(JdbcTemplate jdbc, AuthClient authClient,
                                   ApplicationEventPublisher eventPublisher,
                                   @Value("${purge.follower.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.eventPublisher = eventPublisher;
        this.enabled = enabled;
        this.follower = new PurgeFollower("datasource", authClient, this, this);
    }

    @PostConstruct
    void start() {
        if (enabled) {
            follower.start();
        }
    }

    @PreDestroy
    void stop() {
        follower.stop();
    }

    @Override
    public long read() {
        Long seq = jdbc.queryForObject("SELECT last_seq FROM datasource.purge_cursor WHERE id = 1", Long.class);
        return seq == null ? 0L : seq;
    }

    @Override
    public void advanceTo(long seq) {
        jdbc.update("UPDATE datasource.purge_cursor SET last_seq = GREATEST(last_seq, ?), updated_at = now() WHERE id = 1", seq);
    }

    @Override
    public void purgeOrganization(String orgId) {
        // Any table whose mapping declares a vector column owns an HNSW index named after its
        // id. ColumnType serialises through @JsonValue as the LOWER-CASE word ("vector"), and
        // Postgres LIKE is case-sensitive: the first version matched '%VECTOR%' and therefore
        // matched nothing (caught in audit). ILIKE on the quoted value; a false positive (the
        // quoted word appearing elsewhere in the spec) costs one idempotent DROP INDEX IF
        // EXISTS, a miss would leave an orphan index, so the match stays deliberately broad.
        List<Long> vectorTables = jdbc.queryForList(
                "SELECT id FROM datasource.data_sources WHERE organization_id::text = ? "
                        + "AND mapping_spec::text ILIKE '%\"vector\"%'", Long.class, orgId);
        // Items and vectors cascade from data_sources (FK); the statement is the one the old
        // purger issued.
        jdbc.update("DELETE FROM datasource.data_sources WHERE organization_id::text = ?", orgId);
        for (Long id : vectorTables) {
            eventPublisher.publishEvent(new VectorDataSourceDeletedEvent(id));
        }
    }

    @Override
    public void purgeUser(String userId) {
        // Tables always belong to a workspace.
    }
}
