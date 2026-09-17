package com.apimarketplace.catalog.seed;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogSeedCredentialService")
class CatalogSeedCredentialServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApiToolRepository apiToolRepository;

    private CatalogSeedCredentialService service;

    @BeforeEach
    void setUp() {
        service = new CatalogSeedCredentialService(jdbcTemplate, apiToolRepository);
    }

    private SeedManifest.SeedSpec createSpec(String credentialName, String authType) {
        SeedManifest.SeedSpec spec = new SeedManifest.SeedSpec();
        spec.setId("test");
        spec.setCredentialName(credentialName);
        spec.setAuthType(authType);
        spec.setIconSlug("icon");
        return spec;
    }

    @Test
    @DisplayName("should skip when no credential name")
    void shouldSkipWhenNoCredentialName() {
        SeedManifest.SeedSpec spec = createSpec(null, "apiKey");
        UUID apiId = UUID.randomUUID();

        service.linkCredentials(apiId, spec);

        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(apiToolRepository);
    }

    @Test
    @DisplayName("should skip when no auth type")
    void shouldSkipWhenNoAuthType() {
        SeedManifest.SeedSpec spec = createSpec("myCredential", null);
        UUID apiId = UUID.randomUUID();

        service.linkCredentials(apiId, spec);

        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(apiToolRepository);
    }

    @Test
    @DisplayName("should upsert credential and link tools")
    void shouldUpsertAndLink() {
        SeedManifest.SeedSpec spec = createSpec("openweathermap", "apiKey");
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, spec);

        verify(jdbcTemplate).queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                eq("openweathermap"), eq("openweathermap"), eq("apiKey"), any(String.class), eq("icon"), any());

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                eq(toolId), eq(credentialId), eq("openweathermap"), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertTrue(metadata.contains("\"field\": \"api_key\""));
        assertTrue(metadata.contains("\"injection\""));
        assertTrue(metadata.contains("\"type\": \"header\""));
        assertTrue(metadata.contains("\"key\": \"X-API-Key\""));
    }

    @Test
    @DisplayName("should upsert and link via direct params overload with bearer metadata")
    void shouldUpsertAndLinkViaDirectParams() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "bearer", "myicon");

        verify(jdbcTemplate).queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                eq("myapi"), eq("myapi"), eq("bearer"), any(String.class), eq("myicon"), any());

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                eq(toolId), eq(credentialId), eq("myapi"), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertTrue(metadata.contains("\"field\": \"access_token\""));
        assertTrue(metadata.contains("\"type\": \"header\""));
        assertTrue(metadata.contains("\"key\": \"Authorization\""));
    }

    @Test
    @DisplayName("should use apikey injection metadata for apiKey auth type")
    void shouldUseApiKeyInjectionMetadata() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "apiKey", "icon");

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                any(UUID.class), any(UUID.class), anyString(), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertTrue(metadata.contains("\"field\": \"api_key\""));
        assertTrue(metadata.contains("\"type\": \"header\""));
        assertTrue(metadata.contains("\"key\": \"X-API-Key\""));
    }

    @Test
    @DisplayName("should use X-API-Key for unknown auth type (default branch)")
    void shouldUseDefaultInjectionMetadataForUnknownAuthType() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "custom_auth", "icon");

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                any(UUID.class), any(UUID.class), anyString(), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertTrue(metadata.contains("\"key\": \"X-API-Key\""));
        assertTrue(metadata.contains("\"field\": \"api_key\""));
    }

    @Test
    @DisplayName("should use basic_auth metadata and username/password fields for basic auth")
    void shouldUseBasicAuthInjectionMetadata() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "basic_auth", "icon");

        ArgumentCaptor<String> propertiesCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                eq("myapi"), eq("myapi"), eq("basic_auth"), propertiesCaptor.capture(), eq("icon"), any());
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                any(UUID.class), any(UUID.class), anyString(), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        String properties = propertiesCaptor.getValue();
        assertTrue(properties.contains("\"username\""));
        assertTrue(properties.contains("\"password\""));
        assertTrue(metadata.contains("\"type\": \"basic_auth\""));
        assertTrue(metadata.contains("\"key\": \"Authorization\""));
        assertTrue(metadata.contains("\"field\": \"username\""));
    }

    @Test
    @DisplayName("should preserve explicit custom API header auth wiring")
    void shouldPreserveExplicitHeaderAuthWiring() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(
                apiId,
                "myapi",
                "apikey",
                "icon",
                null,
                new CatalogSeedCredentialService.CustomApiAuthConfig("apikey", "header", "Authorization", "Token "));

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                any(UUID.class), any(UUID.class), anyString(), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertTrue(metadata.contains("\"type\": \"header\""));
        assertTrue(metadata.contains("\"key\": \"Authorization\""));
        assertTrue(metadata.contains("\"prefix\": \"Token \""));
    }

    @Test
    @DisplayName("should preserve explicit custom API query auth wiring")
    void shouldPreserveExplicitQueryAuthWiring() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(
                apiId,
                "myapi",
                "apikey",
                "icon",
                null,
                new CatalogSeedCredentialService.CustomApiAuthConfig("apikey", "query", "api_key", null));

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                any(UUID.class), any(UUID.class), anyString(), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertTrue(metadata.contains("\"type\": \"query\""));
        assertTrue(metadata.contains("\"key\": \"api_key\""));
    }

    @Test
    @DisplayName("should skip direct params when credential name is blank")
    void shouldSkipDirectParamsWhenBlank() {
        UUID apiId = UUID.randomUUID();

        service.linkCredentials(apiId, "", "bearer", "icon");

        verifyNoInteractions(apiToolRepository);
    }

    @Test
    @DisplayName("should delete tool_credentials and credential by name")
    void shouldDeleteCredentialByName() {
        when(jdbcTemplate.update(contains("DELETE FROM catalog.tool_credentials"), eq("myapi")))
                .thenReturn(3);
        when(jdbcTemplate.update(contains("DELETE FROM catalog.credentials"), eq("myapi")))
                .thenReturn(1);

        service.deleteCredentialByName("myapi");

        var inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate).update(contains("DELETE FROM catalog.tool_credentials"), eq("myapi"));
        inOrder.verify(jdbcTemplate).update(contains("DELETE FROM catalog.credentials"), eq("myapi"));
    }

    @Test
    @DisplayName("should skip delete when credential name is null")
    void shouldSkipDeleteWhenNull() {
        service.deleteCredentialByName(null);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("upsert SQL targets (credential_name, variant) UNIQUE - regression for V103 multi-variant schema; pre-fix used ON CONFLICT (credential_name) which Postgres rejects with bad SQL grammar after V103 dropped that constraint")
    void upsertSqlTargetsCredentialNameVariantUniqueConstraint() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(anyString(), eq(UUID.class),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "bearer", "icon");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(UUID.class),
                any(), any(), any(), any(), any(), any());
        String sql = sqlCaptor.getValue();

        assertTrue(sql.contains("INSERT INTO catalog.credentials"));
        assertTrue(sql.contains("variant"));
        assertTrue(sql.contains("'primary'"));
        assertTrue(sql.contains("ON CONFLICT (credential_name, variant)"));
        assertFalse(sql.matches("(?s).*ON\\s+CONFLICT\\s*\\(\\s*credential_name\\s*\\).*"));
    }

    @Test
    @DisplayName("should link multiple tools to same credential")
    void shouldLinkMultipleTools() {
        SeedManifest.SeedSpec spec = createSpec("myApi", "bearer");
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();

        ApiToolEntity tool1 = new ApiToolEntity();
        tool1.setId(UUID.randomUUID());
        ApiToolEntity tool2 = new ApiToolEntity();
        tool2.setId(UUID.randomUUID());
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool1, tool2));

        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, spec);

        verify(jdbcTemplate, times(2)).update(contains("INSERT INTO catalog.tool_credentials"),
                any(UUID.class), eq(credentialId), eq("myApi"), eq("primary"), any(String.class));
    }
}
