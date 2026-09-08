package com.apimarketplace.orchestrator.tools.workflow.help;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the agent is TOLD about which family the generate node belongs to.
 *
 * <p>An agent cannot look at the code. The only statement it gets about where a
 * node lives is this map, returned by {@code help(topics=['concepts'])} - and
 * the plan array it picks from that statement decides whether the node is built
 * at all. Filed under {@code cores} it is written into an array the executor
 * never builds from, and the run then reports fewer nodes than the plan holds,
 * with no error anywhere.
 *
 * <p>Pinned rather than left to prose review because this map and the code
 * disagreed for the whole of this change: the node had moved, the sentence had
 * not, and nothing failed.
 */
@DisplayName("the concepts help files generate with the AI family")
class GenerateNodeFamilyHelpTest {

    @SuppressWarnings("unchecked")
    private Map<String, String> nodeCategories() {
        // The family map lives in the OVERVIEW payload, which is what an agent gets
        // first and reads to decide where a node goes.
        Map<String, Object> concepts = ConceptsHelpProvider.getOverview();
        Object categories = concepts.get("nodeCategories");
        assertThat(categories).as("the concepts payload must carry the family map").isInstanceOf(Map.class);
        return (Map<String, String>) categories;
    }

    /**
     * The names between "AI nodes:" and the first full stop, as a set.
     *
     * <p>Parsed rather than substring-matched. A `contains("agent")` cannot
     * fail while `contains("browser_agent")` passes, and a `contains("agents")`
     * is answered by the phrase "'agents' array" further along the same
     * sentence. Both read like assertions and neither can catch a name going
     * missing, which is the only thing this line can get wrong.
     */
    private java.util.Set<String> familyMembers() {
        String agents = nodeCategories().get("agents");
        assertThat(agents).as("the AI family line must exist").isNotNull();
        int from = agents.indexOf(":");
        int to = agents.indexOf(".", from);
        assertThat(from).as("the line must name its members after a colon").isGreaterThan(-1);
        assertThat(to).as("the member list must end in a full stop").isGreaterThan(from);
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (String part : agents.substring(from + 1, to).split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    @Test
    @DisplayName("the AI family line names all five nodes and nothing else")
    void theFamilyLineNamesTheWholeFamily() {
        // Exactly, not "contains": the line was found naming three of the five
        // while generate was being moved, so an agent reading it had no way to
        // learn that browser_agent or generate belong here at all. An extra name
        // is just as wrong - it sends a node into an array that will not build it.
        assertThat(familyMembers())
            .containsExactlyInAnyOrder("agent", "browser_agent", "classify", "guardrail", "generate");
    }

    @Test
    @DisplayName("and it names the key and the plan array, which is what decides whether the node runs")
    void theLineNamesTheKeyAndTheArray() {
        String agents = nodeCategories().get("agents");

        // The family alone does not tell an agent where to WRITE the node, and
        // the array is what the executor builds from. Quoted, so this cannot be
        // satisfied by the word "agent" appearing in the member list.
        assertThat(agents).contains("'agents'");
        assertThat(agents).contains("agent:<label>");
    }

    @Test
    @DisplayName("generate is NOT listed among the control-flow nodes, which is the array it would be lost in")
    void generateIsNotACore() {
        assertThat(nodeCategories().get("cores"))
                .as("a node listed here is written into cores[], where nothing builds a generate node")
                .doesNotContain("generate");
    }
}
