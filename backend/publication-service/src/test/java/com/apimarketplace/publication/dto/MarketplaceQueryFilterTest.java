package com.apimarketplace.publication.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The marketplace refinements are read straight off a URL, so this record is the
 * boundary between a query string anyone can edit and a SQL clause. Two
 * properties matter and are pinned here: nothing unrecognised may narrow the
 * grid (an unknown value must WIDEN to the default, never produce an empty
 * marketplace or an error page), and the normalised filter must round-trip back
 * to query params unchanged, because the CE proxy re-emits it to the cloud.
 */
@DisplayName("MarketplaceQueryFilter")
class MarketplaceQueryFilterTest {

    @Nested
    @DisplayName("Parsing a request")
    class Parsing {

        @Test
        @DisplayName("Reads every refinement, case-insensitively")
        void readsEveryRefinement() {
            MarketplaceQueryFilter f =
                    MarketplaceQueryFilter.fromRequest("ai", "agent", "Recent", "min_4", 7, "FREE", null);

            assertThat(f.categorySlug()).isEqualTo("ai");
            assertThat(f.displayMode()).isEqualTo("AGENT");
            assertThat(f.sort()).isEqualTo(MarketplaceQueryFilter.Sort.RECENT);
            assertThat(f.rating()).isEqualTo(MarketplaceQueryFilter.Rating.MIN_4);
            assertThat(f.windowDays()).isEqualTo(7);
            assertThat(f.price()).isEqualTo(MarketplaceQueryFilter.Price.FREE);
        }

        @Test
        @DisplayName("An unknown sort / rating / price falls back to its neutral default")
        void unknownEnumsFallBack() {
            MarketplaceQueryFilter f =
                    MarketplaceQueryFilter.fromRequest(null, null, "cheapest", "five_stars", null, "gratis", null);

            assertThat(f.sort()).isEqualTo(MarketplaceQueryFilter.Sort.POPULAR);
            assertThat(f.rating()).isEqualTo(MarketplaceQueryFilter.Rating.ANY);
            assertThat(f.price()).isEqualTo(MarketplaceQueryFilter.Price.ANY);
            assertThat(f.isUnfiltered()).isTrue();
        }

        @Test
        @DisplayName("An unknown display mode WIDENS to every type instead of matching nothing")
        void unknownDisplayModeWidens() {
            // Narrowing on an unrecognised value would render an empty marketplace
            // with no way back except editing the URL - the failure mode has to be
            // "you see everything", not "you see nothing".
            assertThat(MarketplaceQueryFilter.fromRequest(null, "APLICATION", null, null, null, null, null).displayMode())
                    .isNull();
        }

        @Test
        @DisplayName("Blank strings are treated as absent, not as a value to match")
        void blanksAreAbsent() {
            MarketplaceQueryFilter f = MarketplaceQueryFilter.fromRequest("  ", "  ", "  ", "  ", null, "  ", null);

            assertThat(f.categorySlug()).isNull();
            assertThat(f.displayMode()).isNull();
            assertThat(f.isUnfiltered()).isTrue();
        }

        @Test
        @DisplayName("Null enums are normalised, so no call site has to guard them")
        void nullEnumsAreNormalised() {
            MarketplaceQueryFilter f = new MarketplaceQueryFilter("ai", null, null, null, null, null, null);

            assertThat(f.sort()).isEqualTo(MarketplaceQueryFilter.Sort.POPULAR);
            assertThat(f.rating()).isEqualTo(MarketplaceQueryFilter.Rating.ANY);
            assertThat(f.price()).isEqualTo(MarketplaceQueryFilter.Price.ANY);
        }

        @Test
        @DisplayName("A category alone is not 'unfiltered' - it does narrow the grid")
        void categoryCountsAsAFilter() {
            assertThat(MarketplaceQueryFilter.ofCategory("finance").isUnfiltered()).isFalse();
            assertThat(MarketplaceQueryFilter.unfiltered().isUnfiltered()).isTrue();
        }
    }

    @Nested
    @DisplayName("Freshness window")
    class Window {

        @Test
        @DisplayName("Resolves to a floor N days before the clock it is given")
        void resolvesAgainstTheGivenClock() {
            Instant now = Instant.parse("2026-08-24T12:00:00Z");

            assertThat(MarketplaceQueryFilter.fromRequest(null, null, null, null, 7, null, null).publishedAfter(now))
                    .isEqualTo(now.minus(Duration.ofDays(7)));
        }

        @Test
        @DisplayName("No window means no floor at all")
        void noWindowNoFloor() {
            Instant now = Instant.parse("2026-08-24T12:00:00Z");

            assertThat(MarketplaceQueryFilter.unfiltered().publishedAfter(now)).isNull();
        }

        @Test
        @DisplayName("A zero or negative window is dropped, not read as 'published in the future'")
        void nonPositiveWindowIsDropped() {
            assertThat(MarketplaceQueryFilter.fromRequest(null, null, null, null, 0, null, null).windowDays()).isNull();
            assertThat(MarketplaceQueryFilter.fromRequest(null, null, null, null, -3, null, null).windowDays()).isNull();
        }
    }

