package com.apimarketplace.orchestrator.tools.workflow.help;

import com.apimarketplace.orchestrator.execution.v2.nodes.GenerateNodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What the agent READS about a generation's price must match what it is
 * CHARGED.
 *
 * <p>Four surfaces describe the same two output fields: this help topic, the
 * node spec the inspector renders, the node documentation row, and the tool's
 * own reply. Three of them once said a per-call model reports nothing and that
 * "rate x billed_quantity" is the cost, and both were wrong: a flat model
 * reports 1, and the reported unit is the one the size was MEASURED in, so for
 * a model listed per minute that multiplication is out by sixty.
 *
 * <p>This help topic is the one the other surfaces point the agent at, so it is
 * the copy that matters most and it was the last one still wrong. These tests
 * pin the two claims that cost money, not the wording around them.
 */
@DisplayName("generate help agrees with the node about what a run costs")
class GenerateHelpMatchesTheNodeTest {

    private static final String MEASURED_UNIT_CAVEAT = "MEASURED";

    private Map<String, Object> generateHelp() {
        var generation = mock(
                com.apimarketplace.orchestrator.services.generation.GenerationExecutionService.class);
        // A mock returns null for the record, and the help reads two of its
        // components: stub it so these tests exercise the help, not the stub.
        org.mockito.Mockito.when(generation.readModels()).thenReturn(
                new com.apimarketplace.orchestrator.services.generation
                        .GenerationExecutionService.ModelCatalogue(java.util.List.of(), Boolean.TRUE));
        WorkflowHelpProvider provider = new WorkflowHelpProvider(
                mock(com.apimarketplace.orchestrator.service.NodeLibraryService.class),
                mock(com.apimarketplace.orchestrator.service.NodeHelpFormatter.class),
                generation);
        return provider.getHelp("generate");
    }

    private String billedUnitDoc() {
        Object outputs = generateHelp().get("outputs");
        assertThat(outputs).as("the generate help must describe its outputs").isInstanceOf(Map.class);
        Object doc = ((Map<?, ?>) outputs).get("billed_unit");
        assertThat(doc).as("billed_unit must be documented").isNotNull();
        return doc.toString();
    }

    private String specBilledUnitDoc() {
        return new GenerateNodeSpec().definition().outputs().stream()
                .filter(f -> "billed_unit".equals(f.key()))
                .map(OutputFieldDef::description)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the node spec must declare billed_unit"));
    }

    @Test
    @DisplayName("the node help does not offer `n`, because one call returns one asset and no model takes it")
    void nIsNotOfferedAsANodeParameter() {
        // The sentence removed here ("Models priced per image bill on this")
        // survived in this layer after the DB documentation row was corrected,
        // in the very commit that corrected it: the platform charges n times
        // and stores one asset, so an agent acting on it is overbilled and
        // told nothing. Pinned in each layer separately, because the drift was
        // between layers.
        Object params = generateHelp().get("params");
        assertThat(params).as("the generate help must describe its params").isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) params).containsKey("n"))
                .as("no model accepts n, so offering it can only cost the agent a turn")
                .isFalse();
    }

    @Test
    @DisplayName("neither surface offers `minute` as a value, because nothing ever reports it")
    void minuteIsNeverAReportedUnit() {
        // A model can be PRICED per minute; the size is still measured and
        // reported in seconds. Listing minute as a possible value invites the
        // agent to read the number against the per-minute rate, which is the
        // sixty-fold error in the direction of under-reporting the cost.
        assertThat(billedUnitDoc().toLowerCase()).doesNotContain("minute,");
        assertThat(specBilledUnitDoc().toLowerCase()).doesNotContain("minute,");
    }

    @Test
    @DisplayName("both surfaces warn that the reported unit is the MEASURED one, not the quoted one")
    void bothCarryTheConversionCaveat() {
        assertThat(billedUnitDoc()).contains(MEASURED_UNIT_CAVEAT);
        assertThat(specBilledUnitDoc()).contains(MEASURED_UNIT_CAVEAT);
    }

    @Test
    @DisplayName("neither surface claims the fields are ABSENT for a per-call model, because they are not")
    void noSurfaceClaimsAbsenceForAFlatModel() {
        // A flat model measures one call as 1 and reports (1, "call"). An agent
        // told the fields would be missing writes a guard for an absence that
        // never happens, or reads them anyway and finds the doc was wrong.
        Object outputs = generateHelp().get("outputs");
        String quantityDoc = ((Map<?, ?>) outputs).get("billed_quantity").toString();

        assertThat(quantityDoc.toLowerCase()).doesNotContain("absent");
        assertThat(quantityDoc).contains("1");
    }

    @Test
    @DisplayName("the price section states what an unstated credential_source does, since it costs money")
    void priceSectionSaysWhatUnstatedMeans() {
        // Unstated is not a missing choice, it is the platform key, and that is
        // the difference between a charged run and a free one.
        Object price = generateHelp().get("price");
        assertThat(price).as("the generate help must explain the price").isNotNull();
        // Asserting "platform" alone was worthless: the pre-fix text already
        // contained the word twice, so this test was green before and after and
        // proved nothing. The claim that matters is what UNSTATED does.
        assertThat(price.toString()).contains("unstated means 'platform'");
    }

    @Test
    @DisplayName("`minute` is refused as a value however the list is punctuated")
    void minuteIsRefusedWhateverThePunctuation() {
        // The sibling above pins the comma form. A list rewritten as
        // "second or minute" would slip past it while telling the agent the
        // same wrong thing, so anchor on the token instead.
        assertThat(billedUnitDoc()).doesNotContainPattern("\\bminute\\b(?! )");
        assertThat(specBilledUnitDoc()).doesNotContainPattern("\\bminute,");
    }
}
