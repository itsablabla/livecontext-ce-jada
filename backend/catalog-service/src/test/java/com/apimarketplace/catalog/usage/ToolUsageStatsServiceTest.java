package com.apimarketplace.catalog.usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the catalog half of the node-usage ledger (V461).
 *
 * <p>The failure this class exists to prevent is a SILENT one: an identifier form the
 * orchestrator legitimately sends but this resolver does not understand is not an error,
 * it is a whole population of nodes missing from the ranking with nothing in the logs.
 * So every accepted form is pinned, and so is the fact that an unknown one is dropped
 * rather than guessed into a row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ToolUsageStatsService")
class ToolUsageStatsServiceTest {

    private static final String TOOL_UUID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

    @Mock private JdbcTemplate jdbcTemplate;

    private ToolUsageStatsService service;

    /** tool_slug (or id) -> the row the catalog would return for it. */
    private final Map<String, String[]> catalogRows = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new ToolUsageStatsService(jdbcTemplate);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    // JdbcTemplate.queryForList takes varargs, which Mockito hands back
                    // flattened - read them off the argument list rather than as an array.
                    Object[] all = invocation.getArguments();
                    Object[] params = (all.length == 2 && all[1] instanceof Object[] packed)
                            ? packed
                            : java.util.Arrays.copyOfRange(all, 1, all.length);
                    boolean byUuid = sql.contains("t.id::text IN");
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (Object param : params) {
                        String[] row = catalogRows.get(String.valueOf(param));
                        if (row == null) {
                            continue;
                        }
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("ref", byUuid ? String.valueOf(param) : row[0]);
                        out.put("tool_slug", row[0]);
                        out.put("api_slug", row[1]);
                        rows.add(out);
                    }
                    return rows;
                });
    }

    private void catalogHas(String reference, String toolSlug, String apiSlug) {
        catalogRows.put(reference, new String[]{toolSlug, apiSlug});
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> capturedBatches(int index) {
        ArgumentCaptor<List<Object[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).batchUpdate(anyString(), captor.capture());
        return captor.getAllValues().get(index);
    }

    private static Map<String, Long> asMap(List<Object[]> batch, int keyIndex, int valueIndex) {
        Map<String, Long> out = new LinkedHashMap<>();
        batch.forEach(row -> out.put((String) row[keyIndex], ((Number) row[valueIndex]).longValue()));
        return out;
    }

    @Test
    @DisplayName("counts a bare tool slug at both the endpoint and its integration")
    void bareSlugIsRecordedAtBothLevels() {
        catalogHas("elevenlabs-text-to-speech", "elevenlabs-text-to-speech", "elevenlabs");

        long attributed = service.record(Map.of("elevenlabs-text-to-speech", 4L));

        assertEquals(4L, attributed);
        assertEquals(Map.of("elevenlabs-text-to-speech", 4L), asMap(capturedBatches(0), 0, 2));
        assertEquals(Map.of("elevenlabs", 4L), asMap(capturedBatches(1), 0, 1));
    }

    @Test
    @DisplayName("resolves an apiSlug/toolSlug identifier by its LAST segment, not its prefix")
    void qualifiedIdentifierResolvesByToolSlug() {
        // The prefix is decoration: trusting it would attribute runs to an api slug that
        // a rename may have retired, while the tool slug is the row's real identity.
        catalogHas("slack-post-message", "slack-post-message", "slack");

        service.record(Map.of("slack/slack-post-message", 2L));

        assertEquals(Map.of("slack-post-message", 2L), asMap(capturedBatches(0), 0, 2));
        assertEquals(Map.of("slack", 2L), asMap(capturedBatches(1), 0, 1));
    }

    @Test
    @DisplayName("resolves a UUID identifier to its canonical slug")
    void uuidIdentifierResolvesToTheSlugRow() {
        catalogHas(TOOL_UUID, "stripe-create-charge", "stripe");

        service.record(Map.of(TOOL_UUID, 3L));

        assertEquals(Map.of("stripe-create-charge", 3L), asMap(capturedBatches(0), 0, 2));
    }

    @Test
    @DisplayName("merges two identifiers naming the same endpoint instead of counting it twice")
    void identifiersForTheSameEndpointAreMerged() {
        catalogHas(TOOL_UUID, "stripe-create-charge", "stripe");
        catalogHas("stripe-create-charge", "stripe-create-charge", "stripe");

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(TOOL_UUID, 2L);
        counts.put("stripe-create-charge", 3L);
        counts.put("stripe/stripe-create-charge", 1L);

        long attributed = service.record(counts);

        assertEquals(6L, attributed);
        // One endpoint row, one integration row, each carrying the full total: an upsert
        // per identifier would have added 2, then 3, then 1 to the API a second time.
        assertEquals(Map.of("stripe-create-charge", 6L), asMap(capturedBatches(0), 0, 2));
        assertEquals(Map.of("stripe", 6L), asMap(capturedBatches(1), 0, 1));
    }

    @Test
    @DisplayName("rolls several endpoints of one integration into a single API total")
    void endpointsOfOneApiAggregate() {
        catalogHas("slack-post-message", "slack-post-message", "slack");
        catalogHas("slack-list-channels", "slack-list-channels", "slack");
        catalogHas("stripe-create-charge", "stripe-create-charge", "stripe");

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("slack-post-message", 5L);
        counts.put("slack-list-channels", 2L);
        counts.put("stripe-create-charge", 1L);

        service.record(counts);

        assertEquals(Map.of("slack-post-message", 5L, "slack-list-channels", 2L, "stripe-create-charge", 1L),
                asMap(capturedBatches(0), 0, 2));
        assertEquals(Map.of("slack", 7L, "stripe", 1L), asMap(capturedBatches(1), 0, 1));
    }

    @Test
    @DisplayName("drops an identifier the catalog does not know rather than inventing a row")
    void unknownIdentifierIsDropped() {
        catalogHas("slack-post-message", "slack-post-message", "slack");

        long attributed = service.record(Map.of("slack-post-message", 1L, "gone-since-reimport", 9L));

        assertEquals(1L, attributed);
        assertEquals(Map.of("slack-post-message", 1L), asMap(capturedBatches(0), 0, 2));
    }

    @Test
    @DisplayName("writes nothing when no identifier resolves")
    void nothingIsWrittenWhenNothingResolves() {
        assertEquals(0L, service.record(Map.of("not-a-tool", 5L)));
        verify(jdbcTemplate, never()).batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>any());
    }

    @Test
    @DisplayName("ignores zero and negative counts")
    void nonPositiveCountsAreIgnored() {
        catalogHas("slack-post-message", "slack-post-message", "slack");

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("slack-post-message", 0L);

        assertEquals(0L, service.record(counts));
        verify(jdbcTemplate, never()).batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>any());
    }

    @Test
    @DisplayName("refuses a report bigger than the ceiling instead of building an unbounded query")
    void oversizedReportIsRefused() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i <= ToolUsageStatsService.MAX_IDENTIFIERS_PER_REPORT; i++) {
            counts.put("tool-" + i, 1L);
        }

        assertEquals(0L, service.record(counts));
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("handles an empty or null report without touching the database")
    void emptyReportIsANoOp() {
        assertEquals(0L, service.record(Map.of()));
        assertEquals(0L, service.record(null));
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("asks the catalog at most twice however many identifiers a window carries")
    void resolutionIsTwoQueriesRegardlessOfSize() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            catalogHas("tool-" + i, "tool-" + i, "api-" + (i % 5));
            counts.put("tool-" + i, 1L);
        }
        counts.put(TOOL_UUID, 1L);
        catalogHas(TOOL_UUID, "tool-uuid", "api-0");

        service.record(counts);

        verify(jdbcTemplate, org.mockito.Mockito.times(2)).queryForList(anyString(), any(Object[].class));
        assertTrue(asMap(capturedBatches(1), 0, 1).containsKey("api-0"));
    }
}
