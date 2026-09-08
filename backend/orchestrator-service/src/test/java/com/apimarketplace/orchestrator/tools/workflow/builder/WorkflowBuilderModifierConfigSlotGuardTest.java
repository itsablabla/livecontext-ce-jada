package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A configuration block must stay a map, and a REPLACE-shaped field must be watched.
 *
 * <p>Both come from the second audit round of this change set.
 *
 * <p>The first: {@code NodeFieldMerger} falls through to an unconditional REPLACE on a
 * type mismatch, so {@code params={crud: 'oops'}} answered success and left the node's
 * entire crud block as a string. {@code WorkflowPlanParser} casts that to a Map on the
 * next load, so the workflow could no longer be opened to repair itself. That is the same
 * corruption the {@code position} guard was written for, one field to the left, in the
 * routing this very change set introduced. The guard is on the slot NAMES now, so it
 * covers {@code params}, {@code actionMapping}, {@code variableMapping}, {@code metadata}
 * and every nested config key, not just the field that happened to be reported.
 *
 * <p>The second: the gap detector skipped every Map and List, which left
 * {@code where}, {@code set}, {@code rows} and {@code columns} with no cover at all,
 * i.e. the crud fields an agent modifies most. A field the merger OVERLAYS may
 * legitimately differ from what was sent; one it REPLACES may not, and the crud fields
 * are all replaced.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderModifier - a config block stays a map, and replaced fields are watched")
class WorkflowBuilderModifierConfigSlotGuardTest {

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

