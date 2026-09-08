package com.apimarketplace.agent.repository;

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
 * Real-Postgres contract for the agent half of execution-log retention.
 *
 * <p><b>The query this class exists for is the termination predicate.</b> The
 * sweep deliberately KEEPS the {@code agent_executions} row and deletes only its
 * journal, while the candidate query selects on that kept row. If the journal
 * EXISTS clause is dropped, nothing the sweep deletes ever leaves the candidate
 * set: a tenant holding a full batch is handed the same page forever and the
 * sweeper loops until the pod dies. A mocked repository cannot show this, because
 * it has no rows to lose, so the unit test can only prove the LOOP is correct
 * given correct SQL. This proves the SQL.
 *
 * <p>The SQL is read off the {@code @Query} annotations by reflection, so a copy
 * cannot drift from what ships. Runs against the scratch database named by
 * {@code AGENT_TEST_PG_URL}; with {@code CI} set and no URL it FAILS rather than
 * skipping, so it cannot be silently disabled by dropping the env block.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("agent execution-log retention - real Postgres, the shipped SQL")
class AgentExecutionLogRetentionQueryPostgresTest {

    private static final String URL = System.getenv("AGENT_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("AGENT_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("AGENT_TEST_PG_PASSWORD", "postgres");

    private static final String TENANT = "tenant-under-test";
    private static final String OTHER_TENANT = "tenant-next-door";
    private static final String ORG = "org-1";
    private static final String OTHER_ORG = "org-2";

    private Instant cutoff;
    private Instant enforceFrom;

    private String candidatesSql;
    private String scopesSql;
    private String payloadsSql;
    private NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    void setUpSchema() throws Exception {
        requireDatabaseOnCi();

        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException(
                    "AGENT_TEST_PG_URL must point at a scratch database whose name contains 'test' "
                            + "(this test drops tables in the agent schema), got: " + database);
        }

        candidatesSql = shippedSql("findPurgeableExecutionIds",
                String.class, String.class, Collection.class, Instant.class, Instant.class, int.class);
        scopesSql = shippedSql("findScopesWithCandidates",
                Collection.class, Instant.class, Instant.class, String.class, String.class, int.class);
        payloadsSql = shippedSql("findPayloadIds", Collection.class);

        awaitDatabase();
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new NamedParameterJdbcTemplate(ds);

        exec("CREATE SCHEMA IF NOT EXISTS agent");
        exec("DROP TABLE IF EXISTS agent.agent_execution_messages");
        exec("DROP TABLE IF EXISTS agent.agent_execution_tool_calls");
        exec("DROP TABLE IF EXISTS agent.agent_execution_iterations");
        exec("DROP TABLE IF EXISTS agent.agent_executions");
        exec("""
             CREATE TABLE agent.agent_executions (
                 id UUID PRIMARY KEY,
                 tenant_id VARCHAR(255) NOT NULL,
                 -- NOT NULL in production; nullable here to exercise the query's guard.
                 organization_id VARCHAR(255),
                 status VARCHAR(20) NOT NULL,
                 ended_at TIMESTAMPTZ,
                 created_at TIMESTAMPTZ NOT NULL)
             """);
        exec("""
             CREATE TABLE agent.agent_execution_messages (
                 id BIGSERIAL PRIMARY KEY,
                 execution_id UUID NOT NULL,
                 content_storage_id UUID)
             """);
        exec("""
             CREATE TABLE agent.agent_execution_tool_calls (
                 id BIGSERIAL PRIMARY KEY,
                 execution_id UUID NOT NULL,
                 content_storage_id UUID)
             """);
        exec("""
             CREATE TABLE agent.agent_execution_iterations (
                 id BIGSERIAL PRIMARY KEY,
                 execution_id UUID NOT NULL)
             """);
    }

    @BeforeEach
    void resetFixtures() {
        exec("TRUNCATE agent.agent_executions, agent.agent_execution_messages, "
                + "agent.agent_execution_tool_calls, agent.agent_execution_iterations");
        Instant now = Instant.now();
        cutoff = now.minus(90, ChronoUnit.DAYS);
        enforceFrom = now.minus(365, ChronoUnit.DAYS);
    }

