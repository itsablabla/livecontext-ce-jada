package com.apimarketplace.orchestrator.tools.workflow.builder;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V465 files the generate node with the AI family, in the two columns an agent
 * actually reads, and rewrites the addressing examples that live inside the
 * jsonb text.
 *
 * <p>{@code variable_prefix} is the one that bites: it is how the node library
 * GROUPS the node, and it is the prefix the documentation tells an agent to
 * address the output with. Left at {@code core} the row would document
 * {@code {{core:<label>.output.file}}} for a node the engine registers as
 * {@code agent:<label>}, and every reference written from that row resolves to
 * nothing, silently, because an unresolved template is an empty string rather
 * than an error. This migration is the ONLY thing that fixes the database half
 * of the move; code cannot.
 *
 * <p><b>How it runs, and why not Testcontainers.</b> Over plain JDBC, following
 * {@code CredentialSelectorDocsV453MigrationPostgresTest}: the {@code arc-build}
 * CI runners expose no Docker socket, so a {@code @Testcontainers} class there
 * does not fail, it SKIPS - indistinguishable from having no test while looking
 * like coverage. This file started as one, which meant the only proof of the
 * migration ran nowhere. CI provides a {@code postgres:16-alpine} service and
 * sets {@code ORCHESTRATOR_TEST_PG_URL}; with {@code CI} set and no URL this
 * class REFUSES to skip, so moving it out of that job breaks the build instead
 * of quietly disabling it.
 *
 * <p>It creates and drops {@code node_type_documentation}, so it refuses to
 * start unless the database name contains {@code test}. Locally:
 * {@code ORCHESTRATOR_TEST_PG_URL=jdbc:postgresql://localhost:5432/lc_orch_test
 * mvn -pl orchestrator-service test -Dtest=GenerateNodeAiFamilyDocsV465PostgresTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V465 generate joins the AI family - real Postgres, real migration SQL")
class GenerateNodeAiFamilyDocsV465PostgresTest {

    private static final String MIGRATION = "V465__generate_node_moves_to_ai.sql";

    private static final String URL = System.getenv("ORCHESTRATOR_TEST_PG_URL");
    private static final String USER =
            System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_USER", "postgres");
    private static final String PASSWORD =
            System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_PASSWORD", "postgres");

    /** The concept the migration matches on, as V429 wrote it. */
    private static final String MEDIA_CONCEPT =
            "Chain into core:media to edit what was generated: generate a clip, generate a "
                    + "voice over, then mux_audio the two together.";

    /**
     * The concat example the media row ships, as V410/V462 wrote it.
     *
     * <p>It names the two clips as core: nodes because a generate node WAS one.
     * The same example lives in the generate help, which now spells them agent:,
     * and two agent-facing copies of one example must not disagree: whichever an
     * agent reads first, the other one resolves to an empty string.
     */
    private static final String MEDIA_CONCAT_EXAMPLE =
            "Compile clips: inputs: [{source: '{{core:intro.output.file}}'}, "
                    + "{source: '{{core:demo.output.file}}'}, "
                    + "{source: '{{core:outro.output.file}}'}]";

    private String migrationSql;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setUpSchema() {
        requireDatabaseOnCi();

        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL must point at a scratch database whose name contains "
                            + "'test' (this test drops node_type_documentation), got: " + database);
        }

        migrationSql = loadMigration();
        if (migrationSql == null) {
            throw new IllegalStateException(
                    "cannot read " + MIGRATION + " from the module working directory. The test "
                            + "reads the SHIPPED migration on purpose: a copy of the SQL inlined "
                            + "here would pass while the real file was broken.");
        }
        // The shipped file opens with `SET search_path TO orchestrator`, which the
        // fixture below has no schema for. Stripped rather than mirrored: creating
        // an `orchestrator` schema on a shared scratch database would collide with
        // the other Postgres tests in this job.
        migrationSql = migrationSql.replace("SET search_path TO orchestrator;", "");

        awaitDatabase();
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("DROP TABLE IF EXISTS node_type_documentation");
        jdbc.execute("""
                CREATE TABLE node_type_documentation (
                    type            VARCHAR(128) PRIMARY KEY,
                    category        VARCHAR(64),
                    variable_prefix VARCHAR(64),
                    outputs         JSONB,
                    parameters      JSONB,
                    concepts        JSONB,
                    examples        JSONB,
                    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """);
    }

    /**
     * The row as V429/V433 left it, plus the two neighbours a missing WHERE would
     * take with it: {@code media} (a real core, carrying its OWN sentence naming
     * core:media, which is the string the concepts rewrite matches on) and
     * {@code browser_agent}.
     */
    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE node_type_documentation");
        jdbc.update("INSERT INTO node_type_documentation "
                        + "(type, category, variable_prefix, outputs, parameters, concepts) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)",
                "generate", "core", "core",
                """
                {"file": {"type": "object", "description": "The generated asset, stored so it outlives the provider's own link. Reference via {{core:<label>.output.file}} and map the WHOLE object into a downstream file param, never .path or a URL."}}
                """,
                """
                {"input_image": {"type": "object", "description": "A reference image or first frame: the WHOLE FileRef output of an upstream node as a whole-value template (e.g. {{core:download.output.file}}), never .path and never a URL."}}
                """,
                "[\"Pick the model FIRST: it decides the format, the accepted params and the price.\","
                        + " \"Every successful run is charged.\","
                        + " \"" + MEDIA_CONCEPT + "\","
                        + " \"A per-second model costs ten times more for a ten second clip.\","
                        + " \"credential_source decides who pays.\","
                        + " \"credential_id names which of the owner keys runs it.\"]");
        jdbc.update("INSERT INTO node_type_documentation "
                        + "(type, category, variable_prefix, outputs, parameters, concepts) "
                        + "VALUES (?, ?, ?, '{}'::jsonb, '{}'::jsonb, '[]'::jsonb)",
                "browser_agent", "agent", "agent");
        jdbc.update("INSERT INTO node_type_documentation "
                        + "(type, category, variable_prefix, outputs, parameters, concepts, examples) "
                        + "VALUES (?, ?, ?, '{}'::jsonb, '{}'::jsonb, ?::jsonb, ?::jsonb)",
                "media", "core", "core", "[\"" + MEDIA_CONCEPT + "\"]",
                "[\"" + MEDIA_CONCAT_EXAMPLE + "\"]");
    }

    @Test
    @DisplayName("files generate under the AI family and addresses it with the agent prefix")
    void filesGenerateUnderAi() {
        jdbc.execute(migrationSql);

        assertThat(str("SELECT category FROM node_type_documentation WHERE type = 'generate'"))
                .isEqualTo("ai");
        assertThat(str("SELECT variable_prefix FROM node_type_documentation WHERE type = 'generate'"))
                .as("this column decides both how the node library groups it and how its output "
                        + "is addressed; left at core every reference written from this row is dead")
                .isEqualTo("agent");
    }

    @Test
    @DisplayName("realigns browser_agent, the one AI row that answered to a different family name")
    void realignsBrowserAgent() {
        jdbc.execute(migrationSql);

        assertThat(str("SELECT category FROM node_type_documentation WHERE type = 'browser_agent'"))
                .isEqualTo("ai");
        assertThat(str("SELECT variable_prefix FROM node_type_documentation WHERE type = 'browser_agent'"))
                .as("its prefix was already right and is not part of what this fixes")
                .isEqualTo("agent");
    }

    @Test
    @DisplayName("rewrites the addressing examples an agent copies, in outputs, parameters and concepts")
    void rewritesTheAddressingExamples() {
        jdbc.execute(migrationSql);

        assertThat(str("SELECT outputs #>> '{file,description}' FROM node_type_documentation WHERE type = 'generate'"))
                .contains("{{agent:<label>.output.file}}")
                .doesNotContain("{{core:<label>.output.file}}");

        // The INPUT example keeps a core: reference, and must: the file it names
        // comes from a download, which IS a core node. Only the node's own key moved.
        assertThat(str("SELECT parameters #>> '{input_image,description}' FROM node_type_documentation WHERE type = 'generate'"))
                .contains("{{core:download.output.file}}")
                .contains("{{agent:make_cover.output.file}}");

        assertThat(str("SELECT concepts->>2 FROM node_type_documentation WHERE type = 'generate'"))
                .contains("{{agent:<label>.output.file}}");
    }

    @Test
    @DisplayName("leaves the neighbouring concepts and another node's row alone")
    void touchesNothingElse() {
        jdbc.execute(migrationSql);

        // A jsonb_agg that rebuilt the array carelessly would drop or reorder the
        // entries it was not meant to touch.
        assertThat(str("SELECT concepts->>1 FROM node_type_documentation WHERE type = 'generate'"))
                .isEqualTo("Every successful run is charged.");
        assertThat(str("SELECT jsonb_array_length(concepts)::text FROM node_type_documentation WHERE type = 'generate'"))
                .isEqualTo("6");

        // A missing WHERE would move a control-flow node into the AI family...
        assertThat(str("SELECT category FROM node_type_documentation WHERE type = 'media'")).isEqualTo("core");
        assertThat(str("SELECT variable_prefix FROM node_type_documentation WHERE type = 'media'")).isEqualTo("core");
        // ... and rewrite the media row's OWN sentence, which the concepts UPDATE
        // matches on verbatim. Media is still a core; teaching an agent to address
        // it as agent: would be the same failure in the other direction.
        assertThat(str("SELECT concepts->>0 FROM node_type_documentation WHERE type = 'media'"))
                .isEqualTo(MEDIA_CONCEPT);
    }

    @Test
    @DisplayName("applying it twice changes nothing the second time")
    void isIdempotent() {
        jdbc.execute(migrationSql);
        jdbc.execute(migrationSql);

        assertThat(str("SELECT variable_prefix FROM node_type_documentation WHERE type = 'generate'"))
                .isEqualTo("agent");
        // The concepts rewrite matches on the OLD text, so a second pass finds
        // nothing and must leave the array exactly as the first left it. Flyway
        // will not re-run a migration, but a repair or a hand-replay will.
        assertThat(str("SELECT jsonb_array_length(concepts)::text FROM node_type_documentation WHERE type = 'generate'"))
                .isEqualTo("6");
        assertThat(str("SELECT concepts->>1 FROM node_type_documentation WHERE type = 'generate'"))
                .isEqualTo("Every successful run is charged.");
        assertThat(str("SELECT outputs #>> '{file,description}' FROM node_type_documentation WHERE type = 'generate'"))
                .contains("{{agent:<label>.output.file}}");
    }

    @Test
    @DisplayName("repoints the media row's concat example at the key a generate node now has")
    void rewritesTheMediaConcatExample() {
        jdbc.execute(migrationSql);

        String examples = str("SELECT examples::text FROM node_type_documentation "
                + "WHERE type = 'media'");
        assertThat(examples)
                .as("an agent copying this for a generate node writes a reference that "
                        + "resolves to an empty string rather than failing")
                .contains("{{agent:intro.output.file}}", "{{agent:outro.output.file}}")
                .doesNotContain("{{core:intro.output.file}}");
        assertThat(examples)
                .as("the middle clip is a plain core in this example and must stay one")
                .contains("{{core:demo.output.file}}");
    }

    @Test
    @DisplayName("leaves a media row that never carried the example alone")
    void leavesAnUnrelatedMediaRowAlone() {
        jdbc.update("UPDATE node_type_documentation SET examples = ?::jsonb WHERE type = 'media'",
                "[\"Probe a clip: params={operation: 'probe'}\"]");

        jdbc.execute(migrationSql);

        assertThat(str("SELECT examples::text FROM node_type_documentation WHERE type = 'media'"))
                .isEqualTo("[\"Probe a clip: params={operation: 'probe'}\"]");
    }

    // ---------------------------------------------------------------- plumbing

    private String str(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private static void requireDatabaseOnCi() {
        if (URL != null && !URL.isBlank()) {
            return;
        }
        boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
        if (onCi) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL is unset on CI. This class must execute there: it is "
                            + "the only thing that runs V465 against a real engine, and the columns "
                            + "it fixes are what tell an agent how to address the node. Restore the "
                            + "env block on the workflow step that runs it, and keep that step in a "
                            + "job carrying the postgres service.");
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
                last = new IllegalStateException(
                        "ORCHESTRATOR_TEST_PG_URL is set but the database is unreachable: " + URL, e);
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    private static String loadMigration() {
        String[] candidates = {
                "../migration-service/src/main/resources/db/migration/" + MIGRATION,
                "backend/migration-service/src/main/resources/db/migration/" + MIGRATION,
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                try {
                    return Files.readString(path);
                } catch (Exception e) {
                    throw new IllegalStateException("unreadable migration: " + path, e);
                }
            }
        }
        return null;
    }
}
