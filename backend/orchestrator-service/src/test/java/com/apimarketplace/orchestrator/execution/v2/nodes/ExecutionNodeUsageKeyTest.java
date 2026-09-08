package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@code ExecutionNode.usageKey()} - the value that decides WHERE a node
 * launch is counted in the V461 ledger.
 *
 * <p>The interesting cases are all on {@link StepNode}: a wrong key there does not throw,
 * it silently files an endpoint's runs under the wrong owner (or under a "node type" that
 * no palette row corresponds to), which the ranking would then present as fact.
 */
@DisplayName("ExecutionNode.usageKey")
class ExecutionNodeUsageKeyTest {

    private static Step mcpStep(String toolId) {
        return new Step(toolId, "mcp", "Call it", null, Map.of(), null, null, null);
    }

    private static Step crudStep(String crudType) {
        return new Step("crud/whatever", crudType, "Row op", null, Map.of(), 7L, null, null);
    }

    @Test
    @DisplayName("an MCP step reports its tool identifier verbatim, prefixed for catalog")
    void mcpStepReportsItsToolIdentifier() {
        assertEquals("tool:elevenlabs-text-to-speech",
                new StepNode("n1", mcpStep("elevenlabs-text-to-speech")).usageKey());
    }

    @Test
    @DisplayName("does not split an apiSlug/toolSlug identifier - catalog owns that resolution")
    void qualifiedIdentifierIsPassedThroughWhole() {
        assertEquals("tool:slack/slack-post-message",
                new StepNode("n1", mcpStep("slack/slack-post-message")).usageKey());
    }

    @Test
    @DisplayName("passes a UUID tool reference through unchanged")
    void uuidIdentifierIsPassedThrough() {
        String uuid = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
        assertEquals("tool:" + uuid, new StepNode("n1", mcpStep(uuid)).usageKey());
    }

    @Test
    @DisplayName("a table operation counts as a node type, never as a catalog endpoint")
    void crudStepIsCountedAsANodeType() {
        // "crud/read-row" names no endpoint: sent to catalog it would resolve to nothing
        // and the whole table-node population would vanish from the ledger.
        assertEquals("node:table", new StepNode("n1", crudStep("crud-read-row")).usageKey());
        assertEquals("node:table", new StepNode("n1", crudStep("crud-create-row")).usageKey());
    }

    @Test
    @DisplayName("reports nothing when the step names no tool")
    void blankToolIdIsNotCounted() {
        assertNull(new StepNode("n1", mcpStep("")).usageKey());
        assertNull(new StepNode("n1", mcpStep(null)).usageKey());
    }

    @Test
    @DisplayName("every other node type falls back to its lowercased type")
    void defaultKeyIsTheNodeType() {
        ExecutionNode node = new StubNode("n2", NodeType.DECISION);
        assertEquals("node:decision", node.usageKey());
    }

    @Test
    @DisplayName("reports nothing for a node with no type rather than an unusable key")
    void untypedNodeIsNotCounted() {
        assertNull(new StubNode("n3", null).usageKey());
    }

    /** Minimal ExecutionNode: only what usageKey's default implementation reads. */
    private record StubNode(String id, NodeType type) implements ExecutionNode {
        @Override public String getNodeId() { return id; }
        @Override public NodeType getType() { return type; }
        @Override public boolean canExecute(com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext c) { return true; }
        @Override public NodeExecutionResult execute(com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext c) { return null; }
        @Override public void onComplete(com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext c, NodeExecutionResult r) { }
        @Override public java.util.List<ExecutionNode> getNextNodes(NodeExecutionResult r) { return java.util.List.of(); }
    }
}
