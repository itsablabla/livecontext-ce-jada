package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.LatestEpochHeaderRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WorkflowEpochRepository#getLatestEpochHeaderByRunIds} - the batch read behind
 * the "how did this automation's last fire end" badge.
 *
 * <p>Split in two on purpose. The {@link Executed} nested class runs the real SQL against H2, so
 * a spacing bug in the concatenation, a column that does not exist, or a construct H2 rejects
 * fails here rather than in production - {@code H2WorkflowEpochRepository} inherits this method
 * verbatim, and nothing else in the suite executes it. The {@link ShapeOfTheQuery} class keeps
 * the two properties H2 cannot demonstrate: that the run filter is applied to the OUTER relation
 * too (correctness is identical either way, cost is not), and that the newest header is chosen
 * by {@code epoch} rather than by {@code started_at}.
 */
@DisplayName("WorkflowEpochRepository.getLatestEpochHeaderByRunIds")
class WorkflowEpochRepositoryLatestHeaderQueryTest {

    private static final Instant FIRED_AT = Instant.parse("2026-09-06T10:00:00Z");

    @Nested
    @DisplayName("against a real database")
    class Executed {

        private JdbcTemplate jdbc;
        private WorkflowEpochRepository repo;

        @BeforeEach
        void createSchema() {
            // A private in-memory database per test: no shared state, no ordering coupling.
            DriverManagerDataSource ds = new DriverManagerDataSource(
                    "jdbc:h2:mem:epochs-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(ds);
            jdbc.execute("""
                    CREATE TABLE workflow_epochs (
                        run_id      VARCHAR(255) NOT NULL,
                        trigger_id  VARCHAR(255) NOT NULL,
                        epoch       INT          NOT NULL,
                        entry_type  VARCHAR(32)  NOT NULL,
                        entry_key   VARCHAR(255) NOT NULL,
                        status      VARCHAR(64)  NOT NULL,
                        epoch_state CLOB,
                        is_active   BOOLEAN,
                        started_at  TIMESTAMP,
                        closed_at   TIMESTAMP
                    )
                    """);
            repo = new WorkflowEpochRepository(jdbc);
        }

        @Test
        @DisplayName("Returns the run's HIGHEST epoch, and only for the runs asked about")
        void picksTheHighestEpochOfEachRequestedRun() {
            insertHeader("run-A", "trigger:chat", 1, false, FIRED_AT, FIRED_AT.plusSeconds(5));
            insertHeader("run-A", "trigger:chat", 7, false, FIRED_AT.plusSeconds(600), FIRED_AT.plusSeconds(630));
            insertHeader("run-B", "trigger:webhook", 3, true, FIRED_AT, null);
            // A run nobody asked about, and a counter row that is not a header: both must be
            // invisible to this read.
            insertHeader("run-C", "trigger:chat", 99, false, FIRED_AT, FIRED_AT);
            jdbc.update("INSERT INTO workflow_epochs (run_id, trigger_id, epoch, entry_type, entry_key, status)"
                    + " VALUES ('run-A', 'trigger:chat', 42, 'NODE', 'mcp:fetch', 'COMPLETED')");

            Map<String, LatestEpochHeaderRow> result = repo.getLatestEpochHeaderByRunIds(List.of("run-A", "run-B"));

            assertThat(result).containsOnlyKeys("run-A", "run-B");
            assertThat(result.get("run-A").epoch()).isEqualTo(7);
            assertThat(result.get("run-A").triggerId()).isEqualTo("trigger:chat");
            assertThat(result.get("run-A").isActive()).isFalse();
            assertThat(result.get("run-A").startedAt()).isEqualTo(FIRED_AT.plusSeconds(600));
            assertThat(result.get("run-A").closedAt()).isEqualTo(FIRED_AT.plusSeconds(630));
            assertThat(result.get("run-B").epoch()).isEqualTo(3);
            assertThat(result.get("run-B").isActive()).isTrue();
            assertThat(result.get("run-B").closedAt()).isNull();
        }

        @Test
        @DisplayName("The newest epoch is the newest FIRE even when an older one was reopened later")
        void aReopenedOlderEpochDoesNotBecomeTheNewest() {
            // A rerun-from-step reopens an old epoch and leaves its started_at alone but bumps
            // nothing else; ordering on a timestamp would still be wrong the other way round -
            // a reopened epoch that closes LAST would outrank the newest fire. The epoch number
            // is the fire order, which is what the badge is about.
            insertHeader("run-A", "trigger:chat", 2, false, FIRED_AT, FIRED_AT.plusSeconds(9_000));
            insertHeader("run-A", "trigger:chat", 3, false, FIRED_AT.plusSeconds(60), FIRED_AT.plusSeconds(90));

            assertThat(repo.getLatestEpochHeaderByRunIds(List.of("run-A")).get("run-A").epoch()).isEqualTo(3);
        }

        @Test
        @DisplayName("A run with no header at all is absent from the map, not present with a null")
        void neverFiredRunIsAbsent() {
            assertThat(repo.getLatestEpochHeaderByRunIds(List.of("run-never"))).isEmpty();
        }

        @Test
        @DisplayName("Legacy rows sharing one epoch across trigger ids resolve to the CLOSED header")
        void twoHeadersAtTheSameEpochResolveToTheClosedOne() {
            // The V2 to V3 migration left "trigger:default" rows beside real trigger ids, and the
            // header key includes trigger_id, so a legacy run can hold two rows at one epoch. The
            // active one cannot state an outcome, so the closed one has to win - whichever order
            // the driver returns them in.
            insertHeader("run-A", "trigger:default", 4, true, FIRED_AT, null);
            insertHeader("run-A", "trigger:chat", 4, false, FIRED_AT, FIRED_AT.plusSeconds(30));

            LatestEpochHeaderRow header = repo.getLatestEpochHeaderByRunIds(List.of("run-A")).get("run-A");

            assertThat(header.isActive()).isFalse();
            assertThat(header.closedAt()).isEqualTo(FIRED_AT.plusSeconds(30));
        }

        @Test
        @DisplayName("Two CLOSED headers at the same epoch: the later close is the more recent word")
        void twoClosedHeadersAtTheSameEpochPreferTheLaterClose() {
            insertHeader("run-A", "trigger:alpha", 4, false, FIRED_AT, FIRED_AT.plusSeconds(5));
            insertHeader("run-A", "trigger:zulu", 4, false, FIRED_AT, FIRED_AT.plusSeconds(50));

            LatestEpochHeaderRow header = repo.getLatestEpochHeaderByRunIds(List.of("run-A")).get("run-A");

            // Not the alphabetical tie-break - that only applies when nothing ranks them.
            assertThat(header.triggerId()).isEqualTo("trigger:zulu");
            assertThat(header.closedAt()).isEqualTo(FIRED_AT.plusSeconds(50));
        }

        @Test
        @DisplayName("Two OPEN headers at the same epoch resolve the same way whatever order they arrive in")
        void twoActiveHeadersAtTheSameEpochResolveDeterministically() {
            // Neither can state an outcome, so the outcome is null either way - but the row also
            // carries the trigger id the caller attributes the fire by, and the started_at it
            // prints. Letting the driver's row order decide those would make the same data
            // render differently between two polls.
            insertHeader("run-A", "trigger:zulu", 4, true, FIRED_AT.plusSeconds(60), null);
            insertHeader("run-A", "trigger:alpha", 4, true, FIRED_AT, null);

            LatestEpochHeaderRow first = repo.getLatestEpochHeaderByRunIds(List.of("run-A")).get("run-A");
            LatestEpochHeaderRow second = repo.getLatestEpochHeaderByRunIds(List.of("run-A")).get("run-A");

            assertThat(first.triggerId()).isEqualTo("trigger:alpha");
            assertThat(second.triggerId()).isEqualTo(first.triggerId());
            assertThat(second.startedAt()).isEqualTo(first.startedAt());
        }

        private void insertHeader(String runId, String triggerId, int epoch, boolean active,
                                  Instant startedAt, Instant closedAt) {
            jdbc.update("INSERT INTO workflow_epochs (run_id, trigger_id, epoch, entry_type, entry_key, status,"
                            + " epoch_state, is_active, started_at, closed_at)"
                            + " VALUES (?, ?, ?, 'EPOCH_HEADER', '_', '_', ?, ?, ?, ?)",
                    runId, triggerId, epoch, "{\"failedNodeIds\":[]}", active,
                    Timestamp.from(startedAt), closedAt != null ? Timestamp.from(closedAt) : null);
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("shape of the query")
    class ShapeOfTheQuery {

        @Mock private JdbcTemplate jdbcTemplate;
        private WorkflowEpochRepository repo;

        @BeforeEach
        void setUp() {
            repo = new WorkflowEpochRepository(jdbcTemplate);
        }

        @Test
        @DisplayName("Empty and null input short-circuit without touching the database")
        void emptyInputShortCircuits() {
            assertThat(repo.getLatestEpochHeaderByRunIds(List.of())).isEmpty();
            assertThat(repo.getLatestEpochHeaderByRunIds(null)).isEmpty();
            verify(jdbcTemplate, never()).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        }

        @Test
        @DisplayName("The run filter is on the OUTER relation too, with the ids bound twice")
        void theRunFilterIsRepeatedOnTheOuterRelation() {
            // Correctness does not need it - the join already restricts the result - but cost
            // does: Postgres will not push an IN list from the grouped subquery into the joined
            // table, so without this the largest table in the schema is scanned in full on an
            // endpoint the frontend polls. Nothing about the returned rows would reveal it.
            repo.getLatestEpochHeaderByRunIds(List.of("run-A", "run-B"));

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowCallbackHandler.class), argsCaptor.capture());

            String sql = sqlCaptor.getValue();
            assertThat(sql).contains("e.run_id IN (?, ?)");
            assertThat(argsCaptor.getValue()).containsExactly("run-A", "run-B", "run-A", "run-B");
        }

        @Test
        @DisplayName("The newest header is chosen by MAX(epoch) - the indexed, fire-ordered column")
        void theNewestHeaderIsChosenByEpoch() {
            // epoch is a GLOBAL per-run counter (TriggerEpochManager.incrementEpoch returns
            // previousGlobal + 1), so MAX(epoch) is the last fire - and it is indexed, which
            // started_at is not. What this test can check is the column the query groups on;
            // which of the two run_id indexes the planner picks is a cost question no unit test
            // can answer, so it is argued in the method's javadoc and left unasserted here.
            // started_at is rejected on correctness too: a reopened epoch keeps its original
            // stamp, so it is not even the fire it would be read as.
            repo.getLatestEpochHeaderByRunIds(List.of("run-A"));

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowCallbackHandler.class), any(Object[].class));

