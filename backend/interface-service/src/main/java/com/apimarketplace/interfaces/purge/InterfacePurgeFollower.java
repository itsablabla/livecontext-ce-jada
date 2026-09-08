package com.apimarketplace.interfaces.purge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.purge.PurgeFollower;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deletes the interface schema's rows for a purged workspace, driven by
 * {@code auth.purge_log} (auth-client {@link PurgeFollower}).
 */
@Component
public class InterfacePurgeFollower implements PurgeFollower.Handler, PurgeFollower.Cursor {

    public static final List<String> ORG_TABLES = List.of("interface.interfaces");

    private final JdbcTemplate jdbc;
    private final PurgeFollower follower;
    private final boolean enabled;

    public InterfacePurgeFollower(JdbcTemplate jdbc, AuthClient authClient,
                                  @Value("${purge.follower.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.follower = new PurgeFollower("interface", authClient, this, this);
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
        Long seq = jdbc.queryForObject("SELECT last_seq FROM interface.purge_cursor WHERE id = 1", Long.class);
        return seq == null ? 0L : seq;
    }

    @Override
    public void advanceTo(long seq) {
        jdbc.update("UPDATE interface.purge_cursor SET last_seq = GREATEST(last_seq, ?), updated_at = now() WHERE id = 1", seq);
    }

    @Override
    public void purgeOrganization(String orgId) {
        jdbc.update("DELETE FROM interface.interfaces WHERE organization_id::text = ?", orgId);
    }

    @Override
    public void purgeUser(String userId) {
        // Interfaces always belong to a workspace.
    }
}
