package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Edge-case companion to {@link SetNodeTest}: json/auto coercion, coercion-failure
 * fall-through to the raw value, null-value assignments, blank-name skips, the
 * non-Map input-expression guard, keepOnlySet=true exclusivity, and the
 * bad-expression failure path. One behavior per test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SetNode edge cases")
class SetNodeEdgeCasesTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1", "workflow-run-1", "tenant-1", "item-1", 0, new HashMap<>(), mockPlan);
    }

    private SetNode buildNode(Core.SetConfig config) {
        SetNode node = new SetNode("core:set", config);
        node.setTemplateAdapter(mockTemplateAdapter);
        lenient().when(mockTemplateAdapter.resolveTemplates(anyMap(), any()))
            .thenAnswer(inv -> inv.getArgument(0));
        return node;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fields(NodeExecutionResult result) {
        return (Map<String, Object>) result.output().get("fields");
    }

    @Test
    @DisplayName("type=json parses a JSON object string into a Map")
    void jsonTypeParsesObjectString() {
        SetNode node = buildNode(new Core.SetConfig(
            List.of(new Core.SetFieldAssignment("obj", "{\"a\":1,\"b\":\"two\"}", "json")),
            true, null));

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess());
        Object obj = fields(result).get("obj");
        assertTrue(obj instanceof Map, "json type should parse to a Map, got: " + obj);
        assertEquals(1, ((Map<?, ?>) obj).get("a"));
    }

    @Test
    @DisplayName("type=json keeps an already-structured Map/List value as-is")
    void jsonTypeKeepsStructuredValueAsIs() {
        SetNode node = buildNode(new Core.SetConfig(
            List.of(new Core.SetFieldAssignment("list", "{{upstream}}", "json")),
            true, null));
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any()))
            .thenReturn(Map.of("__v__", List.of("x", "y")));

        NodeExecutionResult result = node.execute(context);

        assertEquals(List.of("x", "y"), fields(result).get("list"));
    }

    @Test
    @DisplayName("type=auto passes the resolved value through unchanged")
    void autoTypePassesThrough() {
        SetNode node = buildNode(new Core.SetConfig(
            List.of(new Core.SetFieldAssignment("raw", "42", "auto")),
            true, null));

        NodeExecutionResult result = node.execute(context);

        assertEquals("42", fields(result).get("raw"), "auto must not coerce the string");
    }

    @Test
    @DisplayName("A failing coercion falls back to the raw value instead of failing the node")
    void coercionFailureFallsBackToRawValue() {
        SetNode node = buildNode(new Core.SetConfig(
            List.of(new Core.SetFieldAssignment("n", "not-a-number", "number")),
            true, null));

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess(), "coercion failure must not fail the node");
        assertEquals("not-a-number", fields(result).get("n"));
    }

    @Test
    @DisplayName("A null assignment value is kept as an explicit null field")
    void nullValueIsKeptAsNullField() {
        SetNode node = buildNode(new Core.SetConfig(
            List.of(new Core.SetFieldAssignment("cleared", null, "auto")),
            true, null));

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess());
        assertTrue(fields(result).containsKey("cleared"));
        assertNull(fields(result).get("cleared"));
    }

    @Test
    @DisplayName("An assignment with a blank name is skipped silently")
    void blankNameIsSkipped() {
        SetNode node = buildNode(new Core.SetConfig(
            List.of(
                new Core.SetFieldAssignment("  ", "ignored", "string"),
                new Core.SetFieldAssignment("kept", "yes", "string")),
            true, null));

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess());
        assertEquals(1, fields(result).size());
        assertEquals("yes", fields(result).get("kept"));
    }

    @Test
    @DisplayName("An input expression that resolves to a non-Map is ignored (no merge, no failure)")
    void nonMapInputExpressionIsIgnored() {
        SetNode node = buildNode(new Core.SetConfig(
            List.of(new Core.SetFieldAssignment("field", "v", "string")),
            false, "{{some.list}}"));
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any()))
            .thenAnswer(inv -> {
                Map<String, Object> in = inv.getArgument(0);
                if (in.containsKey("__input__")) {
                    return Map.of("__input__", List.of("not", "a", "map"));
                }
                return in;
            });

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output().get("output");
        assertEquals("v", output.get("field"));
        assertFalse(output.containsValue("not"), "non-Map input must not leak into the output");
    }

    @Test
    @DisplayName("keepOnlySet=true excludes upstream input data from the output map")
    void keepOnlySetTrueExcludesInputData() {
        SetNode node = buildNode(new Core.SetConfig(
            List.of(new Core.SetFieldAssignment("only", "this", "string")),
            true, null));
        context = ExecutionContext.create(
            "run-1", "workflow-run-1", "tenant-1", "item-1", 0,
            new HashMap<>(Map.of("upstream_key", "upstream_value")), mockPlan);

        NodeExecutionResult result = node.execute(context);

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output().get("output");
        assertEquals(Map.of("only", "this"), output);
    }

    @Test
    @DisplayName("A template adapter blow-up fails the node with the error message and resolved_params")
    void templateAdapterExceptionFailsWithOutput() {
        SetNode node = buildNode(new Core.SetConfig(
            List.of(new Core.SetFieldAssignment("field", "{{broken}}", "string")),
            true, null));
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any()))
            .thenThrow(new IllegalStateException("template engine exploded"));

        NodeExecutionResult result = node.execute(context);

        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().orElse("").contains("template engine exploded"));
        assertTrue(result.output().containsKey("resolved_params"),
            "failure output must keep resolved_params for the inspector");
    }

    @Test
    @DisplayName("An empty assignment list fails with the configuration still reported, under the plan's key names")
    @SuppressWarnings("unchecked")
    void emptyAssignmentsStillReportsConfiguration() {
        SetNode node = buildNode(new Core.SetConfig(List.of(), false, "{{core:up.output.items}}"));

        NodeExecutionResult result = node.execute(context);

        assertFalse(result.isSuccess());
        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        assertEquals(false, params.get("keepOnlySet"));
        assertEquals("{{core:up.output.items}}", params.get("input"));
        assertEquals(0, params.get("assignmentCount"));
    }

    @Test
    @DisplayName("Reports its configuration under the plan's key names, the same ones on the failure path as on the success path")
    @SuppressWarnings("unchecked")
    void reportsResolvedParamsUnderPlanKeyNames() {
        SetNode node = buildNode(new Core.SetConfig(
            List.of(new Core.SetFieldAssignment("field", "{{broken}}", "string")),
            true, "{{core:up.output.items}}"));
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any()))
            .thenThrow(new IllegalStateException("boom"));

        Map<String, Object> params = (Map<String, Object>) node.execute(context)
            .output().get("resolved_params");

        // keep_only_set / input_expression / assignment_count were three names the
        // inspector had no label for, and `input` meant two different things
        // depending on which exit path wrote it.
        assertEquals(true, params.get("keepOnlySet"));
        assertEquals("{{core:up.output.items}}", params.get("input"));
        assertEquals(1, params.get("assignmentCount"));
        assertFalse(params.containsKey("keep_only_set"));
        assertFalse(params.containsKey("input_expression"));
        assertFalse(params.containsKey("assignment_count"));
    }

    @Test
    @DisplayName("The SUCCESS path reports keepOnlySet under the plan's key name, alongside each resolved assignment")
    @SuppressWarnings("unchecked")
    void successPathReportsKeepOnlySetUnderPlanKeyName() {
        SetNode node = buildNode(new Core.SetConfig(
            List.of(new Core.SetFieldAssignment("status", "done", "string")),
            true, null));

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess());
        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        // The failure paths are covered above. This one is the path a reader opens
        // most, and it used to report `keep_only_set` - a key the inspector had no
        // label for, and one the alignment check reads as "parameter not reported".
        assertEquals(true, params.get("keepOnlySet"));
        assertFalse(params.containsKey("keep_only_set"),
            "the snake_case alias belongs to the OUTPUT schema, not to the parameters");
        // Each assignment is reported under its own name with its resolved value.
        // That is why `assignments` is in NOT_ECHOED_BY_DESIGN: the structural list
        // would show every assignment a second time.
        assertEquals("done", params.get("status"));
        // ...while the OUTPUT keeps its declared snake_case field, untouched.
        assertEquals(true, result.output().get("keep_only_set"));
    }
}
