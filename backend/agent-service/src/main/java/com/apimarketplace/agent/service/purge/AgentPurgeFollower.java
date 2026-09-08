package com.apimarketplace.agent.service.purge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.purge.PurgeFollower;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deletes the agent schema's rows for a purged workspace or account, driven by
 * {@code auth.purge_log} (auth-client {@link PurgeFollower}). Same statements auth-service
 * used to issue cross-schema; the schema's owner runs them now. Children before parents.
 */
@Component
public class AgentPurgeFollower implements PurgeFollower.Handler, PurgeFollower.Cursor {

    public static final List<String> ORG_TABLES = List.of(
            "agent.agent_execution_tool_calls",
            "agent.agent_execution_messages",
            "agent.agent_execution_iterations",
            "agent.agent_executions",
            "agent.agent_task_recurrences",
            "agent.agent_task_notes",
            "agent.agent_task_events",
            "agent.agent_task_claims",
            "agent.agent_tasks",
            // Before agent.agents: an agent-scoped memory carries an FK to it. The
            // cascade would take the row anyway, but a purge that leans on a cascade
            // cannot prove it emptied the table, and this one reports what it deleted.
            "agent.agent_memories",
            "agent.agents",
            "agent.skill_folders",
            "agent.skills");

    /** User-owned, outside any workspace: dropped when the ACCOUNT is purged. */
    public static final List<String> USER_TABLES = List.of("agent.user_skill_overrides");

    private final JdbcTemplate jdbc;
    private final PurgeFollower follower;
    private final boolean enabled;

    public AgentPurgeFollower(JdbcTemplate jdbc, AuthClient authClient,
                              @Value("${purge.follower.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.follower = new PurgeFollower("agent", authClient, this, this);
    }

    @PostConstruct
    void start() {
        if (enabled) {
            follower.start();
        }
    }

    @PreDestroy
    void stop() {
        follower.stop();
    }

    @Override
    public long read() {
        Long seq = jdbc.queryForObject("SELECT last_seq FROM agent.purge_cursor WHERE id = 1", Long.class);
        return seq == null ? 0L : seq;
    }

    @Override
    public void advanceTo(long seq) {
        jdbc.update("UPDATE agent.purge_cursor SET last_seq = GREATEST(last_seq, ?), updated_at = now() WHERE id = 1", seq);
    }

    @Override
    public void purgeOrganization(String orgId) {
        for (String table : ORG_TABLES) {
            jdbc.update("DELETE FROM " + table + " WHERE organization_id::text = ?", orgId);
        }
    }

    @Override
    public void purgeUser(String userId) {
        jdbc.update("DELETE FROM agent.user_skill_overrides WHERE user_id = ?", userId);
    }
}
