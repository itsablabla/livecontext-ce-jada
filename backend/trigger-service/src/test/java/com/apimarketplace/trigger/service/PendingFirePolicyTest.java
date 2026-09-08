package com.apimarketplace.trigger.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a recomputed cron slot may replace the fire time a schedule row already carries.
 *
 * <p>This is the rule that makes the agenda's "move this occurrence" mean anything. The
 * move writes {@code next_execution_at} and deliberately leaves the cron alone, so the
 * column stops being a cache of the expression and becomes a decision. Seven places in
 * trigger-service used to recompute it unconditionally, including the sync that runs on
 * every workflow save, pin and run start - so a move survived until the workflow next ran,
 * then vanished with no error and no notification.
 *
 * <p>Every case below is written against that failure: the "preserve" ones fail on code
 * that recomputes unconditionally, and the "overwrite" ones fail on a fix that over-corrects
 * into never recomputing, which would leave a paused schedule advertising a fire time that
 * has already gone by.
 */
@DisplayName("PendingFirePolicy")
class PendingFirePolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
    private static final Instant MOVED_TO = Instant.parse("2026-09-03T14:30:00Z");
    private static final Instant CRON_SLOT = Instant.parse("2026-09-04T09:00:00Z");
    private static final String CRON = "0 9 * * *";
    private static final String ZONE = "Europe/Paris";

    @Nested
    @DisplayName("preserves the stored fire")
    class Preserves {

        @Test
        @DisplayName("when the row is rewritten with the same cron and zone")
        void sameShapeKeepsTheMove() {
            // The sync path: same cron, same zone, on every save/pin/run start. This single
            // case is the whole bug - it ran constantly and silently undid the user's drag.
            Instant resolved = PendingFirePolicy.resolve(
                    MOVED_TO, CRON, ZONE, CRON, ZONE, CRON_SLOT, NOW);

            assertThat(resolved).isEqualTo(MOVED_TO);
        }

        @Test
        @DisplayName("when the caller omits the cron entirely (a partial update)")
        void absentCronIsNotARedefinition() {
            // A rename or a max-executions edit sends no cron. Reading that as "cleared"
            // would make every unrelated field edit consume the move.
            assertThat(PendingFirePolicy.resolve(MOVED_TO, CRON, ZONE, null, null, CRON_SLOT, NOW))
                    .isEqualTo(MOVED_TO);
            assertThat(PendingFirePolicy.resolve(MOVED_TO, CRON, ZONE, "  ", "", CRON_SLOT, NOW))
                    .isEqualTo(MOVED_TO);
        }

        @Test
        @DisplayName("when the zone is spelled out rather than left to default to UTC")
        void defaultedZoneIsNotAChange() {
            // One caller omits the zone, another sends "UTC" explicitly; both describe the
            // same schedule. Treating them as different would recompute on every sync from
            // the second caller - the original bug, wearing a different hat.
            assertThat(PendingFirePolicy.resolve(MOVED_TO, CRON, null, CRON, "UTC", CRON_SLOT, NOW))
                    .isEqualTo(MOVED_TO);
            assertThat(PendingFirePolicy.resolve(MOVED_TO, CRON, "UTC", CRON, null, CRON_SLOT, NOW))
                    .isEqualTo(MOVED_TO);
        }

        @Test
        @DisplayName("even when the recompute yields nothing")
        void nullRecomputeNeverBlanksAUsableFire() {
            // An exhausted expression must not erase a fire time the row can still honour.
            assertThat(PendingFirePolicy.resolve(MOVED_TO, CRON, ZONE, CRON, ZONE, null, NOW))
                    .isEqualTo(MOVED_TO);
        }
    }

    @Nested
    @DisplayName("overwrites with the recomputed slot")
    class Overwrites {

        @Test
        @DisplayName("when the cron changed - the user redefined the cadence")
        void newCronSupersedesTheMove() {
            // Moving one run said "not this time". Editing the cron says "not this
            // schedule", which is the larger statement and wins.
            assertThat(PendingFirePolicy.resolve(MOVED_TO, CRON, ZONE, "0 18 * * *", ZONE, CRON_SLOT, NOW))
                    .isEqualTo(CRON_SLOT);
        }

        @Test
        @DisplayName("when the timezone changed")
        void newZoneSupersedesTheMove() {
            // "09:00" means a different instant now, so the stored slot is stale.
            assertThat(PendingFirePolicy.resolve(MOVED_TO, CRON, ZONE, CRON, "Asia/Kolkata", CRON_SLOT, NOW))
                    .isEqualTo(CRON_SLOT);
        }

        @Test
        @DisplayName("when the row carries no fire time yet - a create")
        void createTakesTheComputedSlot() {
            assertThat(PendingFirePolicy.resolve(null, null, null, CRON, ZONE, CRON_SLOT, NOW))
                    .isEqualTo(CRON_SLOT);
        }

        @Test
        @DisplayName("when the stored fire has already gone by")
        void expiredFireIsRecomputed() {
            // Typically a pause that outlived the pending slot. Keeping it would have the
            // row advertise a resume time in the past and the daemon claim it instantly -
            // its claim query is `next_execution_at <= now`.
            Instant past = NOW.minusSeconds(60);

            assertThat(PendingFirePolicy.resolve(past, CRON, ZONE, CRON, ZONE, CRON_SLOT, NOW))
                    .isEqualTo(CRON_SLOT);
        }

        @Test
        @DisplayName("when the stored fire is exactly now - it is due, not pending")
        void theBoundaryIsExclusive() {
            // `isAfter`, not `!isBefore`: a fire time equal to now is claimable this tick,
            // so treating it as a future decision would leave it to be claimed twice.
            assertThat(PendingFirePolicy.resolve(NOW, CRON, ZONE, CRON, ZONE, CRON_SLOT, NOW))
                    .isEqualTo(CRON_SLOT);
        }
    }

    @Test
    @DisplayName("an unresolvable zone falls back to string comparison rather than throwing")
    void corruptZoneDoesNotTakeTheWriteDown() {
        // A row can carry a zone the platform no longer resolves. Comparing must not throw
        // on a path that is only deciding which timestamp to store.
        assertThat(PendingFirePolicy.resolve(MOVED_TO, "0 9 * * *", "Mars/Olympus",
                "0 9 * * *", "Mars/Olympus", CRON_SLOT, NOW)).isEqualTo(MOVED_TO);
        assertThat(PendingFirePolicy.resolve(MOVED_TO, "0 9 * * *", "Mars/Olympus",
                "0 9 * * *", "Europe/Paris", CRON_SLOT, NOW)).isEqualTo(CRON_SLOT);
    }
}
