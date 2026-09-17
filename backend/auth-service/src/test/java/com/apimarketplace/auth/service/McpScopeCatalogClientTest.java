package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpScopeCatalogClient")
class McpScopeCatalogClientTest {

    @Mock
    private RestTemplate restTemplate;

    @Test
    @DisplayName("reads availableScopes from the MCP connection endpoint and normalizes names")
    void getAvailableScopeNames_readsAndNormalizesNames() {
        McpScopeCatalogClient client = new McpScopeCatalogClient(restTemplate, "http://localhost:8099/");
        when(restTemplate.exchange(
                eq("http://localhost:8099/api/mcp-server/connection"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "availableScopes", List.of(
                                Map.of("name", " Workflow "),
                                Map.of("name", "table"),
                                Map.of("name", "workflow"),
                                Map.of("description", "missing name")))));

        Set<String> scopes = client.getAvailableScopeNames(42L);

        assertThat(scopes).containsExactly("workflow", "table");
    }

    @Test
    @DisplayName("fails when the scope catalog response is missing availableScopes")
    void getAvailableScopeNames_rejectsMalformedResponse() {
        McpScopeCatalogClient client = new McpScopeCatalogClient(restTemplate, "http://localhost:8099");
        when(restTemplate.exchange(
                eq("http://localhost:8099/api/mcp-server/connection"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

        assertThatThrownBy(() -> client.getAvailableScopeNames(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to fetch MCP scope catalog");
    }
}
