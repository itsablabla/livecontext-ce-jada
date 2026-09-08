package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * When THIS install first announced a given changelog entry.
 *
 * <p>Write-once per entry, stamped on the first request that mentions it. It answers one question:
 * was this account created after the install started announcing this entry? If so the account
 * never lacked what the entry describes, and it is acknowledged silently instead of being greeted
 * with a panel on its first minute.
 *
 * <p>The entry's own publication date cannot answer that, because a developer writes it and an
 * install adopts it whenever it upgrades. A self-hosted box that takes a release six months later
 * would otherwise seal every user who signed up in between, and none of them would ever see the
 * announcement.
 *
 * <p>Read-only mapping, like {@link UserChangelogSeen}: the row is written through the
 * repository's insert-if-absent statement.
 */
@Entity
@Table(name = "changelog_entry_first_seen", schema = "auth")
public class ChangelogEntryFirstSeen {

    @Id
    @Column(name = "entry_key", nullable = false, length = 120)
    private String entryKey;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    protected ChangelogEntryFirstSeen() {
        // for JPA
    }

    public String getEntryKey() {
        return entryKey;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }
}
