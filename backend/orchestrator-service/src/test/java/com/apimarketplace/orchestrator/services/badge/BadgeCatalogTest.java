package com.apimarketplace.orchestrator.services.badge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog is a data table, and every defect in it is silent: a duplicate
 * code re-labels an existing unlock row, a non-monotonic threshold makes a
 * higher tier unlock before a lower one, and a cohort date off by a day quietly
 * changes who counts as a founder. These pin all three.
 */
@DisplayName("BadgeCatalog")
class BadgeCatalogTest {

    @Test
    @DisplayName("every badge code is unique - a duplicate would re-label an existing unlock row")
    void codesAreUnique() {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (BadgeDefinition definition : BadgeCatalog.all()) {
            if (!seen.add(definition.code())) {
                duplicates.add(definition.code());
            }
        }
        assertThat(duplicates).isEmpty();
    }

    @Test
    @DisplayName("byCode resolves every catalog entry and returns null for an unknown code")
    void byCodeResolvesKnownAndUnknown() {
        for (BadgeDefinition definition : BadgeCatalog.all()) {
            assertThat(BadgeCatalog.byCode(definition.code())).isSameAs(definition);
        }
        assertThat(BadgeCatalog.byCode("no_such_badge")).isNull();
        assertThat(BadgeCatalog.byCode(null)).isNull();
    }

    @Test
    @DisplayName("a harder threshold always carries at least as high a tier - a rarer badge cannot look cheaper")
    void tierNeverRegressesAgainstThreshold() {
        for (BadgeFamily family : BadgeFamily.values()) {
            List<BadgeDefinition> inFamily = BadgeCatalog.all().stream()
                    .filter(d -> d.family() == family)
                    .toList();
            for (BadgeDefinition a : inFamily) {
                for (BadgeDefinition b : inFamily) {
                    if (a.threshold() <= b.threshold()) continue;
                    assertThat(a.tier().ordinal())
                            .as("%s is harder than %s, so its tier must not be lower", a.code(), b.code())
                            .isGreaterThanOrEqualTo(b.tier().ordinal());
                }
            }
        }
    }

    @Test
    @DisplayName("a family is listed hardest-last, except the cohorts which are listed rarest-first")
    void familyIsListedInDifficultyOrder() {
        for (BadgeFamily family : BadgeFamily.values()) {
            List<BadgeDefinition> inFamily = BadgeCatalog.all().stream()
                    .filter(d -> d.family() == family)
                    .toList();
            for (int i = 1; i < inFamily.size(); i++) {
                BadgeDefinition previous = inFamily.get(i - 1);
                BadgeDefinition current = inFamily.get(i);
                // FOUNDER's metric counts DOWN (days left until the cutoff), so
                // its thresholds descend along the list: the earliest, rarest
                // cohort is shown first because nobody progresses INTO it.
                if (family == BadgeFamily.FOUNDER) {
                    assertThat(current.threshold())
                            .as("%s must be a later cohort than %s", current.code(), previous.code())
                            .isLessThan(previous.threshold());
                } else {
                    assertThat(current.threshold())
                            .as("%s must be harder than %s", current.code(), previous.code())
                            .isGreaterThan(previous.threshold());
                }
            }
        }
    }

    @Test
    @DisplayName("every badge in a family measures the same metric")
    void familyIsMetricConsistent() {
        for (BadgeFamily family : BadgeFamily.values()) {
            List<BadgeMetric> metrics = BadgeCatalog.all().stream()
                    .filter(d -> d.family() == family)
                    .map(BadgeDefinition::metric)
                    .distinct()
                    .toList();
            assertThat(metrics).as("family %s", family).hasSize(1);
        }
    }

    @Test
    @DisplayName("every family and every metric has at least one badge")
    void catalogCoversEveryFamilyAndMetric() {
        for (BadgeFamily family : BadgeFamily.values()) {
            assertThat(BadgeCatalog.all()).anyMatch(d -> d.family() == family);
        }
        for (BadgeMetric metric : BadgeMetric.values()) {
            assertThat(BadgeCatalog.all()).anyMatch(d -> d.metric() == metric);
        }
    }

    @Test
    @DisplayName("thresholds are positive - a zero threshold would unlock for everyone on day one")
    void thresholdsArePositive() {
        assertThat(BadgeCatalog.all()).allSatisfy(d -> assertThat(d.threshold()).isPositive());
    }

    @Nested
    @DisplayName("cohort window")
    class CohortWindow {

        private long daysFor(LocalDate joinDate) {
            return BadgeCatalog.daysUntilJoinCutoff(joinDate.atStartOfDay(ZoneOffset.UTC).toInstant());
        }

        private long thresholdOf(String code) {
            return BadgeCatalog.byCode(code).threshold();
        }

        @Test
        @DisplayName("the origin badge unlocks for a 2026 signup and not for a 2027 one")
        void originBoundaryMatchesTheAdvertisedDate() {
            // Its last qualifying day, and the first day that misses it.
            assertUnlocksOnAndMissesAfter("founder_2026", LocalDate.of(2026, 12, 31));
        }

        @Test
        @DisplayName("origin is a single ungraded trophy - no lesser cohorts below it")
        void originHasExactlyOneBadge() {
            List<BadgeDefinition> origin = BadgeCatalog.all().stream()
                    .filter(d -> d.family() == BadgeFamily.FOUNDER)
                    .toList();

            assertThat(origin)
                    .as("adding a second origin badge turns a one-of-one into a ladder, "
                            + "and the medal drops its rank pips on that assumption")
                    .hasSize(1);
            assertThat(origin.get(0).tier()).isEqualTo(BadgeTier.DIAMOND);
        }

        private void assertUnlocksOnAndMissesAfter(String code, LocalDate lastQualifyingDay) {
            BadgeDefinition badge = BadgeCatalog.byCode(code);
            assertThat(badge).as(code).isNotNull();
            assertThat(badge.isUnlockedBy(daysFor(lastQualifyingDay)))
                    .as("%s must unlock for a %s signup", code, lastQualifyingDay)
                    .isTrue();
            assertThat(badge.isUnlockedBy(daysFor(lastQualifyingDay.plusDays(1))))
                    .as("%s must NOT unlock for a %s signup", code, lastQualifyingDay.plusDays(1))
                    .isFalse();
        }

        @Test
        @DisplayName("an account created after the cutoff scores zero rather than a negative")
        void signupAfterCutoffClampsToZero() {
            assertThat(daysFor(LocalDate.of(2027, 6, 1))).isZero();
            // Zero must not unlock the origin badge, which is the whole point of
            // the clamp: a negative would still be < threshold, but zero is the
            // value the UI shows as progress.
            assertThat(thresholdOf("founder_2026")).isPositive();
        }

        @Test
        @DisplayName("a null creation date reads as zero instead of throwing")
        void nullCreatedAtIsZero() {
            assertThat(BadgeCatalog.daysUntilJoinCutoff(null)).isZero();
        }

        @Test
        @DisplayName("the cutoff is the documented 2027-01-01")
        void cutoffIsPinned() {
            assertThat(BadgeCatalog.JOIN_CUTOFF).isEqualTo(LocalDate.of(2027, 1, 1));
            // One day before the cutoff scores exactly 1 - the founder_early
            // threshold - which is what makes "joined in 2026" the boundary.
            Instant lastDayOf2026 = LocalDate.of(2026, 12, 31).atStartOfDay(ZoneOffset.UTC).toInstant();
            assertThat(BadgeCatalog.daysUntilJoinCutoff(lastDayOf2026)).isEqualTo(1);
        }
    }
}
