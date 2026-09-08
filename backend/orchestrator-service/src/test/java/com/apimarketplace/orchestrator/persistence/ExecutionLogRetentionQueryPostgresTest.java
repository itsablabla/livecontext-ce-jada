package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.common.storage.repository.ExecutionLogStorageRetentionRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres contract for the execution-log retention SQL.
 *
 * <p><b>Why this class had to exist.</b> Every other test of this feature is
 * Mockito-only, so nothing ran a single one of these queries against an engine.
 * That gap is not theoretical: while this feature was being written, a missing
 * {@code entry_type = 'EPOCH_HEADER'} predicate turned the candidate query into a
 * permanent no-op, and it was caught by hand-running SQL against production, not
 * by the 89 green assertions. The failure mode is the dangerous kind for a
 * deleting job: it returns a plausible answer (zero rows, or the wrong rows) and
 * never an error.
 *
 * <p><b>The SQL is read off the {@code @Query} annotations by reflection</b>, the
 * same trick as {@link AggregatedStepsQueryPostgresTest}. Copying the queries into
 * this file would let them pass against a string nothing ships.
 *
 * <p><b>How it runs.</b> Plain JDBC against the scratch database named by
 * {@code ORCHESTRATOR_TEST_PG_URL} (the {@code arc-build} runners expose no Docker
 * socket, so a Testcontainers class SKIPS there, which looks like coverage and is
 * not). With {@code CI} set and no URL the class FAILS rather than skipping, so it
 * cannot be silently disabled by dropping the env block from its workflow step.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("execution-log retention - real Postgres, the shipped SQL")
class ExecutionLogRetentionQueryPostgresTest {

    private static final String URL = System.getenv("ORCHESTRATOR_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_PASSWORD", "postgres");

    private static final String TENANT = "tenant-under-test";
    private static final String OTHER_TENANT = "tenant-next-door";
    private static final String ORG = "org-1";
    private static final String OTHER_ORG = "org-2";

    /** now - 90d: anything whose epoch closed before this is past the window. */
    private Instant cutoff;
    /** Grandfathering floor, well before every fixture unless a case says otherwise. */
    private Instant enforceFrom;

