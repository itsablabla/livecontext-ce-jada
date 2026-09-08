package com.apimarketplace.common.retention;

import com.apimarketplace.common.plan.PlanTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LogRetentionPolicy")
class LogRetentionPolicyTest {

    @Nested
    @DisplayName("Advertised windows")
    class AdvertisedWindows {

        @Test
        @DisplayName("Each priced plan gets the window its pricing page advertises")
        void pricedPlansMatchTheGrid() {
            assertEquals(7, LogRetentionPolicy.retentionDays("FREE"));
            assertEquals(30, LogRetentionPolicy.retentionDays("STARTER"));
            assertEquals(30, LogRetentionPolicy.retentionDays("PRO"));
            assertEquals(90, LogRetentionPolicy.retentionDays("TEAM"));
        }

        @Test
        @DisplayName("Plan codes are matched case-insensitively and trimmed")
        void codesAreNormalized() {
            assertEquals(90, LogRetentionPolicy.retentionDays("  team "));
            assertEquals(7, LogRetentionPolicy.retentionDays("free"));
        }

        @Test
        @DisplayName("The feature key names the same window the policy enforces")
        void featureKeyTracksTheWindow() {
            assertEquals("logs7", LogRetentionPolicy.featureKey("FREE"));
            assertEquals("logs30", LogRetentionPolicy.featureKey("STARTER"));
            assertEquals("logs30", LogRetentionPolicy.featureKey("PRO"));
            assertEquals("logs90", LogRetentionPolicy.featureKey("TEAM"));
        }
    }

    @Nested
    @DisplayName("Codes that share a rank")
    class SharedRanks {

        /**
         * Retention is keyed by PlanTier RANK, so these codes are covered without
         * being named. Keying by literal code would leave them unmapped, and an
         * unmapped code retains forever - which for PAYG would silently hand a
         * paying account an unbounded journal.
         */
        @Test
        @DisplayName("PAYG follows STARTER, its rank twin, rather than falling through")
        void paygFollowsStarter() {
            assertEquals(LogRetentionPolicy.retentionDays("STARTER"),
                    LogRetentionPolicy.retentionDays("PAYG"));
        }

        @Test
        @DisplayName("CREDIT_PACK follows FREE: a top-up is not a plan")
        void creditPackFollowsFree() {
            assertEquals(LogRetentionPolicy.retentionDays("FREE"),
                    LogRetentionPolicy.retentionDays("CREDIT_PACK"));
        }

        @Test
        @DisplayName("Every ENTERPRISE SKU retains, including one this file never names")
        void everyEnterpriseSkuRetains() {
            assertNull(LogRetentionPolicy.retentionDays("ENTERPRISE"));
            assertNull(LogRetentionPolicy.retentionDays("ENTERPRISE_BASIC"));
            assertNull(LogRetentionPolicy.retentionDays("ENTERPRISE_STANDARD"));
            assertNull(LogRetentionPolicy.retentionDays("ENTERPRISE_PREMIUM"));
            assertNull(LogRetentionPolicy.retentionDays("ENTERPRISE_ULTIMATE"));
            assertNull(LogRetentionPolicy.retentionDays("ENTERPRISE_SOVEREIGN_2027"));
        }
    }

    @Nested
    @DisplayName("Failing long")
    class FailingLong {

        /**
         * The mirror image of PlanFeatureGate, which fails OPEN. That gate protects
         * revenue, so a lookup failure costs a sale. This policy DELETES, so a lookup
         * failure must cost nothing.
         */
        @Test
        @DisplayName("A plan code the platform does not know retains, it does not get the shortest window")
        void unknownCodeRetains() {
            assertNull(LogRetentionPolicy.retentionDays("GALAXY"));
            assertNull(LogRetentionPolicy.retentionDays("some-future-sku"));
        }

        @Test
        @DisplayName("A self-hosted install retains: plan gating is a cloud-only concept")
        void selfHostedRetains() {
            assertNull(LogRetentionPolicy.retentionDays(PlanTier.CE));
        }

        /**
         * The one that matters most. PlanLimitService.loadPlanCode returns this same
         * sentinel for "account found, no active subscription" AND for "account not
         * found". Reading it as FREE would apply the 7-day window to a tenant the
         * platform merely failed to identify - the August 2026 id-form incident, but
         * destructive instead of merely blocking. The FREE window requires the literal
         * code FREE.
         */
        @Test
        @DisplayName("The no-subscription sentinel retains, because it also means 'account not found'")
        void noSubscriptionSentinelIsNotFree() {
            assertNull(LogRetentionPolicy.retentionDays(PlanTier.NO_SUBSCRIPTION));
            assertNull(LogRetentionPolicy.retentionDays("  __none__  "));
        }

        @Test
        @DisplayName("The sentinel is never advertised as a numbered window")
        void sentinelHasNoNumberedKey() {
            assertEquals("logsCustom", LogRetentionPolicy.featureKey(PlanTier.NO_SUBSCRIPTION));
        }

        @Test
        @DisplayName("A null or blank plan code is the FREE rank, which is the one ambiguity the sentinel guard cannot cover")
        void nullAndBlankRankAsFree() {
            // PlanTier ranks null/blank as FREE. Callers must therefore never pass a
            // blank code to mean "unknown": the resolver sends the sentinel for that,
            // and the guard above catches it.
            assertEquals(7, LogRetentionPolicy.retentionDays(null));
            assertEquals(7, LogRetentionPolicy.retentionDays("   "));
        }
    }

    @Nested
    @DisplayName("isExpired")
    class IsExpired {

        @Test
        @DisplayName("A row is expired only strictly past the window")
        void strictlyPastTheWindow() {
            assertFalse(LogRetentionPolicy.isExpired("TEAM", 90));
            assertTrue(LogRetentionPolicy.isExpired("TEAM", 91));
            assertFalse(LogRetentionPolicy.isExpired("FREE", 7));
            assertTrue(LogRetentionPolicy.isExpired("FREE", 8));
        }

        @Test
        @DisplayName("Nothing is ever expired under a retain-forever plan, however old")
        void retainForeverNeverExpires() {
            assertFalse(LogRetentionPolicy.isExpired("ENTERPRISE", 10_000));
            assertFalse(LogRetentionPolicy.isExpired(PlanTier.CE, 10_000));
            assertFalse(LogRetentionPolicy.isExpired("GALAXY", 10_000));
            assertFalse(LogRetentionPolicy.isExpired(PlanTier.NO_SUBSCRIPTION, 10_000));
        }
    }
}
