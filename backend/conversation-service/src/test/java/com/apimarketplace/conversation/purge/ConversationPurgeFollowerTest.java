package com.apimarketplace.conversation.purge;

import com.apimarketplace.auth.client.AuthClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("ConversationPurgeFollower")
class ConversationPurgeFollowerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ConversationPurgeFollower follower = new ConversationPurgeFollower(jdbc, mock(AuthClient.class), false);

    @Test
    @DisplayName("Messages go before their conversations, both scoped through the org's conversations")
    void messagesBeforeConversations() {
        follower.purgeOrganization(ORG);

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).update("DELETE FROM conversation.messages WHERE conversation_id IN "
                + "(SELECT id FROM conversation.conversations WHERE organization_id::text = ?)", ORG);
        order.verify(jdbc).update("DELETE FROM conversation.conversations WHERE organization_id::text = ?", ORG);
    }

    @Test
    @DisplayName("A USER purge deletes nothing here; the cursor is local and monotonic")
    void userNoopAndCursor() {
        follower.purgeUser("42");
        verify(jdbc, never()).update(anyString(), eq("42"));

        follower.advanceTo(8L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(8L));
        assertThat(sql.getValue()).contains("conversation.purge_cursor").contains("GREATEST(last_seq, ?)");
    }
}
