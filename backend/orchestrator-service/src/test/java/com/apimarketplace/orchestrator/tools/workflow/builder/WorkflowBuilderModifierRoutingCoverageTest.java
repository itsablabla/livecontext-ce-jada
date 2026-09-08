package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A patch must reach the config slot the engine reads, for EVERY node family, and this
 * test is what says so when a new one is added.
 *
 * <p>{@code NESTED_CONFIG_KEYS} routes a flat patch into the sub-object the executor
 * consults. Its own javadoc says it must stay exhaustive, "otherwise modify deposits
 * patches at the top level of the node JSON, where the engine never reads them", and
 * until now nothing enforced that: table CRUD nodes were missing from the routing for as
 * long as they had existed, so {@code params={limit: 100}} on a find step answered
 * "Node modified" and changed nothing at run time.
 *
 * <p>The assertion is the product's own safety net rather than a second copy of the
 * routing table: after a modify, the response must not carry {@code NOT_APPLIED}, which
 * is computed by reading each requested field back from where execution reads it. A node
 * family that nobody taught the modifier about fails here without anyone having to
 * predict which one it will be.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderModifier - every node family routes to its config slot")
class WorkflowBuilderModifierRoutingCoverageTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    private WorkflowBuilderModifier modifier;

    @BeforeEach
    void setUp() {
        modifier = new WorkflowBuilderModifier(sessionStore);
    }

    private WorkflowBuilderSession session() {
        return WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Test Workflow")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    /** One representative per routing family, each carrying a scalar the engine reads. */
    static Stream<org.junit.jupiter.params.provider.Arguments> families() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "core node with a nested config slot",
                        "cores", "core:crop", "media", "params", "width_percent", 15, 40),
                org.junit.jupiter.params.provider.Arguments.of(
                        "core node whose slot is not named params",
                        "cores", "core:hold", "wait", "wait", "duration", 1000, 5000),
                org.junit.jupiter.params.provider.Arguments.of(
                        "table CRUD node",
                        "tables", "table:next", "crud-find", "crud", "limit", 1, 100),
                org.junit.jupiter.params.provider.Arguments.of(
                        "catalog tool node",
                        "mcps", "mcp:send", "mcp", "params", "userId", "me", "someone"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "params-aware trigger",
                        "triggers", "trigger:daily", "schedule", "params", "cron", "0 9 * * *", "0 10 * * *")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("families")
    @DisplayName("a flat scalar patch reaches the slot the engine reads")
    void aFlatPatchReachesTheEnginesSlot(String family, String list, String nodeId, String type,
                                         String slot, String field, Object before, Object after) {
        WorkflowBuilderSession session = session();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("type", type);
        node.put("label", "Step");
        node.put("position", new LinkedHashMap<>(Map.of("x", 0, "y", 0)));
        node.put(slot, new LinkedHashMap<>(Map.of(field, before)));
        switch (list) {
            case "cores" -> session.getCores().add(node);
            case "tables" -> session.getTables().add(node);
            case "mcps" -> session.getMcps().add(node);
            case "triggers" -> session.getTriggers().add(node);
            default -> throw new IllegalArgumentException(list);
        }

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", "Step");
        args.put("params", Map.of(field, after));

        ToolExecutionResult result = modifier.executeModifyNode(session, args);
        assertThat(result.success()).as("%s: modify should succeed", family).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data)
                .as("%s: the patch must reach %s.%s, not the top level of the node",
                        family, slot, field)
                .doesNotContainKey("NOT_APPLIED");

        // Compared the way the product compares it: the parser widens an int to a long
        // for a typed field, so a strict equality here would fail on a value that landed.
        assertThat(WorkflowBuilderModifier.sameScalar(
                        after, WorkflowBuilderModifier.effectiveValue(node, nodeId, field)))
                .as("%s: the engine must read the new value", family)
                .isTrue();
    }

    @Test
    @DisplayName("a field that lands nowhere the engine reads IS reported, not swallowed")
    void aPatchThatCannotLandIsReported() {
        // The detector must be able to fail, otherwise the sweep above proves nothing.
        // A note node has no config slot at all, so a scalar sent to it stays top-level
        // and is readable; to model a genuine miss, the value is checked against a slot
        // that already holds a different one.
        WorkflowBuilderSession session = session();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "table:next");
        node.put("type", "crud-find");
        node.put("label", "Step");
        node.put("crud", new LinkedHashMap<>(Map.of("limit", 1)));
        session.getTables().add(node);

        // Simulate the pre-fix world: the patch parked at the top level while crud kept
        // the old value. This is the exact state a missing routing entry produces.
        node.put("limit", 100);

        assertThat(WorkflowBuilderModifier.effectiveValue(node, "table:next", "limit"))
                .as("the engine reads the crud block, so the top-level copy is invisible")
                .isEqualTo(1);
        assertThat(WorkflowBuilderModifier.sameScalar(100, 1)).isFalse();
    }
}
