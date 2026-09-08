package com.apimarketplace.orchestrator.tools.workflow.help;

import com.apimarketplace.orchestrator.services.generation.GenerationExecutionService;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Discovering a generation model must not require the tool that SPENDS.
 *
 * <p><b>The failure this exists for.</b> The {@code generation} tool is opt-in
 * per agent, deliberately: a create spends the customer's credits at the model's
 * own rate. But listing the models spends nothing, and it lived behind that same
 * gate. So an agent without the opt-in could not learn a single model id, and
 * {@code model} is the one required parameter of a generate node whose values
 * cannot be guessed. It could not build the node, and every message it read told
 * it to run the tool it did not have.
 *
 * <p>So the ids ride on the workflow help, which every builder holds. Spending
 * still requires the opt-in: this is a read, and free.
 */
@DisplayName("the generate help lists the models an agent can actually use")
class GenerateHelpListsTheModelsTest {

    private Map<String, Object> helpWith(List<Map<String, Object>> models) {
        return helpWith(models, Boolean.TRUE);
    }

    private Map<String, Object> helpWith(List<Map<String, Object>> models, Boolean served) {
        GenerationExecutionService generation = mock(GenerationExecutionService.class);
        when(generation.readModels()).thenReturn(
                new GenerationExecutionService.ModelCatalogue(models, served));
        return new WorkflowHelpProvider(
                mock(com.apimarketplace.orchestrator.service.NodeLibraryService.class),
                mock(com.apimarketplace.orchestrator.service.NodeHelpFormatter.class),
                generation)
                .getHelp("generate");
    }

    private static Map<String, Object> model(String id, String kind) {
        return model(id, kind, "platform_or_own_key");
    }

    private static Map<String, Object> model(String id, String kind, String runsOn) {
        return Map.of("model", id, "kind", kind, "label", id, "provider", "Vendor",
                "accepts", List.of("prompt", "voice"), "required", List.of("prompt"),
                "billedOn", "duration_seconds", "runsOn", runsOn);
    }

    @Test
    @DisplayName("names every model id, so a node can be built without the paid tool")
    void listsTheIds() {
        Object models = helpWith(List.of(model("eleven-v3", "voice"), model("flux-dev", "image")))
                .get("models");

        assertThat(models).isInstanceOf(List.class);
        assertThat((List<?>) models).hasSize(2);
        assertThat(((List<?>) models).toString()).contains("eleven-v3").contains("flux-dev");
    }

    @Test
    @DisplayName("carries what each model ACCEPTS, so the params can be written in one pass")
    void carriesTheAcceptedParams() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models =
                (List<Map<String, Object>>) helpWith(List.of(model("eleven-v3", "voice"))).get("models");

        // Without this an agent would have to guess which params the model takes,
        // and a param it does not accept is refused - free, but a wasted turn.
        assertThat(models.get(0).get("accepts")).isEqualTo(List.of("prompt", "voice"));
        assertThat(models.get(0).get("required")).isEqualTo(List.of("prompt"));
        assertThat(models.get(0)).containsKey("billed_on");
    }

    @Test
    @DisplayName("never orders the opt-in tool, which the reader may not hold")
    void doesNotOrderTheGatedTool() {
        String help = helpWith(List.of(model("eleven-v3", "voice"))).toString();

        // Mentioning it as an optional extra is fine; telling the agent to call
        // it, or telling it to tell someone else to, is not: that is what turned
        // a missing opt-in into an agent that simply could not proceed.
        // Unconditional: any spelling of an order to run the gated tool. Two
        // exact literals let "Run generation(action='models')" sail through.
        assertThat(help).doesNotContain("generation(action='models')");
    }

    @Test
    @DisplayName("an install that serves no generation says so, instead of inviting a guess")
    void anEmptyCatalogueIsStated() {
        Object models = helpWith(List.of()).get("models");

        // An id invented here would fail at run time on a node the author has
        // already wired in. Saying "there are none" is the truthful answer.
        assertThat(models).isInstanceOf(String.class);
        assertThat((String) models).contains("No generation model is available");
    }

    @Test
    @DisplayName("carries runs_on, the field that says whether the node can run unstated")
    void carriesRunsOn() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) helpWith(List.of(
                model("sold", "video", "platform_or_own_key"),
                model("byok", "voice", "own_key_only"),
                model("dunno", "image", "unknown"))).get("models");

        // An unstated node runs on the PLATFORM key, so this field is what
        // separates a node that works from one that fails on its first run.
        assertThat(models.get(0)).containsEntry("runs_on", "platform_or_own_key");
        assertThat(models.get(1)).containsEntry("runs_on", "own_key_only");
        // Never folded into one of the two claims: the check can fail to answer.
        assertThat(models.get(2)).containsEntry("runs_on", "unknown");
    }

    @Test
    @DisplayName("says an unstated node runs on the platform key, which is what the node does")
    void statesTheRealDefault() {
        String help = helpWith(List.of(model("m", "video"))).toString();

        // GenerateNode substitutes 'platform' before the run, so there is no
        // owner's-key-first fallback here. Claiming one sends an agent to omit
        // the field on a provider the platform does not resell, which fails.
        assertThat(help).contains("platform");
        assertThat(help).doesNotContain("tries the owner's own provider key FIRST");
    }

    @Test
    @DisplayName("regression: an unreachable catalogue is NOT reported as a feature that does not exist")
    void unreachableIsNotAbsent() {
        // Produced by a caught transport error. Read as "no models here, only an
        // administrator can change it", a three second blip tells the agent to
        // abandon a working feature.
        String models = (String) helpWith(List.of(), null).get("models");

        assertThat(models).contains("could not be read");
        assertThat(models).doesNotContain("Only an administrator");
    }

    @Test
    @DisplayName("an install that genuinely serves no generation still says so plainly")
    void disabledIsStated() {
        String models = (String) helpWith(List.of(), Boolean.FALSE).get("models");

        assertThat(models).contains("does not serve generation");
    }
}
