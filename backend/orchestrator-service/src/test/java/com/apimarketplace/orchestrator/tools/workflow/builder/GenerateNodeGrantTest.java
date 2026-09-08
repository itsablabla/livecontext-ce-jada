package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.config.AgentModuleResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adding a generate node spends the customer's credits, so it answers to the
 * same grant the generation tool does.
 *
 * <p>The tool is opt-in because a paid provider is called. Adding the node was
 * not, and building workflows is on by default for every agent, so an agent
 * refused the tool could add the node, run the workflow, and spend exactly the
 * same money with no grant anywhere. The grant is enforced in six places on the
 * chat side; this was the door left open.
 *
 * <p>These pin the RULE, on the credentials a tool call actually carries. The
 * wiring that puts the grant there is covered where it is written (the CLI
 * session and the chat context builder).
 */
@DisplayName("a generate node answers to the generation grant")
class GenerateNodeGrantTest {

    private static final String KEY = AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY;

    @Nested
    @DisplayName("a plan that writes the node directly answers to the same grant")
    class SetPlanCarriesTheSameGate {

        private List<Object> nodes(Object... types) {
            List<Object> list = new java.util.ArrayList<>();
            for (Object t : types) {
                list.add(t == null ? "not a map" : Map.of("type", t, "label", "x"));
            }
            return list;
        }

        /**
         * A plan filing the node where it actually lives.
         *
         * <p>Generate belongs to the AI family, so a real plan carries it under
         * {@code agents}. A gate that only walked {@code cores} would let every
         * such plan past: the node would be created, the provider called, and
         * the customer charged, on a build that was never granted generation.
         */
        private Map<String, Object> planWith(Object... agentTypes) {
            return Map.of("plan", Map.of("agents", nodes(agentTypes)));
        }

        /**
         * The other bucket, still scanned.
         *
         * <p>Plans are hand-written by agents and older ones put the node in
         * {@code cores}. Whichever array it lands in, it is the same paid node,
         * so the gate reads both rather than trusting the writer to file it
         * correctly.
         */
        private Map<String, Object> planWithCores(Object... coreTypes) {
            return Map.of("plan", Map.of("cores", nodes(coreTypes)));
        }

        @Test
        @DisplayName("a generate node filed under cores is still recognised, since either shape creates it")
        void aGenerateFiledUnderCoresIsRecognised() {
            assertThat(WorkflowBuilderProvider.planCarriesAGenerateNode(
                    planWithCores("agent", "generate", "transform"))).isTrue();
        }

        @Test
        @DisplayName("a plan with neither bucket carrying one is not gated")
        void neitherBucketIsNotGated() {
            assertThat(WorkflowBuilderProvider.planCarriesAGenerateNode(
                    planWithCores("decision", "transform"))).isFalse();
        }

        @Test
        @DisplayName("a plan carrying a generate node is recognised, or the grant is reachable by "
                + "writing the node instead of adding it")
        void aGenerateNodeIsRecognised() {
            // add_node was gated and set_plan was not, though both create the
            // same node and spend the same money on the same provider. The
            // whole bypass was one documented tool call.
            assertThat(WorkflowBuilderProvider.planCarriesAGenerateNode(
                    planWith("agent", "generate", "transform"))).isTrue();
        }

        @Test
        @DisplayName("casing and padding do not dodge it, since the plan is hand-written")
        void spellingVariantsAreRecognised() {
            assertThat(WorkflowBuilderProvider.planCarriesAGenerateNode(planWith("  Generate  "))).isTrue();
        }

        @Test
        @DisplayName("a plan with no generate node is not gated, so ordinary building is untouched")
        void anOrdinaryPlanIsNotGated() {
            assertThat(WorkflowBuilderProvider.planCarriesAGenerateNode(
                    planWith("agent", "decision", "transform"))).isFalse();
        }

        /**
         * Built without running the constructor, so all thirty collaborators
         * stay null. That is the assertion: the grant is checked BEFORE any of
         * them is touched, so a refusal returns cleanly and a missing check
         * dies on the first null. Passing thirty nulls positionally would say
         * the same thing and be unreadable.
         */
        private WorkflowBuilderProvider providerWithNoCollaborators() {
            return org.mockito.Mockito.mock(WorkflowBuilderProvider.class,
                    org.mockito.Mockito.CALLS_REAL_METHODS);
        }

        private ToolExecutionContext contextGranting(List<String> modules) {
            ToolExecutionContext ctx = org.mockito.Mockito.mock(ToolExecutionContext.class);
            org.mockito.Mockito.when(ctx.credentials()).thenReturn(Map.of(KEY, modules));
            return ctx;
        }

