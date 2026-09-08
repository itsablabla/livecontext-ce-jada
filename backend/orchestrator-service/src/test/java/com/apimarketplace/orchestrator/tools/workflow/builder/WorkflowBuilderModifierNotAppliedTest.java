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
 * The gap detector must not cry wolf.
 *
 * <p>{@code NOT_APPLIED} lists scalars the engine does not read back, so a routing gap
 * announces itself instead of costing a silent no-op. An impartial audit found the first
 * version firing on ordinary SUCCESSFUL writes, including on {@code table_id}, the very
 * field the same change had just taught the modifier to route. Worse, the response told
 * the agent {@code "report it: this is a builder defect, not a bad call"}. A detector that
 * is wrong about a success is worse than no detector at all: it teaches its reader to
 * ignore it, and the one time it is right nobody looks.
 *
 * <p>The cause was that the report resolved the caller's RAW key while harmonization
 * routinely renames it: {@code table_id} becomes {@code dataSourceId},
 * {@code is_entry_interface} becomes {@code isEntryInterface}, and a dozen more
 * documented snake_case aliases do the same. The engine then reads nothing under the raw
 * name, which the detector read as a failed write.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderModifier - NOT_APPLIED must only fire on a real miss")
class WorkflowBuilderModifierNotAppliedTest {

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ToolExecutionResult result) {
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> changesOf(ToolExecutionResult result) {
        return (Map<String, Object>) dataOf(result).get("changes");
    }

    @Nested
    @DisplayName("through the real API, on writes that succeeded")
    class NoFalseAlarm {

        @Test
        @DisplayName("table_id landed as dataSourceId and is NOT flagged")
        void tableIdIsNotAFalseAlarm() {
            WorkflowBuilderSession session = session();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "table:next");
            node.put("type", "crud-find");
            node.put("label", "Next Video");
            node.put("dataSourceId", 248);
            node.put("crud", new LinkedHashMap<>(Map.of("limit", 1)));
            session.getTables().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("table_id", 300));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            assertThat(node.get("dataSourceId")).as("the write itself must land").isEqualTo(300);
            assertThat(dataOf(result))
                    .as("a renamed key that landed must not be reported as a builder defect")
                    .doesNotContainKey("NOT_APPLIED");
        }

        @Test
        @DisplayName("is_entry_interface landed as isEntryInterface and is NOT flagged")
        void interfaceAliasIsNotAFalseAlarm() {
            WorkflowBuilderSession session = session();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "interface:report");
            node.put("type", "interface");
            node.put("label", "Report");
            node.put("isEntryInterface", false);
            session.getInterfaces().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Report");
            args.put("params", Map.of("is_entry_interface", true));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            assertThat(node.get("isEntryInterface")).isEqualTo(true);
            assertThat(dataOf(result)).doesNotContainKey("NOT_APPLIED");
        }

        @Test
        @DisplayName("the renamed alias adds no phantom row to the diff")
        void aRenamedAliasDoesNotAddAnEmptyDiffRow() {
            WorkflowBuilderSession session = session();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "table:next");
            node.put("type", "crud-find");
            node.put("label", "Next Video");
            node.put("dataSourceId", 248);
            session.getTables().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("table_id", 300));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            assertThat(changesOf(result)).containsKey("dataSourceId");
            assertThat(changesOf(result))
                    .as("a (not set) -> (not set) row beside the real one reads like it was ignored")
                    .doesNotContainKey("table_id");
        }

        @Test
        @DisplayName("a crud key routed under its OWN name is still watched, and passes")
        void aRoutedCrudKeyIsStillCovered() {
            // similarity is in the parser's crud list but not in TableCreator's, so it is
            // the one crud field where the add and modify paths disagree.
            WorkflowBuilderSession session = session();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "table:next");
            node.put("type", "crud-find");
            node.put("label", "Next Video");
            node.put("crud", new LinkedHashMap<>(Map.of("similarity", "old")));
            session.getTables().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("similarity", "new"));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            @SuppressWarnings("unchecked")
            Map<String, Object> crud = (Map<String, Object>) node.get("crud");
            assertThat(crud).containsEntry("similarity", "new");
            assertThat(dataOf(result)).doesNotContainKey("NOT_APPLIED");
        }
    }

    @Nested
    @DisplayName("the detector itself")
    class Detector {

        @Test
        @DisplayName("FIRES when the engine reads a different value under that same name")
        void itFiresOnARealMiss() {
            // The exact shape a missing routing entry leaves behind: the patch parked at
            // the top level while the config slot kept the old value.
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "crud-find");
            node.put("limit", 100);
            node.put("crud", new LinkedHashMap<>(Map.of("limit", 1)));

            Map<String, Object> found = WorkflowBuilderModifier.detectNotApplied(
                    node, "table:next", Map.of("limit", 100));

            assertThat(found).containsKey("limit");
            assertThat(String.valueOf(found.get("limit")))
                    .as("the message must name both sides or it cannot be acted on")
                    .contains("100").contains("1");
        }

        @Test
        @DisplayName("stays silent when the engine reads nothing under that name")
        void itStaysSilentWhenTheNameWasRenamed() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "crud-find");
            node.put("dataSourceId", 300);

            assertThat(WorkflowBuilderModifier.detectNotApplied(
                    node, "table:next", Map.of("table_id", 300))).isEmpty();
        }

        @Test
        @DisplayName("stays silent on a map, whose merge makes a difference expected")
        void itIgnoresPartialOverlays() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "mcp");
            node.put("params", new LinkedHashMap<>(Map.of("a", 1, "b", 2)));

            assertThat(WorkflowBuilderModifier.detectNotApplied(
                    node, "mcp:x", Map.of("params", Map.of("a", 9)))).isEmpty();
        }

        @Test
        @DisplayName("stays silent when the value did land")
        void itIsSilentOnASuccess() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "crud-find");
            node.put("crud", new LinkedHashMap<>(Map.of("limit", 100)));

            assertThat(WorkflowBuilderModifier.detectNotApplied(
                    node, "table:next", Map.of("limit", 100))).isEmpty();
        }
    }
}
