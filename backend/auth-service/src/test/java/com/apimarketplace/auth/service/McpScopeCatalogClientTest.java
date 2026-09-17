package com.apimarketplace.auth.service;

import com.apimarketplace.common.web.GatewayAuthenticationFilter;
import com.apimarketplace.common.web.GatewayFilterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpScopeCatalogClient")
class McpScopeCatalogClientTest {

    @Mock
    private RestTemplate restTemplate;

    @Test
    @DisplayName("reads availableScopes from the MCP connection endpoint and normalizes names")
    void getAvailableScopeNames_readsAndNormalizesNames() {
        McpScopeCatalogClient client = new McpScopeCatalogClient(restTemplate, "http://localhost:8099/", null);
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
        McpScopeCatalogClient client = new McpScopeCatalogClient(restTemplate, "http://localhost:8099", null);
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

    @Test
    @DisplayName("gateway-signs the orchestrator request so microservice deployments accept it")
    void getAvailableScopeNames_gatewaySignsRequest() {
        String sharedSecret = "test-shared-secret";
        McpScopeCatalogClient client = new McpScopeCatalogClient(restTemplate, "http://localhost:8099", sharedSecret);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        doAnswer(invocation -> {
            HttpEntity<?> entity = invocation.getArgument(2);
            HttpHeaders headers = entity.getHeaders();

            GatewayFilterProperties props = new GatewayFilterProperties();
            props.setVerificationEnabled(true);
            props.setSecretKey(sharedSecret);
            GatewayAuthenticationFilter filter = new GatewayAuthenticationFilter(props);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mcp-server/connection");
            headers.forEach((name, values) -> values.forEach(value -> request.addHeader(name, value)));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);

            return ResponseEntity.ok(Map.of("availableScopes", List.of(Map.of("name", "workflow"))));
        }).when(restTemplate).exchange(
                eq("http://localhost:8099/api/mcp-server/connection"),
                eq(HttpMethod.GET),
                entityCaptor.capture(),
                any(ParameterizedTypeReference.class));

        Set<String> scopes = client.getAvailableScopeNames(42L);

        assertThat(scopes).containsExactly("workflow");
        HttpHeaders sentHeaders = entityCaptor.getValue().getHeaders();
        assertThat(sentHeaders.getFirst("X-User-ID")).isEqualTo("42");
        assertThat(sentHeaders.getFirst("X-Provider-ID")).isEqualTo("internal-mcp-scope-client");
        assertThat(sentHeaders.getFirst("X-Gateway-Timestamp")).isNotBlank();
        assertThat(sentHeaders.getFirst("X-Gateway-Secret")).startsWith("gw_");
    }
}
