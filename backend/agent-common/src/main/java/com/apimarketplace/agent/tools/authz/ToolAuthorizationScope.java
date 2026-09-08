package com.apimarketplace.agent.tools.authz;

import java.util.Map;

/**
 * Whether the caller's context is one where a tool-authorization card is actually raised.
 *
 * <p>This is THE predicate, shared. It used to live only in agent-service, next to the code that
 * raises the card, and any other surface wanting to know "will the user be asked?" had to guess.
 * Guessing is not close enough: a plausible-looking test on
 * {@code conversationId != null || streamId != null} says yes for a sub-agent, for an
 * agent-backed chat, and for an agent node inside an unattended scheduled run, all of which are
 * EXEMPT from the card. An action gated on that guess would run unattended with no one to ask.
 *
 * <p>The rules, in the order they are decided:
 * <ol>
 *   <li>a sub-agent (depth >= 1) inherits its parent's authorization, so no card;</li>
 *   <li>a workflow- or task-driven execution was authorized when the workflow or task was, so
 *       no card;</li>
 *   <li>an interactive conversation (a conversation id AND a live stream) DOES get a card,
 *       unless a specific agent is bound to it, which makes it "an agent" rather than the
 *       general chat;</li>
 *   <li>anything else is headless, so no card.</li>
 * </ol>
 * At every exit an explicit per-agent flag can force the card back on.
 */
public final class ToolAuthorizationScope {

    public static final String KEY_AGENT_DEPTH = "__agent_depth__";
    public static final String KEY_CONVERSATION_ID = "conversationId";
    public static final String KEY_STREAM_ID = "__streamId__";
    public static final String KEY_STREAM_ID_PLAIN = "streamId";
    public static final String KEY_WORKFLOW_RUN_ID = "__workflowRunId__";
    public static final String KEY_WORKFLOW_RUN_ID_PLAIN = "workflowRunId";
    public static final String KEY_TASK_ID = "__taskId__";
    public static final String KEY_AGENT_ID = "__agentId__";
    public static final String KEY_REQUIRE_AUTHORIZATION = "__requireToolAuthorization__";

    /**
     * Set when the call arrives through the CLI bridge, so a CLI is sitting on the MCP
     * request while we hold it. It does not change WHETHER a card is raised, only how long
     * the call may be parked waiting for the answer.
     */
    public static final String KEY_CLI_BRIDGE_SESSION = "__cliBridgeSession__";

    private ToolAuthorizationScope() {
    }

    /** True when a sensitive action in this context would raise a card the user can answer. */
    public static boolean isCardRaised(Map<String, Object> credentials) {
        if (credentials == null) {
            return false;
        }
        boolean agentOverride = isTruthy(credentials.get(KEY_REQUIRE_AUTHORIZATION));

        if (agentDepth(credentials) >= 1) {
            return agentOverride;
        }
        if (hasText(credentials.get(KEY_WORKFLOW_RUN_ID))
                || hasText(credentials.get(KEY_WORKFLOW_RUN_ID_PLAIN))
                || hasText(credentials.get(KEY_TASK_ID))) {
            return agentOverride;
        }
        boolean interactiveChat = hasText(credentials.get(KEY_CONVERSATION_ID))
                && (hasText(credentials.get(KEY_STREAM_ID)) || hasText(credentials.get(KEY_STREAM_ID_PLAIN)));
        if (interactiveChat) {
            if (hasText(credentials.get(KEY_AGENT_ID))) {
                return agentOverride;
            }
            return true;
        }
        return agentOverride;
    }

    /**
     * True when a person is watching this execution and can answer a question put to them.
     *
     * <p>A different question from {@link #isCardRaised}, and deliberately a different
     * predicate. That one asks "must this action be AUTHORIZED", and an agent-backed chat is
     * exempt from authorization by product rule. This one asks "is there a human on the
     * other end", and an agent-backed chat has one just as much as the general chat does.
     * Reusing the authorization predicate here would make {@code ask_user} silently
     * unavailable in exactly the chats it was built for.
     *
     * <p>Interactive means: not a sub-agent, not a workflow or task run, and a conversation
     * with a live stream. Anything headless gets {@code false}, so a question raised there
     * is answered with "nobody is watching" instead of a card nobody will see.
     */
    public static boolean isUserPromptable(Map<String, Object> credentials) {
        if (credentials == null) {
            return false;
        }
        if (agentDepth(credentials) >= 1) {
            return false;
        }
        if (hasText(credentials.get(KEY_WORKFLOW_RUN_ID))
                || hasText(credentials.get(KEY_WORKFLOW_RUN_ID_PLAIN))
                || hasText(credentials.get(KEY_TASK_ID))) {
            return false;
        }
        return hasText(credentials.get(KEY_CONVERSATION_ID))
                && (hasText(credentials.get(KEY_STREAM_ID)) || hasText(credentials.get(KEY_STREAM_ID_PLAIN)));
    }

    private static int agentDepth(Map<String, Object> credentials) {
        Object depth = credentials.get(KEY_AGENT_DEPTH);
        if (depth instanceof Number n) {
            return n.intValue();
        }
        if (depth instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * True when a CLI is holding this call open at the other end of an MCP request.
     *
     * <p>Lives here, next to the key, because the alternative is two truthiness rules for
     * one credential drifting apart in two modules. It answers HOW LONG a parked call may
     * be held, not WHETHER a card is raised, so it is deliberately not part of
     * {@link #isCardRaised}.
     */
    public static boolean isCliBridgeSession(Map<String, Object> credentials) {
        return credentials != null && isTruthy(credentials.get(KEY_CLI_BRIDGE_SESSION));
    }

    private static boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static boolean isTruthy(Object value) {
        if (value instanceof Boolean b) return b;
        return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }
}
