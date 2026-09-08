package com.apimarketplace.trigger.purge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.purge.PurgeFollower;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deletes the trigger schema's rows for a purged workspace, driven by
 * {@code auth.purge_log} (auth-client {@link PurgeFollower}). The schema name is an SQL
 * keyword; {@code trigger} is non-reserved in PostgreSQL, so unquoted works (the old purger
 * wrote it unquoted), and it is quoted here only so nobody has to check that again.
 */
@Component
public class TriggerPurgeFollower implements PurgeFollower.Handler, PurgeFollower.Cursor {

    public static final List<String> ORG_TABLES = List.of(
            "trigger.scheduled_executions",
            "trigger.standalone_webhooks",
            "trigger.standalone_chat_endpoints",
            "trigger.standalone_form_endpoints",
            "trigger.webhook_tokens",
            "trigger.datasource_trigger_subscriptions");

    private final JdbcTemplate jdbc;
    private final PurgeFollower follower;
    private final boolean enabled;

    public TriggerPurgeFollower(JdbcTemplate jdbc, AuthClient authClient,
                                @Value("${purge.follower.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.follower = new PurgeFollower("trigger", authClient, this, this);
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
        Long seq = jdbc.queryForObject("SELECT last_seq FROM \"trigger\".purge_cursor WHERE id = 1", Long.class);
        return seq == null ? 0L : seq;
    }

    @Override
    public void advanceTo(long seq) {
        jdbc.update("UPDATE \"trigger\".purge_cursor SET last_seq = GREATEST(last_seq, ?), updated_at = now() WHERE id = 1", seq);
    }

    @Override
    public void purgeOrganization(String orgId) {
        for (String table : ORG_TABLES) {
            String quoted = "\"trigger\"." + table.substring("trigger.".length());
            jdbc.update("DELETE FROM " + quoted + " WHERE organization_id::text = ?", orgId);
        }
    }

    @Override
    public void purgeUser(String userId) {
        // Triggers always belong to a workspace.
    }
}
