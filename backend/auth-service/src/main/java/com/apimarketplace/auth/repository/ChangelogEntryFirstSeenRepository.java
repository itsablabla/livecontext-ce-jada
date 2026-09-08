package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.ChangelogEntryFirstSeen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** Access to the per-install record of when each changelog entry was first announced here. */
@Repository
public interface ChangelogEntryFirstSeenRepository extends JpaRepository<ChangelogEntryFirstSeen, String> {

    /** When this install first announced {@code entryKey}, or empty when it never has. */
    @Query("SELECT f.firstSeenAt FROM ChangelogEntryFirstSeen f WHERE f.entryKey = :entryKey")
    Optional<Instant> findFirstSeenAt(@Param("entryKey") String entryKey);

    /**
     * Stamps the entry the first time it is served, and never moves the stamp afterwards.
     *
     * <p>{@code DO NOTHING} rather than an update: the value must stay the moment the install
     * STARTED announcing this entry. Two concurrent first requests are the normal case right after
     * an upgrade, and both may reach this - the conflict clause is what makes the earlier of the
     * two win instead of one failing.
     */
    @Modifying
    @Query(value = """
            INSERT INTO auth.changelog_entry_first_seen (entry_key, first_seen_at)
            VALUES (:entryKey, now())
            ON CONFLICT (entry_key) DO NOTHING
            """, nativeQuery = true)
    int stampIfAbsent(@Param("entryKey") String entryKey);
}
