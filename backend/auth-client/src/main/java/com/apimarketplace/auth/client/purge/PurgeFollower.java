package com.apimarketplace.auth.client.purge;

import com.apimarketplace.auth.client.AuthClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Consumes {@code auth.purge_log} for ONE service: pulls the purge decisions it has not
 * seen yet, hands each subject to the service's own {@link Handler}, and advances a cursor
 * the service keeps in its own schema.
 *
 * <p><b>Why a follower and not a fan-out from auth.</b> Until 2026-09-02 auth-service
 * deleted every schema's org-scoped rows itself, in cross-schema SQL, and that one class
 * pinned all nine schemas to a single Postgres. With a follower, auth only ever writes to
 * {@code auth.*}, each service only ever deletes from its own schema, and a schema can move
 * to another database without anyone noticing. The price is latency: a purge completes
 * everywhere within {@link #PERIOD_SECONDS} of the decision instead of inside the same
 * transaction. The old purger offered no atomicity across schemas either (each statement ran
 * in its own savepoint and failures were merely logged), so nothing is lost there.
 *
 * <p><b>Guarantees, and how they are kept.</b>
 * <ul>
 *   <li><i>At least once.</i> The cursor advances only after a subject's handler returned.
 *       A crash mid-batch replays from the last finished record. Handlers must therefore be
 *       idempotent, which a DELETE by id is by nature.</li>
 *   <li><i>In order, and stop on failure.</i> A handler that throws stops the pass without
 *       advancing the cursor, so the same record is retried next period and nothing behind
 *       it is skipped. A single poisoned record therefore stalls the follower (visibly: a
 *       WARN on the first two failing passes, an ERROR naming the record from the third,
 *       which is what log alerting keys on) rather than being silently dropped, which is the
 *       right trade for a deletion the user was promised.</li>
 *   <li><i>No lock needed.</i> Two replicas may run a pass at the same time; both delete the
 *       same rows (harmless) and the cursor update is monotonic
 *       ({@code GREATEST(last_seq, ?)}), so neither can move it backwards.</li>
 *   <li><i>Auth down = no-op.</i> {@link AuthClient#getPurges} returns an empty list on any
 *       transport failure; the pass ends and the next one retries.</li>
 * </ul>
 *
 * <p>Runs on its own single-thread executor rather than Spring's {@code @Scheduled} so that
 * it needs neither {@code @EnableScheduling} (which would wake every dormant
 * {@code @Scheduled} bean in a service that never enabled it) nor a lock provider.
 */
public final class PurgeFollower {

    private static final Logger log = LoggerFactory.getLogger(PurgeFollower.class);

    /** Pass interval. Five minutes: a purge is visible everywhere within that. */
    public static final long PERIOD_SECONDS = 300;
    /** First pass waits for the service to finish booting. */
    public static final long INITIAL_DELAY_SECONDS = 90;
    /** Records per HTTP page; the endpoint caps at the same value. */
    public static final int PAGE_SIZE = 200;
    /** Pages per pass, so a huge backlog never holds one pass for hours. */
    static final int MAX_PAGES_PER_PASS = 50;

    /** The service's own cursor, in its own schema. */
    public interface Cursor {
        long read();

        /** Must be monotonic: implementations write {@code GREATEST(last_seq, seq)}. */
        void advanceTo(long seq);
    }

    /** What the service deletes for one subject. Both methods must be idempotent. */
    public interface Handler {
        void purgeOrganization(String organizationId);

        void purgeUser(String userId);
    }

    /** Passes in a row that ended on the same failure before the WARN becomes an ERROR. */
    static final int STALL_ERROR_THRESHOLD = 3;

    private final String serviceName;
    private final AuthClient authClient;
    private final Cursor cursor;
    private final Handler handler;
    private ScheduledExecutorService executor;
    private int consecutiveFailures;

    public PurgeFollower(String serviceName, AuthClient authClient, Cursor cursor, Handler handler) {
        this.serviceName = serviceName;
        this.authClient = authClient;
        this.cursor = cursor;
        this.handler = handler;
    }

    /** Starts the periodic pass. Idempotent. */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, serviceName + "-purge-follower");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::safePass, INITIAL_DELAY_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
        log.info("[PurgeFollower:{}] started (every {}s, first pass in {}s)", serviceName, PERIOD_SECONDS, INITIAL_DELAY_SECONDS);
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void safePass() {
        try {
            runPass();
        } catch (Exception e) {
            // A scheduled task that throws is silently unscheduled; never let that happen.
            log.warn("[PurgeFollower:{}] pass failed: {}", serviceName, e.getMessage());
        }
    }

    /** One pass. Public and deterministic so it can be driven by tests and by an operator. */
    public PassReport runPass() {
        int applied = 0;
        int pages = 0;
        long after = cursor.read();
        while (pages < MAX_PAGES_PER_PASS) {
            List<PurgeRecord> page = authClient.getPurges(after, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            pages++;
            for (PurgeRecord record : page) {
                try {
                    apply(record);
                } catch (Exception e) {
                    consecutiveFailures++;
                    String msg = "[PurgeFollower:" + serviceName + "] purge " + record.subjectType() + " "
                            + record.subjectId() + " #" + record.seq() + " failed, will retry next pass and nothing "
                            + "behind it is skipped (" + consecutiveFailures + " pass(es) in a row): " + e.getMessage();
                    if (consecutiveFailures >= STALL_ERROR_THRESHOLD) {
                        // Same record failing pass after pass = this schema no longer purges at all.
                        // A WARN every five minutes is easy to miss; an ERROR is what the log
                        // alerting keys on.
                        log.error("{} - STALLED, purges are not being applied in this service", msg);
                    } else {
                        log.warn(msg);
                    }
                    return new PassReport(applied, record.seq(), false);
                }
                cursor.advanceTo(record.seq());
                after = record.seq();
                applied++;
                consecutiveFailures = 0;
            }
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }
        if (applied > 0) {
            log.info("[PurgeFollower:{}] applied {} purge(s), cursor at #{}", serviceName, applied, after);
        }
        return new PassReport(applied, after, true);
    }

    private void apply(PurgeRecord record) {
        if (record.subjectType() == null || record.subjectType().isBlank()
                || record.subjectId() == null || record.subjectId().isBlank()) {
            // Not "a type this build does not know": a record with NO type or NO id is what a
            // wire drift looks like (a renamed field deserialises to null). Skipping it would
            // silently retain every purge behind it; stalling is the visible, retryable choice.
            throw new IllegalStateException("purge record #" + record.seq()
                    + " has no subject type or id: wire contract drift between auth-service and this client");
        }
        if (record.isOrganization()) {
            handler.purgeOrganization(record.subjectId());
        } else if (record.isUser()) {
            handler.purgeUser(record.subjectId());
        } else {
            // A subject type this build does not know: keep it in the log (nothing to do
            // here) and move on. It is not a failure of THIS service.
            log.warn("[PurgeFollower:{}] unknown subject type '{}' at #{}, skipped",
                    serviceName, record.subjectType(), record.seq());
        }
    }

    /**
     * @param applied   subjects handled and committed to the cursor during this pass
     * @param cursorSeq where the cursor stands; on failure, the seq of the record that failed
     * @param clean     false when a handler threw and the pass stopped early
     */
    public record PassReport(int applied, long cursorSeq, boolean clean) {
    }
}
