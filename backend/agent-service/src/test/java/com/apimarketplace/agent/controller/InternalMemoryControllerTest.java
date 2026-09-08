package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.memory.MemoryPromptSection;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The far side of the hop that carries memory to the CLI chat path.
 *
 * <p>This exists because conversation-service posts to the bridge itself for a CLI
 * model and never reaches the injection point in this service. It takes the prompt
 * and returns it enriched, rather than returning a fragment for the caller to join
 * on, so that exactly one place decides the separator and the empty-block case.
 *
 * <p>The property that matters most is negative: it must never fail the caller.
 * The call sits directly in front of a user's chat message, and a chat that dies
 * because a memory read timed out is a far worse outcome than one that runs
 * without memory.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("InternalMemoryController")
class InternalMemoryControllerTest {

    private static final String PROMPT = "You are a helpful assistant.";
    private static final String ORG = "org-alpha";

    @Mock private MemoryPromptSection memoryPromptSection;
    @Mock private TenantResolver tenantResolver;
    @Mock private HttpServletRequest request;

    private InternalMemoryController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalMemoryController(memoryPromptSection, tenantResolver);
        lenient().when(tenantResolver.resolveOrgId(request)).thenReturn(ORG);
    }

    private Map<String, Object> body(String prompt, String agentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("systemPrompt", prompt);
        body.put("agentId", agentId);
        return body;
    }

    @Test
    @DisplayName("returns the prompt with the block appended, resolved for the calling workspace")
    void appendsForTheCallersWorkspace() {
        UUID agentId = UUID.randomUUID();
        when(memoryPromptSection.appendTo(PROMPT, ORG, agentId)).thenReturn(PROMPT + "\n\nBLOCK");

        var response = controller.appendBlock(request, body(PROMPT, agentId.toString()));

        assertThat(response.getBody()).containsEntry("systemPrompt", PROMPT + "\n\nBLOCK");
        // The workspace comes from the header the calling service set, not from the
        // payload: a workspace id in a body is a workspace id a caller could claim.
        verify(memoryPromptSection).appendTo(PROMPT, ORG, agentId);
    }

    @Test
    @DisplayName("treats a missing or unparseable agent id as a chat with no agent bound")
    void aBadAgentIdMeansWorkspaceScopeOnly() {
        when(memoryPromptSection.appendTo(anyString(), anyString(), any())).thenReturn(PROMPT);

        controller.appendBlock(request, body(PROMPT, "not-a-uuid"));
        controller.appendBlock(request, body(PROMPT, null));

        // Null scopes the lookup to workspace rows only, which is the safe reading:
        // no agent's private memory leaks into a conversation that has no agent.
        ArgumentCaptor<UUID> scope = ArgumentCaptor.forClass(UUID.class);
        verify(memoryPromptSection, org.mockito.Mockito.times(2))
            .appendTo(anyString(), anyString(), scope.capture());
        assertThat(scope.getAllValues()).containsExactly(null, null);
    }

    @Test
    @DisplayName("hands the prompt back unchanged when the lookup throws, rather than failing the chat")
    void aFailedLookupReturnsTheInput() {
        when(memoryPromptSection.appendTo(anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("database unavailable"));

        var response = controller.appendBlock(request, body(PROMPT, null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("systemPrompt", PROMPT);
    }

    @Test
    @DisplayName("survives a request with no workspace resolvable at all")
    void aMissingWorkspaceIsNotFatal() {
        when(tenantResolver.resolveOrgId(request)).thenThrow(new IllegalStateException("no workspace"));

        var response = controller.appendBlock(request, body(PROMPT, null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("systemPrompt", PROMPT);
    }

    @Test
    @DisplayName("answers with an empty prompt rather than null when the body carries none")
    void anAbsentPromptIsAnEmptyString() {
        when(memoryPromptSection.appendTo(anyString(), anyString(), any()))
            .thenAnswer(inv -> inv.getArgument(0));

        var response = controller.appendBlock(request, new HashMap<>());

        // A null here would travel back and become the system prompt of the run that
        // follows, so the agent would lose its whole prompt because a field was absent.
        assertThat(response.getBody()).containsEntry("systemPrompt", "");
    }
}
