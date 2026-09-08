package com.apimarketplace.orchestrator.tools.workflow.builder;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mock skeleton offered for a generate node.
 *
 * <p>{@code mock_suggest} resolves a schema name from the node's KEY. Every key
 * under {@code agent:} used to mean "an LLM call", which was true while the AI
 * family was three kinds of one. Generate joined it and runs no LLM: read as an
 * agent it was handed {@code response} / {@code tokens_used} / {@code iterations}
 * / {@code tool_calls}, none of which is in the GENERATE schema.
 *
 * <p>That failure is silent in the worst way: fields outside a node's declared
 * schema are dropped when the mock is persisted, so the agent sets a mock, the
 * call succeeds, and the run behaves as if no mock had been set at all.
 */
@DisplayName("mock_suggest resolves a generate node to its own schema, not the agent one")
class MockOutputSuggesterGenerateTest {

    // The registry is not reached by the resolution under test (it only reads the
    // key and the node's own type), so a null collaborator keeps the test on the
    // one decision it is about.
    private final MockOutputSuggester suggester = new MockOutputSuggester(null);

    private Map<String, Object> node(String type) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("label", "Make Clip");
        node.put("type", type);
        node.put("isAgent", true);
        return node;
    }

    private String resolve(String nodeId, Map<String, Object> node) throws Exception {
        var method = MockOutputSuggester.class
                .getDeclaredMethod("resolveSchemaNodeType", String.class, Map.class);
        method.setAccessible(true);
        return (String) method.invoke(suggester, nodeId, node);
    }

    @Test
    @DisplayName("a generate node keyed agent: resolves GENERATE, so the suggested mock has a file in it")
    void generateResolvesItsOwnSchema() throws Exception {
        assertThat(resolve("agent:make_clip", node("generate")))
                .as("read as AGENT the mock is the conversational skeleton, and every field of "
                        + "it is dropped at persist time because none is in the GENERATE schema")
                .isEqualTo("GENERATE");
    }

    @Test
    @DisplayName("the rest of the AI family is unchanged: agent, classify and guardrail still resolve as before")
    void theOtherAiTypesAreUntouched() throws Exception {
        assertThat(resolve("agent:router", node("classify"))).isEqualTo("CLASSIFY");
        assertThat(resolve("agent:safety", node("guardrail"))).isEqualTo("GUARDRAIL");
        assertThat(resolve("agent:writer", node("agent"))).isEqualTo("AGENT");
    }

    @Test
    @DisplayName("an AI node with no type still resolves AGENT, which is the family's ordinary member")
    void anUntypedAgentStillResolvesAgent() throws Exception {
        Map<String, Object> untyped = new LinkedHashMap<>();
        untyped.put("label", "Writer");
        untyped.put("isAgent", true);

        assertThat(resolve("agent:writer", untyped)).isEqualTo("AGENT");
    }

    /**
     * browser_agent gets its own schema too, and for the same reason.
     *
     * <p>It was answering AGENT beside generate, so the suggested mock was the
     * conversational skeleton (response / tokens_used / iterations / tool_calls),
     * none of which is in the BROWSER_AGENT schema. Fields outside the declared
     * schema are dropped when the mock is saved, so the mock silently did nothing:
     * the author sets one, the run ignores it, and nothing says why.
     */
    @Test
    @DisplayName("browser_agent gets its own schema, not the conversational one")
    void browserAgentGetsItsOwnSchema() throws Exception {
        assertThat(resolve("agent:browse", node("browser_agent")))
                .isEqualTo("BROWSER_AGENT");
    }
}
