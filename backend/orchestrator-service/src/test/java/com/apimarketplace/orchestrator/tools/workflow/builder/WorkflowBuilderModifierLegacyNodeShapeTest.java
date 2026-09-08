package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nodes that do NOT start in the canonical shape.
 *
 * <p>Every other fixture in this suite builds a node the way a creator builds it: one
 * source of truth per field, the config block materialised. A third audit round pointed
 * out that this is exactly the shape both new guards handle well, and that the shapes they
 * slip past are the realistic ones: a plan imported through {@code set_plan}, which the
 * parser tolerates with the block absent and with only a {@code table_id}, or a node last
 * touched by a build from before this change.
 *
 * <p>Two consequences were real. A stale top-level {@code table_id} left beside a freshly
 * canonicalised {@code dataSourceId} made the read-back report a SUCCESSFUL write as
 * NOT_APPLIED, which is the crying-wolf failure an earlier round was written to eliminate.
 * And a node with no {@code crud} block yet could still have one replaced by a scalar,
 * reproducing the corruption that leaves a workflow unopenable.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderModifier - nodes carrying an older shape")
class WorkflowBuilderModifierLegacyNodeShapeTest {

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

    /** A table node as an imported plan can leave it: no crud block, id under table_id. */
    private WorkflowBuilderSession sessionWithImportedNode() {
        WorkflowBuilderSession session = session();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "table:next");
        node.put("type", "crud-find");
        node.put("label", "Next Video");
        node.put("table_id", 999);
        session.getTables().add(node);
        return session;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ToolExecutionResult result) {
        return (Map<String, Object>) result.data();
    }

    @Nested
    @DisplayName("a data source that was written under an older spelling")
    class StaleDataSourceId {

        @Test
        @DisplayName("changing it does not report the successful write as NOT_APPLIED")
        void aStaleAliasDoesNotTriggerAFalseAlarm() {
            WorkflowBuilderSession session = sessionWithImportedNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("table_id", 300));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            Map<String, Object> node = session.getTables().get(0);
            assertThat(node.get("dataSourceId")).isEqualTo(300);
            assertThat(dataOf(result))
                    .as("the engine reads dataSourceId first, so this write did land")
                    .doesNotContainKey("NOT_APPLIED");
        }

        @Test
        @DisplayName("the stale spelling is cleared, so the node holds one id")
        void theStaleSpellingIsCleared() {
            WorkflowBuilderSession session = sessionWithImportedNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("table_id", 300));
            modifier.executeModifyNode(session, args);

            assertThat(session.getTables().get(0))
                    .as("two ids on one node is a disagreement waiting to be read")
                    .doesNotContainKey("table_id");
        }

        @Test
        @DisplayName("the read-back follows the parser's order, not the caller's spelling")
        void theReadBackFollowsTheParserOrder() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "table:next");
            node.put("label", "Next Video");
            node.put("type", "crud-find");
            node.put("dataSourceId", 300);
            node.put("table_id", 999);   // loses to dataSourceId at parse time

            assertThat(WorkflowBuilderModifier.sameScalar(300,
                    WorkflowBuilderModifier.effectiveValue(node, "table:next", "table_id")))
                    .as("asking under any spelling must answer with the id that wins")
                    .isTrue();
        }

        @Test
        @DisplayName("a node with only table_id still reads it, because the parser does")
        void theOnlySpellingIsStillTheAnswer() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "table:next");
            node.put("label", "Next Video");
            node.put("type", "crud-find");
            node.put("table_id", 999);

            assertThat(WorkflowBuilderModifier.sameScalar(999,
                    WorkflowBuilderModifier.effectiveValue(node, "table:next", "dataSourceId")))
                    .isTrue();
        }

        @Test
        @DisplayName("between two aliases the first documented spelling wins, not the last read")
        void aliasPrecedenceIsDeliberate() {
            WorkflowBuilderSession session = sessionWithImportedNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("table_id", 111, "tableId", 222));
            modifier.executeModifyNode(session, args);

            assertThat(session.getTables().get(0).get("dataSourceId")).isEqualTo(111);
        }
    }

    @Nested
    @DisplayName("a config block that was never materialised")
    class MissingBlock {

        @Test
        @DisplayName("a scalar crud is refused even when the node has no crud block yet")
        void aScalarIsRefusedOnANodeWithoutTheBlock() {
            WorkflowBuilderSession session = sessionWithImportedNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("crud", "oops-not-a-map"));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            assertThat(result.success())
                    .as("the shape test alone cannot see this, the node has no block to compare")
                    .isFalse();
            assertThat(session.getTables().get(0).get("crud")).isNull();
        }

        @Test
        @DisplayName("a core node's block never ends up a scalar, by either route")
        void aCoreNodesBlockIsNeverLeftAsAScalar() {
            // A core node reaches safety by a different route: harmonization wraps the
            // value INTO the block rather than leaving it beside it, so the block stays a
            // map and the plan stays loadable. What matters is the invariant, not which
            // of the two mechanisms enforced it, so that is what this asserts.
            WorkflowBuilderSession session = session();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:hold");
            node.put("type", "wait");
            node.put("label", "Hold");
            session.getCores().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Hold");
            args.put("changes", Map.of("wait", "5s"));

            modifier.executeModifyNode(session, args);

            assertThat(node.get("wait"))
                    .as("the parser casts this to a map; a scalar here makes the plan unreadable")
                    .isNotInstanceOf(String.class);
        }

        @Test
        @DisplayName("an ordinary field on the same node is still accepted")
        void anOrdinaryFieldStillWorks() {
            WorkflowBuilderSession session = sessionWithImportedNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("limit", 100));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> crud = (Map<String, Object>) session.getTables().get(0).get("crud");
            assertThat(crud).containsEntry("limit", 100);
        }
    }
}
