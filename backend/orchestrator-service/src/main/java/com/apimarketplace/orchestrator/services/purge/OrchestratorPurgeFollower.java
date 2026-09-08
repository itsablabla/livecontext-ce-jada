package com.apimarketplace.orchestrator.services.purge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.purge.PurgeFollower;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deletes the orchestrator schema's rows for a purged workspace, driven by
 * {@code auth.purge_log} (see auth-client {@link PurgeFollower} for the contract). These
 * are the exact statements auth-service's WorkspaceDataPurger used to run cross-schema,
 * moved to the schema's owner so the schema can live on any database.
 *
 * <p>Order is children before parents: {@code workflow_step_data} references runs by the
 * public varchar {@code run_id} (no FK), hence the {@code id::text} subquery.
 */
@Component
public class OrchestratorPurgeFollower implements PurgeFollower.Handler, PurgeFollower.Cursor {

    /** Every table this follower deletes org-scoped rows from; pinned by its test. */
    public static final List<String> ORG_TABLES = List.of(
            "orchestrator.workflow_step_data",
            "orchestrator.workflow_runs",
            "orchestrator.workflows",
            "orchestrator.projects",
            "orchestrator.notifications");

    private final JdbcTemplate jdbc;
    private final PurgeFollower follower;
    private final boolean enabled;

    public OrchestratorPurgeFollower(JdbcTemplate jdbc, AuthClient authClient,
                                     @Value("${purge.follower.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.follower = new PurgeFollower("orchestrator", authClient, this, this);
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
        Long seq = jdbc.queryForObject("SELECT last_seq FROM orchestrator.purge_cursor WHERE id = 1", Long.class);
        return seq == null ? 0L : seq;
    }

    @Override
    public void advanceTo(long seq) {
        jdbc.update("UPDATE orchestrator.purge_cursor SET last_seq = GREATEST(last_seq, ?), updated_at = now() WHERE id = 1", seq);
    }

    @Override
    public void purgeOrganization(String orgId) {
        jdbc.update("DELETE FROM orchestrator.workflow_step_data WHERE run_id IN "
                + "(SELECT id::text FROM orchestrator.workflow_runs WHERE organization_id::text = ?)", orgId);
        jdbc.update("DELETE FROM orchestrator.workflow_runs WHERE organization_id::text = ?", orgId);
        jdbc.update("DELETE FROM orchestrator.workflows WHERE organization_id::text = ?", orgId);
        jdbc.update("DELETE FROM orchestrator.projects WHERE organization_id::text = ?", orgId);
        jdbc.update("DELETE FROM orchestrator.notifications WHERE organization_id::text = ?", orgId);
    }

    @Override
    public void purgeUser(String userId) {
        // Nothing in this schema is user-owned outside a workspace.
    }
}
