package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.UserChangelogSeen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Access to the per-user changelog acknowledgement (one row per user, keyed by user id). */
@Repository
public interface UserChangelogSeenRepository extends JpaRepository<UserChangelogSeen, Long> {

    /**
     * The acknowledged entry key, or empty when this user acknowledged none.
     *
     * <p>A projection rather than the entity: the read path needs one string, and loading a managed
     * entity would put a mutable object in front of a table this service only ever writes through
     * the upsert below.
     */
    @Query("SELECT s.entryKey FROM UserChangelogSeen s WHERE s.userId = :userId")
    Optional<String> findEntryKey(@Param("userId") Long userId);

    /**
     * Records the acknowledgement in one statement, whether or not the user already had a row.
     *
     * <p>Read-then-write would race: with an ASSIGNED {@code @Id}, {@code save()} takes Hibernate's
     * merge path (select, then insert), and two tabs acknowledging at the same moment can both read
     * "no row" and both insert - one of them failing on the primary key. A user clicking through
     * two open tabs is exactly the shape that produces it.
     *
     * <p>Also the reason there is no monotonic guard: the caller acknowledges the entry it actually
     * displayed, and after a rollback that is legitimately an older key.
     */
    @Modifying
    @Query(value = """
            INSERT INTO auth.user_changelog_seen (user_id, entry_key, seen_at)
            VALUES (:userId, :entryKey, now())
            ON CONFLICT (user_id) DO UPDATE
            SET entry_key = EXCLUDED.entry_key, seen_at = EXCLUDED.seen_at
            """, nativeQuery = true)
    int acknowledge(@Param("userId") Long userId, @Param("entryKey") String entryKey);
}
