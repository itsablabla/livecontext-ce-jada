package com.apimarketplace.storage.purge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.storage.service.file.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StoragePurgeFollower")
class StoragePurgeFollowerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private JdbcTemplate jdbc;
    private FileStorageService files;
    private StoragePurgeFollower follower;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        files = mock(FileStorageService.class);
        follower = new StoragePurgeFollower(jdbc, mock(AuthClient.class), files, false);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(ORG))).thenReturn(List.of());
    }

    private List<String> deletes() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), eq(ORG));
        return sql.getAllValues();
    }

    @Test
    @DisplayName("Deletes every declared table, storage rows first, each org-scoped and cast")
    void deletesEveryDeclaredTable() {
        follower.purgeOrganization(ORG);
        List<String> sql = deletes();

        for (String table : StoragePurgeFollower.ORG_TABLES) {
            assertThat(sql).anyMatch(s -> s.equals("DELETE FROM " + table + " WHERE organization_id::text = ?"));
        }
        assertThat(sql.get(0)).startsWith("DELETE FROM storage.storage ");
    }

    /**
     * The keys only exist in the rows: enumerate and delete the objects BEFORE the rows go,
     * or every uploaded byte stays in the bucket forever, unreachable and unbilled, after we
     * told the customer it was erased.
     */
    @Test
    @DisplayName("Stored objects are deleted BEFORE the rows that carry their keys")
    void objectsBeforeRows() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(ORG))).thenReturn(List.of("1/a/x.png", "1/b/y.mp4"));
        when(files.delete(anyString())).thenReturn(true);

        follower.purgeOrganization(ORG);

        InOrder order = inOrder(jdbc, files);
        order.verify(jdbc).queryForList(anyString(), eq(String.class), eq(ORG));
        order.verify(files).delete("1/a/x.png");
        order.verify(files).delete("1/b/y.mp4");
        order.verify(jdbc).update(eq("DELETE FROM storage.storage WHERE organization_id::text = ?"), eq(ORG));
    }

    @Test
    @DisplayName("A failing object delete never aborts the purge (logged, counted, rows still removed)")
    void objectFailureDoesNotAbort() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(ORG))).thenReturn(List.of("1/a/x.png", "1/b/y.mp4"));
        when(files.delete("1/a/x.png")).thenThrow(new IllegalStateException("bucket hiccup"));
        when(files.delete("1/b/y.mp4")).thenReturn(false);

        assertThatCode(() -> follower.purgeOrganization(ORG)).doesNotThrowAnyException();

        verify(jdbc).update(eq("DELETE FROM storage.storage WHERE organization_id::text = ?"), eq(ORG));
    }

    @Test
    @DisplayName("The cursor lives in storage.purge_cursor and only moves forward")
    void cursorIsMonotonicAndLocal() {
        follower.advanceTo(4L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(4L));
        assertThat(sql.getValue()).contains("storage.purge_cursor").contains("GREATEST(last_seq, ?)");
    }
}
