package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code workflow(action='set_plan')} validation of the {@code generate} node type.
 *
 * <p>Generate belongs to the AI family: it travels in the plan's {@code agents}
 * array, keyed {@code agent:<label>}, beside agent, classify and guardrail.
 *
 * <p>Without the {@code case "generate"} the exporter rejects every such plan as
 * an unknown type even though add_node creates the node and the engine runs it
 * (the same regression class public_link and media each hit).
 *
 * <p>The validation is deliberately thin: a model must be named, and the
 * credential source must be one of the two real pools. Which parameters that
 * model accepts, and their bounds, are the generation catalog's answer, given
 * before the provider is called and therefore before anything is charged, so
 * re-checking them here could only produce a second, staler answer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderPlanExporter - set_plan validation of the generate node type")
class WorkflowBuilderPlanExporterGenerateValidationTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ToolSchemaFetcher toolSchemaFetcher;

    private WorkflowBuilderPlanExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new WorkflowBuilderPlanExporter(sessionStore, toolSchemaFetcher);
    }

    private WorkflowBuilderSession newSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Make a clip")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private Map<String, Object> manualTrigger() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("label", "start");
        t.put("type", "manual");
        return t;
    }

    private Map<String, Object> generateAgent(Map<String, Object> params) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("id", "g1");
        agent.put("type", "generate");
        agent.put("label", "Make Clip");
        if (params != null) {
            agent.put("params", new LinkedHashMap<>(params));
        }
        return agent;
    }

    private ToolExecutionResult setPlan(WorkflowBuilderSession session, List<Map<String, Object>> agents) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(manualTrigger())));
        plan.put("agents", new ArrayList<>(agents));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("plan", plan);
        return exporter.executeSetPlan(session, parameters);
    }

    /**
     * The imported node, read from where the AI family lives.
     *
     * <p>A generate node left among the session's cores is exported back into
     * {@code cores[]}, and the executor builds no generate node from there: the
     * run then reports a step that never existed rather than an error.
     */
    private Map<String, Object> importedGenerate(WorkflowBuilderSession session) {
        assertThat(session.getCores()).isEmpty();
        return session.getMcps().stream()
                .filter(n -> "generate".equals(n.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no generate node landed in the session"));
    }

    @Test
    @DisplayName("a generate node with a model passes validation and lands in the session")
    void generateWithModelImportsSuccessfully() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(generateAgent(Map.of(
                        "model", "seedance-2.0-fast",
                        "prompt", "a paper boat in a rain gutter",
                        "duration_seconds", 5))));

        assertThat(result.success())
                .as("a well-formed generate core must be accepted, got: " + result.error())
                .isTrue();
        Map<String, Object> imported = importedGenerate(session);
        assertThat(imported.get("type")).isEqualTo("generate");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) imported.get("params");
        assertThat(params)
                .containsEntry("model", "seedance-2.0-fast")
                .containsEntry("duration_seconds", 5);
    }

    @Test
    @DisplayName("a generate node WITHOUT a params map is rejected with the dedicated model error, not Unknown type")
    void generateWithoutParamsRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, List.of(generateAgent(null)));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'model'");
        assertThat(result.error()).doesNotContain("Unknown type");
    }

    @Test
    @DisplayName("a blank model is rejected, and the error says how to find a real model id")
    void blankModelRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(generateAgent(Map.of("model", "   ", "prompt", "hello"))));

        assertThat(result.success()).isFalse();
        // Points at the FREE read every builder holds, and must never order the
        // generation tool: that one is opt-in per agent because creating spends
        // credits, so an agent without it was being told, at the exact moment it
        // was stuck, to run something it cannot run.
        assertThat(result.error()).contains("workflow(action='help', topics=['generate'])");
        assertThat(result.error()).doesNotContain("generation(action='models')");
    }

    @Test
    @DisplayName("an unknown credential_source is rejected, naming both real pools")
    void unknownCredentialSourceRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(generateAgent(Map.of("model", "seedance-2.0-fast", "credential_source", "borrowed"))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'platform'").contains("'user'");
    }

    @Test
    @DisplayName("a credential_id that is not an id is rejected, rather than silently running on another key")
    void unusableCredentialIdRejected() {
        // The executor reads an unusable id as "no pin" and falls back to the
        // owner's default key WITHOUT saying so, so a plan that appears to name
        // a key would quietly run on a different one. Refusing at build time is
        // what keeps the plan and the run telling the same story.
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, List.of(generateAgent(Map.of(
                "model", "seedance-2.0-fast", "credential_source", "user", "credential_id", "not-an-id"))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("credential_id");
        // The message has to say what to DO, and the answer is never "pick one":
        // no action here lists the owner's keys.
        assertThat(result.error()).contains("cannot choose one");
    }

    @Test
    @DisplayName("a real credential_id is accepted and KEPT, so an agent can rewrite a node without dropping the owner's choice")
    void aRealCredentialIdSurvives() {
        // Asserting only that the import succeeded would pass against an
        // exporter that quietly deleted the field, which is exactly the
        // regression the surrounding work exists to prevent: the node would run
        // on the account's default key and the plan would no longer say
        // otherwise.
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, List.of(generateAgent(Map.of(
                "model", "seedance-2.0-fast", "credential_source", "user", "credential_id", 42))));

        assertThat(result.success()).isTrue();
        Object params = importedGenerate(session).get("params");
        assertThat(params).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> generateParams = (Map<String, Object>) params;
        assertThat(generateParams).containsEntry("credential_id", 42);
    }

    /**
     * The four writers of this field have to agree.
     *
     * <p>{@code add_node} refuses a pinned key beside the platform pool, because
     * the executor discards it there: the plan would name a key no run ever
     * reads. Accepting it here let an agent write, through set_plan, a node the
     * other tool would have refused - and the run then behaves as if the pin was
     * never there, silently.
     */
    @Test
    @DisplayName("a pinned key beside the PLATFORM pool is refused here too, as add_node refuses it")
    void aPinnedKeyBesideThePlatformPoolIsRefused() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, List.of(generateAgent(Map.of(
                "model", "seedance-2.0-fast", "credential_source", "platform", "credential_id", 42))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("credential_id");
        // The message has to name both ways out, since either is legitimate.
        assertThat(result.error()).contains("user");
    }

    @Test
    @DisplayName("the same pin beside the OWNER pool is accepted, which is the arrangement it describes")
    void theSamePinBesideTheUserPoolIsAccepted() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, List.of(generateAgent(Map.of(
                "model", "seedance-2.0-fast", "credential_source", "user", "credential_id", 42))));

        assertThat(result.success())
                .as("refusing this would make the pin unusable everywhere, got: " + result.error())
                .isTrue();
    }

    @Test
    @DisplayName("a parameter the exporter knows nothing about is ACCEPTED: the catalog is what judges it")
    void unknownGenerationParameterIsAccepted() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(generateAgent(Map.of("model", "seedance-2.0-fast", "some_new_dimension", "v"))));

        assertThat(result.success())
                .as("rejecting here would block a dimension a newer seed added, for a model that accepts it")
                .isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) importedGenerate(session).get("params");
        assertThat(params).containsEntry("some_new_dimension", "v");
    }
    /**
     * The agent-type switch has to name every type it accepts.
     *
     * <p>Found while moving generate into this family: {@code browser_agent} was
     * missing from it, so every plan carrying one was refused by set_plan as an
     * unknown agent type, while add_node created it and the engine ran it. The
     * error named a type the author had used correctly, which is the worst kind
     * of refusal: nothing in it says what to change.
     */
    @Test
    @DisplayName("a browser_agent is a valid agent type, not an unknown one")
    void browserAgentIsAValidType() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> browserAgent = new LinkedHashMap<>();
        browserAgent.put("id", "b1");
        browserAgent.put("type", "browser_agent");
        browserAgent.put("label", "Browse");
        browserAgent.put("prompt", "open the page");

        ToolExecutionResult result = setPlan(session, List.of(browserAgent));

        assertThat(result.success())
                .as("a browser agent must be accepted, got: " + result.error())
                .isTrue();
    }

    @Test
    @DisplayName("an agent type nobody serves is still refused, so the list is a list and not a rubber stamp")
    void anUnknownAgentTypeIsRefused() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> odd = new LinkedHashMap<>();
        odd.put("id", "x1");
        odd.put("type", "telepath");
        odd.put("label", "Guess");

        ToolExecutionResult result = setPlan(session, List.of(odd));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("telepath");
    }

    /**
     * A generate node written into {@code cores} is refused, and the refusal
     * says where it belongs.
     *
     * <p>The forty-name "unknown type" list this used to fall into never
     * mentions the answer, because the answer is not a type: it is a different
     * ARRAY. An agent reading that list has no way to work out that the node it
     * just wrote is fine and only in the wrong place.
     *
     * <p>Accepted instead, the plan would import a node nothing builds from,
     * and the run would report COMPLETED with it and everything after it
     * absent.
     */
    @Test
    @DisplayName("a generate node among the cores is refused, naming the array it belongs in")
    void aGenerateCoreIsRefusedWithTheMove() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> generate = new LinkedHashMap<>();
        generate.put("id", "core:make_clip");
        generate.put("type", "generate");
        generate.put("label", "Make Clip");
        generate.put("params", Map.of("model", "seedance-2.0-fast"));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(manualTrigger())));
        plan.put("cores", new ArrayList<>(List.of(generate)));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("plan", plan);

        ToolExecutionResult result = exporter.executeSetPlan(session, parameters);

        assertThat(result.success()).isFalse();
        String message = String.valueOf(result.error());
        assertThat(message)
                .as("the refusal has to name the array, since the type is not what is wrong")
                .contains("agents");
        assertThat(message)
                .as("and the key the node then carries, which every reference to it must use")
                .contains("agent:<label>");
        assertThat(message)
                .as("it must not be reported as an unknown type: the type is known and correct")
                .doesNotContain("Unknown type");
    }

    @Test
    @DisplayName("an actually unknown core type still gets the type list, so the branch is not a blanket one")
    void anUnknownCoreTypeStillGetsTheList() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> bogus = new LinkedHashMap<>();
        bogus.put("id", "core:whatever");
        bogus.put("type", "not_a_node_type");
        bogus.put("label", "Whatever");

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(manualTrigger())));
        plan.put("cores", new ArrayList<>(List.of(bogus)));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("plan", plan);

        ToolExecutionResult result = exporter.executeSetPlan(session, parameters);

        assertThat(result.success()).isFalse();
        assertThat(String.valueOf(result.error())).contains("Unknown type");
    }

    /**
     * A pin beside an UNSTATED source is refused: unstated means platform.
     *
     * <p>The guard used to test for the literal "platform", so a plan that simply
     * omitted the field kept a pin no run would ever read: the executor
     * substitutes the platform key, discards the id, and the plan is left naming
     * a key nothing uses with nothing in the result saying so.
     */
    @Test
    @DisplayName("a pinned key with no credential_source at all is refused, since unstated means platform")
    void aPinWithNoSourceIsRefused() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", "seedance-2.0-fast");
        params.put("credential_id", 42);

        ToolExecutionResult result = setPlan(session, List.of(generateAgent(params)));

        assertThat(result.success()).isFalse();
        assertThat(String.valueOf(result.error())).contains("credential_id");
    }

    /**
     * And an invalid source draws ONE error, about the source.
     *
     * <p>Both fired before, so an author who typed a bad source was also told
     * that the platform pool never consults their key: a pool they had not
     * chosen, and a fix ("set credential_source to user") that is not the defect.
     */
    @Test
    @DisplayName("an invalid credential_source does not also draw the platform-pool error")
    void anInvalidSourceDoesNotDrawThePinError() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", "seedance-2.0-fast");
        params.put("credential_source", "whatever");
        params.put("credential_id", 42);

        ToolExecutionResult result = setPlan(session, List.of(generateAgent(params)));

        assertThat(result.success()).isFalse();
        String message = String.valueOf(result.error());
        assertThat(message).contains("credential_source");
        assertThat(message)
                .as("naming a pool the author never chose sends them at the wrong value")
                .doesNotContain("never consults");
    }
}
