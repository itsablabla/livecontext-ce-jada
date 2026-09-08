package com.apimarketplace.storage.purge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.purge.PurgeFollower;
import com.apimarketplace.storage.service.file.FileStorageService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deletes the storage schema's rows AND the stored objects for a purged workspace, driven by
 * {@code auth.purge_log} (auth-client {@link PurgeFollower}).
 *
 * <p>Objects first, then rows: the keys exist only in the rows, so once those are gone there
 * is nothing left to enumerate. Object deletion is best-effort per key and never fails the
 * purge (a bucket hiccup must not stall every follower behind it), but the failure count is
 * logged: an erasure we could not complete has to be visible, not assumed. Running inside
 * storage-service means the objects are deleted by the bucket's owner directly, not through
 * an internal HTTP hop with a tenant check as before.
 */
@Component
public class StoragePurgeFollower implements PurgeFollower.Handler, PurgeFollower.Cursor {

    private static final Logger log = LoggerFactory.getLogger(StoragePurgeFollower.class);

    public static final List<String> ORG_TABLES = List.of(
            "storage.storage",
            "storage.organization_storage_quota",
            "storage.org_storage_breakdown",
            "storage.org_storage_usage_history");

    private final JdbcTemplate jdbc;
    private final FileStorageService files;
    private final PurgeFollower follower;
    private final boolean enabled;

    public StoragePurgeFollower(JdbcTemplate jdbc, AuthClient authClient, FileStorageService files,
                                @Value("${purge.follower.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.files = files;
        this.enabled = enabled;
        this.follower = new PurgeFollower("storage", authClient, this, this);
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
        Long seq = jdbc.queryForObject("SELECT last_seq FROM storage.purge_cursor WHERE id = 1", Long.class);
        return seq == null ? 0L : seq;
    }

    @Override
    public void advanceTo(long seq) {
        jdbc.update("UPDATE storage.purge_cursor SET last_seq = GREATEST(last_seq, ?), updated_at = now() WHERE id = 1", seq);
    }

    @Override
    public void purgeOrganization(String orgId) {
        deleteObjects(orgId);
        int rows = jdbc.update("DELETE FROM storage.storage WHERE organization_id::text = ?", orgId);
        if (rows > 0) {
            log.info("Purge: deleted {} storage rows for org {}", rows, orgId);
        }
        // Org storage accounting: the V205 quota row + the V222 LIVE breakdown/usage-history tables.
        jdbc.update("DELETE FROM storage.organization_storage_quota WHERE organization_id::text = ?", orgId);
        jdbc.update("DELETE FROM storage.org_storage_breakdown WHERE organization_id::text = ?", orgId);
        jdbc.update("DELETE FROM storage.org_storage_usage_history WHERE organization_id::text = ?", orgId);
    }

    private void deleteObjects(String orgId) {
        List<String> keys = jdbc.queryForList(
                "SELECT s3_key FROM storage.storage WHERE organization_id::text = ? AND s3_key IS NOT NULL",
                String.class, orgId);
        if (keys.isEmpty()) {
            return;
        }
        int deleted = 0;
        int failed = 0;
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            try {
                if (files.delete(key)) {
                    deleted++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("Purge: object delete failed for key {}: {}", key, e.getMessage());
            }
        }
        if (failed > 0) {
            log.warn("Purge: org {} - {} stored objects deleted, {} FAILED and remain in the bucket", orgId, deleted, failed);
        } else {
            log.info("Purge: org {} - {} stored objects deleted from the bucket", orgId, deleted);
        }
    }

    @Override
    public void purgeUser(String userId) {
        // Storage rows always carry a workspace.
    }
}
