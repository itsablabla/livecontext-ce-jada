package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.tool.ToolExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ask_user parks on the approval gate waiting for a person, so it needs the same extra
 * budget the approval cards get. The grant must be exactly as narrow as the wait it pays
 * for: the ask action, in a chat where somebody can answer, and nothing else.
 */
@DisplayName("AgentLoopExecutor - timeout budget for an ask_user call")
class AgentLoopExecutorAskUserBudgetTest {

    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    /** Kept in sync with AgentLoopExecutor.APPROVAL_GATE_BUDGET_MS. */
    private static final long GATE_BUDGET_MS = 300_000;

    private AgentLoopExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AgentLoopExecutor(
                mock(ToolExecutionService.class), mock(AgentLogger.class),
                Executors.newCachedThreadPool(), DEFAULT_TIMEOUT_MS, false);
    }

    private static ToolDefinition tool(String name, Long timeoutMs) {
        return ToolDefinition.builder().name(name).timeoutMs(timeoutMs).build();
    }

    private static ToolCall call(String toolName, String action) {
        return new ToolCall("call-1", toolName, Map.of("action", action), null);
    }

    private static Map<String, Object> chat() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("conversationId", "conv-1");
        credentials.put("__streamId__", "stream-1");
        return credentials;
    }

    @Test
    @DisplayName("ask_user(action='ask') in an interactive chat gets base + the gate budget")
    void askInChatGetsTheBudget() {
        assertThat(executor.effectiveTimeoutMs(call("ask_user", "ask"), tool("ask_user", null), chat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS + GATE_BUDGET_MS);
        // The tool's own declared timeout is the base the budget is added to.
        assertThat(executor.effectiveTimeoutMs(call("ask_user", "ask"), tool("ask_user", 30_000L), chat()))
                .isEqualTo(30_000L + GATE_BUDGET_MS);
    }

    @Test
    @DisplayName("An agent-backed chat gets the budget too: it is promptable even though it is exempt from authorization")
    void agentBackedChatGetsTheBudget() {
        Map<String, Object> credentials = chat();
        credentials.put("__agentId__", "agent-7");

        assertThat(executor.effectiveTimeoutMs(call("ask_user", "ask"), tool("ask_user", null), credentials))
                .isEqualTo(DEFAULT_TIMEOUT_MS + GATE_BUDGET_MS);
    }

    @Test
    @DisplayName("Where nobody can answer (workflow run, task, sub-agent) the call keeps its base timeout")
    void headlessKeepsBaseTimeout() {
        Map<String, Object> workflow = chat();
        workflow.put("__workflowRunId__", "run-1");
        Map<String, Object> task = chat();
        task.put("__taskId__", "task-1");
        Map<String, Object> subAgent = chat();
        subAgent.put("__agent_depth__", 1);

        for (Map<String, Object> credentials : java.util.List.of(workflow, task, subAgent)) {
            assertThat(executor.effectiveTimeoutMs(call("ask_user", "ask"), tool("ask_user", null), credentials))
                    .isEqualTo(DEFAULT_TIMEOUT_MS);
        }
    }

    @Test
    @DisplayName("ask_user(action='help') never parks, so it never gets the budget")
    void helpKeepsBaseTimeout() {
        assertThat(executor.effectiveTimeoutMs(call("ask_user", "help"), tool("ask_user", null), chat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS);
    }

    @Test
    @DisplayName("The branch does not leak to other tools: a read on a facade tool keeps its base timeout")
    void otherToolsUnaffected() {
        assertThat(executor.effectiveTimeoutMs(call("catalog", "search"), tool("catalog", null), chat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS);
        assertThat(executor.effectiveTimeoutMs(call("wait", "sleep"), tool("wait", 270_000L), chat()))
                .isEqualTo(270_000L);
    }
}
