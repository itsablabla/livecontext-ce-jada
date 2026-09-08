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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the service-to-service intake of endpoint launch counts (V461).
 *
 * <p>The body arrives as untyped JSON from another service, so the parsing is the
 * behaviour worth pinning: a malformed window must be refused ONCE (the caller drops it)
 * rather than accepted as zero (the caller thinks it landed) or thrown (the caller
 * retries the same broken body forever).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ToolUsageIngestController")
class ToolUsageIngestControllerTest {

    @Mock private ToolUsageStatsService statsService;

    private ToolUsageIngestController controller;

    @BeforeEach
    void setUp() {
        controller = new ToolUsageIngestController(statsService);
        when(statsService.record(anyMap())).thenReturn(0L);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> recorded() {
        ArgumentCaptor<Map<String, Long>> captor = ArgumentCaptor.forClass(Map.class);
        verify(statsService).record(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("passes the reported counts through and answers with what was attributed")
    void acceptsAWellFormedWindow() {
        when(statsService.record(anyMap())).thenReturn(7L);

        ResponseEntity<Map<String, Object>> response = controller.recordToolUsage(
                Map.of("counts", Map.of("slack-post-message", 5, "stripe-create-charge", 2)));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().get("received"));
        assertEquals(7L, response.getBody().get("attributed"));
        assertEquals(Map.of("slack-post-message", 5L, "stripe-create-charge", 2L), recorded());
    }

    @Test
    @DisplayName("drops entries whose count is not a positive number")
    void skipsNonPositiveAndNonNumericCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("kept", 3);
        counts.put("zero", 0);
        counts.put("negative", -4);
        counts.put("text", "many");
        counts.put("missing", null);

        controller.recordToolUsage(Map.of("counts", counts));

        assertEquals(Map.of("kept", 3L), recorded());
    }

    @Test
    @DisplayName("refuses a body with no counts object instead of silently recording nothing")
    void refusesAMalformedBody() {
        // 400, not 200-with-zero: the caller must be able to tell "you sent nonsense"
        // from "it landed", because it retries the second and drops the first.
        assertEquals(HttpStatus.BAD_REQUEST, controller.recordToolUsage(Map.of()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.recordToolUsage(null).getStatusCode());

        Map<String, Object> notAnObject = new HashMap<>();
        notAnObject.put("counts", "slack-post-message");
        assertEquals(HttpStatus.BAD_REQUEST, controller.recordToolUsage(notAnObject).getStatusCode());

        verify(statsService, never()).record(anyMap());
    }

    @Test
    @DisplayName("accepts an empty window without touching the ledger")
    void acceptsAnEmptyWindow() {
        ResponseEntity<Map<String, Object>> response = controller.recordToolUsage(Map.of("counts", Map.of()));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().get("received"));
    }
}
