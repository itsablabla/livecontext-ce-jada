package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * {@code workflow(action='add_node', type='generate', ...)}.
 *
 * <p>The creator deliberately validates only what it can know without the
 * catalog: that a model was named and that the credential source is one of the
 * two real pools. Which parameters a given model accepts is the catalog's
 * answer, given before the provider is called and therefore before anything is
 * charged, so the creator must FORWARD unknown-to-it parameters rather than drop
 * them: dropping one would send a call the author did not configure.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UtilityNodeCreator - add_generate")
class UtilityNodeCreatorGenerateTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private WorkflowRepository workflowRepository;

    private UtilityNodeCreator creator;
    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        creator = new UtilityNodeCreator(sessionStore, responseOptimizer, nodeLibraryService, workflowRepository);
        session = WorkflowBuilderSession.builder()
            .sessionId("s")
            .tenantId("t")
            .workflowName("w")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", "Start");
        trigger.put("id", "trigger:start");
        trigger.put("type", "webhook");
        session.getTriggers().add(trigger);

        lenient().when(nodeLibraryService.findByType(anyString())).thenReturn(Optional.empty());
    }

    private static Map<String, Object> baseParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Make Clip");
        p.put("connect_after", "Start");
        p.put("model", "seedance-2.0-fast");
        return p;
    }

    /**
     * The AI nodes of a builder session live in {@code getMcps()}, discriminated
     * by {@code isAgent}: that is where agent, classify and guardrail are held,
     * and generate is one of them. A generate node left among the cores is
     * exported into {@code cores[]}, which the executor never builds a generate
     * node from.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> firstGenerateNode() {
        return session.getMcps().stream()
            .filter(n -> "generate".equals(n.get("type")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no generate node was added to the AI nodes"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstCoreParams() {
        return (Map<String, Object>) firstGenerateNode().get("params");
    }

    @Test
    @DisplayName("creates a core of type 'generate' with the config under params")
    void createsGenerateCore() {
        Map<String, Object> p = baseParams();
        p.put("prompt", "a paper boat in a rain gutter");
        p.put("duration_seconds", 5);

        ToolExecutionResult result = creator.executeAddGenerate(session, p);

        assertThat(result.success()).isTrue();
        assertThat(session.getCores()).isEmpty();
        Map<String, Object> core = firstGenerateNode();
        assertThat(core.get("type")).isEqualTo("generate");
        assertThat(core.get("isAgent")).isEqualTo(true);
        assertThat(core.get("isGenerate")).isEqualTo(true);
        assertThat(core.get("label")).isEqualTo("Make Clip");
        assertThat(firstCoreParams())
            .containsEntry("model", "seedance-2.0-fast")
            .containsEntry("prompt", "a paper boat in a rain gutter")
            .containsEntry("duration_seconds", 5);
    }

    @Test
    @DisplayName("missing model is refused, and the refusal says how to find a model id")
    void modelIsRequired() {
        Map<String, Object> p = baseParams();
        p.remove("model");

        ToolExecutionResult result = creator.executeAddGenerate(session, p);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        // Points at the FREE read every builder holds, and must never order the
        // generation tool: that one is opt-in per agent because creating spends
        // credits, so an agent without it was being told, at the exact moment it
        // was stuck, to run something it cannot run.
        assertThat(result.error()).contains("workflow(action='help', topics=['generate'])");
        assertThat(result.error()).doesNotContain("generation(action='models')");
        assertThat(session.getCores()).isEmpty();
    }

    @Test
    @DisplayName("the model id is normalised the same way the registry resolves it")
    void modelIsNormalised() {
        Map<String, Object> p = baseParams();
        p.put("model", "  Seedance-2.0-Fast  ");

        assertThat(creator.executeAddGenerate(session, p).success()).isTrue();
        assertThat(firstCoreParams().get("model")).isEqualTo("seedance-2.0-fast");
    }

    /**
     * WHICH of the owner's keys the node runs on, offered to the agent too.
     *
     * <p>The inspector has always been able to pin one, and the plan carries it.
     * An agent building the same node could not say it at all, so a workflow
     * written through the tools ran on the account default while the one built
     * by hand ran on the chosen key, for no reason the author could see.
     */
    @Test
    @DisplayName("a pinned credential_id is stored, so an agent can build the node the inspector builds")
    void credentialIdIsStored() {
        Map<String, Object> p = baseParams();
        p.put("credential_source", "user");
        p.put("credential_id", 42);

        ToolExecutionResult result = creator.executeAddGenerate(session, p);

        assertThat(result.success()).isTrue();
        assertThat(firstCoreParams()).containsEntry("credential_id", 42L);
    }

    @Test
    @DisplayName("a pin beside the PLATFORM key is refused rather than dropped, since no run could honour it")
    void credentialIdBesidePlatformIsRefused() {
        // The executor discards it on that branch, so accepting it would leave
        // the plan naming a key nothing uses.
        Map<String, Object> p = baseParams();
        p.put("credential_source", "platform");
        p.put("credential_id", 42);

        ToolExecutionResult result = creator.executeAddGenerate(session, p);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("credential_id");
    }

    @Test
    @DisplayName("a credential_id that is not an id is refused, not silently ignored")
    void credentialIdMustBeAnId() {
        Map<String, Object> p = baseParams();
        p.put("credential_source", "user");
        p.put("credential_id", "not-an-id");

        ToolExecutionResult result = creator.executeAddGenerate(session, p);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("credential_id");
    }

    @Test
    @DisplayName("an unknown credential_source is refused, naming both real pools")
    void credentialSourceMustBeUserOrPlatform() {
        Map<String, Object> p = baseParams();
        p.put("credential_source", "borrowed");

        ToolExecutionResult result = creator.executeAddGenerate(session, p);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ENUM_VALUE);
        assertThat(result.error()).contains("'platform'").contains("'user'");
        assertThat(session.getCores()).isEmpty();
    }

    @Test
    @DisplayName("credential_source 'user' is stored so the run uses the author's own key")
    void credentialSourceIsStored() {
        Map<String, Object> p = baseParams();
        p.put("credential_source", "USER");

        assertThat(creator.executeAddGenerate(session, p).success()).isTrue();
        assertThat(firstCoreParams().get("credential_source")).isEqualTo("user");
    }

    @Test
    @DisplayName("a parameter the creator does not know about is FORWARDED, not dropped")
    void unknownParametersAreForwarded() {
        // The vocabulary lives in the generation catalog. A local allow-list here
        // would silently drop a dimension the day a seed adds one, and the run
        // would then generate something the author did not ask for.
        Map<String, Object> p = baseParams();
        p.put("some_new_dimension", "value");

        assertThat(creator.executeAddGenerate(session, p).success()).isTrue();
        assertThat(firstCoreParams().get("some_new_dimension")).isEqualTo("value");
    }

    @Test
    @DisplayName("builder-only keys never leak into the node's params")
    void builderKeysAreNotGenerationParams() {
        Map<String, Object> p = baseParams();
        p.put("session_id", "s");
        p.put("type", "generate");

        assertThat(creator.executeAddGenerate(session, p).success()).isTrue();
        assertThat(firstCoreParams())
            .doesNotContainKey("label")
            .doesNotContainKey("connect_after")
            .doesNotContainKey("session_id")
            .doesNotContainKey("type");
    }

    @Test
    @DisplayName("the response tells the agent the node is charged per run and where to read the size billed")
    void responseWarnsAboutCost() {
        ToolExecutionResult result = creator.executeAddGenerate(session, baseParams());

        assertThat(result.success()).isTrue();
        String rendered = String.valueOf(result.data());
        assertThat(rendered).contains("charged");
        assertThat(rendered).contains("billed_quantity");
    }
}