    @Nested
    @DisplayName("Termination")
    class Termination {

        /**
         * THE reason this class exists. Without the journal EXISTS clause the sweep
         * never converges, because it keeps the row the query selects on.
         */
        @Test
        @DisplayName("An execution whose journal is already deleted leaves the candidate set")
        void sweptExecutionStopsBeingACandidate() {
            UUID execution = terminalExecution(ORG, TENANT, "COMPLETED", cutoff.minus(5, ChronoUnit.DAYS));
            message(execution, null);

            assertThat(candidateIds()).containsExactly(execution);

            exec("DELETE FROM agent.agent_execution_messages WHERE execution_id = :id",
                    new MapSqlParameterSource().addValue("id", execution));

            assertThat(candidateIds())
                    .as("the execution row survives on purpose, so only the journal check can end the sweep")
                    .isEmpty();
        }

        @Test
        @DisplayName("Any one of the three journal tables keeps an execution in the set")
        void anyRemainingJournalKeepsIt() {
            UUID onlyToolCalls = terminalExecution(ORG, TENANT, "COMPLETED", cutoff.minus(5, ChronoUnit.DAYS));
            toolCall(onlyToolCalls, null);
            UUID onlyIterations = terminalExecution(ORG, TENANT, "FAILED", cutoff.minus(5, ChronoUnit.DAYS));
            iteration(onlyIterations);

            assertThat(candidateIds()).containsExactlyInAnyOrder(onlyToolCalls, onlyIterations);
        }

        @Test
        @DisplayName("A scope whose executions are all swept disappears from the scope page")
        void sweptScopeLeavesThePage() {
            UUID execution = terminalExecution(ORG, TENANT, "COMPLETED", cutoff.minus(5, ChronoUnit.DAYS));
            message(execution, null);

            assertThat(scopesAfter("", "")).containsExactly(ORG + "|" + TENANT);

            exec("DELETE FROM agent.agent_execution_messages WHERE execution_id = :id",
                    new MapSqlParameterSource().addValue("id", execution));

            assertThat(scopesAfter("", "")).isEmpty();
        }
    }

    @Nested
    @DisplayName("What may be swept")
    class Eligibility {

        @Test
        @DisplayName("A running execution is never a candidate, however old")
        void runningExecutionIsNeverACandidate() {
            UUID running = execution(ORG, TENANT, "RUNNING", cutoff.minus(200, ChronoUnit.DAYS),
                    cutoff.minus(200, ChronoUnit.DAYS));
            message(running, null);

            assertThat(candidateIds()).isEmpty();
        }

        /**
         * The allow-list is the point: a status invented later counts as live.
         */
        @Test
        @DisplayName("A status this codebase has never heard of is treated as live and kept")
        void unknownStatusIsKept() {
            UUID unknown = execution(ORG, TENANT, "PAUSED_FOR_REVIEW", cutoff.minus(200, ChronoUnit.DAYS),
                    cutoff.minus(200, ChronoUnit.DAYS));
            message(unknown, null);

            assertThat(candidateIds()).isEmpty();
        }

        @Test
        @DisplayName("The age key is ended_at: a long run that finished recently is kept")
        void ageIsMeasuredFromTheEnd() {
            UUID longRun = execution(ORG, TENANT, "COMPLETED", cutoff.plus(1, ChronoUnit.DAYS),
                    cutoff.minus(300, ChronoUnit.DAYS));
            message(longRun, null);

            assertThat(candidateIds()).isEmpty();
        }

        @Test
        @DisplayName("An execution with no ended_at is kept")
        void nullEndedAtIsKept() {
            UUID unended = execution(ORG, TENANT, "COMPLETED", null, cutoff.minus(200, ChronoUnit.DAYS));
            message(unended, null);

            assertThat(candidateIds()).isEmpty();
        }

