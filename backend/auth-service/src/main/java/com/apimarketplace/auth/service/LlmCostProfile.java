package com.apimarketplace.auth.service;

/**
 * Typical token workloads, one per shape of LLM work the product actually runs.
 * They exist so a model picker can answer "what will this model cost me?" BEFORE
 * the call, in the same credits the ledger will later debit.
 *
 * <h2>Why several profiles and not one</h2>
 * The four differ by two orders of magnitude, and the gap is structural rather
 * than statistical: an agent re-sends the whole transcript on every tool
 * round-trip, so its input grows with the number of tool calls, while a classify
 * step sends one prompt and returns a label. A single "average conversation"
 * would be true of almost no one and would read as either alarming or dishonest
 * depending on which surface showed it. The picker therefore says which shape of
 * work it is pricing.
 *
 * <h2>Where the numbers come from</h2>
 * Measured 2026-09-03 over the platform's real executions
 * ({@code agent.agent_executions}, chat turns summed per conversation), priced
 * on Claude Sonnet 5 ($2 / $10 per 1M tokens) with the platform's cache
 * weighting. The MEDIAN billed cost of each shape was, in credits:
 * agent conversation 285 (n=310, 4.8 turns and 44 tool calls on average),
 * conversation with no tool call 80 (n=19), guardrail check 37 (n=7),
 * classify step 2.9 (n=2,649).
 *
 * <p>Each profile below is a CACHE-FREE workload chosen to reproduce that median
 * on the reference model. Cache-free is deliberate: the cache weights differ per
 * provider family (Anthropic reads at 0.1x, OpenAI cached at 0.5x, Gemini at
 * 0.25x), so a profile carrying cache tokens would make the estimate depend on
 * the family's reporting quirks rather than on the model's price, and two models
 * side by side in a picker would no longer be comparable. With no cache tokens
 * every family prices the same workload identically, which is what makes the
 * column mean something. {@code LlmCostProfileFormulaTest} pins that property.
 *
 * <p>Re-measure whenever the billing multiplier moves or the platform's typical
 * workload changes; the pricing page's public figures are derived from the same
 * measurement (see {@code frontend/lib/billing/pricing-constants.ts}).
 */
public enum LlmCostProfile {

    /** A conversation where an agent works: calls tools, reads files, chains steps. */
    AGENT_CONVERSATION("agentConversation", 103_000, 5_000),

    /** A plain exchange: question in, answer out, no tool call. */
    CHAT_CONVERSATION("chatConversation", 35_000, 100),

    /** One guardrail check over a message. */
    GUARDRAIL_CHECK("guardrailCheck", 16_000, 100),

    /** One classification: a prompt in, a label out. */
    CLASSIFY_STEP("classifyStep", 1_200, 60);

    private final String key;
    private final int inputTokens;
    private final int outputTokens;

    LlmCostProfile(String key, int inputTokens, int outputTokens) {
        this.key = key;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    /** Stable identifier shared with the API response and the UI. */
    public String key() {
        return key;
    }

    public int inputTokens() {
        return inputTokens;
    }

    public int outputTokens() {
        return outputTokens;
    }

    /** The workload as the billing formula consumes it: no cache, no reasoning. */
    public LlmTokenBreakdown breakdown() {
        return LlmTokenBreakdown.of(inputTokens, outputTokens);
    }
}
