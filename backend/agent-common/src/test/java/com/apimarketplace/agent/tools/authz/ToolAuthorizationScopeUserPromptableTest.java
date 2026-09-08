package com.apimarketplace.agent.tools.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Is a person watching" is not "must this be authorized". The ask_user tool was built for
 * agent-backed chats as much as for the general chat, and those are exempt from the
 * authorization card, so the two predicates must be allowed to disagree exactly there.
 */
@DisplayName("ToolAuthorizationScope.isUserPromptable - who can be asked a question")
class ToolAuthorizationScopeUserPromptableTest {

    private static Map<String, Object> chat() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__streamId__", "stream-1");
        return creds;
    }

    @Test
    @DisplayName("The general chat is promptable, and also gets the authorization card")
    void generalChat() {
        assertThat(ToolAuthorizationScope.isUserPromptable(chat())).isTrue();
        assertThat(ToolAuthorizationScope.isCardRaised(chat())).isTrue();
    }

    @Test
    @DisplayName("An agent-backed chat is promptable even though it is exempt from authorization (the bug this predicate avoids)")
    void agentBackedChatIsPromptableButNotAuthorized() {
        Map<String, Object> creds = chat();
        creds.put("__agentId__", "agent-7");

        assertThat(ToolAuthorizationScope.isUserPromptable(creds)).isTrue();
        assertThat(ToolAuthorizationScope.isCardRaised(creds)).isFalse();
    }

    @Test
    @DisplayName("The plain streamId key counts as a live stream too")
    void plainStreamIdKey() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("streamId", "stream-1");

        assertThat(ToolAuthorizationScope.isUserPromptable(creds)).isTrue();
    }

    @Test
    @DisplayName("A sub-agent is not promptable: its parent owns the conversation")
    void subAgent() {
        Map<String, Object> creds = chat();
        creds.put("__agent_depth__", 1);

        assertThat(ToolAuthorizationScope.isUserPromptable(creds)).isFalse();
    }

    @Test
    @DisplayName("A workflow or task run is not promptable, whatever else it carries")
    void workflowAndTask() {
        Map<String, Object> workflow = chat();
        workflow.put("__workflowRunId__", "run-1");
        assertThat(ToolAuthorizationScope.isUserPromptable(workflow)).isFalse();

        Map<String, Object> plainWorkflow = chat();
        plainWorkflow.put("workflowRunId", "run-1");
        assertThat(ToolAuthorizationScope.isUserPromptable(plainWorkflow)).isFalse();

        Map<String, Object> task = chat();
        task.put("__taskId__", "task-1");
        assertThat(ToolAuthorizationScope.isUserPromptable(task)).isFalse();
    }

    @Test
    @DisplayName("No conversation, no stream, or no credentials at all: nobody to ask")
    void headless() {
        assertThat(ToolAuthorizationScope.isUserPromptable(null)).isFalse();
        assertThat(ToolAuthorizationScope.isUserPromptable(Map.of())).isFalse();
        assertThat(ToolAuthorizationScope.isUserPromptable(Map.of("conversationId", "conv-1"))).isFalse();
        assertThat(ToolAuthorizationScope.isUserPromptable(Map.of("__streamId__", "stream-1"))).isFalse();
    }

    @Test
    @DisplayName("The per-agent authorization override does not make a headless run promptable")
    void overrideDoesNotApply() {
        Map<String, Object> creds = chat();
        creds.put("__taskId__", "task-1");
        creds.put("__requireToolAuthorization__", true);

        assertThat(ToolAuthorizationScope.isUserPromptable(creds)).isFalse();
    }
}
