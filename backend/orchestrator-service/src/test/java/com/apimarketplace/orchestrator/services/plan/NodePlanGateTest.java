package com.apimarketplace.orchestrator.services.plan;

import com.apimarketplace.auth.client.entitlement.PlanFeatureGate;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.MergeNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.StepNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.TriggerNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("NodePlanGate")
class NodePlanGateTest {

    /**
     * The node types the backend documents, read from
     * {@code orchestrator.node_type_documentation} (prod, 2026-08-29). The mapping
     * test below asserts against this fixture rather than a live DB read: the point
     * is to catch a key that could never match anything the admin screen offers,
     * and that screen lists exactly these values. Update it in the same pass as any
     * migration that adds or renames a node type.
     */
    private static final Set<String> DOCUMENTED_TYPES = Set.of(
            "agent", "aggregate", "approval", "browser_agent", "chat", "classify", "code",
            "compare_datasets", "compression", "convert_to_file", "create_column", "crypto_jwt",
            "database", "data_input", "date_time", "decision", "delete_row", "download_file",
            "email_inbox", "error", "exit", "extract_from_file", "filter", "find_rows", "fork",
            "form", "generate", "get_rows", "guardrail", "html_extract", "http_request",
            "insert_row", "interface", "limit", "loop", "manual", "mcp", "media", "merge",
            "option", "public_link", "remove_duplicates", "respond_to_webhook", "response", "rss",
            "schedule", "send_email", "set", "sftp", "sort", "split", "ssh", "stop_on_error",
            "sub_workflow", "summarize", "switch", "table", "task", "transform", "update_row",
            "wait", "webhook", "workflow", "xml");

    private PlanFeatureGate planFeatureGate;
    private NodePlanGate gate;

    @BeforeEach
    void setUp() {
        planFeatureGate = mock(PlanFeatureGate.class);
        when(planFeatureGate.isEnabled()).thenReturn(true);
        gate = new NodePlanGate();
        gate.setPlanFeatureGate(planFeatureGate);
    }

    private static MergeNode mergeNode() {
        return new MergeNode("core:join", List.of("mcp:a", "mcp:b"));
    }

    private static StepNode mcpStep() {
        Step step = new Step("youtube-data-api/youtube-upload-video", "mcp", "Upload",
                null, Map.of(), null, null, null, null, null, null);
        return StepNode.builder().nodeId("mcp:upload").stepConfig(step).build();
    }

    private static TriggerNode webhookTrigger() {
        return new TriggerNode("trigger:hook",
                new Trigger("hook-1", "hook", "single", "webhook", Map.of(), null));
    }

    private static TriggerNode tablesTrigger() {
        return new TriggerNode("trigger:rows",
                new Trigger("ds-1", "rows", "single", "datasource", Map.of(), null));
    }

    @Nested
    @DisplayName("feature key")
    class FeatureKey {

        @Test
        @DisplayName("A core node is keyed by its documented type")
        void coreNodeUsesItsType() {
            assertEquals("node:merge", NodePlanGate.featureKeyFor(mergeNode()));
        }

        @Test
        @DisplayName("A trigger is keyed by its OWN kind, not the constant 'trigger'")
        void triggerUsesItsKind() {
            // Every trigger's schemaNodeType() is "TRIGGER"; keying on that would make one
            // gate cover all six trigger types and match no row the admin screen offers.
            assertEquals("node:webhook", NodePlanGate.featureKeyFor(webhookTrigger()));
        }

        @Test
        @DisplayName("A tables trigger is keyed by its DOCUMENTED type, not the one it executes under")
        void tablesTriggerUsesTheDocumentedType() {
            // It runs as 'datasource' (Trigger.type's default) and is documented as
            // 'table'. The admin screen offers what is documented, so keying on the
            // runtime name would give a gate that can be set and never fires.
            assertEquals("node:table", NodePlanGate.featureKeyFor(tablesTrigger()));
        }

        @Test
        @DisplayName("An MCP step is keyed 'node:mcp' - its integration is gated in catalog-service")
        void mcpStepUsesTheGenericType() {
            assertEquals("node:mcp", NodePlanGate.featureKeyFor(mcpStep()));
        }

        @Test
        @DisplayName("Every key this gate can produce is a type the admin screen actually lists")
        void everyKeyMatchesADocumentedType() {
            List<ExecutionNode> nodes = List.of(mergeNode(), mcpStep(), webhookTrigger(), tablesTrigger());
            for (ExecutionNode node : nodes) {
                String key = NodePlanGate.featureKeyFor(node);
                assertNotNull(key, "no key for " + node.getNodeId());
                String type = key.substring("node:".length());
                assertTrue(DOCUMENTED_TYPES.contains(type),
                        "'" + type + "' is not a documented node type, so this gate could never fire");
            }
        }

        @Test
        @DisplayName("A node with no resolvable type produces no key rather than a key nothing matches")
        void nullTypeProducesNoKey() {
            ExecutionNode typeless = mock(ExecutionNode.class);
            when(typeless.schemaNodeType()).thenReturn(null);
            assertNull(NodePlanGate.featureKeyFor(typeless));
        }
    }

    @Nested
    @DisplayName("denyOrNull")
    class Deny {

        @Test
        @DisplayName("Below the bar: the node FAILS carrying the plan that would unlock it")
        void deniesBelowTheBar() {
            when(planFeatureGate.upgradeRequiredFor(eq("tenant-1"), eq(List.of("node:merge"))))
                    .thenReturn("PRO");

            NodeExecutionResult result = gate.denyOrNull(mergeNode(), "tenant-1");

            assertNotNull(result);
            assertTrue(result.isFailure(), "the node must be persisted FAILED, not skipped");
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("PRO"), "the message must name the plan: " + message);
            assertEquals(NodePlanGate.ERROR_CODE, result.output().get("error_code"));
            assertEquals("PRO", result.output().get("required_plan"));
        }

        @Test
        @DisplayName("At or above the bar: the node runs")
        void allowsAtOrAboveTheBar() {
            when(planFeatureGate.upgradeRequiredFor(anyString(), any())).thenReturn(null);
            assertNull(gate.denyOrNull(mergeNode(), "tenant-1"));
        }

        @Test
        @DisplayName("A disabled gate never asks and never blocks")
        void disabledGateAllows() {
            when(planFeatureGate.isEnabled()).thenReturn(false);
            assertNull(gate.denyOrNull(mergeNode(), "tenant-1"));
            verify(planFeatureGate, never()).upgradeRequiredFor(anyString(), any());
        }

        @Test
        @DisplayName("No gate bean at all gates nothing")
        void missingGateAllows() {
            NodePlanGate bare = new NodePlanGate();
            assertNull(bare.denyOrNull(mergeNode(), "tenant-1"));
        }

        @Test
        @DisplayName("A blank tenant is an internal execution and is never gated")
        void blankTenantAllows() {
            assertNull(gate.denyOrNull(mergeNode(), null));
            assertNull(gate.denyOrNull(mergeNode(), "  "));
            verify(planFeatureGate, never()).upgradeRequiredFor(anyString(), any());
        }

        @Test
        @DisplayName("A lookup that throws fails OPEN: the run continues rather than stopping on our own bug")
        void lookupFailureFailsOpen() {
            when(planFeatureGate.upgradeRequiredFor(anyString(), any()))
                    .thenThrow(new IllegalStateException("auth-service down"));
            assertNull(gate.denyOrNull(mergeNode(), "tenant-1"));
        }
    }
}
