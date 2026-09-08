package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.domain.workflow.CredentialSource;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the NODE does with a credential chosen at run time.
 *
 * <p>{@link StepCredentialSelectionTest} pins the decision itself; this pins the
 * code path that actually reaches the gateway, which is where a change can be
 * correct in the abstract and wrong in the run: the markers that leave the node,
 * the shape of the failure when the choice does not resolve, and the record of
 * which account served.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepNode - a credential chosen at run time")
class StepNodeCredentialSelectorTest {

    @Mock private WorkflowPlan plan;
    @Mock private ToolsGateway toolsGateway;
    @Mock private V2TemplateAdapter templateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("account", "Client B");
        context = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, triggerData, plan);
        lenient().when(plan.getId()).thenReturn("workflow-1");
        // Params resolution is not what these tests are about: hand back whatever
        // was asked for, so only the credential path varies between them.
        lenient().when(templateAdapter.resolveTemplates(anyMap(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(templateAdapter.hasUnresolvedTemplates(anyMap(), any())).thenReturn(false);
    }

    private StepNode node(String credentialSelector) {
        Step step = new Step("instagram/publish", "mcp", "Publish", null,
                Map.of("caption", "hello"), null, null, "node-1",
                null, CredentialSource.USER, null, credentialSelector);
        StepNode stepNode = new StepNode("node-1", step);
        stepNode.setToolsGateway(toolsGateway);
        stepNode.setTemplateAdapter(templateAdapter);
        return stepNode;
    }

    private Map<String, Object> capturedMarkers() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(toolsGateway).executeTool(any(ToolRef.class), anyMap(), anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("the resolved account travels to the gateway as a strict choice")
    void resolvedAccountReachesTheGateway() {
        when(templateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(invocation -> {
            Map<String, Object> asked = invocation.getArgument(0);
            Map<String, Object> answer = new HashMap<>(asked);
            if (asked.containsKey("__v__")) answer.put("__v__", "Client B");
            return answer;
        });
        when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                .thenReturn(new ExecutionResult(true, Map.of("ok", true), List.of(), List.of()));

        node("{{trigger.output.account}}").execute(context);

        assertThat(capturedMarkers())
                .containsEntry("__credentialSource__", "user")
                .containsEntry("__selectedCredentialName__", "Client B")
                .containsEntry("__credentialSelectionStrict__", true);
    }

    @Test
    @DisplayName("a step with no selector sends no run-time markers at all")
    void staticStepIsUnchanged() {
        when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                .thenReturn(new ExecutionResult(true, Map.of("ok", true), List.of(), List.of()));

        node(null).execute(context);

        Map<String, Object> markers = capturedMarkers();
        assertThat(markers).containsEntry("__credentialSource__", "user");
        assertThat(markers).doesNotContainKeys(
                "__selectedCredentialName__", "__credentialSelectionStrict__");
    }

    @Test
    @DisplayName("an unresolved selector fails the node and never calls the tool")
    void unresolvedSelectorFailsBeforeTheCall() {
        // The whole point: no call at all, rather than a call on the default
        // account that would come back 200.
        when(templateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(invocation -> {
            Map<String, Object> asked = invocation.getArgument(0);
            Map<String, Object> answer = new HashMap<>(asked);
            if (asked.containsKey("__v__")) answer.put("__v__", "");
            return answer;
        });

        NodeExecutionResult result = node("{{trigger.output.missing}}").execute(context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage().orElse("")).contains("Publish").contains("resolved to nothing");
        verify(toolsGateway, never()).executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap());
    }

    @Test
    @DisplayName("a failed selection is a node failure, not an exception the engine has to guess at")
    void failureIsReported() {
        when(templateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(invocation -> {
            Map<String, Object> asked = invocation.getArgument(0);
            Map<String, Object> answer = new HashMap<>(asked);
            if (asked.containsKey("__v__")) answer.put("__v__", "");
            return answer;
        });

        NodeExecutionResult result = node("{{trigger.output.missing}}").execute(context);

        // The output has to carry the resolved params like any other failure, or the
        // inspector panel goes blank exactly when someone is trying to debug it.
        assertThat(result.output()).isNotNull();
        assertThat(result.output()).containsKey("resolved_params");
    }

    @Test
    @DisplayName("the account that served is recorded on the step output")
    void recordsWhichAccountServed() {
        // Without this, "which account did this run use" is unanswerable after the
        // fact, and every silent-substitution case is undiagnosable.
        when(templateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(invocation -> {
            Map<String, Object> asked = invocation.getArgument(0);
            Map<String, Object> answer = new HashMap<>(asked);
            if (asked.containsKey("__v__")) answer.put("__v__", "Client B");
            return answer;
        });
        when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                .thenReturn(new ExecutionResult(true, Map.of("ok", true), List.of(), List.of()));

        NodeExecutionResult result = node("{{trigger.output.account}}").execute(context);

        @SuppressWarnings("unchecked")
        Map<String, Object> selection =
                (Map<String, Object>) result.output().get("credential_selection");
        assertThat(selection)
                .containsEntry("selector", "{{trigger.output.account}}")
                .containsEntry("resolved_credential_name", "Client B");
    }

    @Test
    @DisplayName("a static step adds nothing to its output, so existing runs look identical")
    void staticStepOutputIsUnchanged() {
        when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                .thenReturn(new ExecutionResult(true, Map.of("ok", true), List.of(), List.of()));

        NodeExecutionResult result = node(null).execute(context);

        assertThat(result.output()).doesNotContainKey("credential_selection");
    }

    @Test
    @DisplayName("analytics attribution markers ride beside the billing ones, never through the billing step key")
    void attributionMarkersReachTheGateway() {
        when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                .thenReturn(new ExecutionResult(true, Map.of("ok", true), List.of(), List.of()));

        node(null).execute(context);

        Map<String, Object> markers = capturedMarkers();
        org.junit.jupiter.api.Assertions.assertEquals("workflow-1", markers.get("__workflowId__"),
                "__workflowId__ attributes api_call_completed to the plan");
        org.junit.jupiter.api.Assertions.assertEquals("node-1", markers.get("__analyticsNodeId__"),
                "__analyticsNodeId__ attributes api_call_completed to the node");
        org.junit.jupiter.api.Assertions.assertEquals("run-1", markers.get("__workflowRunId__"));
        org.junit.jupiter.api.Assertions.assertNull(markers.get("__nodeId__"),
                "__nodeId__ is the billing step key and must stay unset");
    }
}
