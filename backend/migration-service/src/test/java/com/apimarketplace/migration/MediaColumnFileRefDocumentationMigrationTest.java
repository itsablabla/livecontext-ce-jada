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
 * Replays the REAL V478 against a node_type_documentation fixture that already holds what earlier
 * migrations wrote, and asserts three things a hand-read of the SQL cannot: that it parses and runs,
 * that it EXTENDS the two rows rather than replacing what V20 and V253 put there, and that applying
 * it twice changes nothing.
 *
 * <p>The "extends" half is the one worth guarding. V478 rewrites two output descriptions and appends
 * to two concept arrays; a `||` on a null column would blank the documentation instead, and a
 * wholesale description replacement would silently drop the Split-node sentence an agent needs to
 * iterate rows. Both mistakes are invisible in review and only show up as an agent that has stopped
 * being told something.
 */
@DisplayName("V478 media-column file-reference documentation")
class MediaColumnFileRefDocumentationMigrationTest {

    private static final String DB = "media_column_v478_replay";

    private static final String SCHEMA_AND_ROWS = """
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

            -- find_rows as earlier migrations leave it: an items[] output and a populated concepts.
            INSERT INTO orchestrator.node_type_documentation (type, outputs, concepts) VALUES (
                'find_rows',
                '{"items": {"type": "array", "description": "All found rows (after limit applied)"},
                  "item_count": {"type": "number", "description": "Number of items found"}}'::jsonb,
                '["Use where filter to narrow results; omit for all rows"]'::jsonb
            );

            -- get_rows as earlier migrations leave it: a rows[] output and a NULL concepts column,
            -- which is what makes the COALESCE in V478 load-bearing rather than decorative.
            INSERT INTO orchestrator.node_type_documentation (type, outputs, concepts) VALUES (
                'get_rows',
                '{"rows": {"type": "array", "description": "Retrieved rows"},
                  "count": {"type": "number", "description": "Number of rows returned"}}'::jsonb,
                NULL
            );
            """;

    @Test
    @DisplayName("V478 documents the media contract on both rows, keeps what was there, and re-applies cleanly")
    void v478DocumentsMediaCellsAndStaysIdempotent(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);

            assertThatCode(() -> postgres.runFlyway(DB, tempDir))
                    .doesNotThrowAnyException();

            // find_rows: the media contract is on the output an agent reads.
            String items = outputField(postgres, "find_rows", "items", "description");
            assertThat(items)
                    .as("the media cell contract must reach the items[] description")
                    .contains("file OBJECT")
                    .contains("map the WHOLE cell");
            assertThat(items)
                    .as("an earlier migration's Split sentence must survive the rewrite")
                    .contains("Split node");
            assertThat(outputField(postgres, "find_rows", "items", "type")).isEqualTo("array");
            assertThat(outputField(postgres, "find_rows", "item_count", "description"))
                    .as("a sibling output must not be touched")
                    .isEqualTo("Number of items found");

            // get_rows: same contract, and its own key is rows[], not items[].
            String rows = outputField(postgres, "get_rows", "rows", "description");
            assertThat(rows).contains("file OBJECT");
            assertThat(rows)
                    .as("V253's wording must survive")
                    .startsWith("Retrieved rows");

            // The concepts an agent reads before writing a template.
            String findConcepts = concepts(postgres, "find_rows");
            assertThat(findConcepts)
                    .contains("{{table:<label>.output.items[0].<column>}}")
                    .contains("CHANGED:")
                    .contains("Use where filter to narrow results; omit for all rows");
            assertThat(concepts(postgres, "get_rows"))
                    .as("a NULL concepts column must be seeded, not blanked")
                    .contains("{{table:<label>.output.rows[0].<column>}}");

            // Idempotence: the second copy of V478 ran as V4 and must have changed nothing.
            assertThat(countOccurrences(findConcepts, "CHANGED:"))
                    .as("re-applying must not duplicate a concept")
                    .isEqualTo(1);
        }
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_node_type_documentation.sql"), SCHEMA_AND_ROWS);
        copyMigration(directory, "V478__media_column_rows_are_file_refs.sql",
                "V2__media_column_rows_are_file_refs.sql");
        copyMigration(directory, "V478__media_column_rows_are_file_refs.sql",
                "V3__media_column_rows_are_file_refs_reapply.sql");
    }

    private static void copyMigration(Path directory, String source, String target) throws Exception {
        Files.writeString(directory.resolve(target),
                Files.readString(Path.of("src/main/resources/db/migration/" + source)));
    }

    private static String outputField(FlywayTestSupport.PostgresTarget postgres, String type,
                                      String output, String field) throws Exception {
        return scalar(postgres,
                "SELECT outputs -> '" + output + "' ->> '" + field
                        + "' FROM orchestrator.node_type_documentation WHERE type = '" + type + "'");
    }

    private static String concepts(FlywayTestSupport.PostgresTarget postgres, String type) throws Exception {
        return scalar(postgres,
                "SELECT concepts::text FROM orchestrator.node_type_documentation WHERE type = '" + type + "'");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String scalar(FlywayTestSupport.PostgresTarget postgres, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.jdbcUrl(DB), postgres.username(), postgres.password());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).as("query must return a row: " + sql).isTrue();
            return resultSet.getString(1);
        }
    }
}
