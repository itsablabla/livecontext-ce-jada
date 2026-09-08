package com.apimarketplace.orchestrator.tools.workflow.builder.viewer;

import com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher;
import com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher.ToolInputSchema;
import com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher.ToolParameter;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for WorkflowErrorChecker.
 * Validates structural checks (triggers, orphans, dead ends) and
 * the new MISSING_REQUIRED_PARAMS check for MCP tool nodes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowErrorChecker")
class WorkflowErrorCheckerTest {

    @Mock
    private ToolSchemaFetcher toolSchemaFetcher;

    @Mock
    private WorkflowBuilderSession session;

    private WorkflowErrorChecker checker;

    @BeforeEach
    void setUp() {
        checker = new WorkflowErrorChecker(toolSchemaFetcher);
    }

    // ==================== Helper Methods ====================

    private void stubValidSession(List<Map<String, Object>> mcps) {
        lenient().when(session.getTriggers()).thenReturn(List.of(Map.of("type", "form", "label", "Start")));
        lenient().when(session.getMcps()).thenReturn(mcps);
        lenient().when(session.getCores()).thenReturn(List.of());
        lenient().when(session.getInterfaces()).thenReturn(List.of());
        lenient().when(session.getTables()).thenReturn(List.of());
        lenient().when(session.findOrphanNodes()).thenReturn(List.of());
        lenient().when(session.findDeadEndNodes()).thenReturn(List.of());
    }

    private void stubEmptySession() {
        lenient().when(session.getTriggers()).thenReturn(List.of());
        lenient().when(session.getMcps()).thenReturn(List.of());
        lenient().when(session.getCores()).thenReturn(List.of());
        lenient().when(session.getInterfaces()).thenReturn(List.of());
        lenient().when(session.getTables()).thenReturn(List.of());
        lenient().when(session.findOrphanNodes()).thenReturn(List.of());
        lenient().when(session.findDeadEndNodes()).thenReturn(List.of());
    }

    private Map<String, Object> mcpStep(String toolId, String label, Map<String, Object> params) {
        Map<String, Object> step = new LinkedHashMap<>();
        if (toolId != null) step.put("id", toolId);
        step.put("label", label);
        step.put("tool_id", toolId);
        if (params != null) step.put("params", params);
        return step;
    }

