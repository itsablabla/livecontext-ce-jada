package com.apimarketplace.trigger.purge;

import com.apimarketplace.auth.client.AuthClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("TriggerPurgeFollower")
class TriggerPurgeFollowerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TriggerPurgeFollower follower = new TriggerPurgeFollower(jdbc, mock(AuthClient.class), false);

    @Test
    @DisplayName("Deletes every declared table with the quoted schema and the org-scoped, cast predicate")
    void deletesEveryDeclaredTable() {
        follower.purgeOrganization(ORG);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), eq(ORG));
        List<String> statements = sql.getAllValues();

        assertThat(statements).hasSize(TriggerPurgeFollower.ORG_TABLES.size());
        for (String table : TriggerPurgeFollower.ORG_TABLES) {
            String bare = table.substring("trigger.".length());
            assertThat(statements).contains("DELETE FROM \"trigger\"." + bare + " WHERE organization_id::text = ?");
        }
    }

    @Test
    @DisplayName("A USER purge deletes nothing here; the cursor is local and monotonic")
    void userNoopAndCursor() {
        follower.purgeUser("42");
        verify(jdbc, never()).update(anyString(), eq("42"));

        follower.advanceTo(11L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(11L));
        assertThat(sql.getValue()).contains("\"trigger\".purge_cursor").contains("GREATEST(last_seq, ?)");
    }
}
