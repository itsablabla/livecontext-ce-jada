package com.apimarketplace.interfaces.purge;

import com.apimarketplace.auth.client.AuthClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("InterfacePurgeFollower")
class InterfacePurgeFollowerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final InterfacePurgeFollower follower = new InterfacePurgeFollower(jdbc, mock(AuthClient.class), false);

    @Test
    @DisplayName("Deletes the workspace's interfaces with the org-scoped, cast predicate")
    void orgPurge() {
        follower.purgeOrganization(ORG);
        verify(jdbc).update("DELETE FROM interface.interfaces WHERE organization_id::text = ?", ORG);
        verify(jdbc, times(1)).update(anyString(), eq(ORG));
    }

    @Test
    @DisplayName("A USER purge deletes nothing here; the cursor is local and monotonic")
    void userNoopAndCursor() {
        follower.purgeUser("42");
        verify(jdbc, never()).update(anyString(), eq("42"));

        follower.advanceTo(3L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(3L));
        assertThat(sql.getValue()).contains("interface.purge_cursor").contains("GREATEST(last_seq, ?)");
    }
}
