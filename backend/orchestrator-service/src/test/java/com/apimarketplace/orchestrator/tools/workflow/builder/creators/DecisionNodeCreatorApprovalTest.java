package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the approval {@code contextTemplate} passthrough: the agent's
 * {@code add_node type='approval'} path hand-builds the approval config map and previously
 * dropped {@code contextTemplate} (only approverRoles/requiredApprovals/timeoutMs were kept),
 * even though the new prompt advertises it and CoreValidator warns when it is missing. Pre-fix
 * the first test fails: the created core's approval config has no {@code contextTemplate} key.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DecisionNodeCreator - add_node approval contextTemplate passthrough")
class DecisionNodeCreatorApprovalTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ResponseOptimizer responseOptimizer;

    private DecisionNodeCreator creator;
    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        creator = new DecisionNodeCreator(sessionStore, responseOptimizer);
        session = WorkflowBuilderSession.builder()
            .sessionId("s")
            .tenantId("t")
            .workflowName("w")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("label", "Start");
        trig.put("id", "trigger:start");
        trig.put("type", "webhook");
        session.getTriggers().add(trig);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstApprovalConfig() {
        Map<String, Object> node = session.getCores().get(0);
        return (Map<String, Object>) node.get("approval");
    }

    @Test
    @DisplayName("contextTemplate is persisted in the approval config")
    void contextTemplatePersisted() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Manager Review");
        p.put("contextTemplate", "Approve refund of {{trigger:start.output.amount}}?");

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstApprovalConfig().get("contextTemplate"))
            .isEqualTo("Approve refund of {{trigger:start.output.amount}}?");
    }

    @Test
    @DisplayName("context_template snake_case alias is accepted")
    void contextTemplateSnakeCaseAlias() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Manager Review");
        p.put("context_template", "Please review the request");

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstApprovalConfig().get("contextTemplate")).isEqualTo("Please review the request");
    }

    @Test
    @DisplayName("no contextTemplate -> approval config omits the key, node still created (soft-required)")
    void noContextTemplateOmitted() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Manager Review");

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstApprovalConfig()).doesNotContainKey("contextTemplate");
    }

    @Test
    @DisplayName("delegation map is passed through verbatim into the approval config")
    @SuppressWarnings("unchecked")
    void delegationPassedThroughToApprovalConfig() {
        Map<String, Object> delegation = new LinkedHashMap<>();
        delegation.put("channel", "telegram");
        delegation.put("credentialId", 42);
        delegation.put("chatId", "{{trigger:start.output.chat_id}}");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Manager Review");
        p.put("delegation", delegation);

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        assertThat(r.success()).isTrue();
        Map<String, Object> stored = (Map<String, Object>) firstApprovalConfig().get("delegation");
        assertThat(stored)
            .containsEntry("channel", "telegram")
            .containsEntry("credentialId", 42);
    }

    @Test
    @DisplayName("delegation is echoed in the agent-visible saved_params")
    @SuppressWarnings("unchecked")
    void delegationEchoedInSavedParams() {
        Map<String, Object> delegation = new LinkedHashMap<>();
        delegation.put("channel", "telegram");
        delegation.put("credentialId", 42);
        delegation.put("chatId", "123456");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Manager Review");
        p.put("delegation", delegation);

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        assertThat(r.success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) r.data();
        Map<String, Object> savedParams = (Map<String, Object>) data.get("saved_params");
        assertThat((Map<String, Object>) savedParams.get("delegation"))
            .as("the agent only learns what was stored from saved_params; it must echo delegation")
            .containsEntry("channel", "telegram");
    }

    @Test
    @DisplayName("regression: no delegation param -> approval config and saved_params omit the key")
    @SuppressWarnings("unchecked")
    void noDelegationOmitsKey() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Manager Review");

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstApprovalConfig()).doesNotContainKey("delegation");
        Map<String, Object> data = (Map<String, Object>) r.data();
        Map<String, Object> savedParams = (Map<String, Object>) data.get("saved_params");
        assertThat(savedParams).doesNotContainKey("delegation");
    }

    @Test
    @DisplayName("an empty delegation map is treated as absent (no delegation key stored)")
    void emptyDelegationMapTreatedAsAbsent() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Manager Review");
        p.put("delegation", new LinkedHashMap<>());

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstApprovalConfig()).doesNotContainKey("delegation");
    }

    @Test
    @DisplayName("regression: numeric-string credentialId (LLM-quoted \"40\") is coerced to a number at creation")
    @SuppressWarnings("unchecked")
    void numericStringCredentialIdCoerced() {
        // Pre-fix the string rode verbatim into the session: validate warned
        // NO_CREDENTIAL and the plan parser silently dropped the id at run time
        // (delegation configured, no Telegram message ever sent).
        Map<String, Object> delegation = new LinkedHashMap<>();
        delegation.put("channel", "telegram");
        delegation.put("credentialId", "40");
        delegation.put("chatId", "123456");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Manager Review");
        p.put("delegation", delegation);

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        assertThat(r.success()).isTrue();
        Map<String, Object> stored = (Map<String, Object>) firstApprovalConfig().get("delegation");
        assertThat(stored.get("credentialId")).isEqualTo(40L);
    }

    @Test
    @DisplayName("non-numeric credentialId string is left as-is for the validator to flag")
    @SuppressWarnings("unchecked")
    void nonNumericCredentialIdLeftForValidator() {
        Map<String, Object> delegation = new LinkedHashMap<>();
        delegation.put("channel", "telegram");
        delegation.put("credentialId", "not-a-number");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Manager Review");
        p.put("delegation", delegation);

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        assertThat(r.success()).isTrue();
        Map<String, Object> stored = (Map<String, Object>) firstApprovalConfig().get("delegation");
        assertThat(stored.get("credentialId")).isEqualTo("not-a-number");
    }

    /**
     * The half of the parity contract that lives in the creator. {@code PARAM_ALIASES} promises
     * add_node accepts these spellings; only a call through {@code executeAddApproval} proves the
     * value actually lands in the approval config instead of being accepted and dropped, which
     * the parity guard's own javadoc calls the dangerous direction. Delete the
     * {@code parameters.get("timeoutMs")} line in the creator and the alias tests still pass;
     * these fail.
     */
    @Test
    @DisplayName("mutation guard: timeoutMs camelCase alias reaches the approval config, it is not dropped")
    void timeoutMsAliasReachesConfig() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Manager Review");
        p.put("timeoutMs", 3600000);

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstApprovalConfig().get("timeoutMs"))
            .as("the creator reads timeoutMs, so the value must override the 24h default")
            .isEqualTo(3600000L);
    }

    @Test
    @DisplayName("bare timeout alias reaches the approval config")
    void timeoutAliasReachesConfig() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Manager Review");
        p.put("timeout", 7200000);

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        assertThat(r.success()).isTrue();
        assertThat(firstApprovalConfig().get("timeoutMs")).isEqualTo(7200000L);
    }

    @Test
    @DisplayName("approverRoles and roles aliases both reach the approval config")
    void roleAliasesReachConfig() {
        Map<String, Object> camel = new LinkedHashMap<>();
        camel.put("label", "Camel Review");
        camel.put("approverRoles", java.util.List.of("manager"));
        assertThat(creator.executeAddApproval(session, camel).success()).isTrue();
        assertThat(firstApprovalConfig().get("approverRoles")).isEqualTo(java.util.List.of("manager"));

        Map<String, Object> bare = new LinkedHashMap<>();
        bare.put("label", "Bare Review");
        bare.put("roles", java.util.List.of("admin"));
        assertThat(creator.executeAddApproval(session, bare).success()).isTrue();
        assertThat(approvalConfigOf("Bare Review").get("approverRoles")).isEqualTo(java.util.List.of("admin"));
    }

    @Test
    @DisplayName("requiredApprovals camelCase alias reaches the approval config")
    void requiredApprovalsAliasReachesConfig() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Manager Review");
        p.put("requiredApprovals", 3);

        assertThat(creator.executeAddApproval(session, p).success()).isTrue();
        assertThat(firstApprovalConfig().get("requiredApprovals")).isEqualTo(3);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> approvalConfigOf(String label) {
        return (Map<String, Object>) session.getCores().stream()
            .filter(n -> label.equals(n.get("label")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no core node labelled " + label))
            .get("approval");
    }
}
