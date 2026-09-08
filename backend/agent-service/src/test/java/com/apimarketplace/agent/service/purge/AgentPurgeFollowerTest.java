package com.apimarketplace.agent.service.purge;

import com.apimarketplace.auth.client.AuthClient;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AgentPurgeFollower")
class AgentPurgeFollowerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private JdbcTemplate jdbc;
    private AgentPurgeFollower follower;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        follower = new AgentPurgeFollower(jdbc, mock(AuthClient.class), false);
    }

    @Test
    @DisplayName("Deletes every declared org table, children (executions journal, task children) before parents")
    void deletesEveryDeclaredTableInOrder() {
        follower.purgeOrganization(ORG);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), eq(ORG));
        List<String> statements = sql.getAllValues();

        assertThat(statements).hasSize(AgentPurgeFollower.ORG_TABLES.size());
        for (int i = 0; i < statements.size(); i++) {
            assertThat(statements.get(i)).isEqualTo("DELETE FROM " + AgentPurgeFollower.ORG_TABLES.get(i) + " WHERE organization_id::text = ?");
        }
        assertThat(statements.indexOf("DELETE FROM agent.agent_execution_messages WHERE organization_id::text = ?"))
                .isLessThan(statements.indexOf("DELETE FROM agent.agent_executions WHERE organization_id::text = ?"));
        assertThat(statements.indexOf("DELETE FROM agent.agent_task_events WHERE organization_id::text = ?"))
                .isLessThan(statements.indexOf("DELETE FROM agent.agent_tasks WHERE organization_id::text = ?"));
        assertThat(statements.indexOf("DELETE FROM agent.agent_tasks WHERE organization_id::text = ?"))
                .isLessThan(statements.indexOf("DELETE FROM agent.agents WHERE organization_id::text = ?"));
    }

    /**
     * The one USER-scoped row this schema holds outside a workspace. It used to be deleted by
     * AccountPurgeService reaching into agent.*; now the account purge logs a USER subject and
     * this follower drops it.
     */
    @Test
    @DisplayName("A USER purge drops the user's skill overrides and nothing else")
    void userPurgeDropsSkillOverrides() {
        follower.purgeUser("42");
        verify(jdbc).update("DELETE FROM agent.user_skill_overrides WHERE user_id = ?", "42");
        verify(jdbc, org.mockito.Mockito.times(1)).update(anyString(), eq("42"));
    }

    @Test
    @DisplayName("The cursor lives in agent.purge_cursor and only moves forward")
    void cursorIsMonotonicAndLocal() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);
        assertThat(follower.read()).isEqualTo(3L);
        follower.advanceTo(9L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(9L));
        assertThat(sql.getValue()).contains("agent.purge_cursor").contains("GREATEST(last_seq, ?)");
    }
}
