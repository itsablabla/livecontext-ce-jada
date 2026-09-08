package com.apimarketplace.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Budgets for long-term memory, all expressed in CHARACTERS rather than tokens.
 *
 * <p>A character count is model-independent. A token budget is not: the same
 * text is a different number of tokens per tokenizer, so a token-denominated
 * limit silently changes meaning the day an agent is repointed at another
 * model, and enforcing one would mean tokenizing every write. Hermes made the
 * same call for the same reason (the project docs and §10
 * point 10).
 *
 * <p>The two that cost real money are {@link #getIndexBlockChars()} and
 * {@link #getPinnedBlockChars()}: they are paid on <em>every</em> execution of
 * <em>every</em> agent in the workspace, so they are the ceiling on how much a
 * workspace's accumulated memory can slow down and inflate an unrelated run.
 * The per-entry and per-scope counts exist to keep the index from reaching that
 * ceiling in the first place, where truncation would start dropping facts
 * without telling anyone.
 *
 * <p>Config path: {@code ai.agent.memory}
 */
@Configuration
@ConfigurationProperties(prefix = "ai.agent.memory")
public class MemoryLimitsConfig {

    /** Max entries in one workspace's shared scope. */
    private int maxWorkspaceEntries = 200;

    /** Max entries private to a single agent. */
    private int maxAgentEntries = 50;

    /** Max chars of the one-line summary. Mirrors the column width. */
    private int maxSummaryChars = 240;

    /** Max chars of the body fetched on demand. */
    private int maxContentChars = 8000;

    /** Max chars of the rendered index block injected into the system prompt. */
    private int indexBlockChars = 3000;

    /** Max chars of the rendered pinned-bodies block injected into the system prompt. */
    private int pinnedBlockChars = 4000;

    /** Max index lines rendered, before the char budget is applied. */
    private int maxIndexEntries = 40;

    /** Max pinned entries whose full body is injected. */
    private int maxPinnedEntries = 3;

    /**
     * Master switch, for an operator who wants neither the injection nor new
     * entries.
     *
     * <p>Off stops the injection and refuses every WRITE. It does NOT unregister
     * the tool: the agent still sees it and gets an explicit refusal saying why,
     * which is more useful than a tool that vanished. Reads keep working, and so
     * does delete, so whoever just switched it off can still clear out what is
     * stored.
     */
    private boolean enabled = true;

    public int getMaxWorkspaceEntries() { return maxWorkspaceEntries; }
    public void setMaxWorkspaceEntries(int maxWorkspaceEntries) { this.maxWorkspaceEntries = maxWorkspaceEntries; }

    public int getMaxAgentEntries() { return maxAgentEntries; }
    public void setMaxAgentEntries(int maxAgentEntries) { this.maxAgentEntries = maxAgentEntries; }

    public int getMaxSummaryChars() { return maxSummaryChars; }
    public void setMaxSummaryChars(int maxSummaryChars) { this.maxSummaryChars = maxSummaryChars; }

    public int getMaxContentChars() { return maxContentChars; }
    public void setMaxContentChars(int maxContentChars) { this.maxContentChars = maxContentChars; }

    public int getIndexBlockChars() { return indexBlockChars; }
    public void setIndexBlockChars(int indexBlockChars) { this.indexBlockChars = indexBlockChars; }

    public int getPinnedBlockChars() { return pinnedBlockChars; }
    public void setPinnedBlockChars(int pinnedBlockChars) { this.pinnedBlockChars = pinnedBlockChars; }

    public int getMaxIndexEntries() { return maxIndexEntries; }
    public void setMaxIndexEntries(int maxIndexEntries) { this.maxIndexEntries = maxIndexEntries; }

    public int getMaxPinnedEntries() { return maxPinnedEntries; }
    public void setMaxPinnedEntries(int maxPinnedEntries) { this.maxPinnedEntries = maxPinnedEntries; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