    @Nested
    @DisplayName("Rating floors")
    class Ratings {

        @Test
        @DisplayName("Every constraint but ANY demands at least one review")
        void constraintsDemandAReview() {
            assertThat(MarketplaceQueryFilter.Rating.ANY.requiresReviews()).isFalse();
            assertThat(MarketplaceQueryFilter.Rating.RATED.requiresReviews()).isTrue();
            assertThat(MarketplaceQueryFilter.Rating.MIN_3.requiresReviews()).isTrue();
            assertThat(MarketplaceQueryFilter.Rating.MIN_4.requiresReviews()).isTrue();
        }

        @Test
        @DisplayName("RATED carries no average floor - 'has been rated' is the whole condition")
        void ratedHasNoFloor() {
            assertThat(MarketplaceQueryFilter.Rating.RATED.threshold()).isZero();
            assertThat(MarketplaceQueryFilter.Rating.MIN_3.threshold()).isEqualTo(3d);
            assertThat(MarketplaceQueryFilter.Rating.MIN_4.threshold()).isEqualTo(4d);
        }
    }

    @Nested
    @DisplayName("Round-trip to query params (the CE proxy re-emits this upstream)")
    class QueryParams {

        @Test
        @DisplayName("Emits every chosen refinement")
        void emitsChosenRefinements() {
            assertThat(MarketplaceQueryFilter.fromRequest("ai", "AGENT", "recent", "min_4", 7, "free", null)
                    .toQueryParams())
                    .containsEntry("category", "ai")
                    .containsEntry("displayMode", "AGENT")
                    .containsEntry("sort", "RECENT")
                    .containsEntry("rating", "MIN_4")
                    .containsEntry("days", "7")
                    .containsEntry("price", "FREE");
        }

        @Test
        @DisplayName("Omits the defaults, so the upstream URL says only what the visitor chose")
        void omitsDefaults() {
            assertThat(MarketplaceQueryFilter.unfiltered().toQueryParams()).isEmpty();
        }

        @Test
        @DisplayName("Re-parsing the emitted params yields the same filter")
        void roundTrips() {
            MarketplaceQueryFilter original =
                    MarketplaceQueryFilter.fromRequest("ai", "AGENT", "installs", "min_3", 30, "paid", null);

            var params = original.toQueryParams();
            MarketplaceQueryFilter reparsed = MarketplaceQueryFilter.fromRequest(
                    params.get("category"),
                    params.get("displayMode"),
                    params.get("sort"),
                    params.get("rating"),
                    Integer.valueOf(params.get("days")),
                    params.get("price"), null);

            assertThat(reparsed).isEqualTo(original);
        }

        @Test
        @DisplayName("Emits the studio axis, or a cloud-linked install asks for the WHOLE catalogue")
        void emitsTheStudioAxis() {
            // The defect this pins. A self-hosted install proxies its marketplace reads upstream
            // through exactly this map: drop the axis here and a visitor who switched to Studio is
            // served every application in the cloud catalogue, rendered under the Studio heading,
            // with HTTP 200 and no error anywhere. The grid simply answers a narrower question with
            // the widest possible answer.
            assertThat(MarketplaceQueryFilter.fromRequest(null, null, null, null, null, null, true)
                    .toQueryParams())
                    .containsEntry("studio", "true");
        }

        @Test
        @DisplayName("Emits an explicit studio=false rather than treating it as unset")
        void emitsAnExplicitFalse() {
            // The other direction, so a forwarder hardcoded to emit "true" cannot pass. `false`
            // here means "the ordinary shelf, studio apps excluded", which is a real refinement -
            // omitting it upstream would widen it back to "everything, studio ones included".
            assertThat(MarketplaceQueryFilter.fromRequest(null, null, null, null, null, null, false)
                    .toQueryParams())
                    .containsEntry("studio", "false");
        }

        @Test
        @DisplayName("Says nothing about the axis when the visitor did not choose one")
        void omitsAnAbsentAxis() {
            // Absent must stay absent: emitting a default would turn "no opinion" into a
            // refinement the visitor never asked for on every ordinary marketplace read.
            assertThat(MarketplaceQueryFilter.fromRequest("ai", null, null, null, null, null, null)
                    .toQueryParams())
                    .doesNotContainKey("studio");
        }

        @Test
        @DisplayName("Re-parsing the emitted params preserves the studio axis")
        void roundTripsTheStudioAxis() {
            // toQueryParams stringifies; fromRequest takes a Boolean. The proxy performs exactly
            // this hop, so a serialization that cannot survive it silently widens the grid.
            MarketplaceQueryFilter original =
                    MarketplaceQueryFilter.fromRequest("ai", null, null, null, null, null, true);

            var params = original.toQueryParams();
            MarketplaceQueryFilter reparsed = MarketplaceQueryFilter.fromRequest(
                    params.get("category"), null, null, null, null, null,
                    Boolean.valueOf(params.get("studio")));

            assertThat(reparsed).isEqualTo(original);
            assertThat(reparsed.studio()).isTrue();
        }
    }
}
