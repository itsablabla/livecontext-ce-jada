package com.apimarketplace.datasource.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one derivation of an index-build event from a column's display config.
 * It replaced three verbatim copies and gained a fourth caller (the snapshot
 * clone, which previously built no index at all). Pinning it here means a
 * future publish site cannot quietly accept a narrower set of inputs.
 */
@DisplayName("VectorColumnCreatedEvent.forColumn")
class VectorColumnCreatedEventTest {

    @Test
    @DisplayName("a numeric dimension and an explicit metric are read as declared")
    void numericDimensionAndMetric() {
        var event = VectorColumnCreatedEvent.forColumn(7L, Map.of("dimension", 1536, "metric", "l2"));
        assertThat(event).contains(new VectorColumnCreatedEvent(7L, 1536, "l2"));
    }

    @Test
    @DisplayName("a string dimension is parsed, so a column declared through JSON text still gets its index")
    void stringDimensionIsParsed() {
        var event = VectorColumnCreatedEvent.forColumn(7L, Map.of("dimension", " 768 "));
        assertThat(event).contains(new VectorColumnCreatedEvent(7L, 768, "cosine"));
    }

    @Test
    @DisplayName("the metric defaults to cosine, matching the partial index the build creates")
    void metricDefaultsToCosine() {
        assertThat(VectorColumnCreatedEvent.forColumn(7L, Map.of("dimension", 64)).orElseThrow().metric())
                .isEqualTo("cosine");
    }

    @Test
    @DisplayName("no usable dimension means no event: null display, missing key, zero, negative, non-numeric text")
    void unusableDimensionYieldsNoEvent() {
        assertThat(VectorColumnCreatedEvent.forColumn(7L, null)).isEmpty();
        assertThat(VectorColumnCreatedEvent.forColumn(7L, Map.of())).isEmpty();
        assertThat(VectorColumnCreatedEvent.forColumn(7L, Map.of("dimension", 0))).isEmpty();
        assertThat(VectorColumnCreatedEvent.forColumn(7L, Map.of("dimension", -5))).isEmpty();
        assertThat(VectorColumnCreatedEvent.forColumn(7L, Map.of("dimension", "big"))).isEmpty();
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("dimension", null);
        assertThat(VectorColumnCreatedEvent.forColumn(7L, nullValue)).isEmpty();
    }
}