            String sql = sqlCaptor.getValue();
            assertThat(sql).contains("MAX(epoch) AS latest_epoch");
            // The trigger that fired it: the attribution key a per-trigger row needs.
            assertThat(sql).contains("e.trigger_id");
            assertThat(sql).doesNotContain("MAX(started_at)");
            assertThat(sql).contains("entry_type = 'EPOCH_HEADER'");
            assertThat(sql).contains("GROUP BY run_id");
        }

        @Test
        @DisplayName("A NULL closed_at maps to null, not to the epoch's own start time")
        void nullClosedAtStaysNull() throws Exception {
            ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
            when(rs.getString("run_id")).thenReturn("run-live");
            when(rs.getInt("epoch")).thenReturn(2);
            when(rs.getString("trigger_id")).thenReturn("trigger:chat");
            when(rs.getString("epoch_state")).thenReturn("{}");
            when(rs.getBoolean("is_active")).thenReturn(true);
            when(rs.getTimestamp("started_at")).thenReturn(Timestamp.from(FIRED_AT));
            when(rs.getTimestamp("closed_at")).thenReturn(null);
            doAnswer(invocation -> {
                invocation.getArgument(1, RowCallbackHandler.class).processRow(rs);
                return null;
            }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

            assertThat(repo.getLatestEpochHeaderByRunIds(List.of("run-live")).get("run-live").closedAt()).isNull();
        }
    }
}
