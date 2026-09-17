package com.apimarketplace.catalog.seed;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Post-import service that upserts credential schemas into catalog.credentials
 * and links tools via catalog.tool_credentials.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogSeedCredentialService {

    public record CustomApiAuthConfig(String authType, String injectionType, String key, String prefix) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ApiToolRepository apiToolRepository;

    /**
     * Creates or updates a credential schema and links all tools of the given API to it.
     */
    public void linkCredentials(UUID apiId, SeedManifest.SeedSpec spec) {
        if (spec.getCredentialName() == null || spec.getCredentialName().isBlank()) {
            log.debug("No credential name for seed {}, skipping credential linking", spec.getId());
            return;
        }
        if (spec.getAuthType() == null || spec.getAuthType().isBlank()) {
            log.debug("No auth type for seed {}, skipping credential linking", spec.getId());
            return;
        }

        linkCredentials(apiId, spec.getCredentialName(), spec.getAuthType(), spec.getIconSlug());
    }

    /**
     * Creates or updates a credential schema and links all tools of the given API to it.
     * Direct-parameter overload used by custom API registration.
     *
     * @param apiId          the API whose tools should be linked
     * @param credentialName unique credential key (e.g. "stripepayments")
     * @param authType       authentication type (bearer, apikey, oauth2, etc.)
     * @param iconSlug       brand icon identifier (nullable)
     */
    public void linkCredentials(UUID apiId, String credentialName, String authType, String iconSlug) {
        linkCredentials(apiId, credentialName, authType, iconSlug, null);
    }

    /**
     * Creates or updates a credential schema and links all tools of the given API to it.
     *
     * @param apiId          the API whose tools should be linked
     * @param credentialName unique credential key (e.g. "stripepayments")
     * @param authType       authentication type (bearer, apikey, oauth2, etc.)
     * @param iconSlug       brand icon identifier (nullable)
     * @param iconUrl        dynamic icon URL (S3 proxy) for custom API icons (nullable)
     */
    public void linkCredentials(UUID apiId, String credentialName, String authType, String iconSlug, String iconUrl) {
        linkCredentials(apiId, credentialName, authType, iconSlug, iconUrl, defaultCustomApiAuthConfig(authType));
    }

    public void linkCredentials(
            UUID apiId,
            String credentialName,
            String authType,
            String iconSlug,
            String iconUrl,
            CustomApiAuthConfig authConfig) {
        if (credentialName == null || credentialName.isBlank()) {
            log.debug("No credential name for API {}, skipping credential linking", apiId);
            return;
        }
        if (authType == null || authType.isBlank()) {
            log.debug("No auth type for API {}, skipping credential linking", apiId);
            return;
        }

        CustomApiAuthConfig effectiveAuthConfig = authConfig != null
                ? authConfig
                : defaultCustomApiAuthConfig(authType);
        String properties = buildPropertiesJson(authType);
        String displayName = buildDisplayName(credentialName);

        UUID credentialId = upsertCredential(credentialName, displayName, authType, properties, iconSlug, iconUrl);

        List<ApiToolEntity> tools = apiToolRepository.findByApiId(apiId);
        for (ApiToolEntity tool : tools) {
            linkToolCredential(tool.getId(), credentialId, credentialName, effectiveAuthConfig);
        }

        log.info("Linked {} tools to credential '{}' for API {}", tools.size(), credentialName, apiId);
    }

    private UUID upsertCredential(String credentialName, String displayName, String authType, String properties, String iconSlug, String iconUrl) {
        String sql = """
                INSERT INTO catalog.credentials (credential_name, variant, display_name, auth_type, properties, icon_slug, icon_url, created_at, updated_at)
                VALUES (?, 'primary', ?, ?, ?::jsonb, ?, ?, EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000)
                ON CONFLICT (credential_name, variant) DO UPDATE
                SET display_name = EXCLUDED.display_name,
                    auth_type = EXCLUDED.auth_type,
                    properties = EXCLUDED.properties,
                    icon_slug = EXCLUDED.icon_slug,
                    icon_url = EXCLUDED.icon_url,
                    updated_at = EXTRACT(EPOCH FROM NOW()) * 1000
                RETURNING id
                """;

        return jdbcTemplate.queryForObject(sql, UUID.class,
                credentialName, displayName, authType, properties, iconSlug, iconUrl);
    }

    private void linkToolCredential(UUID toolId, UUID credentialId, String credentialName, CustomApiAuthConfig authConfig) {
        String metadata = buildInjectionMetadata(authConfig);
        String variant = "primary";

        String sql = """
                INSERT INTO catalog.tool_credentials (api_tool_id, credential_id, credential_name, variant, is_required, usage, metadata, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, 'authentication', ?::jsonb, EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000)
                ON CONFLICT (api_tool_id, credential_name, variant) DO UPDATE
                SET credential_id = EXCLUDED.credential_id,
                    metadata = EXCLUDED.metadata,
                    updated_at = EXTRACT(EPOCH FROM NOW()) * 1000
                """;

        jdbcTemplate.update(sql, toolId, credentialId, credentialName, variant, metadata);
    }

    /**
     * Delete the credential template and associated tool_credentials by name.
     * tool_credentials.credential_id FK is ON DELETE SET NULL, so deleting
     * the credentials row would leave orphaned tool_credentials rows. We
     * explicitly delete them first by credential_name.
     */
    public void deleteCredentialByName(String credentialName) {
        if (credentialName == null || credentialName.isBlank()) return;
        int toolCredDeleted = jdbcTemplate.update(
                "DELETE FROM catalog.tool_credentials WHERE credential_name = ?", credentialName);
        int deleted = jdbcTemplate.update(
                "DELETE FROM catalog.credentials WHERE credential_name = ?", credentialName);
        if (deleted > 0 || toolCredDeleted > 0) {
            log.info("Deleted credential template '{}' ({} tool_credentials removed)", credentialName, toolCredDeleted);
        }
    }

    private String buildInjectionMetadata(CustomApiAuthConfig authConfig) {
        String authType = normalizeAuthType(authConfig.authType());
        return switch (authType) {
            case "basic_auth" -> """
                    {"field": "username", "injection": {"type": "basic_auth", "key": "Authorization"}}""";
            case "bearer", "oauth2", "apikey" -> {
                String field = credentialFieldForAuthType(authType);
                StringBuilder metadata = new StringBuilder()
                        .append("{\"field\": \"")
                        .append(field)
                        .append("\", \"injection\": {\"type\": \"")
                        .append(escapeJson(authConfig.injectionType()))
                        .append("\", \"key\": \"")
                        .append(escapeJson(authConfig.key()))
                        .append("\"");
                if (authConfig.prefix() != null) {
                    metadata.append(", \"prefix\": \"").append(escapeJson(authConfig.prefix())).append("\"");
                }
                metadata.append("}}");
                yield metadata.toString();
            }
            default -> """
                    {"field": "api_key", "injection": {"type": "header", "key": "X-API-Key"}}""";
        };
    }

    private String buildPropertiesJson(String authType) {
        return switch (normalizeAuthType(authType)) {
            case "apikey" -> """
                    {"api_key": {"type": "string", "displayName": "API Key", "required": true}}""";
            case "bearer" -> """
                    {"access_token": {"type": "string", "displayName": "******", "required": true}}""";
            case "oauth2" -> """
                    {"client_id": {"type": "string", "displayName": "Client ID", "required": true}, "client_secret": {"type": "string", "displayName": "Client Secret", "required": true}}""";
            case "basic_auth" -> """
                    {"username": {"type": "string", "displayName": "Username", "required": true}, "password": {"type": "password", "displayName": "Password", "required": true}}""";
            default -> """
                    {"api_key": {"type": "string", "displayName": "API Key", "required": true}}""";
        };
    }

    public static CustomApiAuthConfig defaultCustomApiAuthConfig(String authType) {
        return switch (normalizeAuthType(authType)) {
            case "bearer", "oauth2" -> new CustomApiAuthConfig(authType, "header", "Authorization", "Bearer ");
            case "basic_auth" -> new CustomApiAuthConfig("basic_auth", "basic_auth", "Authorization", null);
            default -> new CustomApiAuthConfig(authType, "header", "X-API-Key", null);
        };
    }

    private static String credentialFieldForAuthType(String authType) {
        return switch (normalizeAuthType(authType)) {
            case "bearer", "oauth2" -> "access_token";
            case "basic_auth" -> "username";
            default -> "api_key";
        };
    }

    private static String normalizeAuthType(String authType) {
        if (authType == null) return "apikey";
        return switch (authType.trim().toLowerCase(Locale.ROOT)) {
            case "basic" -> "basic_auth";
            case "api_key", "apikey" -> "apikey";
            case "bearer_token", "bearer" -> "bearer";
            default -> authType.trim().toLowerCase(Locale.ROOT);
        };
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String buildDisplayName(String credentialName) {
        return credentialName
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace("_", " ")
                .replace("-", " ");
    }
}
