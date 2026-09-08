package com.apimarketplace.datasource.purge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.events.VectorDataSourceDeletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The datasource follower against a real Postgres, because its one added behaviour, dropping
 * the HNSW index of every vector table it purges, hinges on a text predicate over the stored
 * mapping_spec, and the first version's predicate matched nothing on real data: it looked for
 * 'VECTOR' while {@link ColumnType} serialises as "vector". A mock JdbcTemplate certified that
 * wrong literal. Here the spec is written by the same ObjectMapper the repository uses.
 *
 * <p>Runs when {@code DATASOURCE_TEST_PG_URL} is set (CI sets it; FAILS there if unset).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DatasourcePurgeFollower - real Postgres")
class DatasourcePurgeFollowerPostgresTest {

    private static final String URL = System.getenv("DATASOURCE_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("DATASOURCE_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("DATASOURCE_TEST_PG_PASSWORD", "postgres");
    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";

    private JdbcTemplate jdbc;
    private final List<Object> events = new ArrayList<>();
    private DatasourcePurgeFollower follower;

    @BeforeAll
    void setUpSchema() {
        if (URL == null || URL.isBlank()) {
            if (System.getenv("CI") != null && !System.getenv("CI").isBlank()) {
                throw new IllegalStateException("DATASOURCE_TEST_PG_URL is unset on CI; this test must run there");
            }
            Assumptions.abort("no scratch Postgres: set DATASOURCE_TEST_PG_URL to run this locally");
        }
        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException("DATASOURCE_TEST_PG_URL must name a scratch database containing 'test', got " + database);
        }
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS datasource");
        jdbc.execute("DROP TABLE IF EXISTS datasource.data_sources");
        jdbc.execute("DROP TABLE IF EXISTS datasource.purge_cursor");
        jdbc.execute("CREATE TABLE datasource.data_sources (id BIGSERIAL PRIMARY KEY, organization_id VARCHAR(255) NOT NULL, mapping_spec JSONB)");
        jdbc.execute("CREATE TABLE datasource.purge_cursor (id SMALLINT PRIMARY KEY CHECK (id = 1), last_seq BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbc.update("INSERT INTO datasource.purge_cursor (id) VALUES (1)");
        ApplicationEventPublisher publisher = events::add;
        follower = new DatasourcePurgeFollower(jdbc, mock(AuthClient.class), publisher, false);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE datasource.data_sources");
        jdbc.update("UPDATE datasource.purge_cursor SET last_seq = 0");
        events.clear();
    }

    /** A mapping_spec exactly as the repository stores it: written by the ObjectMapper. */
    private long table(String org, ColumnType type) throws Exception {
        String spec = new ObjectMapper().writeValueAsString(
                Map.of("col", Map.of("type", type, "dimension", 3)));
        return jdbc.queryForObject(
                "INSERT INTO datasource.data_sources (organization_id, mapping_spec) VALUES (?, ?::jsonb) RETURNING id",
                Long.class, org, spec);
    }

    @Test
    @DisplayName("Deletes the org's tables and publishes an index-drop event for exactly the vector ones")
    void dropsIndexesOfRealVectorSpecs() throws Exception {
        long vector = table(ORG, ColumnType.VECTOR);
        long text = table(ORG, ColumnType.TEXT);
        long elsewhere = table(OTHER_ORG, ColumnType.VECTOR);

        follower.purgeOrganization(ORG);

        assertThat(jdbc.queryForList("SELECT id FROM datasource.data_sources ORDER BY id", Long.class))
                .as("only the other org's table survives").containsExactly(elsewhere);
        assertThat(events).as("one drop per VECTOR table of the purged org, none for text or other orgs")
                .containsExactly(new VectorDataSourceDeletedEvent(vector));
        assertThat(events).doesNotContain(new VectorDataSourceDeletedEvent(text), new VectorDataSourceDeletedEvent(elsewhere));
    }

    @Test
    @DisplayName("The cursor advances monotonically and reads back")
    void cursorRoundTrip() {
        assertThat(follower.read()).isZero();
        follower.advanceTo(12L);
        follower.advanceTo(7L);
        assertThat(follower.read()).as("GREATEST: a late, lower write never moves it back").isEqualTo(12L);
    }
}
