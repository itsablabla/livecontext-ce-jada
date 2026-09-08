package com.apimarketplace.orchestrator.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.service.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

/**
 * Tests for NodeParamsValidator - validates node parameters against schema from DB.
 * Focuses on the table_id rejection issue for find_rows when used via add_node.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NodeParamsValidator")
class NodeParamsValidatorTest {

    @Mock
    private NodeLibraryService nodeLibraryService;

    @Mock
    private AgentClient agentClient;

    private NodeParamsValidator validator;

    @BeforeEach
    void setUp() {
        validator = new NodeParamsValidator(nodeLibraryService, new ModelCatalogEnricher(agentClient));
    }

    /**
     * A generate node may carry a parameter only the MODEL knows about.
     *
     * <p>The inspector offers every key in the chosen model's `accepts`,
     * including ones no build has heard of (a guidance scale, a step count).
     * The generation catalog declares them per model and judges them before the
     * provider is called. Refusing them here made the node configurable by hand
     * and not by an agent - the exact asymmetry moving it was meant to close.
     */
    @Nested
    @DisplayName("generate: parameters the CATALOG judges")
    class GenerateCatalogJudgedParams {

        @BeforeEach
        void setUpGenerateSchema() {
            NodeTypeDocumentationEntity doc = new NodeTypeDocumentationEntity();
            doc.setType("generate");
            doc.setParameters(Map.of(
                "model", Map.of("type", "string", "required", true),
                "prompt", Map.of("type", "string", "required", false)));
            when(nodeLibraryService.findByType("generate")).thenReturn(Optional.of(doc));
        }

        @Test
        @DisplayName("a model-specific parameter is accepted, because the catalog is what judges it")
        void aModelSpecificParameterIsAccepted() {
            ValidationResult result = validator.validate("generate",
                Map.of("model", "flux-1.1-pro", "guidance_scale", 7));

            assertThat(result.valid())
                .as("the inspector renders this field; refusing it here means an agent "
                    + "cannot build the node a human can")
                .isTrue();
        }

        @Test
        @DisplayName("the node's own required parameter is still enforced")
        void theRequiredParameterIsStillEnforced() {
            // The pass-through must widen what is ACCEPTED, never weaken what is
            // demanded: a generate node with no model cannot run at all.
            ValidationResult result = validator.validate("generate", Map.of("prompt", "a boat"));

            assertThat(result.valid()).isFalse();
        }
    }

    @Nested
    @DisplayName("find_rows validation")
    class FindRowsTests {

        @BeforeEach
        void setUpFindRowsSchema() {
            // Simulate find_rows node_type_documentation with where, limit, offset params
            NodeTypeDocumentationEntity findRowsDoc = new NodeTypeDocumentationEntity();
            findRowsDoc.setType("find_rows");
            findRowsDoc.setParameters(Map.of(
                "where", Map.of("type", "object", "required", false, "description", "Filter condition"),
                "limit", Map.of("type", "number", "required", false, "description", "Max rows"),
                "offset", Map.of("type", "number", "required", false, "description", "Starting position")
            ));
            when(nodeLibraryService.findByType("find_rows")).thenReturn(Optional.of(findRowsDoc));
        }

        @Test
        @DisplayName("Should reject table_id as unknown parameter for find_rows")
        void shouldRejectTableIdAsUnknown() {
            // This is the bug: table_id is a routing param, not a node param.
            // The validator rejects it because it's not in node_type_documentation.
            // The fix is to skip validation for table types in WorkflowBuilderProvider.
            ValidationResult result = validator.validate("find_rows", Map.of(
                "table_id", 1,
                "where", Map.of("column", "status", "operator", "==", "value", "active")
            ));

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                e.code().equals("UNKNOWN_PARAM") && e.parameter().equals("table_id"));
        }

        @Test
        @DisplayName("Should accept valid find_rows params (where, limit)")
        void shouldAcceptValidParams() {
            ValidationResult result = validator.validate("find_rows", Map.of(
                "where", Map.of("column", "status", "operator", "==", "value", "active"),
                "limit", 50
            ));

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("Should accept find_rows alias 'filter' for 'where'")
        void shouldAcceptFilterAlias() {
            ValidationResult result = validator.validate("find_rows", Map.of(
                "filter", Map.of("column", "name", "operator", "==", "value", "test")
            ));

            assertThat(result.valid()).isTrue();
        }

        // #TC3: `find_rows` help docs recommend `dataSourceId`. Validator previously
        // rejected it as UNKNOWN_PARAM even though the help said to use it. Now accepted
        // as an alias of `table_id`.
        @Test
        @DisplayName("#TC3 Should ACCEPT dataSourceId as alias for table_id (find_rows)")
        void shouldAcceptDataSourceIdAsAlias() {
            ValidationResult result = validator.validate("find_rows", Map.of(
                "dataSourceId", 1
            ));

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("#TC3 Should ACCEPT datasource_id as alias for table_id (find_rows)")
        void shouldAcceptDatasourceIdSnakeAsAlias() {
            ValidationResult result = validator.validate("find_rows", Map.of(
                "datasource_id", 1
            ));

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("#TC3 Should ACCEPT tableId (camelCase) as alias for table_id (find_rows)")
        void shouldAcceptTableIdCamelCaseAsAlias() {
            ValidationResult result = validator.validate("find_rows", Map.of(
                "tableId", 1
            ));

            assertThat(result.valid()).isTrue();
        }
    }

    @Nested
    @DisplayName("#TC3 CRUD dataSourceId alias across all table ops")
    class CrudDataSourceIdAliasTests {

        private void stubSchema(String type, Map<String, Object> schema) {
            NodeTypeDocumentationEntity doc = new NodeTypeDocumentationEntity();
            doc.setType(type);
            doc.setParameters(schema);
            when(nodeLibraryService.findByType(type)).thenReturn(Optional.of(doc));
        }

        @Test
        @DisplayName("insert_row accepts dataSourceId alias")
        void insertRowAcceptsDataSourceId() {
            stubSchema("insert_row", Map.of(
                "columns", Map.of("type", "object", "required", false)
            ));
            ValidationResult result = validator.validate("insert_row", Map.of(
                "dataSourceId", 1, "columns", Map.of("name", "x")
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("get_rows accepts dataSourceId alias")
        void getRowsAcceptsDataSourceId() {
            stubSchema("get_rows", Map.of(
                "where", Map.of("type", "object", "required", false)
            ));
            ValidationResult result = validator.validate("get_rows", Map.of("dataSourceId", 1));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("update_row accepts dataSourceId alias")
        void updateRowAcceptsDataSourceId() {
            stubSchema("update_row", Map.of(
                "where", Map.of("type", "object", "required", false),
                "set", Map.of("type", "object", "required", false)
            ));
            ValidationResult result = validator.validate("update_row", Map.of(
                "dataSourceId", 1, "set", Map.of("s", 1), "where", Map.of("c", "id")
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("delete_row accepts dataSourceId alias")
        void deleteRowAcceptsDataSourceId() {
            stubSchema("delete_row", Map.of(
                "where", Map.of("type", "object", "required", false)
            ));
            ValidationResult result = validator.validate("delete_row", Map.of(
                "dataSourceId", 1, "where", Map.of("c", "id")
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("create_column accepts dataSourceId alias")
        void createColumnAcceptsDataSourceId() {
            stubSchema("create_column", Map.of());
            ValidationResult result = validator.validate("create_column", Map.of("dataSourceId", 1));
            assertThat(result.valid()).isTrue();
        }
    }

    @Nested
    @DisplayName("Interface aliases - snake_case ↔ camelCase parity for toggles")
    class InterfaceAliases {

        private void stubInterfaceSchema() {
            NodeTypeDocumentationEntity doc = new NodeTypeDocumentationEntity();
            doc.setType("interface");
            // Mirrors the V228 + V229 + V230 + V376 DB doc state (canonical keys are camelCase).
            doc.setParameters(Map.of(
                "interface_id", Map.of("type", "uuid", "required", true),
                "variable_mapping", Map.of("type", "object", "required", false),
                "action_mapping", Map.of("type", "object", "required", false),
                "isEntryInterface", Map.of("type", "boolean", "required", false),
                "generateScreenshot", Map.of("type", "boolean", "required", false),
                "exposeRenderedSource", Map.of("type", "boolean", "required", false),
                "generatePdf", Map.of("type", "boolean", "required", false),
                "pdfFormat", Map.of("type", "string", "required", false),
                "pdfLandscape", Map.of("type", "boolean", "required", false)
            ));
            when(nodeLibraryService.findByType("interface")).thenReturn(Optional.of(doc));
        }

        @Test
        @DisplayName("legacy node-level format is accepted and ignored, not rejected as unknown")
        void legacyNodeFormatAcceptedAndIgnored() {
            // The format moved from the interface NODE to the interface ENTITY, so V407 drops it
            // from the DB doc. Plans written before that move still carry it: they must keep
            // validating. Without the deprecated-tolerated set this hits the interface branch's
            // "these look like template variables, put them in variable_mapping" suggestion -
            // actively wrong guidance for a param that was documented the day before.
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "format", "vertical"
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("legacy interface_format / interfaceFormat aliases are tolerated too")
        void legacyNodeFormatAliasesAccepted() {
            stubInterfaceSchema();
            assertThat(validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "interface_format", "1080x1920"
            )).valid()).isTrue();
            assertThat(validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "interfaceFormat", "square"
            )).valid()).isTrue();
        }

        @Test
        @DisplayName("a genuinely unknown interface param is still rejected")
        void unknownInterfaceParamStillRejected() {
            // Guards the tolerance above from becoming a blanket "accept anything" on interface
            // nodes: only the retired format keys are waived.
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "totallyMadeUp", "x"
            ));
            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("camelCase generateScreenshot accepted (canonical key)")
        void camelCaseGenerateScreenshotAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "generateScreenshot", true
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("snake_case generate_screenshot accepted (alias)")
        void snakeCaseGenerateScreenshotAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "generate_screenshot", true
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("camelCase exposeRenderedSource accepted (canonical key)")
        void camelCaseExposeRenderedSourceAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "exposeRenderedSource", true
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("snake_case expose_rendered_source accepted (alias)")
        void snakeCaseExposeRenderedSourceAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "expose_rendered_source", true
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("snake_case is_entry_interface accepted (alias)")
        void snakeCaseIsEntryInterfaceAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "is_entry_interface", true
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("camelCase generatePdf / pdfFormat / pdfLandscape accepted (canonical keys)")
        void camelCasePdfParamsAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "generatePdf", true,
                "pdfFormat", "A4",
                "pdfLandscape", false
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("snake_case generate_pdf / pdf_format / pdf_landscape accepted (alias) - regression: builder MCP rejected these")
        void snakeCasePdfParamsAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "generate_pdf", true,
                "pdf_format", "A4",
                "pdf_landscape", true
            ));
            assertThat(result.valid())
                .as("snake_case PDF params must validate via PARAM_ALIASES (caught live via the workflow MCP tool)")
                .isTrue();
        }

        @Test
        @DisplayName("Both snake_case toggles together accepted (regression guard for MCP add_node from agent)")
        void bothSnakeCaseTogglesAcceptedTogether() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "generate_screenshot", true,
                "expose_rendered_source", true
            ));
            assertThat(result.valid()).isTrue();
        }
    }

    @Test
    @DisplayName("Should return success for unknown node type")
    void shouldReturnErrorForUnknownType() {
        when(nodeLibraryService.findByType("nonexistent")).thenReturn(Optional.empty());

        ValidationResult result = validator.validate("nonexistent", Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.code().equals("UNKNOWN_TYPE"));
    }

    @Test
    @DisplayName("Should return success for node with no schema params")
    void shouldReturnSuccessForNoSchemaParams() {
        NodeTypeDocumentationEntity doc = new NodeTypeDocumentationEntity();
        doc.setType("merge");
        doc.setParameters(Map.of());
        when(nodeLibraryService.findByType("merge")).thenReturn(Optional.of(doc));

        ValidationResult result = validator.validate("merge", Map.of("anything", "here"));

        assertThat(result.valid()).isTrue();
    }

    /**
     * Found 2026-07-31 building a loop through the MCP builder: add_node rejected
     * {@code loopCondition} with "Unknown parameter", while the node help promises
     * "loopCondition and maxIterations (camelCase) and snake_case condition / loop_condition /
     * max_iterations are accepted" and workflow(action='modify') accepted it. The alias map only
     * had {@code maxIterations}, so of the six spellings
     * {@code UtilityNodeCreator.createLoop} actually reads, four were rejected by the validator.
     *
     * <p>The reverse gap mattered more: {@code loopCondition} now passes validation only because
     * the creator was ALSO taught to read it - accepting a param the creator ignores would take
     * the condition and silently drop it, which is worse than rejecting it outright.
     */
    @Nested
    @DisplayName("Loop param aliases - validator must mirror UtilityNodeCreator.createLoop")
    class LoopParamAliases {

        private void stubLoopSchema() {
            NodeTypeDocumentationEntity doc = new NodeTypeDocumentationEntity();
            doc.setType("loop");
            doc.setParameters(Map.of(
                "condition", Map.of("type", "string", "required", false),
                "max_iterations", Map.of("type", "integer", "required", false)
            ));
            when(nodeLibraryService.findByType("loop")).thenReturn(Optional.of(doc));
        }

        @ParameterizedTest(name = "condition spelling ''{0}'' is accepted")
        @ValueSource(strings = {"condition", "loopCondition", "loop_condition", "expression", "while"})
        @DisplayName("regression: every condition spelling the creator reads passes validation")
        void everyConditionSpellingIsAccepted(String spelling) {
            stubLoopSchema();

            ValidationResult result = validator.validate("loop", Map.of(spelling, "1 == 1"));

            assertThat(result.valid())
                .as("%s is read by UtilityNodeCreator.createLoop - rejecting it here blocks a "
                    + "spelling the node help tells agents to use", spelling)
                .isTrue();
        }

        @ParameterizedTest(name = "max-iterations spelling ''{0}'' is accepted")
        @ValueSource(strings = {"max_iterations", "maxIterations", "limit"})
        @DisplayName("every max-iterations spelling the creator reads passes validation")
        void everyMaxIterationsSpellingIsAccepted(String spelling) {
            stubLoopSchema();

            assertThat(validator.validate("loop", Map.of(spelling, 5)).valid()).isTrue();
        }

        @Test
        @DisplayName("A genuinely unknown loop param is still rejected")
        void unknownLoopParamStillRejected() {
            stubLoopSchema();

            ValidationResult result = validator.validate("loop", Map.of("iterations", 5));

            assertThat(result.valid())
                .as("widening the aliases must not turn the validator into a no-op")
                .isFalse();
        }
    }

    /**
     * The approval node's documented schema stores ONE spelling of each parameter, but
     * {@code DecisionNodeCreator.executeAddApproval} reads both conventions and the builder help
     * advertises the camelCase ones. Every spelling the creator honours must therefore validate,
     * or add_node answers "Unknown parameter" for a value that would have worked.
     *
     * <p>Found live on 2026-09-05 while scripting an invoice workflow:
     * {@code add_node(type='approval', params={timeoutMs: 86400000})} was rejected while
     * {@code timeout_ms} passed.
     */
    @Nested
    @DisplayName("approval: both spellings of every documented param")
    class ApprovalParamSpellings {

        private void stubApprovalSchema() {
            NodeTypeDocumentationEntity doc = new NodeTypeDocumentationEntity();
            doc.setType("approval");
            // Mirrors the live DB doc state (V11 + V391 + V394 + V402): one spelling each,
            // mixing snake_case and camelCase.
            doc.setParameters(Map.of(
                "timeout_ms", Map.of("type", "integer", "required", false),
                "approver_roles", Map.of("type", "array", "required", false),
                "required_approvals", Map.of("type", "integer", "required", false),
                "contextTemplate", Map.of("type", "string", "required", false),
                "continuationMode", Map.of("type", "string", "required", false),
                "delegation", Map.of("type", "object", "required", false)
            ));
            when(nodeLibraryService.findByType("approval")).thenReturn(Optional.of(doc));
        }

        @ParameterizedTest(name = "timeout spelling ''{0}'' is accepted")
        @ValueSource(strings = {"timeout_ms", "timeoutMs", "timeout"})
        @DisplayName("regression: every timeout spelling the creator reads passes validation")
        void everyTimeoutSpellingIsAccepted(String spelling) {
            stubApprovalSchema();

            assertThat(validator.validate("approval", Map.of(spelling, 86400000)).valid())
                .as("executeAddApproval honours this spelling, so add_node must not reject it")
                .isTrue();
        }

        @ParameterizedTest(name = "roles spelling ''{0}'' is accepted")
        @ValueSource(strings = {"approver_roles", "approverRoles", "roles"})
        @DisplayName("every approver-roles spelling the creator reads passes validation")
        void everyRolesSpellingIsAccepted(String spelling) {
            stubApprovalSchema();

            assertThat(validator.validate("approval", Map.of(spelling, java.util.List.of("manager"))).valid())
                .isTrue();
        }

        @ParameterizedTest(name = "spelling ''{0}'' is accepted")
        @ValueSource(strings = {"required_approvals", "requiredApprovals"})
        @DisplayName("both required-approvals spellings pass validation")
        void bothRequiredApprovalsSpellingsAccepted(String spelling) {
            stubApprovalSchema();

            assertThat(validator.validate("approval", Map.of(spelling, 2)).valid()).isTrue();
        }

        @ParameterizedTest(name = "spelling ''{0}'' is accepted")
        @ValueSource(strings = {"contextTemplate", "context_template"})
        @DisplayName("both context-template spellings pass validation")
        void bothContextTemplateSpellingsAccepted(String spelling) {
            stubApprovalSchema();

            assertThat(validator.validate("approval", Map.of(spelling, "Send it?")).valid()).isTrue();
        }

        @ParameterizedTest(name = "spelling ''{0}'' is accepted")
        @ValueSource(strings = {"continuationMode", "continuation_mode"})
        @DisplayName("both continuation-mode spellings pass validation")
        void bothContinuationModeSpellingsAccepted(String spelling) {
            stubApprovalSchema();

            assertThat(validator.validate("approval", Map.of(spelling, "per_item")).valid()).isTrue();
        }

        /**
         * Pins the KNOWN GAP documented at the alias short-circuit in NodeParamsValidator: an
         * alias skips the type check, so the two spellings of the same field are not equally
         * strict. This is not the desired end state; it is asserted so that closing it is a
         * deliberate change with a failing test to update, rather than a silent drift.
         */
        @Test
        @DisplayName("known gap: the canonical spelling is type-checked, its alias is not")
        void aliasSkipsTypeValidation() {
            stubApprovalSchema();

            assertThat(validator.validate("approval", Map.of("timeout_ms", "not-a-number")).valid())
                .as("the schema entry exists for the canonical name, so the type is checked")
                .isFalse();
            assertThat(validator.validate("approval", Map.of("timeoutMs", "not-a-number")).valid())
                .as("an alias has no schema entry, so the value is accepted here and the creator "
                    + "falls back to the documented default")
                .isTrue();
        }

        @Test
        @DisplayName("A genuinely unknown approval param is still rejected, as UNKNOWN_PARAM and by name")
        void unknownApprovalParamStillRejected() {
            stubApprovalSchema();

            ValidationResult result = validator.validate("approval", Map.of("expiresIn", 60));

            assertThat(result.valid())
                .as("widening the aliases must not turn the validator into a no-op")
                .isFalse();
            // Assert the REASON, not just the boolean: with an all-optional schema a missing
            // required param would also make this false, so the boolean alone could pass for
            // the wrong reason if the stub ever gains a required field.
            assertThat(result.errors())
                .extracting(com.apimarketplace.orchestrator.service.validation.ValidationError::code,
                            com.apimarketplace.orchestrator.service.validation.ValidationError::parameter)
                .containsExactly(tuple("UNKNOWN_PARAM", "expiresIn"));
        }
    }
}
