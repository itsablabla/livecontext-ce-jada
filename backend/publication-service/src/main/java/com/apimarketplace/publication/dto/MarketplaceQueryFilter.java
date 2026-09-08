package com.apimarketplace.publication.dto;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The refinements the public marketplace grid can ask the database for.
 *
 * <p>Every field used to be applied in the browser, on whatever the single
 * {@code page=0&size=50} popularity-ordered fetch happened to return. That made
 * each refinement a filter over an arbitrary window rather than over the
 * catalogue: with 76 public publications, 26 of them could not be reached by any
 * combination of clicks, "sort by recent" never showed the newest publication
 * (it was not in the popular 50 to begin with), and "published in the last 7
 * days" came back empty on the very day something was published. Pushing the
 * whole set down to SQL is what makes a filter mean what it says, and what makes
 * paging through the result correct, since the page boundary is then computed on
 * the filtered, sorted set.
 *
 * <p>{@link Sort}, {@link Rating} and {@link Price} are enums rather than
 * strings because {@code sort} chooses an ORDER BY clause: a whitelist by
 * construction, so no caller-supplied text ever reaches the SQL. Every
 * {@code parse} falls back to the neutral default instead of throwing, so a
 * stale bookmark or a hand-edited query string renders the unfiltered grid
 * rather than an error page.
 *
 * <p>The freshness window is kept as a day count, not as the instant it resolves
 * to, so this record is both the query shape and the wire shape: the CE proxy
 * re-emits it upstream through {@link #toQueryParams()} without having to invert
 * a subtraction against a clock that has since moved.
 *
 * @param categorySlug category to restrict to, or {@code null} for all
 * @param displayMode  {@code DisplayMode} name to restrict to, or {@code null} for all types
 * @param sort         ordering; never {@code null}
 * @param rating       rating floor; never {@code null}
 * @param windowDays   only publications published within the last N days, or {@code null} for no window
 * @param price        free / paid split; never {@code null}
 * @param studio       {@code TRUE} to keep only studio applications, {@code null} for no constraint.
 *                     A SECOND AXIS, not a category: a studio application keeps whatever category it
 *                     is about, so this narrows the grid without replacing that question.
 *                     {@code FALSE} is deliberately accepted too - "everything that is not a studio
 *                     app" is a real question, and silently widening it to "everything" would answer
 *                     a different one.
 */
public record MarketplaceQueryFilter(
        String categorySlug,
        String displayMode,
        Sort sort,
        Rating rating,
        Integer windowDays,
        Price price,
        Boolean studio) {

    /** Ordering of the marketplace grid. Each constant owns one ORDER BY clause. */
    public enum Sort {
        /**
         * The service's own popularity score (favorites, installs, rating mass).
         * Stays the default: it is the only ordering that knows the favorite
         * counts, which are not columns on the publication row.
         */
        POPULAR,
        /** Best rated first, unrated last, ties broken by how many people rated. */
        RATING,
        /** Newest publication first. */
        RECENT,
        /** Most installed first. */
        INSTALLS;

        public static Sort parse(String raw) {
            return parseOr(Sort.class, raw, POPULAR);
        }
    }

    /**
     * Rating floor. An unrated publication has no average to compare, so it fails
     * every constraint other than {@link #ANY} rather than passing as a silent 0.
     */
    public enum Rating {
        ANY(0d),
        RATED(0d),
        MIN_3(3d),
        MIN_4(4d);

        private final double threshold;

        Rating(double threshold) {
            this.threshold = threshold;
        }

        /** Minimum {@code average_rating} to accept; only meaningful when {@link #requiresReviews()}. */
        public double threshold() {
            return threshold;
        }

        /** Whether the publication must carry at least one review to pass. */
        public boolean requiresReviews() {
            return this != ANY;
        }

        public static Rating parse(String raw) {
            return parseOr(Rating.class, raw, ANY);
        }
    }

    /** Free / paid split, read off {@code credits_per_use}. */
    public enum Price {
        ANY,
        FREE,
        PAID;

        public static Price parse(String raw) {
            return parseOr(Price.class, raw, ANY);
        }
    }

    /**
     * Normalising constructor: blanks collapse to {@code null}, a non-positive
     * window collapses to "no window" (a literal reading would ask for
     * publications from the future), an unknown {@code displayMode} widens to
     * "every type" rather than narrowing the grid to a value no publication
     * carries, and the three enums are never {@code null}. Doing it here means no
     * call site repeats the guards and the query builder can branch on identity.
     */
    public MarketplaceQueryFilter {
        categorySlug = blankToNull(categorySlug);
        displayMode = normalizeDisplayMode(displayMode);
        if (sort == null) sort = Sort.POPULAR;
        if (rating == null) rating = Rating.ANY;
        if (price == null) price = Price.ANY;
        if (windowDays != null && windowDays <= 0) windowDays = null;
    }

    /** The unfiltered marketplace: every type, popularity order, no refinement. */
    public static MarketplaceQueryFilter unfiltered() {
        return new MarketplaceQueryFilter(null, null, Sort.POPULAR, Rating.ANY, null, Price.ANY, null);
    }

    /** Just a category, everything else neutral (the pre-refinement browse call). */
    public static MarketplaceQueryFilter ofCategory(String categorySlug) {
        return new MarketplaceQueryFilter(categorySlug, null, null, null, null, null, null);
    }

    /**
     * Build from raw request params; every unparseable value falls back to its default.
     *
     * <p>There is deliberately NO shorter overload that omits {@code studio}. One existed, and all
     * three of its production callers were the same bug: the CE proxy and the cloud search silently
     * dropped the studio axis and answered with the whole marketplace, HTTP 200, heading unchanged.
     * A defaulted parameter is invisible at the call site, so nothing pointed at them. Every caller
     * passing the axis explicitly - {@code null} when there is genuinely none - is what makes the
     * omission a compile error instead of a wrong page.
     */
    public static MarketplaceQueryFilter fromRequest(
            String category, String displayMode, String sort, String rating, Integer days, String price,
            Boolean studio) {
        return new MarketplaceQueryFilter(
                category, displayMode, Sort.parse(sort), Rating.parse(rating), days, Price.parse(price),
                studio);
    }

    /** True when nothing but the default ordering is asked for. */
    public boolean isUnfiltered() {
        return categorySlug == null
                && displayMode == null
                && rating == Rating.ANY
                && windowDays == null
                && price == Price.ANY
                // A studio constraint is a refinement like any other. Leaving it out here would let
                // the caller take the unfiltered fast path and answer the whole catalogue.
                && studio == null;
    }

    /**
     * The oldest {@code published_at} this filter accepts, or {@code null} when no
     * window is set. The clock is passed in rather than read here so the boundary
     * is fixed for the whole query (the count and the data query must agree) and
     * so tests can pin it.
     */
    public Instant publishedAfter(Instant now) {
        return windowDays == null ? null : now.minus(Duration.ofDays(windowDays));
    }

    /**
     * The normalised refinements as marketplace query params, for the CE proxy
     * that forwards this filter to the cloud. Defaults are omitted so the
     * upstream URL carries only what the visitor actually chose.
     */
    public Map<String, String> toQueryParams() {
        Map<String, String> params = new LinkedHashMap<>();
        if (categorySlug != null) params.put("category", categorySlug);
        if (displayMode != null) params.put("displayMode", displayMode);
        if (sort != Sort.POPULAR) params.put("sort", sort.name());
        if (rating != Rating.ANY) params.put("rating", rating.name());
        if (windowDays != null) params.put("days", String.valueOf(windowDays));
        if (price != Price.ANY) params.put("price", price.name());
        // Forwarded like any other refinement. Left out, a cloud-linked self-hosted install asks
        // the cloud for the whole catalogue and renders it under the Studio heading.
        if (studio != null) params.put("studio", String.valueOf(studio));
        return params;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String normalizeDisplayMode(String raw) {
        String trimmed = blankToNull(raw);
        if (trimmed == null) return null;
        try {
            return DisplayMode.valueOf(trimmed.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The enum class is passed explicitly rather than read off {@code fallback}:
     * {@code fallback.getClass()} returns the anonymous subclass for any constant
     * that ever gains a body, and {@code Enum.valueOf} then throws on every input.
     */
    private static <E extends Enum<E>> E parseOr(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
