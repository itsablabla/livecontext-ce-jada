package com.apimarketplace.auth.web;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Service-to-service read of {@code auth.purge_log}, the outbox every other service's
 * {@code PurgeFollower} consumes to delete its own rows for a purged workspace or account.
 *
 * <p>Keyset by {@code seq}: the caller passes the last seq it finished and gets the next
 * page in log order. The page is capped at {@link #MAX_LIMIT}; a larger request is clamped,
 * not refused, because this endpoint only ever under-delivers (the caller pages again) and
 * never truncates something the caller believes it got.
 *
 * <p>Lives under {@code /api/internal}, which the gateway does not route from the edge.
 */
@RestController
@RequestMapping("/api/internal/auth/purges")
public class InternalPurgeLogController {

    static final int MAX_LIMIT = 200;
    /** A row is served only once older than this, so an uncommitted lower seq cannot be skipped. */
    static final int SETTLE_SECONDS = 60;

    /**
     * Wire shape, mirrored by auth-client's {@code PurgeRecord} (auth-service does not depend
     * on its own client jar). Field names are the contract: {@code seq}, {@code subjectType},
     * {@code subjectId}.
     */
    public record PurgeLogEntry(long seq, String subjectType, String subjectId) {
    }

    @PersistenceContext
    private EntityManager em;

    /**
     * @param after the last seq the caller has applied; 0 for the beginning of the log
     * @param limit page size, clamped to [1, {@link #MAX_LIMIT}]
     */
    @GetMapping
    public ResponseEntity<List<PurgeLogEntry>> purgesAfter(@RequestParam(defaultValue = "0") long after,
                                                           @RequestParam(defaultValue = "200") int limit) {
        int size = Math.max(1, Math.min(MAX_LIMIT, limit));
        // The commit-visibility gap of every keyset outbox: seq is assigned at INSERT and
        // visible at COMMIT, and a purge transaction stays open across dozens of statements
        // (AccountPurgeService), so seq N+1 can commit before seq N. A follower that reads in
        // that window would store N+1 and never be served N again, silently. Rows are
        // therefore served only once they are older than any purge transaction can plausibly
        // be; a follower is minutes behind anyway.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT seq, subject_type, subject_id FROM auth.purge_log "
                                + "WHERE seq > ?1 AND purged_at < now() - interval '" + SETTLE_SECONDS + " seconds' "
                                + "ORDER BY seq LIMIT ?2")
                .setParameter(1, after)
                .setParameter(2, size)
                .getResultList();
        List<PurgeLogEntry> page = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            page.add(new PurgeLogEntry(((Number) row[0]).longValue(), String.valueOf(row[1]), String.valueOf(row[2])));
        }
        return ResponseEntity.ok(page);
    }
}
