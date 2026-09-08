package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replays the REAL V409 (media node doc insert), V410 (video-suite refresh) and V462
 * (subtitles refresh) against a minimal node_type_documentation fixture, and asserts the
 * row ends up describing the subtitles operation with the contract the node actually
 * enforces: the cues array with its ordering rule, the style/font/colour options, and the
 * two facts an agent cannot discover by trying (captions are permanent, a missing font is
 * refused rather than substituted). Also proves nothing V409/V410 documented was dropped,
 * and that a V462 re-apply changes nothing.
 */
@DisplayName("V462 media subtitles documentation refresh")
class MediaSubtitlesDocumentationMigrationTest {

    private static final String DB = "media_v462_replay";
    private static final String MISSING_ROW_DB = "media_v462_missing_row";

    private static final String SCHEMA_ONLY = """
            CREATE SCHEMA orchestrator;

            CREATE TABLE orchestrator.node_type_documentation (
                type             VARCHAR(128) PRIMARY KEY,
                label            VARCHAR(255),
                category         VARCHAR(64),
                variable_prefix  VARCHAR(64),
                description      TEXT NOT NULL DEFAULT '',
                parameters       JSONB NOT NULL DEFAULT '{}'::jsonb,
                outputs          JSONB NOT NULL DEFAULT '{}'::jsonb,
                global_variables JSONB,
                edge_ports       JSONB,
                concepts         JSONB,
                examples         JSONB NOT NULL DEFAULT '[]'::jsonb,
                keywords         JSONB,
                enabled          BOOLEAN NOT NULL DEFAULT true,
                created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
            """;

    @Test
    @DisplayName("V462 documents the subtitles operation and stays idempotent")
    void v462DocumentsSubtitlesAndStaysIdempotent(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);

            assertThatCode(() -> postgres.runFlyway(DB, tempDir))
                    .doesNotThrowAnyException();

            // The operation itself is listed where an agent looks for it.
            assertThat(paramField(postgres, "operation", "description"))
                    .as("the operation enum must name subtitles")
                    .contains("subtitles");
            String description = scalar(postgres,
                    "SELECT description FROM orchestrator.node_type_documentation WHERE type = 'media'");
            assertThat(description).contains("subtitles");

            // cues: the shape and, critically, the ordering rule the node REFUSES on.
            assertThat(paramField(postgres, "cues", "type")).isEqualTo("array");
            String cues = paramField(postgres, "cues", "description");
            assertThat(cues)
                    .contains("start_seconds")
                    .contains("end_seconds")
                    .contains("text")
                    .contains("600")
                    .contains("240")
                    .containsIgnoringCase("non-overlapping");

            // The look: preset plus the four overrides, each typed.
            assertThat(paramField(postgres, "style", "default")).isEqualTo("tiktok");
            assertThat(paramField(postgres, "font_size_percent", "type")).isEqualTo("number");
            assertThat(paramField(postgres, "position_percent", "type")).isEqualTo("number");
            assertThat(paramField(postgres, "text_color", "default")).isEqualTo("#FFFFFF");
            assertThat(paramField(postgres, "outline_color", "default")).isEqualTo("#000000");
            assertThat(paramField(postgres, "font_size_percent", "description"))
                    .as("sizes are a percent of the HEIGHT, which is what makes a preset resolution-independent")
                    .containsIgnoringCase("height");

            // A missing font is refused, never substituted: an agent that does not read
            // this ships a video captioned in the wrong face and sees a green run.
            assertThat(paramField(postgres, "font_family", "description"))
                    .containsIgnoringCase("refused");

            // subtitles is a video param too, alongside mux_audio and overlay.
            assertThat(paramField(postgres, "video", "description")).contains("subtitles");
            // ... and produces an mp4 like the other file-producing operations.
            assertThat(outputField(postgres, "file", "description")).contains("subtitles");

            // The two irreversibility facts live in concepts, where the agent reads them
            // BEFORE choosing where in the chain to caption.
            String concepts = scalar(postgres,
                    "SELECT concepts::text FROM orchestrator.node_type_documentation WHERE type = 'media'");
            assertThat(concepts)
                    .containsIgnoringCase("cannot be turned off")
                    .containsIgnoringCase("Caption LAST");

            // Nothing V409/V410 documented was lost in the refresh.
            assertThat(paramField(postgres, "inputs", "type")).isEqualTo("array");
            assertThat(paramField(postgres, "at_seconds", "description")).containsIgnoringCase("middle");
            assertThat(paramField(postgres, "position", "default")).isEqualTo("bottom_right");
            assertThat(outputField(postgres, "timestamp_seconds", "type")).isEqualTo("number");
            assertThat(outputField(postgres, "has_audio", "type")).isEqualTo("boolean");

