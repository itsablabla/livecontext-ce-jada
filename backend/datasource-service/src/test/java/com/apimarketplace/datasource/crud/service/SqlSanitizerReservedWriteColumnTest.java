package com.apimarketplace.datasource.crud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A write to a reserved column name must be REFUSED, on every path.
 *
 * <p>The check existed only on the agent-tool surface, so
 * {@code table(action='update_rows', set={priority: 9})} was correctly refused while the
 * same write from a workflow {@code update_row} node went straight to the repository and
 * answered {@code success: true, rows_affected: 1}. Nothing had changed: the value landed
 * in the {@code data} JSONB blob under a key the physical column of the same name shadows
 * on every projection. Verified in production on 2026-09-02, where the visible
 * consequence was a publish queue that could not be reordered while every attempt to
 * reorder it reported success.
 *
 * <p>Reads are deliberately still allowed: a WHERE clause on {@code id} addresses the
 * row's primary key and is the documented way to target a row.
 */
@DisplayName("SqlSanitizer - reserved column names on a write")
class SqlSanitizerReservedWriteColumnTest {

    private final SqlSanitizer sanitizer = new SqlSanitizer();

    @ParameterizedTest
    @ValueSource(strings = {"id", "data_source_id", "tenant_id", "data",
                            "priority", "row_index", "created_at", "updated_at"})
    @DisplayName("every physical column name is refused")
    void everyPhysicalColumnIsRefused(String reserved) {
        assertThatThrownBy(() -> sanitizer.rejectReservedWriteColumn(reserved))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is reserved");
    }

    @Test
    @DisplayName("the refusal says WHY, since the write would otherwise look successful")
    void theRefusalExplainsTheShadowing() {
        assertThatThrownBy(() -> sanitizer.rejectReservedWriteColumn("priority"))
                .hasMessageContaining("never readable")
                .hasMessageContaining("shadows it");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIORITY", "  priority  ", "Created_At"})
    @DisplayName("case and padding do not get a value past the guard")
    void spellingVariantsAreRefusedToo(String reserved) {
        assertThatThrownBy(() -> sanitizer.rejectReservedWriteColumn(reserved))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "processed", "video_ref_json", "priorityScore",
                            "my_id", "from", "select"})
    @DisplayName("an ordinary user column is untouched, including SQL keywords")
    void ordinaryColumnsPass(String ordinary) {
        assertThatCode(() -> sanitizer.rejectReservedWriteColumn(ordinary))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null name is not the guard's business")
    void nullIsLeftToTheNameValidator() {
        assertThatCode(() -> sanitizer.rejectReservedWriteColumn(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the agent-tool surface and the repository share ONE definition")
    void oneDefinitionForBothSurfaces() {
        // Two copies would drift, and the drift is invisible: the tool refuses, the
        // workflow accepts, and only the second one is silent about it.
        assertThat(com.apimarketplace.datasource.tools.datasource.ToolParameterUtils.RESERVED_COLUMN_NAMES)
                .isSameAs(SqlSanitizer.RESERVED_DATA_COLUMN_NAMES);
    }
}