    private Map<String, Object> agentStep(String label) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("label", label);
        step.put("isAgent", true);
        return step;
    }

    private ToolInputSchema schemaWithRequired(String... paramNameTypePairs) {
        Map<String, ToolParameter> required = new LinkedHashMap<>();
        for (int i = 0; i < paramNameTypePairs.length; i += 2) {
            String name = paramNameTypePairs[i];
            String type = paramNameTypePairs[i + 1];
            required.put(name, new ToolParameter(name, type, "Description of " + name));
        }
        return ToolInputSchema.builder()
                .toolId("test-tool")
                .toolName("Test Tool")
                .requiredParameters(required)
                .optionalParameters(Map.of())
                .build();
    }

    private ToolInputSchema schemaWithNoRequired() {
        return ToolInputSchema.builder()
                .toolId("test-tool")
                .toolName("Test Tool")
                .requiredParameters(Map.of())
                .optionalParameters(Map.of("optional1", new ToolParameter("optional1", "string", "Optional param")))
                .build();
    }

    // ==================== Existing Checks Regression ====================

    @Nested
    @DisplayName("Existing structural checks")
    class ExistingChecks {

        @Test
        @DisplayName("MISSING_TRIGGER error when no triggers")
        void missingTrigger() {
            stubEmptySession();
            // Override to have at least one step so we don't also get MISSING_STEPS
            when(session.getMcps()).thenReturn(List.of(mcpStep("abc", "Step1", Map.of())));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).extracting(e -> e.get("type")).contains("MISSING_TRIGGER");
            assertThat(result.canCreate()).isFalse();
        }

        @Test
        @DisplayName("MISSING_STEPS error when no mcps and no cores")
        void missingSteps() {
            stubEmptySession();

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).extracting(e -> e.get("type")).contains("MISSING_STEPS");
            assertThat(result.canCreate()).isFalse();
        }

        @Test
        @DisplayName("canCreate=true when no errors (only warnings)")
        void canCreateWithWarningsOnly() {
            stubValidSession(List.of(mcpStep("550e8400-e29b-41d4-a716-446655440000", "Send Email",
                    Map.of("to", "test@example.com", "body", "Hello"))));

            // Mock schema to return all params satisfied
            when(toolSchemaFetcher.fetchToolInputSchema("550e8400-e29b-41d4-a716-446655440000"))
                    .thenReturn(Optional.of(schemaWithRequired("to", "string", "body", "string")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).isEmpty();
            assertThat(result.canCreate()).isTrue();
        }
    }

    // ==================== Tool ID existence check ====================

    @Nested
    @DisplayName("TOOL_NOT_FOUND check")
    class ToolNotFoundCheck {

        @Test
        @DisplayName("Adds TOOL_NOT_FOUND error when catalog returns NOT_FOUND")
        void notFoundProducesError() {
            String toolId = "8f13d91b-6945-4b1d-8956-d38d7040599e";
            stubValidSession(List.of(mcpStep(toolId, "Label Finance",
                    Map.of("addLabelIds", "Label_1"))));
            when(toolSchemaFetcher.checkToolExists(toolId))
                    .thenReturn(ToolSchemaFetcher.ToolExistence.NOT_FOUND);
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString()))
                    .thenReturn(Optional.empty());

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "TOOL_NOT_FOUND".equals(e.get("type"))
                            && ((String) e.get("message")).contains(toolId));
            assertThat(result.canCreate()).isFalse();
        }

        @Test
        @DisplayName("Permissive on UNKNOWN (transient catalog outage)")
        void unknownDoesNotError() {
            String toolId = "550e8400-e29b-41d4-a716-446655440099";
            stubValidSession(List.of(mcpStep(toolId, "Send Email", Map.of())));
            when(toolSchemaFetcher.checkToolExists(toolId))
                    .thenReturn(ToolSchemaFetcher.ToolExistence.UNKNOWN);
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString()))
                    .thenReturn(Optional.empty());

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "TOOL_NOT_FOUND".equals(e.get("type")));
        }

        @Test
        @DisplayName("Skips reserved sentinels (__transform__/__wait__)")
        void skipsSentinels() {
            stubValidSession(List.of(mcpStep("__transform__", "Transform", Map.of())));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString()))
                    .thenReturn(Optional.empty());

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "TOOL_NOT_FOUND".equals(e.get("type")));
            verify(toolSchemaFetcher, never()).checkToolExists("__transform__");
        }

        @Test
        @DisplayName("Skips agent steps")
        void skipsAgents() {
            stubValidSession(List.of(agentStep("Classifier")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "TOOL_NOT_FOUND".equals(e.get("type")));
            verify(toolSchemaFetcher, never()).checkToolExists(anyString());
        }

        @Test
        @DisplayName("Aggregates errors across multiple invalid mcps")
        void aggregatesMultipleInvalid() {
            String good = "550e8400-e29b-41d4-a716-446655440000";
            String bad1 = "00000000-0000-0000-0000-000000000001";
            String bad2 = "00000000-0000-0000-0000-000000000002";
            stubValidSession(List.of(
                    mcpStep(good, "Lister", Map.of()),
                    mcpStep(bad1, "Label Finance", Map.of()),
                    mcpStep(bad2, "Label Tech", Map.of())
            ));
            when(toolSchemaFetcher.checkToolExists(good))
                    .thenReturn(ToolSchemaFetcher.ToolExistence.EXISTS);
            when(toolSchemaFetcher.checkToolExists(bad1))
                    .thenReturn(ToolSchemaFetcher.ToolExistence.NOT_FOUND);
            when(toolSchemaFetcher.checkToolExists(bad2))
                    .thenReturn(ToolSchemaFetcher.ToolExistence.NOT_FOUND);
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString()))
                    .thenReturn(Optional.empty());

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            long count = result.errors().stream()
                    .filter(e -> "TOOL_NOT_FOUND".equals(e.get("type")))
                    .count();
            assertThat(count).isEqualTo(2);
            assertThat(result.canCreate()).isFalse();
        }
    }

    // ==================== Missing Required Params ====================

    @Nested
    @DisplayName("MISSING_REQUIRED_PARAMS check")
    class MissingRequiredParams {

        @Test
        @DisplayName("Error when some required params are missing")
        void someMissingParams() {
            String toolId = "550e8400-e29b-41d4-a716-446655440000";
            Map<String, Object> params = Map.of("to", "test@example.com");
            stubValidSession(List.of(mcpStep(toolId, "Send Email", params)));

            when(toolSchemaFetcher.fetchToolInputSchema(toolId))
                    .thenReturn(Optional.of(schemaWithRequired("to", "string", "body", "string", "subject", "string")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).hasSize(1);
            Map<String, Object> error = result.errors().get(0);
            assertThat(error.get("type")).isEqualTo("MISSING_REQUIRED_PARAMS");
            assertThat(error.get("node")).isEqualTo("mcp:Send Email");
            assertThat((String) error.get("message")).contains("body").contains("subject");
            assertThat(result.canCreate()).isFalse();
        }

        @Test
        @DisplayName("Error when all required params are missing")
        void allMissingParams() {
            String toolId = "550e8400-e29b-41d4-a716-446655440000";
            stubValidSession(List.of(mcpStep(toolId, "Send Email", Map.of())));

            when(toolSchemaFetcher.fetchToolInputSchema(toolId))
                    .thenReturn(Optional.of(schemaWithRequired("to", "string", "body", "string")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).hasSize(1);
            Map<String, Object> error = result.errors().get(0);
            assertThat(error.get("type")).isEqualTo("MISSING_REQUIRED_PARAMS");
            assertThat((String) error.get("message")).contains("body").contains("to");
        }

        @Test
        @DisplayName("Error when step has no params key at all")
        void noParamsKey() {
            String toolId = "550e8400-e29b-41d4-a716-446655440000";
            stubValidSession(List.of(mcpStep(toolId, "Send Email", null)));

            when(toolSchemaFetcher.fetchToolInputSchema(toolId))
                    .thenReturn(Optional.of(schemaWithRequired("to", "string")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().get(0).get("type")).isEqualTo("MISSING_REQUIRED_PARAMS");
        }

        @Test
        @DisplayName("No error when all required params are provided")
        void allParamsProvided() {
            String toolId = "550e8400-e29b-41d4-a716-446655440000";
            Map<String, Object> params = Map.of("to", "test@example.com", "body", "Hello");
            stubValidSession(List.of(mcpStep(toolId, "Send Email", params)));

            when(toolSchemaFetcher.fetchToolInputSchema(toolId))
                    .thenReturn(Optional.of(schemaWithRequired("to", "string", "body", "string")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).isEmpty();
            assertThat(result.canCreate()).isTrue();
        }

        @Test
        @DisplayName("No error when tool has only optional params")
        void noRequiredParams() {
            String toolId = "550e8400-e29b-41d4-a716-446655440000";
            stubValidSession(List.of(mcpStep(toolId, "List Items", Map.of())));

            when(toolSchemaFetcher.fetchToolInputSchema(toolId))
                    .thenReturn(Optional.of(schemaWithNoRequired()));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).isEmpty();
            assertThat(result.canCreate()).isTrue();
        }
    }

    // ==================== Skip Cases ====================

    @Nested
    @DisplayName("Skip cases")
    class SkipCases {

        @Test
        @DisplayName("Skips agent nodes - never calls fetchToolInputSchema")
        void skipAgentNodes() {
            stubValidSession(List.of(agentStep("My Agent")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            verify(toolSchemaFetcher, never()).fetchToolInputSchema(anyString());
            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("Skips non-UUID tool IDs (crud/, __transform__, __wait__)")
        void skipNonUuidToolIds() {
            List<Map<String, Object>> mcps = List.of(
                    mcpStep("crud/create-row", "Create Row", Map.of()),
                    mcpStep("__transform__", "Transform", Map.of()),
                    mcpStep("__wait__", "Wait", Map.of())
            );
            stubValidSession(mcps);

            // ToolSchemaFetcher.fetchToolInputSchema returns empty for non-UUID internally
            when(toolSchemaFetcher.fetchToolInputSchema("crud/create-row")).thenReturn(Optional.empty());
            when(toolSchemaFetcher.fetchToolInputSchema("__transform__")).thenReturn(Optional.empty());
            when(toolSchemaFetcher.fetchToolInputSchema("__wait__")).thenReturn(Optional.empty());

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors().stream()
                    .filter(e -> "MISSING_REQUIRED_PARAMS".equals(e.get("type")))
                    .toList()).isEmpty();
        }

        @Test
        @DisplayName("Skips step when catalog service throws exception")
        void skipOnCatalogFailure() {
            String toolId = "550e8400-e29b-41d4-a716-446655440000";
            stubValidSession(List.of(mcpStep(toolId, "Broken Tool", Map.of())));

            when(toolSchemaFetcher.fetchToolInputSchema(toolId))
                    .thenThrow(new RuntimeException("Catalog service unavailable"));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors().stream()
                    .filter(e -> "MISSING_REQUIRED_PARAMS".equals(e.get("type")))
                    .toList()).isEmpty();
            assertThat(result.canCreate()).isTrue();
        }

        @Test
        @DisplayName("Skips step when fetchToolInputSchema returns empty Optional")
        void skipOnEmptySchema() {
            String toolId = "550e8400-e29b-41d4-a716-446655440000";
            stubValidSession(List.of(mcpStep(toolId, "Unknown Tool", Map.of())));

            when(toolSchemaFetcher.fetchToolInputSchema(toolId)).thenReturn(Optional.empty());

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors().stream()
                    .filter(e -> "MISSING_REQUIRED_PARAMS".equals(e.get("type")))
                    .toList()).isEmpty();
        }

        @Test
        @DisplayName("Skips step with blank tool ID")
        void skipBlankToolId() {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", "  ");
            step.put("label", "Empty Step");
            stubValidSession(List.of(step));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            verify(toolSchemaFetcher, never()).fetchToolInputSchema(anyString());
        }

        @Test
        @DisplayName("Skips step with null tool ID (no id or tool_id key)")
        void skipNullToolId() {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("label", "No ID Step");
            stubValidSession(List.of(step));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            verify(toolSchemaFetcher, never()).fetchToolInputSchema(anyString());
        }
    }

    // ==================== Mixed Scenarios ====================

    @Nested
    @DisplayName("Mixed scenarios")
    class MixedScenarios {

        @Test
        @DisplayName("Multiple MCP nodes: one valid + one missing + one agent = exactly 1 error")
        void mixedNodes() {
            String toolId1 = "550e8400-e29b-41d4-a716-446655440001";
            String toolId2 = "550e8400-e29b-41d4-a716-446655440002";

            List<Map<String, Object>> mcps = new ArrayList<>();
            mcps.add(mcpStep(toolId1, "Send Email", Map.of("to", "a@b.com", "body", "hi")));
            mcps.add(mcpStep(toolId2, "Create Task", Map.of()));
            mcps.add(agentStep("My Agent"));

            stubValidSession(mcps);

            when(toolSchemaFetcher.fetchToolInputSchema(toolId1))
                    .thenReturn(Optional.of(schemaWithRequired("to", "string", "body", "string")));
            when(toolSchemaFetcher.fetchToolInputSchema(toolId2))
                    .thenReturn(Optional.of(schemaWithRequired("title", "string", "priority", "integer")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            List<Map<String, Object>> missingParamErrors = result.errors().stream()
                    .filter(e -> "MISSING_REQUIRED_PARAMS".equals(e.get("type")))
                    .toList();
            assertThat(missingParamErrors).hasSize(1);
            assertThat(missingParamErrors.get(0).get("node")).isEqualTo("mcp:Create Task");
        }

        @Test
        @DisplayName("MISSING_REQUIRED_PARAMS blocks canCreate")
        void blocksCreation() {
            String toolId = "550e8400-e29b-41d4-a716-446655440000";
            stubValidSession(List.of(mcpStep(toolId, "Send Email", Map.of())));

            when(toolSchemaFetcher.fetchToolInputSchema(toolId))
                    .thenReturn(Optional.of(schemaWithRequired("to", "string")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.canCreate()).isFalse();
            assertThat(result.message()).contains("error(s) must be fixed");
        }
    }

    // ==================== Fix Suggestion Format ====================

    @Nested
    @DisplayName("Fix suggestion format")
    class FixSuggestionFormat {

        @Test
        @DisplayName("Fix contains workflow modify syntax with type hints")
        void fixFormatWithTypeHints() {
            String toolId = "550e8400-e29b-41d4-a716-446655440000";
            stubValidSession(List.of(mcpStep(toolId, "Send Email", Map.of())));

            when(toolSchemaFetcher.fetchToolInputSchema(toolId))
                    .thenReturn(Optional.of(schemaWithRequired("body", "string", "to", "string")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).hasSize(1);
            String fix = (String) result.errors().get(0).get("fix");
            assertThat(fix).contains("workflow(action='modify'");
            assertThat(fix).contains("node='Send Email'");
            assertThat(fix).contains("params={");
            assertThat(fix).contains("body: '<string>'");
            assertThat(fix).contains("to: '<string>'");
        }

        @Test
        @DisplayName("Fix includes correct type hints (integer, boolean)")
        void fixTypeHintsVariety() {
            String toolId = "550e8400-e29b-41d4-a716-446655440000";
            stubValidSession(List.of(mcpStep(toolId, "Create Task", Map.of())));

            when(toolSchemaFetcher.fetchToolInputSchema(toolId))
                    .thenReturn(Optional.of(schemaWithRequired("count", "integer", "active", "boolean")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            String fix = (String) result.errors().get(0).get("fix");
            assertThat(fix).contains("count: '<integer>'");
            assertThat(fix).contains("active: '<boolean>'");
        }

        @Test
        @DisplayName("Fix defaults to string type when type is null")
        void fixDefaultsToString() {
            String toolId = "550e8400-e29b-41d4-a716-446655440000";
            stubValidSession(List.of(mcpStep(toolId, "Do Something", Map.of())));

            Map<String, ToolParameter> required = new LinkedHashMap<>();
            required.put("field", new ToolParameter("field", null, "A field"));
            ToolInputSchema schema = ToolInputSchema.builder()
                    .toolId(toolId)
                    .toolName("Tool")
                    .requiredParameters(required)
                    .optionalParameters(Map.of())
                    .build();

            when(toolSchemaFetcher.fetchToolInputSchema(toolId)).thenReturn(Optional.of(schema));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            String fix = (String) result.errors().get(0).get("fix");
            assertThat(fix).contains("field: '<string>'");
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Step with tool_id key but no id key still fetches schema")
        void toolIdFallback() {
            String toolId = "550e8400-e29b-41d4-a716-446655440000";
            Map<String, Object> step = new LinkedHashMap<>();
            // No "id" key, only "tool_id"
            step.put("tool_id", toolId);
            step.put("label", "Fallback Step");
            stubValidSession(List.of(step));

            when(toolSchemaFetcher.fetchToolInputSchema(toolId))
                    .thenReturn(Optional.of(schemaWithRequired("param1", "string")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().get(0).get("type")).isEqualTo("MISSING_REQUIRED_PARAMS");
        }

        @Test
        @DisplayName("Missing params list is sorted alphabetically")
        void missingParamsSorted() {
            String toolId = "550e8400-e29b-41d4-a716-446655440000";
            stubValidSession(List.of(mcpStep(toolId, "Step", Map.of())));

            when(toolSchemaFetcher.fetchToolInputSchema(toolId))
                    .thenReturn(Optional.of(schemaWithRequired("zebra", "string", "apple", "string", "mango", "string")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            String message = (String) result.errors().get(0).get("message");
            assertThat(message).contains("[apple, mango, zebra]");
        }
    }

    // ==================== MISSING_TOOL fix (id vs tool_id) ====================

    @Nested
    @DisplayName("MISSING_TOOL check - id vs tool_id")
    class MissingToolCheck {

        @Test
        @DisplayName("No MISSING_TOOL when step has 'id' field only (McpCreator format)")
        void noWarningWithIdFieldOnly() {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", "550e8400-e29b-41d4-a716-446655440000");
            step.put("label", "Gmail Send");
            stubValidSession(List.of(step));

            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.warnings().stream()
                    .filter(w -> "MISSING_TOOL".equals(w.get("type")))
                    .toList()).isEmpty();
        }

        @Test
        @DisplayName("No MISSING_TOOL when step has 'tool_id' field only (legacy format)")
        void noWarningWithToolIdFieldOnly() {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("tool_id", "550e8400-e29b-41d4-a716-446655440000");
            step.put("label", "Gmail Send");
            stubValidSession(List.of(step));

            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.warnings().stream()
                    .filter(w -> "MISSING_TOOL".equals(w.get("type")))
                    .toList()).isEmpty();
        }

        @Test
        @DisplayName("MISSING_TOOL warning when step has neither 'id' nor 'tool_id'")
        void warningWhenNoIdOrToolId() {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("label", "Orphan Step");
            stubValidSession(List.of(step));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.warnings().stream()
                    .filter(w -> "MISSING_TOOL".equals(w.get("type")))
                    .toList()).hasSize(1);
        }

        @Test
        @DisplayName("No MISSING_TOOL for agent nodes regardless of id fields")
        void noWarningForAgentNodes() {
            stubValidSession(List.of(agentStep("My Agent")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.warnings().stream()
                    .filter(w -> "MISSING_TOOL".equals(w.get("type")))
                    .toList()).isEmpty();
        }
    }

    // ==================== DEAD_END fix (split/loop body nodes) ====================

    @Nested
    @DisplayName("DEAD_END check - split/loop body suppression")
    class DeadEndSplitLoopBody {

        private void stubSessionWithCoresAndEdges(List<Map<String, Object>> cores,
                                                   List<String> deadEnds,
                                                   Map<String, List<Map<String, Object>>> outgoingByNode) {
            lenient().when(session.getTriggers()).thenReturn(List.of(Map.of("type", "manual", "label", "Start")));
            lenient().when(session.getMcps()).thenReturn(List.of(mcpStep("uuid-1", "Step1", Map.of())));
            lenient().when(session.getCores()).thenReturn(cores);
            lenient().when(session.getInterfaces()).thenReturn(List.of());
            lenient().when(session.getTables()).thenReturn(List.of());
            lenient().when(session.findOrphanNodes()).thenReturn(List.of());
            lenient().when(session.findDeadEndNodes()).thenReturn(deadEnds);
            lenient().when(session.formatNodeRefWithLabel(anyString())).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(session.getLogicalId(anyString())).thenAnswer(inv -> inv.getArgument(0));

            // Mock outgoing connections per node
            for (Map.Entry<String, List<Map<String, Object>>> entry : outgoingByNode.entrySet()) {
                lenient().when(session.getOutgoingConnections(entry.getKey())).thenReturn(entry.getValue());
            }
            // Default: empty connections for nodes not in the map
            lenient().when(session.getOutgoingConnections(anyString())).thenAnswer(inv -> {
                String nodeId = inv.getArgument(0);
                return outgoingByNode.getOrDefault(nodeId, List.of());
            });

            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("No DEAD_END for node inside split body")
        void noDeadEndInsideSplitBody() {
            // split → mcp:a → mcp:b (dead end inside body)
            List<Map<String, Object>> cores = List.of(Map.of("type", "split", "label", "For Each"));
            List<String> deadEnds = List.of("mcp:b");

            Map<String, List<Map<String, Object>>> outgoing = Map.of(
                    "core:for_each", List.of(
                            Map.of("from", "core:for_each", "to", "mcp:a")
                    ),
                    "mcp:a", List.of(
                            Map.of("from", "mcp:a", "to", "mcp:b")
                    ),
                    "mcp:b", List.of()
            );

            stubSessionWithCoresAndEdges(cores, deadEnds, outgoing);

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.warnings().stream()
                    .filter(w -> "DEAD_END".equals(w.get("type")))
                    .toList()).isEmpty();
        }

        @Test
        @DisplayName("No DEAD_END for node inside loop body")
        void noDeadEndInsideLoopBody() {
            // loop → mcp:process (dead end inside body)
            List<Map<String, Object>> cores = List.of(Map.of("type", "loop", "label", "My Loop"));
            List<String> deadEnds = List.of("mcp:process");

            Map<String, List<Map<String, Object>>> outgoing = Map.of(
                    "core:my_loop", List.of(
                            Map.of("from", "core:my_loop:body", "to", "mcp:process"),
                            Map.of("from", "core:my_loop:exit", "to", "mcp:after_loop")
                    ),
                    "mcp:process", List.of()
            );

            stubSessionWithCoresAndEdges(cores, deadEnds, outgoing);

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.warnings().stream()
                    .filter(w -> "DEAD_END".equals(w.get("type")))
                    .toList()).isEmpty();
        }

        @Test
        @DisplayName("DEAD_END warning for node outside split (not connected)")
        void deadEndOutsideSplit() {
            // split exists, but mcp:orphan_end is NOT reachable from split
            List<Map<String, Object>> cores = List.of(Map.of("type", "split", "label", "For Each"));
            List<String> deadEnds = List.of("mcp:orphan_end");

            Map<String, List<Map<String, Object>>> outgoing = Map.of(
                    "core:for_each", List.of(
                            Map.of("from", "core:for_each", "to", "mcp:inside")
                    ),
                    "mcp:inside", List.of(),
                    "mcp:orphan_end", List.of()
            );

            stubSessionWithCoresAndEdges(cores, deadEnds, outgoing);

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.warnings().stream()
                    .filter(w -> "DEAD_END".equals(w.get("type")))
                    .toList()).hasSize(1);
        }

        @Test
        @DisplayName("DEAD_END warning for node after split:exit (not inside body)")
        void deadEndAfterSplitExit() {
            // split:exit → mcp:finalize (dead end, but AFTER exit = should warn)
            List<Map<String, Object>> cores = List.of(Map.of("type", "split", "label", "For Each"));
            List<String> deadEnds = List.of("mcp:finalize");

            Map<String, List<Map<String, Object>>> outgoing = Map.of(
                    "core:for_each", List.of(
                            Map.of("from", "core:for_each", "to", "mcp:body_step"),
                            Map.of("from", "core:for_each:exit", "to", "mcp:finalize")
                    ),
                    "mcp:body_step", List.of(),
                    "mcp:finalize", List.of()
            );

            stubSessionWithCoresAndEdges(cores, deadEnds, outgoing);

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.warnings().stream()
                    .filter(w -> "DEAD_END".equals(w.get("type")))
                    .toList()).hasSize(1);
        }

        @Test
        @DisplayName("No DEAD_END for switch branches inside split body")
        void switchBranchesInsideSplit() {
            // split → core:switch → mcp:a, mcp:b, mcp:c (all dead ends inside body)
            List<Map<String, Object>> cores = List.of(
                    Map.of("type", "split", "label", "For Each"),
                    Map.of("type", "switch", "label", "Route")
            );
            List<String> deadEnds = List.of("mcp:a", "mcp:b", "mcp:c");

            Map<String, List<Map<String, Object>>> outgoing = Map.of(
                    "core:for_each", List.of(
                            Map.of("from", "core:for_each", "to", "core:route")
                    ),
                    "core:route", List.of(
                            Map.of("from", "core:route:case_0", "to", "mcp:a"),
                            Map.of("from", "core:route:case_1", "to", "mcp:b"),
                            Map.of("from", "core:route:default", "to", "mcp:c")
                    ),
                    "mcp:a", List.of(),
                    "mcp:b", List.of(),
                    "mcp:c", List.of()
            );

            stubSessionWithCoresAndEdges(cores, deadEnds, outgoing);

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.warnings().stream()
                    .filter(w -> "DEAD_END".equals(w.get("type")))
                    .toList()).isEmpty();
        }

        @Test
        @DisplayName("No DEAD_END for nodes inside nested split")
        void nestedSplit() {
            // outer split → inner split → mcp:deep (dead end)
            List<Map<String, Object>> cores = List.of(
                    Map.of("type", "split", "label", "Outer"),
                    Map.of("type", "split", "label", "Inner")
            );
            List<String> deadEnds = List.of("mcp:deep");

            Map<String, List<Map<String, Object>>> outgoing = Map.of(
                    "core:outer", List.of(
                            Map.of("from", "core:outer", "to", "core:inner")
                    ),
                    "core:inner", List.of(
                            Map.of("from", "core:inner", "to", "mcp:deep")
                    ),
                    "mcp:deep", List.of()
            );

            stubSessionWithCoresAndEdges(cores, deadEnds, outgoing);

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.warnings().stream()
                    .filter(w -> "DEAD_END".equals(w.get("type")))
                    .toList()).isEmpty();
        }

        @Test
        @DisplayName("CONTROL_NODE_NO_EXIT still reported for split with no body edges")
        void splitNoBodyEdgesStillWarns() {
            // split itself is dead end (no outgoing edges at all)
            List<Map<String, Object>> cores = List.of(Map.of("type", "split", "label", "Empty Split"));
            List<String> deadEnds = List.of("core:empty_split");

            Map<String, List<Map<String, Object>>> outgoing = Map.of(
                    "core:empty_split", List.of()
            );

            stubSessionWithCoresAndEdges(cores, deadEnds, outgoing);

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            // The split itself is dead-end → CONTROL_NODE_NO_EXIT (not suppressed - it's the split itself, not a body node)
            assertThat(result.warnings().stream()
                    .filter(w -> "CONTROL_NODE_NO_EXIT".equals(w.get("type")))
                    .toList()).hasSize(1);
        }

        @Test
        @DisplayName("CONTROL_NODE_NO_EXIT still reported for decision with no branches")
        void decisionNoBranchesStillWarns() {
            List<Map<String, Object>> cores = List.of(Map.of("type", "decision", "label", "Empty Decision"));
            List<String> deadEnds = List.of("core:empty_decision");

            Map<String, List<Map<String, Object>>> outgoing = Map.of(
                    "core:empty_decision", List.of()
            );

            stubSessionWithCoresAndEdges(cores, deadEnds, outgoing);

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.warnings().stream()
                    .filter(w -> "CONTROL_NODE_NO_EXIT".equals(w.get("type")))
                    .toList()).hasSize(1);
        }
    }

    // ==================== MISSING_INPUT check - public_link ====================

    @Nested
    @DisplayName("MISSING_INPUT check - public_link requires params.file")
    class PublicLinkFileCheck {

        private void stubSessionWithCores(List<Map<String, Object>> cores) {
            lenient().when(session.getTriggers()).thenReturn(List.of(Map.of("type", "manual", "label", "Start")));
            lenient().when(session.getMcps()).thenReturn(List.of());
            lenient().when(session.getCores()).thenReturn(cores);
            lenient().when(session.getInterfaces()).thenReturn(List.of());
            lenient().when(session.getTables()).thenReturn(List.of());
            lenient().when(session.findOrphanNodes()).thenReturn(List.of());
            lenient().when(session.findDeadEndNodes()).thenReturn(List.of());
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());
        }

        private Map<String, Object> publicLinkCore(Map<String, Object> params) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "public_link");
            c.put("label", "Share Video");
            if (params != null) c.put("params", params);
            return c;
        }

        @Test
        @DisplayName("public_link without params.file -> MISSING_INPUT 'requires a file' error listed")
        void missingFileFlagged() {
            stubSessionWithCores(List.of(publicLinkCore(Map.of("ttl_minutes", 60))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && "core:share_video".equals(e.get("node"))
                            && ((String) e.get("message")).contains("requires a file"));
            assertThat(result.canCreate()).isFalse();
        }

        @Test
        @DisplayName("public_link without any params map at all is flagged the same way")
        void missingParamsMapFlagged() {
            stubSessionWithCores(List.of(publicLinkCore(null)));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && ((String) e.get("message")).contains("requires a file"));
        }

        @Test
        @DisplayName("public_link with params.file -> no MISSING_INPUT error")
        void fileProvidedNotFlagged() {
            stubSessionWithCores(List.of(publicLinkCore(Map.of("file", "{{core:dl.output.file}}"))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "MISSING_INPUT".equals(e.get("type")));
            assertThat(result.canCreate()).isTrue();
        }
    }

    // ==================== MISSING_INPUT check - media ====================

    @Nested
    @DisplayName("MISSING_INPUT check - media requires operation + per-operation file params")
    class MediaCheck {

        private void stubSessionWithCores(List<Map<String, Object>> cores) {
            lenient().when(session.getTriggers()).thenReturn(List.of(Map.of("type", "manual", "label", "Start")));
            lenient().when(session.getMcps()).thenReturn(List.of());
            lenient().when(session.getCores()).thenReturn(cores);
            lenient().when(session.getInterfaces()).thenReturn(List.of());
            lenient().when(session.getTables()).thenReturn(List.of());
            lenient().when(session.findOrphanNodes()).thenReturn(List.of());
            lenient().when(session.findDeadEndNodes()).thenReturn(List.of());
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());
        }

        private Map<String, Object> mediaCore(Map<String, Object> params) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "media");
            c.put("label", "Add Music");
            if (params != null) c.put("params", params);
            return c;
        }

        @Test
        @DisplayName("media without an operation -> MISSING_INPUT listing every operation")
        void missingOperationFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of("video", "{{x}}"))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && "core:add_music".equals(e.get("node"))
                            && ((String) e.get("message")).contains("requires an operation"));
            assertThat(result.canCreate()).isFalse();
        }

        @Test
        @DisplayName("media with an unknown operation -> MISSING_INPUT naming it")
        void unknownOperationFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of("operation", "transcode"))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && ((String) e.get("message")).contains("unknown operation 'transcode'"));
        }

        @Test
        @DisplayName("mux_audio missing audio -> MISSING_INPUT for the audio param (video provided is not flagged)")
        void muxMissingAudioFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "mux_audio", "video", "{{interface:card.output.video}}"))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && ((String) e.get("message")).contains("requires an audio"));
            assertThat(result.errors())
                    .noneMatch(e -> ((String) e.get("message")).contains("requires a video"));
        }

        @Test
        @DisplayName("probe without input -> MISSING_INPUT; mix without tracks -> MISSING_INPUT")
        void probeAndMixRequiredParamsFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of("operation", "probe"))));
            WorkflowErrorChecker.CheckResult probeResult = checker.checkForErrors(session);
            assertThat(probeResult.errors())
                    .anyMatch(e -> ((String) e.get("message")).contains("requires an input"));

            stubSessionWithCores(List.of(mediaCore(Map.of("operation", "mix"))));
            WorkflowErrorChecker.CheckResult mixResult = checker.checkForErrors(session);
            assertThat(mixResult.errors())
                    .anyMatch(e -> ((String) e.get("message")).contains("requires tracks"));
        }

        @Test
        @DisplayName("well-formed mux_audio -> no MISSING_INPUT error, workflow creatable")
        void wellFormedMuxNotFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "mux_audio",
                    "video", "{{interface:card.output.video}}",
                    "audio", "{{core:dl.output.file}}"))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "MISSING_INPUT".equals(e.get("type")));
            assertThat(result.canCreate()).isTrue();
        }

        // ---- INVALID_CONFIG: loop+trim and the audio-only all-loop anchor ----

        private Map<String, Object> muxParams(Map<String, Object> extra) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("operation", "mux_audio");
            p.put("video", "{{interface:card.output.video}}");
            p.put("audio", "{{core:dl.output.file}}");
            p.putAll(extra);
            return p;
        }

        @Test
        @DisplayName("mux_audio combining loop:true with a trim -> INVALID_CONFIG error")
        void muxLoopWithTrimFlagged() {
            stubSessionWithCores(List.of(mediaCore(muxParams(Map.of(
                    "loop", true, "trim_start_seconds", 3)))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && "core:add_music".equals(e.get("node"))
                            && ((String) e.get("message")).contains("combines loop:true with trim_start_seconds/trim_end_seconds"));
        }

        @Test
        @DisplayName("mux_audio with loop:true but NO trim -> no INVALID_CONFIG error")
        void muxLoopWithoutTrimNotFlagged() {
            stubSessionWithCores(List.of(mediaCore(muxParams(Map.of("loop", true)))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "INVALID_CONFIG".equals(e.get("type")));
        }

        @Test
        @DisplayName("mix track combining loop:true with a trim -> INVALID_CONFIG naming 'track N' (1-based)")
        void mixTrackLoopWithTrimFlaggedWithIndex() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "mix",
                    "tracks", List.of(Map.of(
                            "source", "{{core:dl.output.file}}",
                            "loop", true,
                            "trim_end_seconds", 10))))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("track 1 combines loop:true"));
        }

        @Test
        @DisplayName("mix track with loop:true but NO trim -> no per-track INVALID_CONFIG loop+trim error")
        void mixTrackLoopWithoutTrimNotFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "mix",
                    "tracks", List.of(
                            Map.of("source", "{{core:bed.output.file}}", "loop", true),
                            Map.of("source", "{{core:voice.output.file}}"))))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "INVALID_CONFIG".equals(e.get("type")));
        }

        @Test
        @DisplayName("audio-only mix where EVERY track loops -> INVALID_CONFIG (nothing anchors the output length)")
        void audioOnlyAllLoopingMixFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "mix",
                    "tracks", List.of(
                            Map.of("source", "{{core:bed.output.file}}", "loop", true),
                            Map.of("source", "{{core:rain.output.file}}", "loop", true))))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("EVERY track loops"));
        }

        @Test
        @DisplayName("all-looping tracks WITH a non-blank video param -> the anchor check is suppressed")
        void allLoopingMixWithVideoNotFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "mix",
                    "video", "{{interface:card.output.video}}",
                    "tracks", List.of(
                            Map.of("source", "{{core:bed.output.file}}", "loop", true),
                            Map.of("source", "{{core:rain.output.file}}", "loop", true))))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "INVALID_CONFIG".equals(e.get("type")));
        }

        @Test
        @DisplayName("all-looping tracks with a LITERAL FileRef OBJECT video -> the anchor check is suppressed too (audit regression)")
        void allLoopingMixWithObjectVideoNotFlagged() {
            // The video param legally holds a literal FileRef map (Files picker / agent-built
            // plan); a string-only check false-positived the anchor error on those plans.
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "mix",
                    "video", Map.of("_type", "file", "path", "1/general/files/x_clip.mp4", "name", "clip.mp4"),
                    "tracks", List.of(
                            Map.of("source", "{{core:bed.output.file}}", "loop", true),
                            Map.of("source", "{{core:rain.output.file}}", "loop", true))))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "INVALID_CONFIG".equals(e.get("type")));
        }

        @Test
        @DisplayName("audio-only mix where ONE track does not loop -> no anchor INVALID_CONFIG error")
        void audioOnlyMixWithOneNonLoopingTrackNotFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "mix",
                    "tracks", List.of(
                            Map.of("source", "{{core:bed.output.file}}", "loop", true),
                            Map.of("source", "{{core:voice.output.file}}", "loop", false))))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "INVALID_CONFIG".equals(e.get("type")));
        }

        // ---- v2 operations: concat / frame / overlay -----------------------

        private Map<String, Object> concatParams(List<Map<String, Object>> clips, Map<String, Object> extra) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("operation", "concat");
            p.put("inputs", clips);
            p.putAll(extra);
            return p;
        }

        private List<Map<String, Object>> twoClips() {
            return List.of(
                    Map.of("source", "{{core:clip_a.output.file}}"),
                    Map.of("source", "{{core:clip_b.output.file}}"));
        }

        @Test
        @DisplayName("concat without inputs -> MISSING_INPUT; frame without input -> MISSING_INPUT")
        void concatAndFrameRequiredParamsFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of("operation", "concat"))));
            WorkflowErrorChecker.CheckResult concatResult = checker.checkForErrors(session);
            assertThat(concatResult.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && ((String) e.get("message")).contains("requires inputs"));

            stubSessionWithCores(List.of(mediaCore(Map.of("operation", "frame"))));
            WorkflowErrorChecker.CheckResult frameResult = checker.checkForErrors(session);
            assertThat(frameResult.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && ((String) e.get("message")).contains("frame requires an input"));
        }

        @Test
        @DisplayName("concat clip without a source -> MISSING_INPUT naming inputs[i]")
        void concatClipMissingSourceFlagged() {
            stubSessionWithCores(List.of(mediaCore(concatParams(List.of(
                    Map.of("source", "{{core:clip_a.output.file}}"),
                    Map.of("speed", 1.5)), Map.of()))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && ((String) e.get("message")).contains("inputs[1] requires a source"));
        }

        @Test
        @DisplayName("concat with crossfade and ONE input -> INVALID_CONFIG (crossfade needs 2)")
        void concatCrossfadeSingleInputFlagged() {
            stubSessionWithCores(List.of(mediaCore(concatParams(
                    List.of(Map.of("source", "{{core:clip_a.output.file}}")),
                    Map.of("transition", "crossfade")))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("crossfade needs at least 2 inputs"));
        }

        @Test
        @DisplayName("concat with crossfade and TWO inputs -> no crossfade INVALID_CONFIG error")
        void concatCrossfadeTwoInputsNotFlagged() {
            stubSessionWithCores(List.of(mediaCore(concatParams(twoClips(),
                    Map.of("transition", "crossfade")))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "INVALID_CONFIG".equals(e.get("type")));
        }

        @Test
        @DisplayName("transition_seconds outside 0.1-5.0 -> INVALID_CONFIG naming the bound")
        void concatTransitionSecondsOutOfBoundsFlagged() {
            stubSessionWithCores(List.of(mediaCore(concatParams(twoClips(),
                    Map.of("transition", "crossfade", "transition_seconds", 6)))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("between 0.1 and 5.0"));
        }

        @Test
        @DisplayName("transition_seconds as a {{template}} -> NOT flagged (resolves at run time)")
        void concatTransitionSecondsTemplateNotFlagged() {
            stubSessionWithCores(List.of(mediaCore(concatParams(twoClips(),
                    Map.of("transition_seconds", "{{trigger:start.output.fade}}")))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "INVALID_CONFIG".equals(e.get("type")));
        }

        @Test
        @DisplayName("clip with trim_end_seconds <= trim_start_seconds -> INVALID_CONFIG naming the clip")
        void concatTrimEndBeforeTrimStartFlagged() {
            stubSessionWithCores(List.of(mediaCore(concatParams(List.of(
                    Map.of("source", "{{core:clip_a.output.file}}",
                            "trim_start_seconds", 10, "trim_end_seconds", 5)), Map.of()))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("inputs[0]")
                            && ((String) e.get("message")).contains("trim_end_seconds must be greater"));
        }

        @Test
        @DisplayName("clip with a valid trim window -> no trim INVALID_CONFIG error")
        void concatValidTrimWindowNotFlagged() {
            stubSessionWithCores(List.of(mediaCore(concatParams(List.of(
                    Map.of("source", "{{core:clip_a.output.file}}",
                            "trim_start_seconds", 2, "trim_end_seconds", 10)), Map.of()))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "INVALID_CONFIG".equals(e.get("type")));
        }

        @Test
        @DisplayName("target_width without target_height -> INVALID_CONFIG (BOTH or NEITHER)")
        void concatTargetWidthAloneFlagged() {
            stubSessionWithCores(List.of(mediaCore(concatParams(twoClips(),
                    Map.of("target_width", 1920)))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("BOTH or NEITHER"));
        }

        @Test
        @DisplayName("target_width AND target_height together -> no dimension INVALID_CONFIG error")
        void concatBothTargetDimsNotFlagged() {
            stubSessionWithCores(List.of(mediaCore(concatParams(twoClips(),
                    Map.of("target_width", 1920, "target_height", 1080)))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .noneMatch(e -> "INVALID_CONFIG".equals(e.get("type")));
        }

        @Test
        @DisplayName("frame with negative at_seconds -> INVALID_CONFIG; a valid at_seconds is not flagged")
        void frameAtSecondsBounds() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "frame", "input", "{{core:reel.output.file}}", "at_seconds", -1))));
            WorkflowErrorChecker.CheckResult negative = checker.checkForErrors(session);
            assertThat(negative.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("at_seconds"));

            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "frame", "input", "{{core:reel.output.file}}", "at_seconds", 3))));
            WorkflowErrorChecker.CheckResult valid = checker.checkForErrors(session);
            assertThat(valid.errors())
                    .noneMatch(e -> "INVALID_CONFIG".equals(e.get("type")));
        }

        @Test
        @DisplayName("overlay missing image -> MISSING_INPUT for the image param (video provided is not flagged)")
        void overlayMissingImageFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "overlay", "video", "{{core:reel.output.file}}"))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && ((String) e.get("message")).contains("requires an image"));
            assertThat(result.errors())
                    .noneMatch(e -> ((String) e.get("message")).contains("requires a video"));
        }

        @Test
        @DisplayName("overlay width_percent outside 1-100 -> INVALID_CONFIG naming the bound")
        void overlayWidthPercentOutOfBoundsFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "overlay",
                    "video", "{{core:reel.output.file}}",
                    "image", "{{core:logo.output.file}}",
                    "width_percent", 150))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("between 1 and 100"));
        }

        @Test
        @DisplayName("overlay opacity outside 0-1 -> INVALID_CONFIG naming the bound")
        void overlayOpacityOutOfBoundsFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "overlay",
                    "video", "{{core:reel.output.file}}",
                    "image", "{{core:logo.output.file}}",
                    "opacity", 2))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("between 0 and 1"));
        }

        @Test
        @DisplayName("well-formed overlay with in-bounds options -> no error, workflow creatable")
        void wellFormedOverlayNotFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "overlay",
                    "video", "{{core:reel.output.file}}",
                    "image", "{{core:logo.output.file}}",
                    "position", "bottom_right",
                    "width_percent", 15,
                    "opacity", 0.7))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).isEmpty();
            assertThat(result.canCreate()).isTrue();
        }

        // ---- subtitles ----

        private Map<String, Object> cue(Object start, Object end, String text) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("start_seconds", start);
            c.put("end_seconds", end);
            c.put("text", text);
            return c;
        }

        @Test
        @DisplayName("subtitles missing cues -> MISSING_INPUT (video provided is not flagged)")
        void subtitlesMissingCuesFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles", "video", "{{core:reel.output.file}}"))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && ((String) e.get("message")).contains("requires cues"));
            assertThat(result.errors())
                    .noneMatch(e -> ((String) e.get("message")).contains("requires a video"));
        }

        @Test
        @DisplayName("subtitles missing video -> MISSING_INPUT for the video param")
        void subtitlesMissingVideoFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles",
                    "cues", List.of(cue(0, 2.4, "It starts here"))))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && ((String) e.get("message")).contains("requires a video"));
        }

        @Test
        @DisplayName("subtitles cue with end_seconds <= start_seconds -> INVALID_CONFIG naming the cue")
        void subtitlesZeroLengthCueFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles",
                    "video", "{{core:reel.output.file}}",
                    "cues", List.of(cue(2, 2, "Never visible"))))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("cues[0]")
                            && ((String) e.get("message")).contains("end_seconds must be greater"));
        }

        @Test
        @DisplayName("subtitles overlapping cues -> INVALID_CONFIG explaining they would be drawn on top of each other")
        void subtitlesOverlappingCuesFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles",
                    "video", "{{core:reel.output.file}}",
                    "cues", List.of(cue(0, 3, "First"), cue(2, 5, "Overlaps"))))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("cues[1]")
                            && ((String) e.get("message")).contains("non-overlapping"));
        }

        @Test
        @DisplayName("subtitles cue without text -> MISSING_INPUT naming the cue")
        void subtitlesCueWithoutTextFlagged() {
            Map<String, Object> textless = new LinkedHashMap<>();
            textless.put("start_seconds", 0);
            textless.put("end_seconds", 2.4);
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles",
                    "video", "{{core:reel.output.file}}",
                    "cues", List.of(textless)))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && ((String) e.get("message")).contains("cues[0] requires a text"));
        }

        @Test
        @DisplayName("subtitles cue with no timings -> MISSING_INPUT at BUILD time, not only when the run refuses it")
        void subtitlesCueWithoutTimingsFlagged() {
            Map<String, Object> timeless = new LinkedHashMap<>();
            timeless.put("text", "Typed the line, not the timings yet");
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles",
                    "video", "{{core:reel.output.file}}",
                    "cues", List.of(timeless)))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && ((String) e.get("message")).contains("cues[0] needs both start_seconds and end_seconds"));
            assertThat(result.canCreate()).isFalse();
        }

        @Test
        @DisplayName("a BLANK timing counts as missing, the same as an absent one")
        void subtitlesBlankTimingFlagged() {
            Map<String, Object> blank = new LinkedHashMap<>();
            blank.put("start_seconds", "");
            blank.put("end_seconds", "");
            blank.put("text", "Hi");
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles",
                    "video", "{{core:reel.output.file}}",
                    "cues", List.of(blank)))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> ((String) e.get("message")).contains("cues[0] needs both start_seconds and end_seconds"));
        }

        @Test
        @DisplayName("subtitles cue timings written as {{...}} templates are NOT judged here (they resolve at run time)")
        void subtitlesTemplateTimingsNotFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles",
                    "video", "{{core:reel.output.file}}",
                    "cues", List.of(
                            cue("{{core:timing.output.result.start}}", "{{core:timing.output.result.end}}", "Line"))))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).isEmpty();
            assertThat(result.canCreate()).isTrue();
        }

        @Test
        @DisplayName("subtitles beyond the cue cap -> INVALID_CONFIG naming the cap and the count")
        void subtitlesTooManyCuesFlagged() {
            List<Map<String, Object>> many = new java.util.ArrayList<>();
            for (int i = 0; i < 601; i++) {
                many.add(cue(i, i + 0.5, "line " + i));
            }
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles", "video", "{{core:reel.output.file}}", "cues", many))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("at most 600 cues")
                            && ((String) e.get("message")).contains("601"));
        }

        @Test
        @DisplayName("a cue that is not an object at all -> MISSING_INPUT naming its index")
        void subtitlesNonObjectCueFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles", "video", "{{core:reel.output.file}}",
                    "cues", List.of("0 -> 2.4 It starts here")))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "MISSING_INPUT".equals(e.get("type"))
                            && ((String) e.get("message")).contains("cues[0] must be an object"));
        }

        @Test
        @DisplayName("a caption line over the character limit -> INVALID_CONFIG at BUILD time, not only at run time")
        void subtitlesOverlongTextFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles", "video", "{{core:reel.output.file}}",
                    "cues", List.of(cue(0, 2, "x".repeat(241)))))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("241 characters"));
        }

        @Test
        @DisplayName("subtitles look bounds are checked here too, like overlay's are")
        void subtitlesLookBoundsFlagged() {
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("operation", "subtitles");
            base.put("video", "{{core:reel.output.file}}");
            base.put("cues", List.of(cue(0, 2, "Hi")));

            Map<String, Object> tooBig = new LinkedHashMap<>(base);
            tooBig.put("font_size_percent", 30);
            stubSessionWithCores(List.of(mediaCore(tooBig)));
            assertThat(checker.checkForErrors(session).errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("between 1 and 20"));

            Map<String, Object> offFrame = new LinkedHashMap<>(base);
            offFrame.put("position_percent", 140);
            stubSessionWithCores(List.of(mediaCore(offFrame)));
            assertThat(checker.checkForErrors(session).errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("between 0 and 100"));
        }

        @Test
        @DisplayName("a negative cue timing is INVALID_CONFIG here, not only when the run refuses it")
        void subtitlesNegativeTimingFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles", "video", "{{core:reel.output.file}}",
                    "cues", List.of(cue(-1, 2, "Too early"))))));

            assertThat(checker.checkForErrors(session).errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("negative timing"));
        }

        @Test
        @DisplayName("a track past the TOTAL character limit is flagged here, matching what the run enforces")
        void subtitlesTotalLengthFlagged() {
            List<Map<String, Object>> cues = new java.util.ArrayList<>();
            for (int i = 0; i < 600; i++) {
                cues.add(cue(i, i + 0.5, "x".repeat(240)));
            }
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles", "video", "{{core:reel.output.file}}", "cues", cues))));

            assertThat(checker.checkForErrors(session).errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("characters of caption in total"));
        }

        @Test
        @DisplayName("cues given as an EXPRESSION are accepted: a caption track is often computed upstream")
        void subtitlesTemplatedCuesAccepted() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles",
                    "video", "{{core:reel.output.file}}",
                    "cues", "{{core:build_cues.output.result.cues}}"))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).isEmpty();
            assertThat(result.canCreate()).isTrue();
        }

        @Test
        @DisplayName("an expression for cues still does not excuse an out-of-range look value")
        void subtitlesTemplatedCuesStillCheckLookBounds() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles",
                    "video", "{{core:reel.output.file}}",
                    "cues", "{{core:build_cues.output.result.cues}}",
                    "font_size_percent", 30))));

            assertThat(checker.checkForErrors(session).errors())
                    .anyMatch(e -> "INVALID_CONFIG".equals(e.get("type"))
                            && ((String) e.get("message")).contains("between 1 and 20"));
        }

        @Test
        @DisplayName("well-formed subtitles with ordered cues -> no error, workflow creatable")
        void wellFormedSubtitlesNotFlagged() {
            stubSessionWithCores(List.of(mediaCore(Map.of(
                    "operation", "subtitles",
                    "video", "{{core:reel.output.file}}",
                    "style", "tiktok",
                    "cues", List.of(cue(0, 2.4, "First line"), cue(2.4, 5, "Second line"))))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).isEmpty();
            assertThat(result.canCreate()).isTrue();
        }
    }

    // ==================== EXPRESSION_NOT_EVALUATED (F15/F21 boundary trap) ====================

    @Nested
    @DisplayName("EXPRESSION_NOT_EVALUATED warning - operators outside {{...}}")
    class ExpressionBoundaryWarning {

        private void stubSessionWithCores(List<Map<String, Object>> cores) {
            lenient().when(session.getTriggers()).thenReturn(List.of(Map.of("type", "manual", "label", "Start")));
            lenient().when(session.getMcps()).thenReturn(List.of());
            lenient().when(session.getCores()).thenReturn(cores);
            lenient().when(session.getInterfaces()).thenReturn(List.of());
            lenient().when(session.getTables()).thenReturn(List.of());
            lenient().when(session.findOrphanNodes()).thenReturn(List.of());
            lenient().when(session.findDeadEndNodes()).thenReturn(List.of());
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());
        }

        private Map<String, Object> switchCore(String label, String expression) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "switch");
            c.put("label", label);
            c.put("switchExpression", expression);
            return c;
        }

        private Map<String, Object> transformCore(String label, String fieldLabel, String expression) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "transform");
            c.put("label", label);
            c.put("transform", Map.of("mappings", List.of(Map.of("label", fieldLabel, "expression", expression))));
            return c;
        }

        private Map<String, Object> aggregateCore(String label, String fieldLabel, String expression) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "aggregate");
            c.put("label", label);
            c.put("aggregate", Map.of("fields", List.of(Map.of("label", fieldLabel, "expression", expression))));
            return c;
        }

        private Map<String, Object> decisionCore(String label, String conditionExpression) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "decision");
            c.put("label", label);
            c.put("decisionConditions", List.of(Map.of("type", "if", "expression", conditionExpression)));
            return c;
        }

        private List<Map<String, Object>> boundaryWarnings(WorkflowErrorChecker.CheckResult result) {
            return result.warnings().stream()
                    .filter(w -> "EXPRESSION_NOT_EVALUATED".equals(w.get("type")))
                    .toList();
        }

        @Test
        @DisplayName("Switch with a ternary OUTSIDE the braces is flagged (the observed F15 bug)")
        void switchTernaryOutsideBracesWarns() {
            stubSessionWithCores(List.of(switchCore("Route", "{{n}} < 5 ? 'low' : 'high'")));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            List<Map<String, Object>> warnings = boundaryWarnings(result);
            assertThat(warnings).hasSize(1);
            Map<String, Object> w = warnings.get(0);
            assertThat(w.get("node")).isEqualTo("core:route");
            assertThat(w.get("severity")).isEqualTo("HIGH");
            assertThat((String) w.get("message")).contains("OUTSIDE the braces").contains("fall through to default");
            assertThat((String) w.get("fix")).contains("workflow(action='modify'").contains("switchExpression");
            // A warning must NOT block creation.
            assertThat(result.canCreate()).isTrue();
        }

        @Test
        @DisplayName("Switch with the whole ternary wrapped in one {{...}} is NOT flagged")
        void switchPureTernaryNoWarning() {
            stubSessionWithCores(List.of(switchCore("Route", "{{n < 5 ? 'low' : 'high'}}")));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).isEmpty();
        }

        @Test
        @DisplayName("Switch on a plain {{ref}} value is NOT flagged")
        void switchPureReferenceNoWarning() {
            stubSessionWithCores(List.of(switchCore("Route", "{{trigger:start.output.status}}")));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).isEmpty();
        }

        @Test
        @DisplayName("Switch on a composite key {{a}}-{{b}} (plain separator) is NOT flagged")
        void switchCompositeKeyNoWarning() {
            stubSessionWithCores(List.of(switchCore("Route", "{{a}}-{{b}}")));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).isEmpty();
        }

        @Test
        @DisplayName("Switch on a literal value (no placeholder) is NOT flagged")
        void switchLiteralNoWarning() {
            stubSessionWithCores(List.of(switchCore("Route", "active")));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).isEmpty();
        }

        @Test
        @DisplayName("Transform field with a comparison OUTSIDE the braces is flagged")
        void transformComparisonOutsideBracesWarns() {
            stubSessionWithCores(List.of(transformCore("Shape", "grade", "{{score}} > 90 ? 'A' : 'B'")));

            List<Map<String, Object>> warnings = boundaryWarnings(checker.checkForErrors(session));
            assertThat(warnings).hasSize(1);
            assertThat((String) warnings.get(0).get("message")).contains("grade").contains("literal text");
            assertThat((String) warnings.get(0).get("fix")).contains("mappings");
        }

        @Test
        @DisplayName("Transform field fully wrapped in one {{...}} is NOT flagged")
        void transformPureExpressionNoWarning() {
            stubSessionWithCores(List.of(transformCore("Shape", "grade", "{{score > 90 ? 'A' : 'B'}}")));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).isEmpty();
        }

        @Test
        @DisplayName("Aggregate field with a logical operator OUTSIDE the braces is flagged")
        void aggregateOperatorOutsideBracesWarns() {
            stubSessionWithCores(List.of(aggregateCore("Collect", "ok", "{{a}} && {{b}}")));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).hasSize(1);
        }

        @Test
        @DisplayName("Decision condition with operators outside braces is NOT flagged (it re-evaluates)")
        void decisionConditionNotFlagged() {
            // {{amount}} > 100 is VALID on a decision (the engine re-evaluates the resolved string),
            // so it must never raise the value-field boundary warning.
            stubSessionWithCores(List.of(decisionCore("Check", "{{amount}} > 100")));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).isEmpty();
        }

        @Test
        @DisplayName("Each broken value field across nodes yields its own warning")
        void multipleBrokenFieldsEachWarn() {
            stubSessionWithCores(List.of(
                    switchCore("Route", "{{n}} < 5 ? 'low' : 'high'"),
                    transformCore("Shape", "grade", "{{score}} >= 90 ? 'A' : 'B'")
            ));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).hasSize(2);
        }

        private Map<String, Object> setCore(String label, String fieldName, String value) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "set");
            c.put("label", label);
            c.put("set", Map.of("assignments", List.of(Map.of("name", fieldName, "value", value, "type", "auto"))));
            return c;
        }

        @Test
        @DisplayName("Set assignment with operators OUTSIDE the braces is flagged (value/name keys)")
        void setValueOutsideBracesWarns() {
            stubSessionWithCores(List.of(setCore("Init", "tier", "{{n}} >= 10 ? 'gold' : 'silver'")));

            List<Map<String, Object>> warnings = boundaryWarnings(checker.checkForErrors(session));
            assertThat(warnings).hasSize(1);
            assertThat((String) warnings.get(0).get("message")).contains("tier");
            assertThat((String) warnings.get(0).get("fix")).contains("assignments");
        }

        @Test
        @DisplayName("Set assignment fully wrapped in one {{...}} is NOT flagged")
        void setPureValueNoWarning() {
            stubSessionWithCores(List.of(setCore("Init", "tier", "{{n >= 10 ? 'gold' : 'silver'}}")));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).isEmpty();
        }

        // --- Precision-boundary trade-offs (intentional false NEGATIVES, pinned so a future
        //     regex change cannot silently flip them) ---

        @Test
        @DisplayName("Arithmetic between placeholders is intentionally NOT flagged (ambiguous vs a separator)")
        void arithmeticBetweenPlaceholdersNotFlagged() {
            // {{a}} + {{b}} is broken (concatenates as text) but '+'/'-'/'/' are indistinguishable
            // from legitimate separators/URLs, so the heuristic deliberately stays silent here.
            stubSessionWithCores(List.of(transformCore("Sum", "total", "{{a}} + {{b}}")));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).isEmpty();
        }

        @Test
        @DisplayName("Unspaced single comparison ({{n}}<5) is intentionally NOT flagged (avoids HTML false positives)")
        void unspacedComparisonNotFlagged() {
            stubSessionWithCores(List.of(switchCore("Route", "{{n}}<5")));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).isEmpty();
        }

        // --- Malformed-input robustness (no NPE / no ClassCast) ---

        @Test
        @DisplayName("Non-String switchExpression does not throw and does not warn")
        void nonStringSwitchExpressionIsSafe() {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "switch");
            c.put("label", "Route");
            c.put("switchExpression", 42); // malformed plan: not a String
            stubSessionWithCores(List.of(c));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).isEmpty();
        }

        @Test
        @DisplayName("Transform mapping without an expression key does not throw and does not warn")
        void transformMappingMissingExpressionIsSafe() {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "transform");
            c.put("label", "Shape");
            // mapping entry has only a label, no expression/value
            c.put("transform", Map.of("mappings", List.of(Map.of("label", "grade"))));
            stubSessionWithCores(List.of(c));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).isEmpty();
        }

        @Test
        @DisplayName("Aggregate without a fields list does not throw and does not warn")
        void aggregateMissingFieldsIsSafe() {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "aggregate");
            c.put("label", "Collect");
            c.put("aggregate", Map.of("fields", List.of())); // empty; also exercises MISSING_CONFIG path
            stubSessionWithCores(List.of(c));

            assertThat(boundaryWarnings(checker.checkForErrors(session))).isEmpty();
        }
    }

    @Nested
    @DisplayName("MISSING_INPUT check - email_inbox action params")
    class EmailInboxActionCheck {

        private void stubSessionWithCores(List<Map<String, Object>> cores) {
            lenient().when(session.getTriggers()).thenReturn(List.of(Map.of("type", "manual", "label", "Start")));
            lenient().when(session.getMcps()).thenReturn(List.of());
            lenient().when(session.getCores()).thenReturn(cores);
            lenient().when(session.getInterfaces()).thenReturn(List.of());
            lenient().when(session.getTables()).thenReturn(List.of());
            lenient().when(session.findOrphanNodes()).thenReturn(List.of());
            lenient().when(session.findDeadEndNodes()).thenReturn(List.of());
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());
        }

        private Map<String, Object> inboxCore(Map<String, Object> emailInbox) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "email_inbox");
            c.put("label", "File Mail");
            c.put("emailInbox", emailInbox);
            return c;
        }

        private List<String> messagesOf(WorkflowErrorChecker.CheckResult result) {
            return result.errors().stream().map(e -> String.valueOf(e.get("message"))).toList();
        }

        @Test
        @DisplayName("create_folder without targetFolder -> MISSING_INPUT naming targetFolder")
        void createFolderWithoutTargetFolderFlagged() {
            stubSessionWithCores(List.of(inboxCore(Map.of("action", "create_folder"))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(messagesOf(result)).anyMatch(m -> m.contains("create_folder action requires targetFolder"));
        }

        @Test
        @DisplayName("create_folder must NOT be asked for messageUid, it is a mailbox-level action")
        void createFolderNotAskedForMessageUid() {
            // Pre-fix every action but none/list_folders demanded a messageUid, so a valid
            // create_folder node was rejected with an error that made no sense for it.
            stubSessionWithCores(List.of(inboxCore(Map.of(
                "action", "create_folder", "targetFolder", "INBOX.Clients"))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(messagesOf(result)).noneMatch(m -> m.contains("requires messageUid"));
            assertThat(messagesOf(result)).noneMatch(m -> m.contains("requires targetFolder"));
        }

        @Test
        @DisplayName("move still requires both messageUid and targetFolder")
        void moveStillRequiresBoth() {
            stubSessionWithCores(List.of(inboxCore(Map.of("action", "move"))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(messagesOf(result)).anyMatch(m -> m.contains("requires messageUid"));
            assertThat(messagesOf(result)).anyMatch(m -> m.contains("move action requires targetFolder"));
        }

        @Test
        @DisplayName("list_folders needs neither messageUid nor targetFolder")
        void listFoldersNeedsNothing() {
            stubSessionWithCores(List.of(inboxCore(Map.of("action", "list_folders"))));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(messagesOf(result)).noneMatch(m -> m.contains("requires messageUid"));
        }
    }

    // ==================== Loop stop rule ====================

    /**
     * Found 2026-07-31 while probing a prod loop fix: this checker demanded {@code loopCondition}
     * outright, so {@code workflow(action='validate')} rejected a COUNT-ONLY loop
     * (no condition, {@code maxIterations} set) - a pattern the node docs advertise and that runs
     * fine ({@code LoopNode.evaluateCondition} returns true for a null/blank condition, so the body
     * runs exactly {@code maxIterations} times). {@code CoreValidator} had always accepted either.
     * The two validators must stay in lockstep, otherwise a documented, working workflow cannot be
     * saved through the builder.
     */
    @Nested
    @DisplayName("Loop stop rule - loopCondition OR maxIterations")
    class LoopStopRule {

        private void stubSessionWithCores(List<Map<String, Object>> cores) {
            lenient().when(session.getTriggers()).thenReturn(List.of(Map.of("type", "manual", "label", "Start")));
            lenient().when(session.getMcps()).thenReturn(List.of());
            lenient().when(session.getCores()).thenReturn(cores);
            lenient().when(session.getInterfaces()).thenReturn(List.of());
            lenient().when(session.getTables()).thenReturn(List.of());
            lenient().when(session.findOrphanNodes()).thenReturn(List.of());
            lenient().when(session.findDeadEndNodes()).thenReturn(List.of());
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());
        }

        private Map<String, Object> loopCore(String loopCondition, Integer maxIterations) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "loop");
            c.put("label", "Boucle");
            if (loopCondition != null) c.put("loopCondition", loopCondition);
            if (maxIterations != null) c.put("maxIterations", maxIterations);
            return c;
        }

        private List<String> loopErrors(WorkflowErrorChecker.CheckResult result) {
            return result.errors().stream()
                    .map(e -> String.valueOf(e.get("message")))
                    .filter(m -> m.contains("Boucle"))
                    .toList();
        }

        @Test
        @DisplayName("regression: a count-only loop (maxIterations, no condition) is accepted")
        void countOnlyLoopIsAccepted() {
            stubSessionWithCores(List.of(loopCore(null, 5)));

            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(loopErrors(result))
                    .as("max_iterations alone is a complete stop rule - rejecting it blocked a "
                        + "documented pattern that executes correctly")
                    .isEmpty();
        }

        @Test
        @DisplayName("A condition-only loop is accepted")
        void conditionOnlyLoopIsAccepted() {
            stubSessionWithCores(List.of(loopCore("{{mcp:api.output.has_more}} == true", null)));

            assertThat(loopErrors(checker.checkForErrors(session))).isEmpty();
        }

        @Test
        @DisplayName("A loop with both is accepted")
        void bothIsAccepted() {
            stubSessionWithCores(List.of(loopCore("{{mcp:api.output.has_more}} == true", 10)));

            assertThat(loopErrors(checker.checkForErrors(session))).isEmpty();
        }

        @Test
        @DisplayName("A loop with NEITHER is still rejected, and says which action fixes it")
        void neitherIsRejected() {
            stubSessionWithCores(List.of(loopCore(null, null)));

            List<String> errors = loopErrors(checker.checkForErrors(session));

            assertThat(errors)
                    .as("a loop with no stop rule at all must still fail - that one would spin")
                    .hasSize(1);
            assertThat(errors.get(0)).contains("loopCondition or maxIterations");
        }

        @Test
        @DisplayName("A blank condition does not count as a stop rule")
        void blankConditionIsNotAStopRule() {
            stubSessionWithCores(List.of(loopCore("   ", null)));

            assertThat(loopErrors(checker.checkForErrors(session)))
                    .as("a whitespace-only condition is treated as absent at runtime too")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("generate node: the pinned provider key")
    class GeneratePinnedKey {

        private WorkflowErrorChecker.CheckResult check(Object credentialId) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("credential_source", "user");
            if (credentialId != null) params.put("credential_id", credentialId);
            // The AI nodes of a session live in getMcps(), discriminated by
            // isAgent. A check that only walked the cores would never see a
            // generate node again, and the whole guard would pass vacuously.
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "agent:make_clip");
            node.put("type", "generate");
            node.put("label", "Make Clip");
            node.put("isAgent", true);
            node.put("isGenerate", true);
            node.put("params", params);

            stubValidSession(List.of());
            lenient().when(session.getMcps()).thenReturn(List.of(node));
            return checker.checkForErrors(session);
        }

        @Test
        @DisplayName("a value that is not an id is refused HERE, because a node built with add_node never sees set_plan")
        void anUnusableIdIsRefused() {
            // set_plan validates this too, but add_node -> validate -> finish
            // never passes through it. Left unchecked, the run reads the value
            // as "no pin", uses the owner's default key, and the plan goes on
            // naming a key nothing used.
            WorkflowErrorChecker.CheckResult result = check("{{trigger:chat.output.key}}");

            assertThat(result.errors()).anySatisfy(e ->
                    assertThat(String.valueOf(e.get("message"))).contains("credential_id"));
            assertThat(result.canCreate()).isFalse();
        }

        /** Same node, same pin, but the pool that never reads it. */
        private WorkflowErrorChecker.CheckResult checkWithSource(Object credentialId, String source) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("credential_source", source);
            if (credentialId != null) params.put("credential_id", credentialId);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "agent:make_clip");
            node.put("type", "generate");
            node.put("label", "Make Clip");
            node.put("isAgent", true);
            node.put("isGenerate", true);
            node.put("params", params);

            stubValidSession(List.of());
            lenient().when(session.getMcps()).thenReturn(List.of(node));
            return checker.checkForErrors(session);
        }

        /**
         * The one path neither add_node nor set_plan sees.
         *
         * <p>`modify` merges what it is given without judging it, so a pin beside
         * the platform pool can only be caught here. Left uncaught, the plan names
         * a key the executor discards, and the run bills the platform while the
         * plan says otherwise - with nothing on screen saying which happened.
         */
        /**
         * An UNSTATED source is the platform, so a pin beside it is refused too.
         *
         * <p>The distinction that made this worth writing: the guard used to test
         * for the literal "platform", so a plan that simply omitted the field kept
         * a pin no run would ever read. Omitting it is not neutral here - the node
         * substitutes the platform key before the run, so leaving it out is a
         * choice of payer, and the same choice.
         */
        @Test
        @DisplayName("a pin beside an UNSTATED source is refused too, since unstated means platform")
        void aPinBesideAnUnstatedSourceIsRefused() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("credential_id", 42);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "agent:make_clip");
            node.put("type", "generate");
            node.put("label", "Make Clip");
            node.put("isAgent", true);
            node.put("isGenerate", true);
            node.put("params", params);

            stubValidSession(List.of());
            lenient().when(session.getMcps()).thenReturn(List.of(node));
            WorkflowErrorChecker.CheckResult result = checker.checkForErrors(session);

            assertThat(result.errors()).anySatisfy(e ->
                    assertThat(String.valueOf(e.get("message"))).contains("credential_id"));
        }

        @Test
        @DisplayName("a pin beside the PLATFORM pool is refused, since that pool never reads it")
        void aPinBesideThePlatformPoolIsRefused() {
            WorkflowErrorChecker.CheckResult result = checkWithSource(42, "platform");

            assertThat(result.errors()).anySatisfy(e ->
                    assertThat(String.valueOf(e.get("message"))).contains("credential_id"));
        }

        @Test
        @DisplayName("the same pin beside the OWNER pool passes: that is the arrangement it describes")
        void theSamePinBesideTheUserPoolPasses() {
            assertThat(checkWithSource(42, "user").errors()).noneSatisfy(e ->
                    assertThat(String.valueOf(e.get("message"))).contains("credential_id"));
        }

        @Test
        @DisplayName("a real id passes, so an agent can carry the owner's choice through a rewrite")
        void aRealIdPasses() {
            assertThat(check(42).errors()).noneSatisfy(e ->
                    assertThat(String.valueOf(e.get("message"))).contains("credential_id"));
        }

        @Test
        @DisplayName("no pin at all passes: the node then runs on the owner's default key")
        void noPinPasses() {
            assertThat(check(null).errors()).noneSatisfy(e ->
                    assertThat(String.valueOf(e.get("message"))).contains("credential_id"));
        }
    }

    @Nested
    @DisplayName("a step set to choose its account at run time, with no expression")
    class BlankCredentialSelector {

        @Test
        @DisplayName("is an error at BUILD time, not only when the workflow finally runs")
        void blankSelectorIsAnError() {
            // The run-time failure arrives once the workflow is already live. This
            // state is reached by toggling the field on and saving it unwritten, and it
            // is what an ACQUIRED workflow arrives in, because publishing blanks the
            // publisher choice on purpose. Without this check validate() is green and
            // every catalog step of a freshly cloned app fails on its first real run.
            Map<String, Object> step = mcpStep("tool-1", "Publish", Map.of());
            step.put("credentialSelector", "");
            stubValidSession(List.of(step));

            List<Map<String, Object>> errors = checker.checkForErrors(session).errors();

            assertThat(errors).anySatisfy(error -> {
                assertThat(error.get("type")).isEqualTo("MISSING_REQUIRED_FIELD");
                assertThat(error.get("message").toString()).contains("Publish");
            });
        }

        @Test
        @DisplayName("the message says which accounts qualify and how to find their names")
        void messageNamesTheDiscoveryActionAndTheActiveRule() {
            // This error is where an agent lands after building the step, so it is the
            // one surface that has to answer "so what do I put here". Telling it the
            // field is required, without saying that only an ACTIVE account resolves or
            // naming an action that lists them, leaves it guessing at a fail-closed field.
            Map<String, Object> step = mcpStep("tool-1", "Publish", Map.of());
            step.put("credentialSelector", "");
            stubValidSession(List.of(step));

            List<Map<String, Object>> errors = checker.checkForErrors(session).errors();

            assertThat(errors).anySatisfy(error -> {
                assertThat(error.get("message").toString())
                        .contains("ACTIVE")
                        .contains("get_connected_services");
                // The suggested fix is copied verbatim, so it has to resolve. This one
                // always did ({{item.account}}); it is spelled the same as every other
                // surface now so an agent does not meet two shapes for one field.
                assertThat(error.get("fix").toString()).contains("{{item.ig_account}}");
            });
        }

        @Test
        @DisplayName("a whitespace-only expression counts as blank, because it resolves to nothing")
        void whitespaceCountsAsBlank() {
            Map<String, Object> step = mcpStep("tool-1", "Publish", Map.of());
            step.put("credentialSelector", "   ");
            stubValidSession(List.of(step));

            assertThat(checker.checkForErrors(session).errors())
                    .anyMatch(e -> "MISSING_REQUIRED_FIELD".equals(e.get("type")));
        }

        @Test
        @DisplayName("the snake spelling is checked too, because the plan parser reads it")
        void snakeSpellingIsChecked() {
            // A plan imported through set_plan can carry credential_selector, and it is
            // a live selector at run time. Checked only under the camel spelling, a
            // blank one produced NO build-time error while every run of it failed.
            Map<String, Object> step = mcpStep("tool-1", "Publish", Map.of());
            step.put("credential_selector", "");
            stubValidSession(List.of(step));

            assertThat(checker.checkForErrors(session).errors())
                    .anyMatch(e -> "MISSING_REQUIRED_FIELD".equals(e.get("type")));
        }

        @Test
        @DisplayName("a filled expression is not an error")
        void filledSelectorIsFine() {
            Map<String, Object> step = mcpStep("tool-1", "Publish", Map.of());
            step.put("credentialSelector", "{{item.ig_account}}");
            stubValidSession(List.of(step));

            assertThat(checker.checkForErrors(session).errors())
                    .noneMatch(e -> "MISSING_REQUIRED_FIELD".equals(e.get("type")));
        }

        @Test
        @DisplayName("a step with no selector at all is not an error, so existing workflows stay clean")
        void absentSelectorIsFine() {
            // Every workflow written before this feature takes this branch. An error
            // here would light up every canvas in the product.
            stubValidSession(List.of(mcpStep("tool-1", "Publish", Map.of())));

            assertThat(checker.checkForErrors(session).errors())
                    .noneMatch(e -> "MISSING_REQUIRED_FIELD".equals(e.get("type")));
        }
    }

    @Nested
    @DisplayName("a step set to BOTH choose an account and run on the platform pool")
    class ConflictingCredentialModes {

        @Test
        @DisplayName("is an error at build time, because the run refuses it")
        void conflictIsAnError() {
            // The two modes answer one question. Unchecked here the plan saves clean,
            // validate() is green, and every run of that step fails.
            Map<String, Object> step = mcpStep("tool-1", "Publish", Map.of());
            step.put("credentialSelector", "{{item.account}}");
            step.put("credentialSource", "platform");
            stubValidSession(List.of(step));

            assertThat(checker.checkForErrors(session).errors())
                    .anyMatch(e -> "CONFLICTING_FIELDS".equals(e.get("type")));
        }

        @Test
        @DisplayName("the advice it gives can actually be followed")
        void adviceRoutesToTheNodeNotTheToolParams() {
            // The other way out of the conflict - moving the step off the platform pool
            // - is not a parameter modify routes to the NODE: it would land in the tool
            // params and travel to the provider as an undeclared argument. Advising it
            // would have reintroduced the failure the reserved-parameter list exists to
            // prevent, through the fix string of the error that reports the conflict.
            Map<String, Object> step = mcpStep("tool-1", "Publish", Map.of());
            step.put("credentialSelector", "{{item.account}}");
            step.put("credentialSource", "platform");
            stubValidSession(List.of(step));

            String fix = checker.checkForErrors(session).errors().stream()
                    .filter(e -> "CONFLICTING_FIELDS".equals(e.get("type")))
                    .map(e -> String.valueOf(e.get("fix")))
                    .findFirst().orElseThrow();

            assertThat(fix).contains("credential_selector: null");
            assertThat(fix).doesNotContain("credential_source");
        }

        @Test
        @DisplayName("a step on the platform pool with no expression is not a conflict")
        void platformAloneIsFine() {
            // The no-regression half: every platform-pinned step today takes this branch.
            Map<String, Object> step = mcpStep("tool-1", "Publish", Map.of());
            step.put("credentialSource", "platform");
            stubValidSession(List.of(step));

            assertThat(checker.checkForErrors(session).errors())
                    .noneMatch(e -> "CONFLICTING_FIELDS".equals(e.get("type")));
        }
    }
}
