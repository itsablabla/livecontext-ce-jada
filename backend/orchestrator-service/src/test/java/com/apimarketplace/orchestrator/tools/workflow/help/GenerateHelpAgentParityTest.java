package com.apimarketplace.orchestrator.tools.workflow.help;

import com.apimarketplace.orchestrator.services.generation.GenerationExecutionService;
import com.apimarketplace.orchestrator.services.generation.GenerationExecutionService.ModelCatalogue;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An agent must be able to build the same node a person can, from one call.
 *
 * <p>The generate inspector shows, for the model being considered, the values
 * each parameter allows, what each file slot is FOR, and what a run will cost.
 * This help topic is the only place an agent can learn the same things: the
 * generation tool carries them too, but it is opt-in per agent because creating
 * spends credits, so most agents do not hold it.
 *
 * <p>Withholding them does not fail loudly. A parameter with a closed
 * vocabulary is guessed at and refused (for free, but a round trip the builder
 * never makes); a file slot is filled with the wrong asset and the mistake is
 * only visible in the result; and a price nobody can quote is a run charged
 * without warning. So the assertion is that the row carries them, not that the
 * payload is worded a particular way.
 */
@DisplayName("generate help gives an agent what the inspector shows")
class GenerateHelpAgentParityTest {

    /** One catalogue row shaped like the models endpoint's, which is where these come from. */
    private Map<String, Object> modelRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("model", "seedance-2.0-fast");
        row.put("kind", "video");
        row.put("label", "Seedance 2.0 Fast");
        row.put("provider", "seedance");
        row.put("accepts", List.of("prompt", "duration_seconds", "aspect_ratio", "input_image"));
        row.put("required", List.of("prompt"));
        row.put("billedOn", "duration_seconds");
        row.put("runsOn", "platform_or_own_key");
        row.put("limits", Map.of("aspect_ratio", Map.of("allowed", List.of("16:9", "9:16"))));
        row.put("inputs", Map.of("input_image", Map.of("role", "first_frame", "maxItems", 1)));
        row.put("price", Map.of("unit", "second", "unitCredits", "60", "baseCredits", "0"));
        return row;
    }

    private Map<String, Object> helpWith(Map<String, Object> row) {
        GenerationExecutionService generation = mock(GenerationExecutionService.class);
        when(generation.readModels()).thenReturn(new ModelCatalogue(List.of(row), Boolean.TRUE));
        WorkflowHelpProvider provider = new WorkflowHelpProvider(
                mock(com.apimarketplace.orchestrator.service.NodeLibraryService.class),
                mock(com.apimarketplace.orchestrator.service.NodeHelpFormatter.class),
                generation);
        return provider.getHelp("generate");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> onlyModel(Map<String, Object> help) {
        Object models = help.get("models");
        assertThat(models)
                .as("a non-empty catalogue must be reported as rows, not as a sentence explaining "
                        + "why there are none")
                .isInstanceOf(List.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) models;
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    @DisplayName("carries the allowed values of a parameter, so a closed vocabulary is not guessed at")
    void carriesTheLimits() {
        Map<String, Object> row = onlyModel(helpWith(modelRow()));

        assertThat(row)
                .as("the inspector renders aspect_ratio as a list of two values; an agent given only "
                        + "the parameter NAME can do no better than guess and be refused")
                .containsKey("limits");
        assertThat(row.get("limits").toString()).contains("16:9");
    }

    @Test
    @DisplayName("carries what each file slot is for, since the slot name does not say")
    void carriesTheInputRoles() {
        Map<String, Object> row = onlyModel(helpWith(modelRow()));

        assertThat(row)
                .as("'input_image' says an image goes here; it does not say the image becomes the "
                        + "clip's FIRST FRAME rather than a style reference, and filling it wrong "
                        + "costs a paid call")
                .containsKey("inputs");
        assertThat(row.get("inputs").toString()).contains("first_frame");
    }

    @Test
    @DisplayName("carries the rate, so the cost of a run can be stated before it is charged")
    void carriesThePrice() {
        Map<String, Object> row = onlyModel(helpWith(modelRow()));

        assertThat(row)
                .as("every successful run is charged and the amount scales with a parameter the "
                        + "caller chooses, so a rate nobody can quote is a bill nobody can predict")
                .containsKey("price");
        assertThat(row.get("price").toString()).contains("60");
    }

    @Test
    @DisplayName("omits what the catalogue did not send, rather than inventing an empty shape")
    void omitsWhatIsAbsent() {
        // A model with no file slots and no closed vocabulary is ordinary. An
        // empty `limits: {}` on it reads as "checked, and nothing is allowed",
        // which is the opposite of the truth.
        Map<String, Object> bare = modelRow();
        bare.remove("limits");
        bare.remove("inputs");

        Map<String, Object> row = onlyModel(helpWith(bare));

        assertThat(row).doesNotContainKey("limits").doesNotContainKey("inputs");
        assertThat(row).as("the fields the catalogue did send are unaffected").containsKey("price");
    }

    @Test
    @DisplayName("no longer sends the reader to the opt-in tool for the limits it now carries")
    void doesNotDeferToTheOptInTool() {
        Map<String, Object> help = helpWith(modelRow());

        // The sentence that used to say the limits live behind action='models'.
        // Left in place it tells an agent to reach for a tool it probably does
        // not hold, to fetch something it was just handed.
        assertThat(help.toString())
                .as("the help must not point at the generation tool for per-model limits it now includes")
                .doesNotContain("per-model limits");
    }
}
