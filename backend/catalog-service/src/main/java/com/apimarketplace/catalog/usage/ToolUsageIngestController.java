package com.apimarketplace.catalog.usage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Service-to-service intake for endpoint launch counts (V461).
 *
 * <p>Under {@code /api/internal/} on purpose: that prefix is unroutable from the edge in
 * cloud ({@code SimpleGatewayConfigTest.mustNotPubliclyRouteApiInternal}) and reachable
 * only in-process on CE ({@code MonolithSecurityFilter} loopback trust), so the ranking
 * cannot be stuffed from outside.
 */
@RestController
@RequestMapping("/api/internal/catalog")
@RequiredArgsConstructor
@Slf4j
public class ToolUsageIngestController {

    private final ToolUsageStatsService toolUsageStatsService;

    /**
     * POST /api/internal/catalog/tool-usage
     *
     * <p>Body: <code>{"counts": {"&lt;tool identifier&gt;": &lt;launches&gt;}}</code>.
     *
     * <p>Answers 200 with what was attributed. The caller keeps its window and retries on
     * anything else, so a failure here costs ordering freshness and never a run: a
     * malformed body is the only case that must not be retried forever, which is why it
     * is a 400 rather than a 500.
     */
    @PostMapping("/tool-usage")
    public ResponseEntity<Map<String, Object>> recordToolUsage(@RequestBody Map<String, Object> body) {
        Object rawCounts = body == null ? null : body.get("counts");
        if (!(rawCounts instanceof Map<?, ?> counts)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "counts must be an object of tool identifier to launch count"));
        }

        Map<String, Long> parsed = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : counts.entrySet()) {
            if (!(entry.getKey() instanceof String identifier) || !(entry.getValue() instanceof Number count)) {
                continue;
            }
            long value = count.longValue();
            if (value > 0) {
                parsed.put(identifier, value);
            }
        }

        long attributed = toolUsageStatsService.record(parsed);
        return ResponseEntity.ok(Map.of(
                "received", parsed.size(),
                "attributed", attributed));
    }
}
