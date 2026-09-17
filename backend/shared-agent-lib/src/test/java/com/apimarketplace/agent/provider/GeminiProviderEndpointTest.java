package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.resolver.LlmCredentialResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeminiProviderEndpointTest {
    private GeminiProvider provider(String endpoint) {
        GeminiProvider provider = new GeminiProvider();
        ReflectionTestUtils.setField(provider, "apiBaseUrl", "https://93.184.216.34/default");
        LlmCredentialResolver resolver = mock(LlmCredentialResolver.class);
        when(resolver.resolveApiUrl("google")).thenReturn(Optional.of(endpoint));
        when(resolver.resolveApiKey("google")).thenReturn(Optional.of("test-key"));
        provider.setCredentialResolver(resolver);
        return provider;
    }

    @Test
    void completionUsesSavedEndpoint() {
        String url = ReflectionTestUtils.invokeMethod(provider("https://93.184.216.34/custom/models/"),
                "getApiUrlForModel", "gemini-test");
        assertThat(url).isEqualTo("https://93.184.216.34/custom/models/gemini-test:generateContent?key=test-key");
    }

    @Test
    void streamingUsesSavedEndpoint() {
        String url = ReflectionTestUtils.invokeMethod(provider("https://93.184.216.34/custom/models"),
                "getStreamingUrlForModel", "gemini-test");
        assertThat(url).isEqualTo("https://93.184.216.34/custom/models/gemini-test:streamGenerateContent?alt=sse&key=test-key");
    }

    @Test
    void bothPathsRejectPrivateEndpointsBeforeSendingTheKey() {
        GeminiProvider provider = provider("http://127.0.0.1:8080/models");
        for (String method : new String[]{"getApiUrlForModel", "getStreamingUrlForModel"}) {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(provider, method, "gemini-test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("private/internal network");
        }
    }
}