    private WorkflowBuilderSession sessionWithFindNode() {
        WorkflowBuilderSession session = session();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "table:next");
        node.put("type", "crud-find");
        node.put("label", "Next Video");
        node.put("dataSourceId", 248);
        Map<String, Object> crud = new LinkedHashMap<>();
        crud.put("limit", 1);
        crud.put("where", new LinkedHashMap<>(Map.of(
                "column", "processed", "operator", "==", "value", "no")));
        node.put("crud", crud);
        session.getTables().add(node);
        return session;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ToolExecutionResult result) {
        return (Map<String, Object>) result.data();
    }

    @Nested
    @DisplayName("a scalar sent to a config block")
    class SlotShapeGuard {

        @Test
        @DisplayName("is REFUSED, and the crud block survives intact")
        void aScalarCrudIsRefused() {
            WorkflowBuilderSession session = sessionWithFindNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("crud", "oops-not-a-map"));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("configuration block");
            // The node must still be loadable: the parser casts this to a Map.
            assertThat(session.getTables().get(0).get("crud")).isInstanceOf(Map.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"actionMapping", "variableMapping", "metadata"})
        @DisplayName("is refused for every merge-map slot, not only the reported one")
        void everyMergeMapSlotIsGuarded(String slot) {
            // On an INTERFACE node these three are real config blocks and stay top-level.
            // On an MCP node the same names are ordinary tool arguments that harmonization
            // routes into params, which is correct and not what this guard is about.
            WorkflowBuilderSession session = session();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "interface:report");
            node.put("type", "interface");
            node.put("label", "Report");
            node.put(slot, new LinkedHashMap<>(Map.of("a", 1)));
            session.getInterfaces().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Report");
            args.put("changes", Map.of(slot, "scalar"));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            assertThat(result.success()).as("%s must not accept a scalar", slot).isFalse();
            assertThat(node.get(slot)).isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("a crud FIELD called limit is NOT mistaken for a config block")
        void aFieldSharingItsNameWithANodeTypeIsNotGuarded() {
            // `limit` is also the name of a node type whose config block is called limit,
            // so a guard written as a list of block NAMES would refuse this call, which is
            // the one this whole change set exists to make work. Keying on the shape
            // already stored is what keeps the two apart.
            WorkflowBuilderSession session = sessionWithFindNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("limit", 100));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> crud = (Map<String, Object>) session.getTables().get(0).get("crud");
            assertThat(crud).containsEntry("limit", 100);
        }

        @Test
        @DisplayName("the refusal names the node, so the agent knows which one to fix")
        void theRefusalNamesTheNode() {
            WorkflowBuilderSession session = sessionWithFindNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("crud", 42));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Next Video").contains("unreadable");
        }

        @Test
        @DisplayName("the block sent AS a map is still accepted")
        void aProperBlockStillWorks() {
            WorkflowBuilderSession session = sessionWithFindNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("crud", Map.of("limit", 50)));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> crud = (Map<String, Object>) session.getTables().get(0).get("crud");
            assertThat(crud).containsEntry("limit", 50).containsKey("where");
        }
    }

    @Nested
    @DisplayName("a replaced structured field")
    class ReplacedStructuredFields {

        @Test
        @DisplayName("a where that landed is not flagged")
        void aWhereThatLandedIsNotFlagged() {
            WorkflowBuilderSession session = sessionWithFindNode();
            Map<String, Object> newWhere = Map.of("column", "id", "operator", "==", "value", "7");

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("where", newWhere));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            assertThat(result.success()).isTrue();
            assertThat(dataOf(result)).doesNotContainKey("NOT_APPLIED");
        }

        @Test
        @DisplayName("a where the engine does NOT read back IS flagged")
        void aWhereLeftOutsideTheBlockIsFlagged() {
            // The shape a routing gap leaves behind, built on `where` instead of `limit`:
            // an audit showed the detector silent here because it skipped every map.
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "crud-find");
            Map<String, Object> stale = new LinkedHashMap<>(Map.of(
                    "column", "processed", "operator", "==", "value", "no"));
            node.put("crud", new LinkedHashMap<>(Map.of("where", stale)));
            Map<String, Object> requested = Map.of(
                    "where", Map.of("column", "id", "operator", "==", "value", "7"));

            Map<String, Object> found =
                    WorkflowBuilderModifier.detectNotApplied(node, "table:next", requested);

            assertThat(found).containsKey("where");
        }

        @Test
        @DisplayName("a params overlay is still exempt, its difference is by design")
        void aMergedOverlayIsStillExempt() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "mcp");
            node.put("params", new LinkedHashMap<>(Map.of("a", 1, "b", 2)));

            assertThat(WorkflowBuilderModifier.detectNotApplied(
                    node, "mcp:x", Map.of("params", Map.of("a", 9)))).isEmpty();
        }

        @Test
        @DisplayName("a list field merged by label is exempt too")
        void aLabelMergedListIsExempt() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "switch");
            node.put("switchCases", List.of(Map.of("label", "a")));

            assertThat(WorkflowBuilderModifier.detectNotApplied(
                    node, "core:s", Map.of("switchCases", List.of(Map.of("label", "b"))))).isEmpty();
        }
    }

    @Nested
    @DisplayName("housekeeping around the crud block")
    class Housekeeping {

        @Test
        @DisplayName("an explicit dataSourceId beats the alias sent beside it")
        void theCanonicalNameWinsOverItsAlias() {
            WorkflowBuilderSession session = sessionWithFindNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("table_id", 300, "dataSourceId", 999));

            modifier.executeModifyNode(session, args);

            assertThat(session.getTables().get(0).get("dataSourceId"))
                    .as("an alias must not silently override the canonical name")
                    .isEqualTo(999);
        }

        @Test
        @DisplayName("a stale copy of a crud field outside the block is cleaned up")
        void staleCopiesAreRemoved() {
            // The creation path leaves `similarity` in params rather than in the block, so
            // after a modify writes it into crud the outer copy is dead data that
            // disagrees with the live value.
            WorkflowBuilderSession session = sessionWithFindNode();
            Map<String, Object> node = session.getTables().get(0);
            node.put("params", new LinkedHashMap<>(Map.of("similarity", "stale")));

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("similarity", "fresh"));

            modifier.executeModifyNode(session, args);

            @SuppressWarnings("unchecked")
            Map<String, Object> crud = (Map<String, Object>) node.get("crud");
            assertThat(crud).containsEntry("similarity", "fresh");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            assertThat(params).doesNotContainKey("similarity");
        }

        @Test
        @DisplayName("a copy the block does NOT carry is left alone, it is the live value")
        void theOnlyCopyIsNeverRemoved() {
            WorkflowBuilderSession session = sessionWithFindNode();
            Map<String, Object> node = session.getTables().get(0);
            node.put("params", new LinkedHashMap<>(Map.of("similarity", "the only one")));

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("limit", 7));

            modifier.executeModifyNode(session, args);

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            assertThat(params)
                    .as("the parser falls back to params, so removing this would change behaviour")
                    .containsEntry("similarity", "the only one");
        }
    }
}
