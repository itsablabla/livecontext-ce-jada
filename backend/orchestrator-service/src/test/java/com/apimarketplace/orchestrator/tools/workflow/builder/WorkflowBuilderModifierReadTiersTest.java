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
 * A table node's crud field is read on THREE tiers, and the report has to know all three.
 *
 * <p>{@code WorkflowPlanParser.parseTables} reads each crud field from the {@code crud}
 * block, then the node's top level, then {@code params}. The read-back knew only the first
 * two, which was invisible for every field except one: {@code TableCreator} does not move
 * {@code similarity} into the block, so on a freshly created node {@code params} is its
 * ONLY home. A fourth audit round demonstrated the consequence by running it: modifying
 * {@code similarity} reported {@code before: "(not set)"} while the engine had been
 * reading the value all along.
 *
 * <p>That is the same mistake this whole change set exists to remove, "the report
 * describes the writer, not the engine", one tier down from where it was first found. The
 * write was already correct; what was wrong was the answer to "what was it before", which
 * is what an agent consults before overwriting something.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderModifier - the crud read tiers, all three of them")
class WorkflowBuilderModifierReadTiersTest {

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

    /** A find node as TableCreator leaves it: similarity stays in params, not in crud. */
    /**
     * A real similarity value. It is a typed object (column, queryVector, topK,
     * threshold), and a plain string is DROPPED by the parser, so a fixture using one
     * cannot exercise the tier it claims to: the engine would read nothing either way.
     * Reading the value back through the parser is what made that visible.
     */
    static final Map<String, Object> STALE =
        Map.of("column", "embedding", "queryVector", "old", "topK", 3);
    static final Map<String, Object> FRESH =
        Map.of("column", "embedding", "queryVector", "new", "topK", 5);

    private WorkflowBuilderSession sessionWithSimilarityInParams() {
        WorkflowBuilderSession session = session();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "table:next");
        node.put("type", "crud-find");
        node.put("label", "Next Video");
        node.put("dataSourceId", 248);
        node.put("crud", new LinkedHashMap<>(Map.of("limit", 1)));
        node.put("params", new LinkedHashMap<>(Map.of("similarity", STALE)));
        session.getTables().add(node);
        return session;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> changesOf(ToolExecutionResult result) {
        Map<String, Object> data = (Map<String, Object>) result.data();
        return (Map<String, Object>) data.get("changes");
    }

    @Nested
    @DisplayName("the third tier, params")
    class ThirdTier {

        @Test
        @DisplayName("the reported before is the value the engine was reading")
        void theBeforeComesFromWhereTheEngineWasReading() {
            WorkflowBuilderSession session = sessionWithSimilarityInParams();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("similarity", FRESH));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            @SuppressWarnings("unchecked")
            Map<String, Object> diff = (Map<String, Object>) changesOf(result).get("similarity");
            // The view hands back the PARSED object, so it carries every component of the
            // typed shape, threshold included even when unset. That is the engine's own
            // view of the field and it tells the agent which components exist, so the
            // assertion is on the components that were set rather than on map equality.
            assertThat(diff.get("before")).asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .as("saying (not set) here tells an agent it is creating a value it is overwriting")
                    .containsEntry("queryVector", "old").containsEntry("topK", 3);
            assertThat(diff.get("after")).asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("queryVector", "new").containsEntry("topK", 5);
        }

        @Test
        @DisplayName("effectiveValue reads params when neither the block nor the node has it")
        void paramsIsConsultedLast() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "table:next");
            node.put("label", "Next Video");
            node.put("type", "crud-find");
            node.put("crud", new LinkedHashMap<>(Map.of("limit", 1)));
            node.put("params", new LinkedHashMap<>(Map.of("similarity", STALE)));

            assertThat(WorkflowBuilderModifier.effectiveValue(node, "table:next", "similarity"))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("queryVector", "old");
        }

        @Test
        @DisplayName("the block still wins over both outer tiers")
        void theBlockKeepsItsPrecedence() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "crud-find");
            node.put("crud", new LinkedHashMap<>(Map.of("limit", 1)));
            node.put("limit", 50);
            node.put("params", new LinkedHashMap<>(Map.of("limit", 99)));

            assertThat(WorkflowBuilderModifier.effectiveValue(node, "table:next", "limit"))
                    .as("the parser reads the block first, so the report must too")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the node's top level still beats params, as the parser has it")
        void theNodeBeatsParams() {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "crud-find");
            node.put("offset", 5);
            node.put("params", new LinkedHashMap<>(Map.of("offset", 9)));

            assertThat(WorkflowBuilderModifier.effectiveValue(node, "table:next", "offset"))
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("a field living only in params is still reported at all")
        void aParamsOnlyFieldIsNotDroppedFromTheReport() {
            WorkflowBuilderSession session = sessionWithSimilarityInParams();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            args.put("params", Map.of("similarity", FRESH));

            ToolExecutionResult result = modifier.executeModifyNode(session, args);

            assertThat(changesOf(result)).containsKey("similarity");
        }
    }

    @Nested
    @DisplayName("null means delete, inside the crud block too")
    class NullDeletes {

        @Test
        @DisplayName("a null clears the OUTER tiers too, or the delete is a silent no-op")
        void aNullClearsEveryTier() {
            // The near miss of the previous round: the existing delete test put the value
            // inside the block, which is the one place clearing it is enough. A field
            // whose only home is params is the case that was broken, and the report made
            // it worse than silent: it read "before stale, after stale", which is what a
            // no-change looks like, not what an ignored request looks like.
            WorkflowBuilderSession session = sessionWithSimilarityInParams();

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("similarity", null);
            args.put("params", patch);

            modifier.executeModifyNode(session, args);

            Map<String, Object> node = session.getTables().get(0);
            assertThat(WorkflowBuilderModifier.effectiveValue(node, "table:next", "similarity"))
                    .as("the engine must stop reading the value the caller deleted")
                    .isNull();
        }

        @Test
        @DisplayName("a null removes the key instead of storing a null-valued one")
        void aNullRemovesTheKey() {
            WorkflowBuilderSession session = session();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "table:next");
            node.put("type", "crud-find");
            node.put("label", "Next Video");
            Map<String, Object> crud = new LinkedHashMap<>();
            crud.put("limit", 1);
            crud.put("similarity", STALE);
            node.put("crud", crud);
            session.getTables().add(node);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Next Video");
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("similarity", null);   // Map.of rejects a null value
            args.put("params", patch);

            modifier.executeModifyNode(session, args);

            @SuppressWarnings("unchecked")
            Map<String, Object> stored = (Map<String, Object>) session.getTables().get(0).get("crud");
            assertThat(stored)
                    .as("null deletes everywhere else in this class; a null-valued key is a zombie")
                    .doesNotContainKey("similarity");
            assertThat(stored).containsEntry("limit", 1);
        }
    }
}
