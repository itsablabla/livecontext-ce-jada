package com.apimarketplace.orchestrator.services.badge;

import java.util.EnumMap;
import java.util.Map;

/**
 * One snapshot of every quantity the catalog can unlock on, for a single user.
 *
 * <p>Collected once per evaluation ({@code BadgeStatsCollector}) and then read
 * many times - once per badge - so the evaluator never issues a query per
 * badge. A metric the collector could not read (cross-service call failed) is
 * simply 0: badges are never revoked, so a transient zero delays an unlock to
 * the next evaluation instead of taking one away.
 */
public record BadgeStats(Map<BadgeMetric, Long> values) {

    public BadgeStats {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    /** Value of one metric; 0 when the collector had nothing for it. */
    public long get(BadgeMetric metric) {
        Long v = values.get(metric);
        return v == null ? 0L : v;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** No metric read at all - every {@link #get} answers 0. */
    public static BadgeStats empty() {
        return new BadgeStats(Map.of());
    }

    /** Mutable accumulator - the collector fills it query by query. */
    public static final class Builder {
        private final Map<BadgeMetric, Long> values = new EnumMap<>(BadgeMetric.class);

        /** Negative inputs are clamped to 0: no metric here can be meaningfully negative. */
        public Builder put(BadgeMetric metric, long value) {
            values.put(metric, Math.max(0L, value));
            return this;
        }

        public BadgeStats build() {
            return new BadgeStats(values);
        }
    }
}
