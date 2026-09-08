package com.apimarketplace.trigger.service;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Whether a freshly computed cron slot may overwrite the fire time a schedule row already
 * carries.
 *
 * <p><b>The rule this exists to state once:</b> a stored {@code next_execution_at} that is
 * still in the FUTURE is a <i>decision</i>, not a cache of the cron expression. The agenda's
 * "move this occurrence" writes exactly there and deliberately leaves the cron alone, so the
 * single moved run happens at the new time and every later one returns to its normal slot.
 * Recomputing that column from the cron therefore does not "refresh" it, it <b>destroys a
 * user's edit</b>, silently: no error, no notification, the calendar simply redraws the chip
 * where it used to be.
 *
 * <p>This was found the hard way. Eight places in this service wrote the column, seven of
 * them unconditionally, and the sync that runs on every workflow save, every pin and every
 * run start was among them - so a move survived only until the workflow next ran. The first
 * fix repaired one of the eight (the workflow resume path) and a doc line was written
 * asserting moves were durable in general, which was not true. Hence one policy, one place,
 * applied at every site.
 *
 * <p>Overwriting is legitimate in exactly two cases:
 * <ol>
 *   <li><b>The schedule's shape changed.</b> A new cron or a new timezone makes the old
 *       pending slot meaningless - the user redefined when this thing runs, which supersedes
 *       their earlier one-off move.</li>
 *   <li><b>The stored fire can no longer be honoured</b> - it is absent, or it is already in
 *       the past (typically a pause that outlived it). Keeping a stale timestamp would have
 *       the row advertise a fire time that has gone by, and the daemon claim it instantly.</li>
 * </ol>
 *
 * <p>Note what is NOT a reason to overwrite: merely writing the row again. Sync, adoption,
 * resume, an unrelated field edit and a re-pin all rewrite the row with the SAME cron, and
 * every one of them used to consume the move.
 */
public final class PendingFirePolicy {

    private PendingFirePolicy() {
    }

    /**
     * Resolve the value to store in {@code next_execution_at}.
     *
     * @param storedFire    the fire time currently on the row ({@code null} on create)
     * @param storedCron    the cron currently on the row ({@code null} on create)
     * @param storedZone    the timezone currently on the row ({@code null} on create)
     * @param newCron       the cron being written
     * @param newZone       the timezone being written
     * @param recomputed    the next slot derived from {@code newCron}/{@code newZone}, which
     *                      may be {@code null} when the expression yields nothing further
     * @return the instant to store: {@code recomputed} when the shape changed or the stored
     *         fire cannot be honoured, otherwise the stored fire, preserved
     */
    public static Instant resolve(Instant storedFire,
                                  String storedCron,
                                  String storedZone,
                                  String newCron,
                                  String newZone,
                                  Instant recomputed) {
        return resolve(storedFire, storedCron, storedZone, newCron, newZone, recomputed, Instant.now());
    }

    /** Overload taking the clock, so the boundary is testable without sleeping. */
    static Instant resolve(Instant storedFire,
                           String storedCron,
                           String storedZone,
                           String newCron,
                           String newZone,
                           Instant recomputed,
                           Instant now) {
        if (shapeChanged(storedCron, storedZone, newCron, newZone)) {
            // The user redefined the cadence. Their earlier one-off move described a slot
            // of a schedule that no longer exists, so it does not survive - but a null
            // recompute must not blank a usable fire time either.
            return recomputed != null ? recomputed : storedFire;
        }
        if (storedFire == null || !storedFire.isAfter(now)) {
            return recomputed != null ? recomputed : storedFire;
        }
        return storedFire;
    }

    /**
     * Whether the schedule's shape changed. A blank/absent incoming value means "not
     * specified" rather than "cleared", so it is not a change: partial updates (a rename, a
     * max-executions edit) must not be read as a redefinition of the cadence.
     */
    private static boolean shapeChanged(String storedCron, String storedZone,
                                        String newCron, String newZone) {
        if (newCron != null && !newCron.isBlank() && !newCron.equals(storedCron)) {
            return true;
        }
        return newZone != null && !newZone.isBlank() && !sameZone(storedZone, newZone);
    }

    /**
     * Compare zones by the rules the scheduler itself uses. An absent zone IS {@code UTC}
     * on this entity, so a caller that omits it, and one that spells it out, describe the
     * same schedule; treating those as different would recompute on every sync from a
     * caller that happens to send the default explicitly. Equivalent spellings of one zone
     * ({@code Europe/Paris} vs an alias) resolve to the same rules and are likewise not a
     * change; an unresolvable zone falls back to string equality rather than throwing.
     */
    private static boolean sameZone(String storedZone, String newZone) {
        String left = storedZone == null || storedZone.isBlank() ? "UTC" : storedZone;
        String right = newZone == null || newZone.isBlank() ? "UTC" : newZone;
        if (left.equals(right)) {
            return true;
        }
        try {
            return ZoneId.of(left).getRules().equals(ZoneId.of(right).getRules());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
