package com.apimarketplace.catalog.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The caller's explicit credential choice.
 *
 * <p>{@code GenerationModule} has always forwarded a caller's
 * {@code credential_source} into this module, and the {@code agent:generate} node
 * surfaces it as a toggle, but the value used to be dropped on the floor: it was
 * not a reserved control key, so it fell through to the tool inputs and was
 * filtered out downstream. The visible consequence was a workflow that asked to
 * run on its OWN provider key being billed the platform price instead.
 */
@DisplayName("CatalogExecuteModule - explicit credential source")
class CatalogExecuteModuleCredentialSourceTest {

    @Test
    @DisplayName("'user' and 'platform' are honoured, in any casing or padding")
    void honoursBothPools() {
        assertThat(CatalogExecuteModule.normalizedCredentialSource(Map.of("credential_source", "user")))
                .isEqualTo("user");
        assertThat(CatalogExecuteModule.normalizedCredentialSource(Map.of("credential_source", "PLATFORM")))
                .isEqualTo("platform");
        assertThat(CatalogExecuteModule.normalizedCredentialSource(Map.of("credential_source", "  User  ")))
                .isEqualTo("user");
    }

    @Test
    @DisplayName("no choice at all leaves the agentic default in place")
    void noChoiceIsNull() {
        assertThat(CatalogExecuteModule.normalizedCredentialSource(Map.of())).isNull();
        assertThat(CatalogExecuteModule.normalizedCredentialSource(null)).isNull();

        Map<String, Object> withNull = new HashMap<>();
        withNull.put("credential_source", null);
        assertThat(CatalogExecuteModule.normalizedCredentialSource(withNull)).isNull();
    }

    @Test
    @DisplayName("an unrecognised value falls back to the agentic default rather than pinning an unknown pool")
    void unknownValueFallsBack() {
        // Pinning on a typo would strand the call on a pool that resolves nothing,
        // with no fallback, for a node the author believed was configured.
        assertThat(CatalogExecuteModule.normalizedCredentialSource(Map.of("credential_source", "borrowed")))
                .isNull();
        assertThat(CatalogExecuteModule.normalizedCredentialSource(Map.of("credential_source", "")))
                .isNull();
    }

    @Test
    @DisplayName("credential_source is a control key, never forwarded upstream as a tool input")
    void credentialSourceIsNotAToolInput() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tool_id", "seedance/create-video");
        parameters.put("credential_source", "user");
        parameters.put("prompt", "a boat");

        CatalogExecuteModule.ShapingParams shaping =
                CatalogExecuteModule.extractShapingParams(parameters, Map.of("prompt", "a boat"));

        // The provider never declared a `credential_source` parameter; sending it
        // would be an unknown field on the upstream request.
        assertThat(shaping.remainingParams()).doesNotContainKey("credential_source");
        assertThat(shaping.remainingParams()).containsEntry("prompt", "a boat");
    }
}