        @Test
        @DisplayName("Grandfathering: an execution created before enforce-from is never swept")
        void grandfatheredExecutionIsKept() {
            UUID old = execution(ORG, TENANT, "COMPLETED", cutoff.minus(5, ChronoUnit.DAYS),
                    enforceFrom.minus(1, ChronoUnit.DAYS));
            message(old, null);

            assertThat(candidateIds()).isEmpty();
        }

        @Test
        @DisplayName("Another tenant's executions are never returned")
        void otherTenantIsInvisible() {
            UUID foreign = terminalExecution(ORG, OTHER_TENANT, "COMPLETED", cutoff.minus(5, ChronoUnit.DAYS));
            message(foreign, null);

            assertThat(candidateIds()).isEmpty();
        }

        /**
         * The window is the WORKSPACE's: the same tenant's executions in another
         * workspace form another scope, with possibly another window.
         */
        @Test
        @DisplayName("The same tenant's executions in another workspace are never returned")
        void otherWorkspaceOfSameTenantIsInvisible() {
            UUID here = terminalExecution(ORG, TENANT, "COMPLETED", cutoff.minus(5, ChronoUnit.DAYS));
            message(here, null);
            UUID elsewhere = terminalExecution(OTHER_ORG, TENANT, "COMPLETED", cutoff.minus(5, ChronoUnit.DAYS));
            message(elsewhere, null);

            assertThat(candidateIds()).containsExactly(here);
        }
    }

    @Nested
    @DisplayName("Scope paging (workspace x tenant)")
    class ScopePaging {

        /**
         * Keyset on the PAIR: after (org-1, tenant-under-test) the next page starts
         * at (org-2, tenant-next-door), whose tenant sorts BEFORE the bound's
         * tenant and would be skipped by a tenant-only bound.
         */
        @Test
        @DisplayName("Keyset on the pair: the bound is exclusive and the next workspace's earlier tenant is not skipped")
        void keysetBoundIsExclusiveOnThePair() {
            message(terminalExecution(ORG, TENANT, "COMPLETED", cutoff.minus(5, ChronoUnit.DAYS)), null);
            message(terminalExecution(OTHER_ORG, OTHER_TENANT, "COMPLETED", cutoff.minus(5, ChronoUnit.DAYS)), null);

            assertThat(scopesAfter("", ""))
                    .containsExactly(ORG + "|" + TENANT, OTHER_ORG + "|" + OTHER_TENANT);
            assertThat(scopesAfter(ORG, TENANT))
                    .as("tenant-next-door < tenant-under-test, so a tenant-only bound would skip it")
                    .containsExactly(OTHER_ORG + "|" + OTHER_TENANT);
            assertThat(scopesAfter(OTHER_ORG, OTHER_TENANT)).isEmpty();
        }

