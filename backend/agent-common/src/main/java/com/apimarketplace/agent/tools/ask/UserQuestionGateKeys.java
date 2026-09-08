package com.apimarketplace.agent.tools.ask;

/**
 * The gate key a question park uses, shared by the side that parks (agent-service) and the
 * side that answers (conversation-service).
 *
 * <p>The suffix is a security check, not a naming convention: the approval gate parses any
 * JSON envelope as a verdict, so an answer written against an AUTHORIZATION park's key
 * (the bare tool call id) would approve a sensitive action with no permission card ever
 * clicked. The answer endpoint refuses any key that is not {@link #forToolCall} of its own
 * call, and both sides must agree on the shape for that refusal to mean anything.
 */
public final class UserQuestionGateKeys {

    /** Keeps a question park's key apart from an authorization park of the same call. */
    public static final String SUFFIX = ":ask";

    private UserQuestionGateKeys() {
    }

    /** The gate key for the question raised by {@code toolCallId}. */
    public static String forToolCall(String toolCallId) {
        return toolCallId + SUFFIX;
    }

    /** True when {@code gateKey} is exactly this call's question key. */
    public static boolean belongsTo(String toolCallId, String gateKey) {
        return gateKey != null && gateKey.equals(forToolCall(toolCallId));
    }
}
