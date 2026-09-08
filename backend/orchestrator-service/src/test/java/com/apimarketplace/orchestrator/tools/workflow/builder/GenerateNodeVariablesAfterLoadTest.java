package com.apimarketplace.orchestrator.tools.workflow.builder;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * What {@code describe} tells the agent a LOADED generate node produces.
 *
 * <p>The reference an agent copies comes from one of two places, and which one
 * depends on something the agent cannot see. For a node added in the current
 * session, {@link ResponseContextBuilder} builds it. For a node read back by
 * {@code load}, the LOADER has already registered a schema, and the context
 * builder prefers that schema whenever one exists. So the two have to agree,
 * and the loaded path is the one that matters in practice: an author edits an
 * existing workflow far more often than they build one in a single sitting.
 *
 * <p>Both used to answer {@code {{agent:<label>.output.response}}} for every
 * node under an {@code agent:} key. That is true of an LLM agent and false of
 * generate, whose outputs are file / model / kind / provider / billed_quantity /
 * billed_unit. The failure is silent by construction: an unresolved template is
 * an empty string, not an error, so the downstream parameter simply receives
 * nothing and the run reports success.
 */
@DisplayName("a loaded generate node advertises the file it produces, not an agent's response")
class GenerateNodeVariablesAfterLoadTest {

    private WorkflowBuilderSession session() {
        return WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Make a clip")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private Map<String, Object> generateNode() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("label", "Make Clip");
        node.put("type", "generate");
        node.put("isAgent", true);
        node.put("params", Map.of("model", "seedance-2.0-fast"));
        return node;
    }

    private Map<String, Object> llmAgentNode() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("label", "Writer");
        node.put("type", "agent");
        node.put("isAgent", true);
        return node;
    }

    /** The loader's private schema rebuild, which runs for every entry of plan.agents on load. */
    private void rebuildSchema(WorkflowBuilderSession session, String nodeId, Map<String, Object> node)
            throws Exception {
        // Built without running the constructor: rebuildStepSchema touches none of
        // the twelve collaborators for an AI node, and passing twelve nulls
        // positionally would say the same thing and be unreadable.
        WorkflowBuilderLoader loader =
                Mockito.mock(WorkflowBuilderLoader.class, Mockito.CALLS_REAL_METHODS);
        Method method = WorkflowBuilderLoader.class.getDeclaredMethod(
                "rebuildStepSchema", WorkflowBuilderSession.class, String.class, Map.class, boolean.class);
        method.setAccessible(true);
        method.invoke(loader, session, nodeId, node, true);
    }

    @Test
    @DisplayName("the loader registers .output.file for it, never .output.response")
    void theLoaderRegistersTheFile() throws Exception {
        WorkflowBuilderSession session = session();

        rebuildSchema(session, "agent:make_clip", generateNode());

        WorkflowBuilderSession.NodeSchema schema = session.getNodeSchemas().get("agent:make_clip");
        assertThat(schema).as("the load path must register a schema for the node").isNotNull();
        assertThat(schema.getReferenceSyntax())
                .as("`response` is an LLM agent's answer; this node produces a file, so a "
                        + "reference to .output.response resolves to an empty string")
                .containsEntry("file", "{{agent:make_clip.output.file}}")
                .doesNotContainKey("response");
        assertThat(schema.getOutputs()).containsKey("file").doesNotContainKey("response");
    }

    @Test
    @DisplayName("an ordinary LLM agent still gets .output.response, so the family is not broken wholesale")
    void anOrdinaryAgentIsUntouched() throws Exception {
        WorkflowBuilderSession session = session();

        rebuildSchema(session, "agent:writer", llmAgentNode());

        assertThat(session.getNodeSchemas().get("agent:writer").getReferenceSyntax())
                .containsEntry("response", "{{agent:writer.output.response}}");
    }

    /**
     * Wire the node upstream of a consumer, because variables are listed by
     * PREDECESSOR: only what a node can actually reach is offered to it.
     */
    private void wireDownstreamConsumer(WorkflowBuilderSession session) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", "agent:make_clip");
        edge.put("to", "core:use_clip");
        session.getEdges().add(edge);
    }

    @Test
    @DisplayName("the context builder agrees with the loader when no schema was registered")
    void theInSessionPathAgrees() {
        // The path taken by a node added in THIS session: no schema exists yet, so
        // the context builder answers from the node itself. Its answer has to match
        // the loader's, or the same node reads differently depending on whether the
        // author reopened the workflow.
        WorkflowBuilderSession session = session();
        Map<String, Object> node = generateNode();
        node.put("isGenerate", true);
        session.getMcps().add(node);
        wireDownstreamConsumer(session);

        Map<String, Object> variables =
                new ResponseContextBuilder().getAccessibleVariables(session, "core:use_clip");

        assertThat(variables).containsKey("Make Clip");
        @SuppressWarnings("unchecked")
        Map<String, Object> refs = (Map<String, Object>) variables.get("Make Clip");
        assertThat(refs)
                .containsEntry("file", "{{agent:make_clip.output.file}}")
                .doesNotContainKey("response");
    }

    @Test
    @DisplayName("and the loader's answer is the one that wins, because a registered schema is preferred")
    void theRegisteredSchemaWins() throws Exception {
        // This is why the loader had to be fixed rather than only the context
        // builder: with a schema present the context builder never consults the
        // node, so a fix there alone would have been dead code on every load.
        WorkflowBuilderSession session = session();
        Map<String, Object> node = generateNode();
        node.put("isGenerate", true);
        session.getMcps().add(node);
        rebuildSchema(session, "agent:make_clip", node);
        wireDownstreamConsumer(session);

        Map<String, Object> variables =
                new ResponseContextBuilder().getAccessibleVariables(session, "core:use_clip");

        @SuppressWarnings("unchecked")
        Map<String, Object> refs = (Map<String, Object>) variables.get("Make Clip");
        assertThat(refs)
                .as("whichever source answers, the agent must be handed the same reference")
                .containsEntry("file", "{{agent:make_clip.output.file}}");
        assertThat(refs).doesNotContainKey("response");
    }
}
