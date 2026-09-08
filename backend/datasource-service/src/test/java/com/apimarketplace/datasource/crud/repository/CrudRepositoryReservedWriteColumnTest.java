package com.apimarketplace.datasource.crud.repository;

import com.apimarketplace.datasource.crud.dto.CreateRowRequest;
import com.apimarketplace.datasource.crud.domain.WhereCondition;
import com.apimarketplace.datasource.crud.service.SqlSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reserved-column guard has to be ON the two repository write paths, not merely
 * available as a method.
 *
 * <p>{@code SqlSanitizer.rejectReservedWriteColumn} is unit-tested on its own, but the
 * defect being fixed was that nothing on the workflow path CALLED it: a
 * {@code update_row} node answered {@code success: true, rows_affected: 1} while the
 * value went into the {@code data} JSONB blob under a key the physical column of the same
 * name shadows on every projection. Deleting the two call sites and leaving the method
 * behind would restore that defect and pass every isolated test, which is exactly what an
 * audit flagged, so these tests drive the repository itself.
 *
 * <p>The refusal must also happen BEFORE any SQL is issued: a write that is going to be
 * unreadable should not reach the database at all.
 */
@DisplayName("CrudRepository - a reserved column name never reaches the database")
class CrudRepositoryReservedWriteColumnTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private CrudRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        repository = new CrudRepository(jdbcTemplate, new SqlSanitizer(), new ObjectMapper());
    }

    private CreateRowRequest.RowData row(Map<String, Object> columns) {
        return new CreateRowRequest.RowData("row1", columns);
    }

    @Test
    @DisplayName("updateRows refuses a reserved set-key, and issues no SQL")
    void updateRowsRefusesAReservedSetKey() {
        WhereCondition where = new WhereCondition("id", "=", "4461");

        assertThatThrownBy(() -> repository.updateRows(
                248L, "tenant-1", where, Map.of("priority", 9)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is reserved");

        verify(jdbcTemplate, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @DisplayName("updateRows refuses it under the data. prefix too")
    void updateRowsRefusesTheDataPrefixedSpelling() {
        // The prefix is stripped before the column is used, so the guard has to run on
        // the stripped name or `data.priority` walks straight past it.
        WhereCondition where = new WhereCondition("id", "=", "4461");

        assertThatThrownBy(() -> repository.updateRows(
                248L, "tenant-1", where, Map.of("data.priority", 9)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is reserved");
    }

    @Test
    @DisplayName("createRows refuses a reserved column, and inserts nothing")
    void createRowsRefusesAReservedColumn() {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("title", "Sea drone");
        columns.put("created_at", "2026-09-02");

        assertThatThrownBy(() -> repository.createRows(
                248L, "tenant-1", List.of(row(columns))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is reserved");

        verify(jdbcTemplate, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @DisplayName("an ordinary update still goes through")
    void anOrdinaryUpdateIsUntouched() {
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        WhereCondition where = new WhereCondition("id", "=", "4461");

        assertThatCode(() -> repository.updateRows(
                248L, "tenant-1", where, Map.of("processed", "yes")))
                .doesNotThrowAnyException();

        verify(jdbcTemplate).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @DisplayName("a WHERE on id still addresses the primary key")
    void readsAreNotCaughtByTheWriteGuard() {
        // The guard is write-only on purpose: `where={column:'id'}` is the documented way
        // to target a row, and is the idiom for wiping a table.
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(3);
        WhereCondition where = new WhereCondition("id", "IS NOT NULL", null);

        assertThatCode(() -> repository.updateRows(
                248L, "tenant-1", where, Map.of("processed", "no")))
                .doesNotThrowAnyException();
    }
}
