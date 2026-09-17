package com.apimarketplace.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Auth-service client for the authoritative MCP scope vocabulary exposed by
 * orchestrator's settings endpoint. Scoped lc_live_ keys are validated against
 * the same tool list the UI renders so stale/unknown tool names can never be
 * persisted server-side.
 */
@Component
public class McpScopeCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(McpScopeCatalogClient.class);

    private final RestTemplate restTemplate;
    private final String orchestratorUrl;

    @Autowired
    public McpScopeCatalogClient(@Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl) {
        this(buildRestTemplate(), orchestratorUrl);
    }

    McpScopeCatalogClient(RestTemplate restTemplate, String orchestratorUrl) {
        this.restTemplate = restTemplate;
        this.orchestratorUrl = stripTrailingSlash(orchestratorUrl);
    }

    public Set<String> getAvailableScopeNames(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-ID", String.valueOf(userId));
        try {
            Map<String, Object> body = restTemplate.exchange(
                    orchestratorUrl + "/api/mcp-server/connection",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {})
                    .getBody();
            return extractScopeNames(body);
        } catch (Exception e) {
            log.warn("Failed to fetch MCP scope catalog for userId={}: {}", userId, e.getMessage());
            throw new IllegalStateException("Failed to fetch MCP scope catalog", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> extractScopeNames(Map<String, Object> body) {
        if (body == null || !(body.get("availableScopes") instanceof Collection<?> scopes)) {
            throw new IllegalStateException("MCP connection response did not contain availableScopes");
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object scope : scopes) {
            if (!(scope instanceof Map<?, ?> scopeMap)) {
                continue;
            }
            Object name = scopeMap.get("name");
            if (name == null) {
                continue;
            }
            String normalized = name.toString().trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                names.add(normalized);
            }
        }
        return names;
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        return new RestTemplate(factory);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8099";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