            // The V330 boot invariant: every examples element MUST be a JSON string
            // (an object element crash-loops orchestrator's List<String> mapping).
            assertThat(Integer.parseInt(scalar(postgres, """
                    SELECT COUNT(*)
                      FROM orchestrator.node_type_documentation,
                           jsonb_array_elements(examples) AS elem
                     WHERE type = 'media' AND jsonb_typeof(elem) <> 'string'
                    """))).isZero();
            assertThat(Integer.parseInt(scalar(postgres, """
                    SELECT jsonb_array_length(examples)
                      FROM orchestrator.node_type_documentation WHERE type = 'media'
                    """)))
                    .as("V462 keeps V410's three examples and adds the subtitles one")
                    .isEqualTo(4);
            assertThat(scalar(postgres,
                    "SELECT examples::text FROM orchestrator.node_type_documentation WHERE type = 'media'"))
                    .contains("Caption It");

            // Idempotency: the fixture applied V462 TWICE (V4 + V5) - a second
            // application must leave a single, still-valid media row.
            assertThat(Integer.parseInt(scalar(postgres,
                    "SELECT COUNT(*) FROM orchestrator.node_type_documentation WHERE type = 'media'")))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V462 alone, with no media row to refresh, applies cleanly and writes nothing")
    void v462OnAMissingRowIsAHarmlessNoOp(@TempDir Path tempDir) throws Exception {
        // V462 is an UPDATE, so it depends on V409 having inserted the row. Ordering
        // decides whether it documents anything at all, and a silent no-op is exactly
        // the failure an agent would meet as "the docs never mentioned subtitles".
        Files.writeString(tempDir.resolve("V1__seed_node_type_documentation.sql"), SCHEMA_ONLY);
        copyMigration(tempDir, "V462__media_subtitles_documentation.sql", "V2__media_subtitles_documentation.sql");

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(MISSING_ROW_DB);

            assertThatCode(() -> postgres.runFlyway(MISSING_ROW_DB, tempDir))
                    .as("an UPDATE that matches no row must still apply cleanly")
                    .doesNotThrowAnyException();

            assertThat(Integer.parseInt(scalarIn(postgres, MISSING_ROW_DB,
                    "SELECT COUNT(*) FROM orchestrator.node_type_documentation WHERE type = 'media'")))
                    .as("V462 must not invent a row: it refreshes the one V409 inserted")
                    .isZero();
        }
    }

    /**
     * Minimal fixture: the node_type_documentation table with the columns the media
     * migrations touch, then the REAL V409 (insert), V410 (video suite) and V462 applied
     * twice (refresh + idempotency re-apply). Mirrors the production chain without the
     * full multi-hundred-migration schema.
     */
    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_node_type_documentation.sql"), SCHEMA_ONLY);

        copyMigration(directory, "V409__add_media_node_documentation.sql", "V2__add_media_node_documentation.sql");
        copyMigration(directory, "V410__media_video_suite_documentation.sql", "V3__media_video_suite_documentation.sql");
        copyMigration(directory, "V462__media_subtitles_documentation.sql", "V4__media_subtitles_documentation.sql");
        copyMigration(directory, "V462__media_subtitles_documentation.sql", "V5__media_subtitles_reapply.sql");
    }

    private static void copyMigration(Path directory, String source, String target) throws Exception {
        Files.writeString(directory.resolve(target),
                Files.readString(Path.of("src/main/resources/db/migration/" + source)));
    }

    private static String paramField(FlywayTestSupport.PostgresTarget postgres, String param, String field) throws Exception {
        return scalar(postgres,
                "SELECT parameters -> '" + param + "' ->> '" + field
                        + "' FROM orchestrator.node_type_documentation WHERE type = 'media'");
    }

    private static String outputField(FlywayTestSupport.PostgresTarget postgres, String output, String field) throws Exception {
        return scalar(postgres,
                "SELECT outputs -> '" + output + "' ->> '" + field
                        + "' FROM orchestrator.node_type_documentation WHERE type = 'media'");
    }

    private static String scalar(FlywayTestSupport.PostgresTarget postgres, String sql) throws Exception {
        return scalarIn(postgres, DB, sql);
    }

    private static String scalarIn(FlywayTestSupport.PostgresTarget postgres, String database, String sql)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.jdbcUrl(database), postgres.username(), postgres.password());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).as("query must return a row: " + sql).isTrue();
            return resultSet.getString(1);
        }
    }
}
