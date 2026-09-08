package com.apimarketplace.datasource.purge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.datasource.events.VectorDataSourceDeletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The follower that makes the split worth it: datasource.* is the schema that moves to its
 * own database. Besides the row delete it must drop the per-table HNSW indexes, which a bulk
 * DELETE never does on its own (the by-workflow delete had exactly that leak).
 */
@DisplayName("DatasourcePurgeFollower")
class DatasourcePurgeFollowerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private JdbcTemplate jdbc;
    private ApplicationEventPublisher events;
    private DatasourcePurgeFollower follower;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        events = mock(ApplicationEventPublisher.class);
        follower = new DatasourcePurgeFollower(jdbc, mock(AuthClient.class), events, false);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(ORG))).thenReturn(List.of());
    }

    @Test
    @DisplayName("Deletes the org's data_sources with the org-scoped, cast predicate (items and vectors cascade)")
    void deletesDataSources() {
        follower.purgeOrganization(ORG);
        verify(jdbc).update("DELETE FROM datasource.data_sources WHERE organization_id::text = ?", ORG);
    }

    /**
     * Ids are read BEFORE the delete (afterwards nothing is left to enumerate) and one drop
     * event is published per vector table AFTER the rows are gone, so a listener that runs
     * after commit finds no table to keep an index for.
     */
    @Test
    @DisplayName("Reads the vector-carrying tables before deleting, then publishes one index-drop event per table")
    void dropsHnswIndexesOfVectorTables() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(ORG))).thenReturn(List.of(7L, 9L));

        follower.purgeOrganization(ORG);

        ArgumentCaptor<String> select = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(select.capture(), eq(Long.class), eq(ORG));
        // ColumnType serialises as the lower-case word; a case-sensitive LIKE on 'VECTOR'
        // matched nothing in production data (audit 2026-09-02). The real-Postgres sibling
        // test proves the predicate against a spec written by the ObjectMapper.
        assertThat(select.getValue()).contains("FROM datasource.data_sources").contains("organization_id::text = ?")
                .contains("ILIKE '%\"vector\"%'").doesNotContain("VECTOR");
        InOrder order = inOrder(jdbc, events);
        order.verify(jdbc).queryForList(anyString(), eq(Long.class), eq(ORG));
        order.verify(jdbc).update(anyString(), eq(ORG));
        order.verify(events).publishEvent(new VectorDataSourceDeletedEvent(7L));
        order.verify(events).publishEvent(new VectorDataSourceDeletedEvent(9L));
    }

    @Test
    @DisplayName("No vector table, no event")
    void noVectorTableNoEvent() {
        follower.purgeOrganization(ORG);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("A USER purge deletes nothing here, and the cursor is local and monotonic")
    void userNoopAndCursor() {
        follower.purgeUser("42");
        verify(jdbc, never()).update(anyString(), eq("42"));

        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(5L);
        assertThat(follower.read()).isEqualTo(5L);
        follower.advanceTo(6L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(6L));
        assertThat(sql.getValue()).contains("datasource.purge_cursor").contains("GREATEST(last_seq, ?)");
    }
}