        @Test
        @DisplayName("An execution with no workspace never forms a scope")
        void workspacelessExecutionIsNeverAScope() {
            message(terminalExecution(null, TENANT, "COMPLETED", cutoff.minus(5, ChronoUnit.DAYS)), null);

            assertThat(scopesAfter("", "")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Payload links")
    class PayloadLinks {

        @Test
        @DisplayName("Ids come from both journal tables, de-duplicated, nulls excluded")
        void payloadsUnionBothTables() {
            UUID execution = terminalExecution(ORG, TENANT, "COMPLETED", cutoff.minus(5, ChronoUnit.DAYS));
            UUID fromMessage = UUID.randomUUID();
            UUID fromToolCall = UUID.randomUUID();
            message(execution, fromMessage);
            message(execution, null);
            toolCall(execution, fromToolCall);
            toolCall(execution, fromMessage);

            List<UUID> payloads = jdbc.query(payloadsSql,
                    new MapSqlParameterSource().addValue("executionIds", List.of(execution)),
                    (rs, i) -> (UUID) rs.getObject(1));

            assertThat(payloads).containsExactlyInAnyOrder(fromMessage, fromToolCall);
        }
    }

    // ===== helpers =====

    private List<UUID> candidateIds() {
        return jdbc.query(candidatesSql, new MapSqlParameterSource()
                .addValue("organizationId", ORG)
                .addValue("tenantId", TENANT)
                .addValue("terminalStatuses", ExecutionLogAgentRetentionRepository.TERMINAL_STATUSES)
                .addValue("endedBefore", java.sql.Timestamp.from(cutoff))
                .addValue("enforceFrom", java.sql.Timestamp.from(enforceFrom))
                .addValue("batchSize", 100), (rs, i) -> (UUID) rs.getObject(1));
    }

    /** Scopes as "organizationId|tenantId", in the order the query returns them. */
    private List<String> scopesAfter(String afterOrganizationId, String afterTenantId) {
        return jdbc.query(scopesSql, new MapSqlParameterSource()
                .addValue("terminalStatuses", ExecutionLogAgentRetentionRepository.TERMINAL_STATUSES)
                .addValue("endedBefore", java.sql.Timestamp.from(cutoff))
                .addValue("enforceFrom", java.sql.Timestamp.from(enforceFrom))
                .addValue("afterOrganizationId", afterOrganizationId)
                .addValue("afterTenantId", afterTenantId)
                .addValue("limit", 100),
                (rs, i) -> rs.getString("organizationId") + "|" + rs.getString("tenantId"));
    }

    private UUID terminalExecution(String organizationId, String tenantId, String status, Instant endedAt) {
        return execution(organizationId, tenantId, status, endedAt, endedAt);
    }

    private UUID execution(String organizationId, String tenantId, String status,
                           Instant endedAt, Instant createdAt) {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO agent.agent_executions (id, organization_id, tenant_id, status, ended_at, created_at) "
                        + "VALUES (:id, :organizationId, :tenantId, :status, :endedAt, :createdAt)",
                new MapSqlParameterSource().addValue("id", id)
                        .addValue("organizationId", organizationId).addValue("tenantId", tenantId)
                        .addValue("status", status)
                        .addValue("endedAt", endedAt == null ? null : java.sql.Timestamp.from(endedAt))
                        .addValue("createdAt", java.sql.Timestamp.from(createdAt)));
        return id;
    }

    private void message(UUID executionId, UUID payloadId) {
        exec("INSERT INTO agent.agent_execution_messages (execution_id, content_storage_id) "
                        + "VALUES (:executionId, :payloadId)",
                new MapSqlParameterSource().addValue("executionId", executionId)
                        .addValue("payloadId", payloadId));
    }

    private void toolCall(UUID executionId, UUID payloadId) {
        exec("INSERT INTO agent.agent_execution_tool_calls (execution_id, content_storage_id) "
                        + "VALUES (:executionId, :payloadId)",
                new MapSqlParameterSource().addValue("executionId", executionId)
                        .addValue("payloadId", payloadId));
    }

    private void iteration(UUID executionId) {
        exec("INSERT INTO agent.agent_execution_iterations (execution_id) VALUES (:executionId)",
                new MapSqlParameterSource().addValue("executionId", executionId));
    }

    private void exec(String sql) {
        jdbc.getJdbcTemplate().execute(sql);
    }

    private void exec(String sql, MapSqlParameterSource params) {
        jdbc.update(sql, params);
    }

    private static String shippedSql(String method, Class<?>... params) throws NoSuchMethodException {
        Query query = ExecutionLogAgentRetentionRepository.class.getMethod(method, params)
                .getAnnotation(Query.class);
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
                    "AGENT_TEST_PG_URL is unset on CI. This class must execute there: it is the only "
                            + "test that runs the agent retention SQL against a real engine, and its "
                            + "subject is a sweep that never terminates if one EXISTS clause is lost. "
                            + "Restore the env block on the workflow step that runs it, and keep that "
                            + "step in a job carrying the postgres service.");
        }
        Assumptions.abort("no scratch Postgres: set AGENT_TEST_PG_URL to run this locally "
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
