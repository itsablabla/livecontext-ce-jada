package com.apimarketplace.orchestrator.tools.workflow.help;

import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The pin help topic must state ONE rule.
 *
 * <p>When pin stopped requiring a prior run (2026-08-25), the topic was updated in three
 * places and left stating the old rule in two others - both of which an agent reads
 * BEFORE the correction. An agent following the stale half performs a real
 * {@code execute} on a production workflow, with real side effects, purely as a
 * pre-pin ritual that no longer exists. That is not a documentation nit: it is the
 * platform telling an agent to fire something.
 *
 * <p>So this test walks the WHOLE rendered topic - every nested string, whatever the
 * key - and fails on any sentence that reasserts the old contract. It is deliberately
 * blunt about phrasing rather than pinned to specific keys: the defect was that a
 * correction was added next to the stale text instead of replacing it, and a
 * key-scoped assertion would have missed it exactly the way the original edit did.
 */
@DisplayName("workflow(action='help', topics=['pin']) states one rule, not two")
class PinHelpStatesOneRuleTest {

    /**
     * Phrases that only make sense under the removed execute-then-pin contract.
     * Scoped to what the pin TOPIC can render - the identical sentence in
     * TriggerStepResponseBuilder is guarded by its own test, and listing it here would
     * make this denylist read broader than it is.
     */
    private static final String[] REMOVED_CONTRACT_PHRASES = {
        "you must run it at least once",
        "must already have at least one",
        "Run it once via action='execute'",
        "then retry pin",
    };

    private static void collectStrings(Object node, StringBuilder sink) {
        if (node instanceof String s) {
            sink.append(s).append('\n');
        } else if (node instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectStrings(v, sink));
        } else if (node instanceof Iterable<?> iterable) {
            iterable.forEach(v -> collectStrings(v, sink));
        }
    }

    private static String renderedPinHelp() {
        StringBuilder sink = new StringBuilder();
        // The pin topic is static text; the collaborators exist for other topics.
        WorkflowHelpProvider provider = new WorkflowHelpProvider(
                mock(com.apimarketplace.orchestrator.service.NodeLibraryService.class),
                mock(com.apimarketplace.orchestrator.service.NodeHelpFormatter.class),
                mock(com.apimarketplace.orchestrator.services.generation.GenerationExecutionService.class));
        collectStrings(provider.getHelp("pin"), sink);
        return sink.toString();
    }

    @Test
    @DisplayName("no sentence anywhere in the topic still demands a run before pinning")
    void pinHelpNeverDemandsAPriorRun() {
        String help = renderedPinHelp();
        assertThat(help).as("the pin topic should render some text").isNotBlank();

        for (String phrase : REMOVED_CONTRACT_PHRASES) {
            assertThat(help)
                .as("pin help still carries the removed execute-then-pin rule: \"%s\"", phrase)
                .doesNotContain(phrase);
        }
    }

    @Test
    @DisplayName("the topic says positively that a never-executed version is pinnable")
    void pinHelpStatesTheCurrentRule() {
        String help = renderedPinHelp();
        // Stated positively, because an agent cannot discover by trying that the
        // requirement was dropped - it can only read that it was.
        assertThat(help).containsIgnoringCase("never executed");
        assertThat(help).containsIgnoringCase("pin prepares the production run");
    }
}