        @Test
        @DisplayName("set_plan REFUSES a generate node when the grant is absent, with the same message "
                + "add_node gives")
        void setPlanRefusesWithoutTheGrant() {
            // The detector tests above prove the shape is recognised. This
            // proves the recognition is WIRED: remove the check from
            // delegatePlanImport and this dies on a null session manager
            // instead of returning a refusal.
            var result = providerWithNoCollaborators().delegatePlanImport(
                    planWith("generate"), "tenant-1",
                    contextGranting(List.of("catalog", "workflow")));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not set up to run generations");
        }

        @Test
        @DisplayName("and lets the same plan through once the grant is there, so the gate is not a wall")
        void setPlanProceedsWithTheGrant() {
            // With the grant, the guard returns null and the method walks on to
            // the session manager, which is null here: reaching it at all is the
            // proof that the gate did not stop it.
            assertThatThrownBy(() -> providerWithNoCollaborators().delegatePlanImport(
                    planWith("generate"), "tenant-1",
                    contextGranting(List.of("catalog", "workflow", "generation"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a malformed plan is not treated as a generation, because it creates no node either")
        void malformedShapesAreNotGated() {
            // This runs BEFORE validation, so anything can arrive. Refusing on a
            // shape that cannot produce a node would block plans that never
            // reach the money path.
            assertThat(WorkflowBuilderProvider.planCarriesAGenerateNode(Map.of())).isFalse();
            assertThat(WorkflowBuilderProvider.planCarriesAGenerateNode(null)).isFalse();
            assertThat(WorkflowBuilderProvider.planCarriesAGenerateNode(Map.of("plan", "a string"))).isFalse();
            assertThat(WorkflowBuilderProvider.planCarriesAGenerateNode(
                    Map.of("plan", Map.of("cores", "not a list")))).isFalse();
            assertThat(WorkflowBuilderProvider.planCarriesAGenerateNode(planWith((Object) null))).isFalse();
        }
    }

    @Nested
    @DisplayName("renaming a node into a generation answers to the same grant")
    class ModifyCarriesTheSameGate {

        @Test
        @DisplayName("a modify that flips a node to generate is recognised, or the gate is two calls away")
        void flippingTypeToGenerateIsRecognised() {
            // `type` is a writable top-level key, so gating creation alone left
            // the grant reachable in two ordinary calls: add a transform, then
            // rename it. Same node, same provider, same money.
            assertThat(WorkflowBuilderProvider.modifyTurnsANodeIntoAGeneration(
                    Map.of("node", "X", "params", Map.of("type", "generate")))).isTrue();
        }

        @Test
        @DisplayName("the legacy `changes` spelling is covered too, or gating one of them is the same hole")
        void theLegacySpellingIsCovered() {
            assertThat(WorkflowBuilderProvider.modifyTurnsANodeIntoAGeneration(
                    Map.of("node", "X", "changes", Map.of("type", "  Generate  ")))).isTrue();
        }

        @Test
        @DisplayName("an ordinary modify is untouched, so editing a workflow does not start asking permission")
        void anOrdinaryModifyIsNotGated() {
            assertThat(WorkflowBuilderProvider.modifyTurnsANodeIntoAGeneration(
                    Map.of("node", "X", "params", Map.of("prompt", "hello")))).isFalse();
            assertThat(WorkflowBuilderProvider.modifyTurnsANodeIntoAGeneration(
                    Map.of("node", "X", "params", Map.of("type", "transform")))).isFalse();
            assertThat(WorkflowBuilderProvider.modifyTurnsANodeIntoAGeneration(Map.of())).isFalse();
            assertThat(WorkflowBuilderProvider.modifyTurnsANodeIntoAGeneration(null)).isFalse();
        }

        @Test
        @DisplayName("modify REFUSES the flip when the grant is absent, and the refusal is WIRED")
        void modifyRefusesWithoutTheGrant() {
            // The predicate tests above prove the shape is recognised. Only this
            // proves the recognition is called: remove the guard from the modify
            // dispatch and every one of them stays green while the door reopens.
            // Same construction as the set_plan twin, and the same reason.
            var provider = org.mockito.Mockito.mock(WorkflowBuilderProvider.class,
                    org.mockito.Mockito.CALLS_REAL_METHODS);
            ToolExecutionContext ctx = org.mockito.Mockito.mock(ToolExecutionContext.class);
            org.mockito.Mockito.when(ctx.credentials())
                    .thenReturn(Map.of(KEY, List.of("catalog", "workflow")));

            var result = provider.delegateModify(
                    Map.of("node", "X", "params", Map.of("type", "generate")), "tenant-1", ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not set up to run generations");
        }

        @Test
        @DisplayName("editing an EXISTING generate node is not gated, since it creates nothing new")
        void editingAnExistingGenerationIsNotGated() {
            // Only the transition into a generation is a new grant question. A
            // node that already is one was gated when it was created, and
            // refusing to edit it would strand a workflow its owner can run.
            assertThat(WorkflowBuilderProvider.modifyTurnsANodeIntoAGeneration(
                    Map.of("node", "Make Clip", "params", Map.of("model", "seedance-2.0")))).isFalse();
        }
    }

    @Nested
    @DisplayName("when the caller states what it was granted")
    class GrantStated {

        @Test
        @DisplayName("an agent WITHOUT generation may not add the node")
        void withoutTheGrantItIsRefused() {
            Map<String, Object> credentials = Map.of(KEY,
                    List.of("catalog", "table", "workflow", "web_search"));

            assertThat(AgentModuleResolver.callerMayUse(credentials, "generation")).isFalse();
            assertThat(AgentModuleResolver.callerMayUse(credentials, "image_generation")).isFalse();
        }

        @Test
        @DisplayName("an agent WITH generation may, so the gate is not a wall")
        void withTheGrantItProceeds() {
            Map<String, Object> credentials = Map.of(KEY,
                    List.of("catalog", "workflow", "generation"));

            assertThat(AgentModuleResolver.callerMayUse(credentials, "generation")).isTrue();
        }

        /**
         * The image module is retired, so it can no longer stand in for the generation
         * grant at the node gate: a generate node spends the customer's credits exactly
         * as the generation tool does, and the retired grant never authorised video.
         */
        @Test
        @DisplayName("the retired image_generation module alone does NOT pass the generation gate")
        void theRetiredImageModuleIsNotEnough() {
            Map<String, Object> credentials = Map.of(KEY,
                    List.of("catalog", "workflow", "image_generation"));

            assertThat(AgentModuleResolver.callerMayUse(credentials, "generation")).isFalse();
        }
    }

    @Nested
    @DisplayName("when the caller says nothing")
    class GrantAbsent {

        @Test
        @DisplayName("silence is NOT a denial, or every caller older than this key stops working")
        void absenceIsNotDenial() {
            // Absence covers every caller that predates the key and every path
            // with no bound agent at all: a workflow fired by a schedule has no
            // agent to ask. Reading silence as "denied" would refuse work that
            // was always allowed, and it would be invisible until a customer
            // reported it.
            assertThat(AgentModuleResolver.callerMayUse(Map.of(), "generation")).isTrue();
            assertThat(AgentModuleResolver.callerMayUse(null, "generation")).isTrue();
        }

        @Test
        @DisplayName("a value of the wrong shape is silence too, not a grant to parse")
        void aNonListValueIsSilence() {
            assertThat(AgentModuleResolver.callerMayUse(Map.of(KEY, "generation"), "generation")).isTrue();
        }

        @Test
        @DisplayName("an EMPTY grant is a real statement and does deny")
        void anEmptyGrantDenies() {
            // A tool-less agent (mode=off) resolves to no modules at all. That
            // is a decision, not an absence, and it must be honoured or the
            // strictest configuration would be the most permissive.
            assertThat(AgentModuleResolver.callerMayUse(Map.of(KEY, List.of()), "generation")).isFalse();
        }
    }

    /**
     * The third bucket a plan can put the node in, and the one that builds it.
     *
     * <p>What decides whether a generate node is BUILT is the export step, not
     * the array the plan arrived in: import copies mcps across verbatim, and the
     * export files any entry carrying isAgent into the plan's agents. So an
     * entry written under mcps with isAgent and type generate is saved as an
     * agent and built as a real generate node, spending the customer's credits
     * on a paid provider.
     *
     * <p>The route was inert while the node was built from cores; moving it to
     * the AI family is what made it work, so the gate had to follow. Scanning
     * fewer buckets than the export reads is a grant that refuses the one-node
     * action while a whole plan carrying the same node walks past it.
     */
    @Test
    @DisplayName("a generate node hidden among the plan's mcps is seen, since that is where it is BUILT from")
    void aGenerateNodeAmongTheMcpsIsSeen() {
        Map<String, Object> hidden = new LinkedHashMap<>();
        hidden.put("id", "__wait__");
        hidden.put("label", "Sneaky");
        hidden.put("isAgent", true);
        hidden.put("type", "generate");
        hidden.put("params", Map.of("model", "seedance-2.0-fast"));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("mcps", List.of(hidden));

        assertThat(WorkflowBuilderProvider.planCarriesAGenerateNode(Map.of("plan", plan)))
                .as("the export files this entry under agents and the factory builds it")
                .isTrue();
    }

    @Test
    @DisplayName("an ordinary mcp step is not mistaken for one, so the gate does not refuse every plan")
    void anOrdinaryMcpStepIsNotSeen() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "gmail/send_email");
        step.put("label", "Notify");
        step.put("type", "mcp");

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("mcps", List.of(step));

        assertThat(WorkflowBuilderProvider.planCarriesAGenerateNode(Map.of("plan", plan)))
                .isFalse();
    }
}
