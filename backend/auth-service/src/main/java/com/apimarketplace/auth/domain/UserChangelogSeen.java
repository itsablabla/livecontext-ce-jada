package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The last in-app changelog entry a user has acknowledged.
 *
 * <p>One row per user, rewritten in place. There is no history because the feature never replays a
 * backlog: a user who was away for three releases sees the latest entry only, and everything older
 * lives on the public changelog page.
 *
 * <p><strong>Read-only mapping.</strong> Writes go through the repository's native upsert, never
 * through this entity: the id is assigned, so a JPA {@code save()} would select-then-insert and let
 * two tabs race into a duplicate key. Nothing here can mutate the row, so no call site can
 * accidentally reintroduce that path.
 *
 * <p>The entry itself (copy, media, date) is not stored here either - it ships with the build, so
 * cloud and CE each announce exactly what their own build contains.
 */
@Entity
@Table(name = "user_changelog_seen", schema = "auth")
public class UserChangelogSeen {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "entry_key", nullable = false, length = 120)
    private String entryKey;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt;

    protected UserChangelogSeen() {
        // for JPA
    }

    public Long getUserId() {
        return userId;
    }

    public String getEntryKey() {
        return entryKey;
    }

    public Instant getSeenAt() {
        return seenAt;
    }
}
