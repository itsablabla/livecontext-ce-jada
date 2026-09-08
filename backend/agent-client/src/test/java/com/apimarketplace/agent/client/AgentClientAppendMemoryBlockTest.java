package com.apimarketplace.agent.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The hop that carries long-term memory to the one chat path that never reaches
 * agent-service.
 *
 * <p>When the chat model is a CLI provider, conversation-service posts to the
 * bridge itself, so nothing in agent-service can append the memory block. This
 * call is how it gets there. Two properties matter and neither is obvious from
 * reading the method:
 *
 * <ol>
 *   <li>the workspace has to travel, or the far side resolves no memories at all
 *       and the whole hop is a no-op that looks like an empty workspace;</li>
 *   <li>it must NEVER throw. Memory is an enrichment: a chat that runs without it
 *       is degraded, a chat that dies because a memory read timed out is broken,
 *       and this call sits directly in front of the user's message.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentClient.appendMemoryBlock")
class AgentClientAppendMemoryBlockTest {

    private static final String PROMPT = "You are a helpful assistant.";

    @Mock
    private RestTemplate restTemplate;

    private AgentClient agentClient;

    @BeforeEach
    void setUp() throws Exception {
        agentClient = new AgentClient("http://localhost:8090");
        Field f = AgentClient.class.getDeclaredField("memoryRestTemplate");
        f.setAccessible(true);
        f.set(agentClient, restTemplate);
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(ResponseEntity<Map<String, String>> response) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
            any(ParameterizedTypeReference.class))).thenReturn((ResponseEntity) response);
    }

    @Test
    @DisplayName("returns the enriched prompt the far side produced")
    void returnsTheEnrichedPrompt() {
        stubResponse(ResponseEntity.ok(Map.of("systemPrompt", PROMPT + "\n\n<recalled-memory>...")));

        assertThat(agentClient.appendMemoryBlock(PROMPT, "user-1", "org-alpha", null))
            .isEqualTo(PROMPT + "\n\n<recalled-memory>...");
    }

    @Test
    @DisplayName("sends the caller, the workspace and the agent, since all three decide what is recalled")
    @SuppressWarnings("unchecked")
    void sendsTheScope() {
        String agentId = UUID.randomUUID().toString();
        ArgumentCaptor<HttpEntity<Map<String, Object>>> sent = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), sent.capture(),
            any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(Map.of("systemPrompt", PROMPT)));

        agentClient.appendMemoryBlock(PROMPT, "user-1", "org-alpha", agentId);

        // Without the workspace header the far side resolves nothing and the hop is a
        // silent no-op; without the agent id a sub-agent would be handed the whole
        // workspace's memory and none of its own.
        assertThat(sent.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo("user-1");
        assertThat(sent.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-alpha");
        assertThat(sent.getValue().getBody()).containsEntry("agentId", agentId);
        assertThat(sent.getValue().getBody()).containsEntry("systemPrompt", PROMPT);
    }

    @Test
    @DisplayName("hands back the ORIGINAL prompt when the far side is unreachable, rather than throwing")
    void anUnreachableServiceIsNotFatal() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
            any(ParameterizedTypeReference.class)))
            .thenThrow(new ResourceAccessException("connection refused"));

        // Throwing here would take down the user's chat because a memory lookup was
        // slow. The run without memory is the correct degradation.
        assertThat(agentClient.appendMemoryBlock(PROMPT, "user-1", "org-alpha", null)).isEqualTo(PROMPT);
    }

    @Test
    @DisplayName("hands back the ORIGINAL prompt on an empty or malformed body, never null")
    void aMalformedBodyIsNotFatal() {
        stubResponse(ResponseEntity.ok(Map.of()));
        assertThat(agentClient.appendMemoryBlock(PROMPT, "user-1", "org-alpha", null)).isEqualTo(PROMPT);

        stubResponse(ResponseEntity.ok(null));
        // A null return would become the system prompt of the run that follows: the
        // agent would lose its entire prompt because memory was unavailable.
        assertThat(agentClient.appendMemoryBlock(PROMPT, "user-1", "org-alpha", null)).isEqualTo(PROMPT);
    }

    @Test
    @DisplayName("sends an empty string rather than null when there is no prompt yet")
    @SuppressWarnings("unchecked")
    void aNullPromptIsSentAsEmpty() {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> sent = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), sent.capture(),
            any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(Map.of("systemPrompt", "block")));

        agentClient.appendMemoryBlock(null, "user-1", "org-alpha", null);

        // Map.of refuses a null value, so a null prompt would throw inside the client
        // itself - the one place that must not throw.
        assertThat(sent.getValue().getBody()).containsEntry("systemPrompt", "");
    }
}
