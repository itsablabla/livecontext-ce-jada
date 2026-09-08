package com.apimarketplace.orchestrator.services.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Ships one flush window's catalog-endpoint counts to catalog-service.
 *
 * <p>WHY IT IS NOT A LOCAL WRITE. The counts are per ENDPOINT and the palette ranks
 * INTEGRATIONS, so someone has to map an endpoint identifier to its API. Only catalog
 * can: {@code tool_slug} is derived at import time and an api slug cannot be recovered
 * from it by string surgery (AGENTS.md), and the orchestrator must not read the
 * {@code catalog} schema. So the orchestrator sends what it saw and catalog resolves it.
 *
 * <p>WHY IT IS CHEAP. One request per flush window (a minute by default) carrying a map
 * of the distinct endpoints launched in it, not one request per launch.
 *
 * <p>WHY ITS OWN CLIENT. It runs on a scheduler thread and must not inherit the shared
 * template's ten-minute read window, nor a transport that re-sends a POST by itself -
 * see {@code RestTemplateConfig.nodeUsageRestTemplate}.
 */
@Component
public class ToolUsagePublisher {

    private static final Logger log = LoggerFactory.getLogger(ToolUsagePublisher.class);

    private final RestTemplate restTemplate;
    private final String catalogBaseUrl;

    public ToolUsagePublisher(@Qualifier("nodeUsageRestTemplate") RestTemplate restTemplate,
                              @Value("${orchestrator.catalog.base-url:http://localhost:8081}") String catalogBaseUrl) {
        this.restTemplate = restTemplate;
        this.catalogBaseUrl = catalogBaseUrl;
    }

    /**
     * @param deltas tool identifier (UUID, {@code apiSlug/toolSlug} or bare slug) -> launches
     * @return true when catalog accepted the window; false means the caller should keep the
     *         deltas and retry them on the next flush
     */
    public boolean publish(Map<String, Long> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return true;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(
                    catalogBaseUrl + "/api/internal/catalog/tool-usage",
                    new HttpEntity<>(Map.of("counts", deltas), headers),
                    Void.class);
            log.debug("Tool usage published: {} endpoint(s)", deltas.size());
            return true;
        } catch (Exception e) {
            // Popularity is not worth an alert, and the deltas are handed back to the
            // accumulator, so a catalog restart costs ordering freshness and nothing else.
            log.warn("Could not publish tool usage to catalog ({} endpoints): {}",
                    deltas.size(), e.getMessage());
            return false;
        }
    }
}
