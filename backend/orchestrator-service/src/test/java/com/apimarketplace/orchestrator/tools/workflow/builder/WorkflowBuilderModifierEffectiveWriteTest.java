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
 * {@code workflow(action='modify')} must write where the ENGINE reads, and must report
 * back what the engine reads.
 *
 * <p>Both halves failed in production on 2026-09-02. Patching a table CRUD node with
 * {@code params={limit: 100}} answered {@code "Node modified"} with
 * {@code modified_fields: ["limit"]} and {@code after: 100}, while the executor went on
 * reading {@code crud.limit}, still 1. Two independent defects produced that: the patch
 * landed at the top level of the node, and the before/after report echoed the top level
 * too, so it confirmed the write instead of catching it. The {@code before: "(not set)"}
 * in that response was the tell, since the field it claimed to be changing did have a
 * value.
 *
 * <p>{@code WorkflowPlanParser} makes the first defect worse rather than harmless: it
 * reads {@code crud.<key>} first and falls back to the node top level only when the crud
 * block does NOT carry the key. So the same patch worked or did nothing depending on
 * whether the field had ever been set, which is why it read as arbitrary.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderModifier - a patch lands where the engine reads")
class WorkflowBuilderModifierEffectiveWriteTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    private WorkflowBuilderModifier modifier;

    @BeforeEach
    void setUp() {
        modifier = new WorkflowBuilderModifier(sessionStore);
    }

    private WorkflowBuilderSession createSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Test Workflow")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> crudOf(Map<String, Object> node) {
        return (Map<String, Object>) node.get("crud");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paramsOf(Map<String, Object> node) {
        return (Map<String, Object>) node.get("params");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> changesOf(ToolExecutionResult result) {
        Map<String, Object> data = (Map<String, Object>) result.data();
        return (Map<String, Object>) data.get("changes");
    }

    // Table CRUD nodes - the reported defect

    @Nested
    @DisplayName("table CRUD node")
    class TableCrudNode {

        /** A find node shaped exactly as TableCreator produces it. */
        private WorkflowBuilderSession sessionWithFindNode() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "table:next_video");
            node.put("type", "crud-find");
            node.put("label", "Next Video");
            node.put("dataSourceId", 248);
            node.put("position", new LinkedHashMap<>(Map.of("x", 290, "y", 50)));
            Map<String, Object> crud = new LinkedHashMap<>();
            crud.put("limit", 1);
            crud.put("where", new LinkedHashMap<>(Map.of(
                    "column", "data.processed", "operator", "==", "value", "no")));
            node.put("crud", crud);
            session.getTables().add(node);
            return session;
        }

        @Test
        @DisplayName("a flat limit reaches crud.limit, where the executor reads it")
        void flatLimitReachesTheCrudBlock() {
            WorkflowBuilderSession session = sessionWithFindNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("limit", 100));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);
            assertThat(result.success()).isTrue();

            Map<String, Object> node = session.getTables().get(0);
            assertThat(crudOf(node)).containsEntry("limit", 100);
            // The top level is where it used to land, and where nothing reads it.
            assertThat(node).doesNotContainKey("limit");
        }

        @Test
        @DisplayName("the untouched half of the crud block survives the patch")
        void patchingOneCrudFieldKeepsTheOthers() {
            WorkflowBuilderSession session = sessionWithFindNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("limit", 100));
            modifier.executeModifyNode(session, args);

            assertThat(crudOf(session.getTables().get(0))).containsKey("where");
        }

        @Test
        @DisplayName("table_id stays at the top level, it is not a crud field")
        void tableIdIsNotACrudField() {
            WorkflowBuilderSession session = sessionWithFindNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("table_id", 300, "limit", 5));
            modifier.executeModifyNode(session, args);

            Map<String, Object> node = session.getTables().get(0);
            assertThat(crudOf(node)).containsEntry("limit", 5).doesNotContainKey("table_id");
            assertThat(node.get("dataSourceId")).isEqualTo(300);
        }

        @Test
        @DisplayName("an already-shaped crud block is merged, not dropped")
        void anExplicitCrudBlockIsMerged() {
            WorkflowBuilderSession session = sessionWithFindNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("crud", Map.of("limit", 42)));
            modifier.executeModifyNode(session, args);

            Map<String, Object> crud = crudOf(session.getTables().get(0));
            assertThat(crud).containsEntry("limit", 42);
            assertThat(crud).containsKey("where");
        }

        @Test
        @DisplayName("the report shows the crud value, not the top-level one")
        void theReportReadsBackFromTheCrudBlock() {
            WorkflowBuilderSession session = sessionWithFindNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("limit", 100));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            @SuppressWarnings("unchecked")
            Map<String, Object> limitDiff = (Map<String, Object>) changesOf(result).get("limit");
            // Pre-fix this said before "(not set)", because it compared against the top
            // level, which had never held the value. That line is what made a no-op look
            // like a change.
            assertThat(limitDiff).containsEntry("before", 1).containsEntry("after", 100);
        }

        @Test
        @DisplayName("nothing is flagged NOT_APPLIED when the patch really took")
        void aSuccessfulPatchIsNotFlagged() {
            WorkflowBuilderSession session = sessionWithFindNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("limit", 100));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data).doesNotContainKey("NOT_APPLIED");
        }
    }

    // `position` - a node field and a media parameter share one name

    @Nested
    @DisplayName("position, which is both canvas placement and an overlay anchor")
    class PositionCollision {

        private WorkflowBuilderSession sessionWithMediaNode() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:watermark_film");
            node.put("type", "media");
            node.put("label", "Watermark Film");
            node.put("position", new LinkedHashMap<>(Map.of("x", 1770, "y", 50)));
            node.put("params", new LinkedHashMap<>(Map.of(
                    "operation", "overlay", "position", "bottom_right", "margin_px", 24)));
            session.getCores().add(node);
            return session;
        }

        @Test
        @DisplayName("an anchor string goes to params, and the canvas placement survives")
        void anAnchorDoesNotOverwriteTheCanvasPlacement() {
            WorkflowBuilderSession session = sessionWithMediaNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Watermark Film");
            args.put("params", Map.of("position", "top_left", "margin_px", 28));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);
            assertThat(result.success()).isTrue();

            Map<String, Object> node = session.getCores().get(0);
            assertThat(paramsOf(node))
                    .containsEntry("position", "top_left")
                    .containsEntry("margin_px", 28)
                    .containsEntry("operation", "overlay");
            // The plan parser reads this as a map. A string here made the whole workflow
            // unloadable, AFTER it had been persisted.
            assertThat(node.get("position")).isInstanceOf(Map.class);
            assertThat(node.get("position")).asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("x", 1770);
        }

        @Test
        @DisplayName("a real canvas move is still a canvas move")
        void aMapPositionStillMovesTheNode() {
            WorkflowBuilderSession session = sessionWithMediaNode();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Watermark Film");
            args.put("params", Map.of("position", Map.of("x", 10, "y", 20)));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);
            assertThat(result.success()).isTrue();

            Map<String, Object> node = session.getCores().get(0);
            assertThat(node.get("position")).asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("x", 10).containsEntry("y", 20);
            assertThat(paramsOf(node)).containsEntry("position", "bottom_right");
        }

        @Test
        @DisplayName("a node with nowhere to put an anchor is REFUSED, not corrupted")
        void aScalarPositionOnANodeThatCannotHoldItIsRefused() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "core:check");
            node.put("type", "decision");
            node.put("label", "Check");
            node.put("position", new LinkedHashMap<>(Map.of("x", 5, "y", 5)));
            session.getCores().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Check");
            args.put("params", Map.of("position", "top_left"));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            assertThat(result.success()).isFalse();
            // The refusal is the point: the node must be left loadable.
            assertThat(session.getCores().get(0).get("position")).isInstanceOf(Map.class);
        }
    }

    // The read-back that makes a future silent no-op visible

    @Nested
    @DisplayName("effectiveValue, the engine's view of a field")
    class EffectiveValueView {

        @Test
        @DisplayName("a table node is read through its crud block")
        void tableNodeReadsThroughCrud() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "crud-find");
            node.put("limit", 100);   // where a bad writer would leave it
            node.put("crud", new LinkedHashMap<>(Map.of("limit", 1)));

            assertThat(WorkflowBuilderModifier.effectiveValue(node, "table:x", "limit"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a core node with a config slot is read through that slot")
        void coreNodeReadsThroughItsSlot() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "media");
            node.put("params", new LinkedHashMap<>(Map.of("opacity", 0.65)));

            assertThat(WorkflowBuilderModifier.effectiveValue(node, "core:wm", "opacity"))
                    .isEqualTo(0.65);
        }

        @Test
        @DisplayName("a field the slot does not carry falls back to the node itself")
        void unknownFieldFallsBackToTheNode() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "media");
            node.put("label", "Watermark");
            node.put("params", new LinkedHashMap<>(Map.of("opacity", 0.65)));

            assertThat(WorkflowBuilderModifier.effectiveValue(node, "core:wm", "label"))
                    .isEqualTo("Watermark");
        }

        @Test
        @DisplayName("a number that arrived as a string is not reported as a failure")
        void scalarComparisonToleratesJsonNumberShapes() {
            assertThat(WorkflowBuilderModifier.sameScalar(100, 100L)).isTrue();
            assertThat(WorkflowBuilderModifier.sameScalar("100", 100)).isTrue();
            assertThat(WorkflowBuilderModifier.sameScalar(100, 1)).isFalse();
        }
    }
}