    private String candidatesSql;
    private String scopesSql;
    private String storageCandidatesSql;
    private NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    void setUpSchema() throws Exception {
        requireDatabaseOnCi();

        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL must point at a scratch database whose name contains "
                            + "'test' (this test drops tables in the orchestrator and storage schemas), got: "
                            + database);
        }

        candidatesSql = shippedSql(ExecutionLogStepDataRetentionRepository.class,
                "findPurgeCandidates", String.class, String.class, Instant.class, Instant.class, int.class);
        scopesSql = shippedSql(ExecutionLogStepDataRetentionRepository.class,
                "findScopesWithCandidates", Instant.class, Instant.class, String.class, String.class, int.class);
        storageCandidatesSql = shippedSql(ExecutionLogStorageRetentionRepository.class,
                "findCandidatesByIds", String.class, Collection.class);

        awaitDatabase();
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new NamedParameterJdbcTemplate(ds);

        // Only the columns the shipped queries read. A fuller mirror of the real
        // tables would drift; what this pins is the SQL's behaviour, not the schema.
        exec("CREATE SCHEMA IF NOT EXISTS orchestrator");
        exec("CREATE SCHEMA IF NOT EXISTS storage");
        exec("DROP TABLE IF EXISTS orchestrator.workflow_step_data");
        exec("DROP TABLE IF EXISTS orchestrator.workflow_epochs");
        exec("DROP TABLE IF EXISTS storage.storage");
        exec("""
             CREATE TABLE orchestrator.workflow_step_data (
                 id BIGSERIAL PRIMARY KEY,
                 run_id VARCHAR(255) NOT NULL,
                 epoch INTEGER,
                 tenant_id VARCHAR(255) NOT NULL,
                 -- NOT NULL in production; nullable here so the query's own guard
                 -- against a workspace-less row can be exercised.
                 organization_id VARCHAR(255),
                 start_time TIMESTAMPTZ,
                 output_storage_id UUID)
             """);
        exec("""
             CREATE TABLE orchestrator.workflow_epochs (
                 id BIGSERIAL PRIMARY KEY,
                 run_id VARCHAR(255) NOT NULL,
                 epoch INTEGER,
                 entry_type VARCHAR(32) NOT NULL,
                 -- NULLABLE, mirroring V1: a fixture that declares NOT NULL cannot
                 -- express the unknown state the query has to take a side on.
                 is_active BOOLEAN,
                 closed_at TIMESTAMPTZ)
             """);
        exec("""
             CREATE TABLE storage.storage (
                 id UUID PRIMARY KEY,
                 tenant_id VARCHAR(255) NOT NULL,
                 organization_id VARCHAR(255),
                 source_type VARCHAR(64),
                 storage_type VARCHAR(64),
                 file_name VARCHAR(255),
                 s3_key VARCHAR(1024),
                 size_bytes INTEGER,
                 status VARCHAR(32) NOT NULL,
                 is_folder BOOLEAN NOT NULL DEFAULT false)
             """);
    }

    @BeforeEach
    void resetFixtures() {
        exec("TRUNCATE orchestrator.workflow_step_data, orchestrator.workflow_epochs, storage.storage");
        Instant now = Instant.now();
        cutoff = now.minus(90, ChronoUnit.DAYS);
        enforceFrom = now.minus(365, ChronoUnit.DAYS);
    }

    // ===== step-data candidates =====

    @Nested
    @DisplayName("Which step rows the candidate query returns")
    class Candidates {

        /**
         * THE regression. workflow_epochs holds NODE and EDGE rows that are
         * permanently is_active=true with a null closed_at (production 2026-09-01:
         * 40,247 and 38,012 of them against 2,933 headers). Without the
         * entry_type filter, "is any row for this epoch still active" answers yes
         * for every epoch ever created, and the sweep silently returns nothing.
         */
        @Test
        @DisplayName("A closed epoch is a candidate even though its NODE and EDGE rows stay active forever")
        void nodeAndEdgeNoiseDoesNotHideAClosedEpoch() {
            closedEpoch("run-1", 0, cutoff.minus(5, ChronoUnit.DAYS));
            structuralRows("run-1", 0);
            long stepId = step("run-1", 0, ORG, TENANT, cutoff.minus(5, ChronoUnit.DAYS), null);

            assertThat(candidateIds()).containsExactly(stepId);
        }

        @Test
        @DisplayName("An epoch still running is never a candidate, however old its steps")
        void activeEpochIsNeverACandidate() {
            openEpoch("run-1", 0);
            step("run-1", 0, ORG, TENANT, cutoff.minus(200, ChronoUnit.DAYS), null);

            assertThat(candidateIds()).isEmpty();
        }

        @Test
        @DisplayName("An epoch closed after the cutoff is inside the window and kept")
        void recentlyClosedEpochIsKept() {
            closedEpoch("run-1", 0, cutoff.plus(1, ChronoUnit.DAYS));
            step("run-1", 0, ORG, TENANT, cutoff.minus(200, ChronoUnit.DAYS), null);

            assertThat(candidateIds()).isEmpty();
        }

        /**
         * A step with no epoch header cannot be shown to be finished, so it is
         * kept. Deleting it would be acting on absence of evidence.
         */
        @Test
        @DisplayName("A step whose epoch has no header at all is kept")
        void stepWithoutHeaderIsKept() {
            structuralRows("run-1", 0);
            step("run-1", 0, ORG, TENANT, cutoff.minus(5, ChronoUnit.DAYS), null);

            assertThat(candidateIds()).isEmpty();
        }

        /**
         * is_active is NULLABLE in production. Under {@code = true} a header with a
         * NULL is_active and an old closed_at satisfies none of the disqualifying
         * conditions and gets swept, i.e. an UNKNOWN state read as "finished". For a
         * job that deletes, unknown belongs on the retaining side.
         */
        @Test
        @DisplayName("A header whose is_active is NULL is unknown, not finished, and is kept")
        void nullIsActiveIsKept() {
            exec("INSERT INTO orchestrator.workflow_epochs (run_id, epoch, entry_type, is_active, closed_at) "
                            + "VALUES ('run-1', 0, 'EPOCH_HEADER', NULL, :closedAt)",
                    new MapSqlParameterSource().addValue("closedAt",
                            java.sql.Timestamp.from(cutoff.minus(5, ChronoUnit.DAYS))));
            step("run-1", 0, ORG, TENANT, cutoff.minus(5, ChronoUnit.DAYS), null);

            assertThat(candidateIds()).isEmpty();
        }

        @Test
        @DisplayName("One open header among several disqualifies the epoch")
        void anyOpenHeaderDisqualifies() {
            closedEpoch("run-1", 0, cutoff.minus(5, ChronoUnit.DAYS));
            openEpoch("run-1", 0);
            step("run-1", 0, ORG, TENANT, cutoff.minus(5, ChronoUnit.DAYS), null);

            assertThat(candidateIds()).isEmpty();
        }

        @Test
        @DisplayName("Grandfathering: a step created before enforce-from is never swept")
        void grandfatheredStepIsKept() {
            closedEpoch("run-1", 0, cutoff.minus(5, ChronoUnit.DAYS));
            step("run-1", 0, ORG, TENANT, enforceFrom.minus(1, ChronoUnit.DAYS), null);

            assertThat(candidateIds()).isEmpty();
        }

        @Test
        @DisplayName("A step with no start_time is kept: NULL is not 'old enough'")
        void nullStartTimeIsKept() {
            closedEpoch("run-1", 0, cutoff.minus(5, ChronoUnit.DAYS));
            step("run-1", 0, ORG, TENANT, null, null);

            assertThat(candidateIds()).isEmpty();
        }

        @Test
        @DisplayName("Another tenant's rows are never returned")
        void otherTenantIsInvisible() {
            closedEpoch("run-1", 0, cutoff.minus(5, ChronoUnit.DAYS));
            step("run-1", 0, ORG, OTHER_TENANT, cutoff.minus(5, ChronoUnit.DAYS), null);

            assertThat(candidateIds()).isEmpty();
        }

        /**
         * The window is the WORKSPACE's. The same tenant's rows in another
         * workspace belong to another scope with possibly another window, so a
         * query scoped to (org-1, tenant) must not reach them.
         */
        @Test
        @DisplayName("The same tenant's rows in another workspace are never returned")
        void otherWorkspaceOfSameTenantIsInvisible() {
            closedEpoch("run-1", 0, cutoff.minus(5, ChronoUnit.DAYS));
            long here = step("run-1", 0, ORG, TENANT, cutoff.minus(5, ChronoUnit.DAYS), null);
            step("run-1", 0, OTHER_ORG, TENANT, cutoff.minus(5, ChronoUnit.DAYS), null);

            assertThat(candidateIds()).containsExactly(here);
        }

        @Test
        @DisplayName("The payload id travels with the candidate")
        void payloadIdIsReturned() {
            UUID payload = UUID.randomUUID();
            closedEpoch("run-1", 0, cutoff.minus(5, ChronoUnit.DAYS));
            step("run-1", 0, ORG, TENANT, cutoff.minus(5, ChronoUnit.DAYS), payload);

            List<UUID> payloads = jdbc.query(candidatesSql, candidateParams(10),
                    (rs, i) -> (UUID) rs.getObject("outputStorageId"));
            assertThat(payloads).containsExactly(payload);
        }
    }

    // ===== tenant paging =====

    @Nested
    @DisplayName("Scope paging (workspace x tenant)")
    class ScopePaging {

        /**
         * Keyset on the PAIR. The page after (org-1, tenant-b) must start at
         * (org-2, tenant-a), not at "the next tenant after tenant-b" regardless
         * of workspace: a plain tenant bound would skip org-2's tenant-a, whose
         * name sorts before tenant-b.
         */
        @Test
        @DisplayName("Keyset on the pair: the bound is exclusive and the next page starts at the next (workspace, tenant)")
        void keysetBoundIsExclusiveOnThePair() {
            closedEpoch("run-1", 0, cutoff.minus(5, ChronoUnit.DAYS));
            step("run-1", 0, ORG, "tenant-a", cutoff.minus(5, ChronoUnit.DAYS), null);
            step("run-1", 0, ORG, "tenant-b", cutoff.minus(5, ChronoUnit.DAYS), null);
            step("run-1", 0, OTHER_ORG, "tenant-a", cutoff.minus(5, ChronoUnit.DAYS), null);

            assertThat(scopesAfter("", "", 10))
                    .containsExactly("org-1|tenant-a", "org-1|tenant-b", "org-2|tenant-a");
            assertThat(scopesAfter(ORG, "tenant-a", 10))
                    .containsExactly("org-1|tenant-b", "org-2|tenant-a");
            assertThat(scopesAfter(ORG, "tenant-b", 10))
                    .as("a tenant-only bound would have skipped org-2's tenant-a")
                    .containsExactly("org-2|tenant-a");
            assertThat(scopesAfter(OTHER_ORG, "tenant-a", 10)).isEmpty();
        }

        @Test
        @DisplayName("One tenant in two workspaces is two scopes")
        void sameTenantInTwoWorkspacesIsTwoScopes() {
            closedEpoch("run-1", 0, cutoff.minus(5, ChronoUnit.DAYS));
            step("run-1", 0, ORG, TENANT, cutoff.minus(5, ChronoUnit.DAYS), null);
            step("run-1", 0, OTHER_ORG, TENANT, cutoff.minus(5, ChronoUnit.DAYS), null);

            assertThat(scopesAfter("", "", 10))
                    .containsExactly("org-1|" + TENANT, "org-2|" + TENANT);
        }

        /**
         * A row that names no workspace has no window, and no window means retain.
         * The column is NOT NULL in production; this pins the guard for schemas
         * where it is not.
         */
        @Test
        @DisplayName("A row with no workspace never forms a scope")
        void workspacelessRowIsNeverAScope() {
            closedEpoch("run-1", 0, cutoff.minus(5, ChronoUnit.DAYS));
            step("run-1", 0, null, TENANT, cutoff.minus(5, ChronoUnit.DAYS), null);

            assertThat(scopesAfter("", "", 10)).isEmpty();
        }

        @Test
        @DisplayName("A scope with only live epochs never appears in the page")
        void scopeWithoutCandidatesIsAbsent() {
            openEpoch("run-1", 0);
            step("run-1", 0, ORG, "tenant-a", cutoff.minus(5, ChronoUnit.DAYS), null);

            assertThat(scopesAfter("", "", 10)).isEmpty();
        }
    }

    // ===== storage payload candidates =====

    @Nested
    @DisplayName("Which payload rows the storage query offers")
    class StorageCandidates {

        /**
         * The veto is stated in SQL as well as in Java so that an object-backed row
         * cannot even enter a result set. This asserts the SQL half.
         */
        @Test
        @DisplayName("A row backed by an object is filtered out by the query itself")
        void objectBackedRowNeverReachesJava() {
            UUID withObject = storageRow("STEP_OUTPUT", "S3_FILE", "clip.mp4", "1/a/clip.mp4", TENANT, false);
            UUID inDatabase = storageRow("STEP_OUTPUT", "JSON", null, null, TENANT, false);

            assertThat(storageCandidateIds(List.of(withObject, inDatabase)))
                    .containsExactly(inDatabase);
        }

        @Test
        @DisplayName("A folder row is filtered out even with no object behind it")
        void folderRowIsFiltered() {
            UUID folder = storageRow("FOLDER", "JSON", null, null, TENANT, true);

            assertThat(storageCandidateIds(List.of(folder))).isEmpty();
        }

        /**
         * output_storage_id has no foreign key, so nothing at the database level
         * guarantees the target belongs to the referring tenant. A mis-copied
         * reference must not become a cross-tenant delete.
         */
        @Test
        @DisplayName("An id belonging to another tenant is refused even when asked for by id")
        void crossTenantIdIsRefused() {
            UUID foreign = storageRow("STEP_OUTPUT", "JSON", null, null, OTHER_TENANT, false);

            assertThat(storageCandidateIds(List.of(foreign))).isEmpty();
        }

        @Test
        @DisplayName("The columns the purger rules on all come back populated")
        void projectionCarriesEveryColumnTheAllowListNeeds() {
            UUID id = storageRow("SKIPPED_NODE", "JSON", null, null, TENANT, false);

            var row = jdbc.queryForMap(storageCandidatesSql, storageParams(List.of(id)));
            assertThat(row).containsKeys("id", "sourceType", "storageType", "fileName",
                    "s3Key", "sizeBytes", "status", "tenantId", "organizationId");
            assertThat(row.get("sourceType")).isEqualTo("SKIPPED_NODE");
        }
    }

    // ===== helpers =====

    private List<Long> candidateIds() {
        return jdbc.query(candidatesSql, candidateParams(100), (rs, i) -> rs.getLong("id"));
    }

    private MapSqlParameterSource candidateParams(int batchSize) {
        return new MapSqlParameterSource()
                .addValue("organizationId", ORG)
                .addValue("tenantId", TENANT)
                .addValue("closedBefore", java.sql.Timestamp.from(cutoff))
                .addValue("enforceFrom", java.sql.Timestamp.from(enforceFrom))
                .addValue("batchSize", batchSize);
    }

    /** Scopes as "organizationId|tenantId", in the order the query returns them. */
    private List<String> scopesAfter(String afterOrganizationId, String afterTenantId, int limit) {
        return jdbc.query(scopesSql, new MapSqlParameterSource()
                .addValue("closedBefore", java.sql.Timestamp.from(cutoff))
                .addValue("enforceFrom", java.sql.Timestamp.from(enforceFrom))
                .addValue("afterOrganizationId", afterOrganizationId)
                .addValue("afterTenantId", afterTenantId)
                .addValue("limit", limit),
                (rs, i) -> rs.getString("organizationId") + "|" + rs.getString("tenantId"));
    }

    private List<UUID> storageCandidateIds(List<UUID> ids) {
        return jdbc.query(storageCandidatesSql, storageParams(ids), (rs, i) -> (UUID) rs.getObject("id"));
    }

    private MapSqlParameterSource storageParams(List<UUID> ids) {
        return new MapSqlParameterSource().addValue("tenantId", TENANT).addValue("ids", ids);
    }

    private void closedEpoch(String runId, int epoch, Instant closedAt) {
        exec("INSERT INTO orchestrator.workflow_epochs (run_id, epoch, entry_type, is_active, closed_at) "
                        + "VALUES (:runId, :epoch, 'EPOCH_HEADER', false, :closedAt)",
                new MapSqlParameterSource().addValue("runId", runId).addValue("epoch", epoch)
                        .addValue("closedAt", java.sql.Timestamp.from(closedAt)));
    }

    private void openEpoch(String runId, int epoch) {
        exec("INSERT INTO orchestrator.workflow_epochs (run_id, epoch, entry_type, is_active, closed_at) "
                        + "VALUES (:runId, :epoch, 'EPOCH_HEADER', true, NULL)",
                new MapSqlParameterSource().addValue("runId", runId).addValue("epoch", epoch));
    }

    /** The NODE and EDGE rows that are always active and always unclosed. */
    private void structuralRows(String runId, int epoch) {
        for (String type : List.of("NODE", "EDGE")) {
            exec("INSERT INTO orchestrator.workflow_epochs (run_id, epoch, entry_type, is_active, closed_at) "
                            + "VALUES (:runId, :epoch, :type, true, NULL)",
                    new MapSqlParameterSource().addValue("runId", runId).addValue("epoch", epoch)
                            .addValue("type", type));
        }
    }

    private long step(String runId, int epoch, String organizationId, String tenantId,
                      Instant startTime, UUID payloadId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("runId", runId).addValue("epoch", epoch)
                .addValue("organizationId", organizationId).addValue("tenantId", tenantId)
                .addValue("startTime", startTime == null ? null : java.sql.Timestamp.from(startTime))
                .addValue("payloadId", payloadId);
        return jdbc.queryForObject(
                "INSERT INTO orchestrator.workflow_step_data "
                        + "(run_id, epoch, organization_id, tenant_id, start_time, output_storage_id) "
                        + "VALUES (:runId, :epoch, :organizationId, :tenantId, :startTime, :payloadId) RETURNING id",
                params, Long.class);
    }

    private UUID storageRow(String sourceType, String storageType, String fileName, String s3Key,
                            String tenantId, boolean isFolder) {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO storage.storage (id, tenant_id, organization_id, source_type, storage_type, "
                        + "file_name, s3_key, size_bytes, status, is_folder) "
                        + "VALUES (:id, :tenantId, :org, :sourceType, :storageType, :fileName, :s3Key, "
                        + "100, 'ACTIVE', :isFolder)",
                new MapSqlParameterSource().addValue("id", id).addValue("tenantId", tenantId)
                        .addValue("org", ORG).addValue("sourceType", sourceType)
                        .addValue("storageType", storageType).addValue("fileName", fileName)
                        .addValue("s3Key", s3Key).addValue("isFolder", isFolder));
        return id;
    }

    private void exec(String sql) {
        jdbc.getJdbcTemplate().execute(sql);
    }

    private void exec(String sql, MapSqlParameterSource params) {
        jdbc.update(sql, params);
    }

    /**
     * The SQL as the application ships it, read off the repository method's
     * {@code @Query}. Copying it here would let this class pass against a string
     * nothing runs.
     */
    private static String shippedSql(Class<?> repository, String method, Class<?>... params)
            throws NoSuchMethodException {
        Query query = repository.getMethod(method, params).getAnnotation(Query.class);
        if (query == null || !query.nativeQuery()) {
            throw new IllegalStateException(method + " no longer carries a native @Query. If the "
                    + "retention SQL moved, move this test with it: it is the only place these "
                    + "queries run against a real engine, and they delete data.");
        }
        return query.value();
    }

    private static void requireDatabaseOnCi() {
        if (URL != null && !URL.isBlank()) {
            return;
        }
        boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
        if (onCi) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL is unset on CI. This class must execute there: it is "
                            + "the only test that runs the execution-log retention SQL against a real "
                            + "engine, and that SQL DELETES. Its known failure mode is a plausible "
                            + "wrong answer (a missing entry_type filter silently matched nothing), "
                            + "never an error. Restore the env block on the workflow step that runs "
                            + "it, and keep that step in a job carrying the postgres service.");
        }
        Assumptions.abort(
                "no scratch Postgres: set ORCHESTRATOR_TEST_PG_URL to run this locally "
                        + "(CI always sets it)");
    }

    private static void awaitDatabase() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
                return;
            } catch (Exception e) {
                last = new IllegalStateException("cannot reach " + URL, e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }
}
