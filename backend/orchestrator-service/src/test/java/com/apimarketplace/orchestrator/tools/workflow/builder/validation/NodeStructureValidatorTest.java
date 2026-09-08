package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Structural-integrity warnings emitted by {@link NodeStructureValidator}.
 *
 * <p>Two regression-flavored checks live here:
 *
 * <ul>
 *   <li>{@code NODE_DUAL_WRITE} - a {@code set} (or any other nested-config)
 *       node that carries BOTH its nested slot AND a top-level twin of one of
 *       the inner fields. This is the exact shape that produced
 *       {@code price=null, triggered=false} forever for the user's stock
 *       workflow on 2026-05-14.</li>
 *   <li>{@code NESTED_TEMPLATE} - a string value embedding
 *       {@code &#123;&#123;…&#123;&#123;…&#125;&#125;…&#125;&#125;}. The
 *       template engine resolves a single pass; nested braces explode at
 *       runtime as SpEL syntax errors when the inner doesn't resolve to a
 *       valid expression.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NodeStructureValidator - dual-write + nested-template warnings")
class NodeStructureValidatorTest {

    @Mock
    private WorkflowBuilderSession session;

    private NodeStructureValidator validator;

    @BeforeEach
    void setUp() {
        validator = new NodeStructureValidator();
        // All getXxx() lists default to empty so each test only fills what it needs.
        lenient().when(session.getCores()).thenReturn(List.of());
        lenient().when(session.getMcps()).thenReturn(List.of());
        lenient().when(session.getInterfaces()).thenReturn(List.of());
        lenient().when(session.getTables()).thenReturn(List.of());
        lenient().when(session.getEdges()).thenReturn(List.of());
    }

    @Test
    @DisplayName("Flags NODE_DUAL_WRITE when a set node has both set.assignments and top-level assignments")
    void flagsDualWriteOnSetNode() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:set_stock_values");
        node.put("type", "set");
        node.put("label", "Set Stock Values");
        node.put("set", Map.of("assignments", List.of(
                Map.of("name", "price", "value", "42"))));
        node.put("assignments", List.of(
                Map.of("name", "stale", "value", "ghost")));
        lenient().when(session.getCores()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0).code()).isEqualTo("NODE_DUAL_WRITE");
        assertThat(result.getWarnings().get(0).message())
                .contains("assignments")
                .contains("set");
    }

    /**
     * The check has to walk the AI nodes too, not only the cores.
     *
     * <p>Generate is the one nested-config node held with the agents, so a sweep
     * that stopped at {@code getCores()} left it as the single node type nobody
     * checked. A stale top-level {@code model} beside {@code params.model} is
     * exactly what a modify used to leave behind, and the run reads only the
     * nested one.
     */
    @Test
    @DisplayName("Flags NODE_DUAL_WRITE on a generate node, which lives with the agents rather than the cores")
    void flagsDualWriteOnGenerateNodeAmongTheAgents() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "agent:make_clip");
        node.put("type", "generate");
        node.put("label", "Make Clip");
        node.put("isAgent", true);
        node.put("isGenerate", true);
        node.put("params", Map.of("model", "seedance-2.0-fast"));
        node.put("model", "a-stale-orphan");
        lenient().when(session.getMcps()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0).code()).isEqualTo("NODE_DUAL_WRITE");
        assertThat(result.getWarnings().get(0).message())
                .contains("model")
                .contains("params");
    }

    /**
     * The blast radius of widening the sweep to the AI nodes.
     *
     * <p>The check now walks every node in {@code getMcps()}, not only the
     * cores. Most nodes in that list are ordinary MCP steps that keep their
     * tool arguments FLAT in {@code params} by design: if the widening made one
     * of those look like a dual write, every tool step in the product would
     * carry a warning it cannot act on.
     */
    @Test
    @DisplayName("an ordinary MCP step with flat params emits no NODE_DUAL_WRITE, so the widened sweep costs nothing")
    void anOrdinaryMcpStepIsNotFlaggedByTheWidenedSweep() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "mcp:read_email");
        node.put("type", "gmail-read-message");
        node.put("label", "Read Email");
        node.put("params", Map.of("messageId", "{{trigger:start.output.id}}", "format", "full"));
        lenient().when(session.getMcps()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings())
                .as("a tool step keeps its arguments flat on purpose; flagging that would "
                        + "put an unactionable warning on every step in the product")
                .noneSatisfy(w -> assertThat(w.code()).isEqualTo("NODE_DUAL_WRITE"));
    }

    /**
     * The neighbours the sweep must keep its hands off.
     *
     * <p>Note what this can and cannot prove. The dual-write check is invoked for
     * the generate node ALONE among the mcps, so for the step below it is never
     * called at all and the assertion holds however that check behaves. What it
     * pins is the SCOPE: widen the condition back to every mcp entry and this
     * starts failing the day one of them grows a params-shaped child.
     *
     * <p>An MCP step typed with a slug cannot collide - {@code NESTED_CONFIG_KEYS}
     * has no entry for it, so the check is trivially skipped. The steps that DO
     * have an entry are the ones typed {@code transform} and {@code wait} (from
     * the {@code __transform__} / {@code __wait__} tool ids), which the modifier's
     * own comment names as keeping their params FLAT. Those are the ones the
     * widening had to leave alone, so those are the ones worth pinning.
     */
    @Test
    @DisplayName("a flat-params transform step among the mcps emits no NODE_DUAL_WRITE")
    void aFlatParamsTransformStepIsNotFlagged() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "mcp:reshape");
        node.put("type", "transform");
        node.put("label", "Reshape");
        node.put("params", Map.of("input", "{{trigger:start.output.items}}"));
        lenient().when(session.getMcps()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings())
                .as("this step keeps its arguments flat by design and has no nested "
                        + "child to disagree with, so the widened sweep must say nothing")
                .noneSatisfy(w -> assertThat(w.code()).isEqualTo("NODE_DUAL_WRITE"));
    }

    @Test
    @DisplayName("Clean set node (only nested config) emits no NODE_DUAL_WRITE")
    void cleanSetNodePasses() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:ok");
        node.put("type", "set");
        node.put("label", "OK");
        node.put("set", Map.of("assignments", List.of(
                Map.of("name", "price", "value", "42"))));
        lenient().when(session.getCores()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Top-level meta keys (label, position) on a nested-config node are not flagged")
    void topLevelMetaKeysAreNotOrphans() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:ok");
        node.put("type", "code");
        node.put("label", "OK");                // protected
        node.put("position", Map.of("x", 0));   // protected
        node.put("description", "blah");        // protected
        node.put("code", Map.of("code", "$output = {}", "language", "javascript"));
        lenient().when(session.getCores()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Flags NESTED_TEMPLATE when a value embeds {{...{{...}}...}}")
    void flagsNestedTemplate() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:check");
        node.put("type", "decision");
        node.put("label", "Check");
        // Real shape from the prod bug: a SpEL wrapping an inner template
        node.put("decisionConditions", List.of(
                Map.of("id", "check-if", "type", "if", "label", "Triggered",
                        "expression", "{{('{{x}}' == 'above' && {{y}} >= 5) || false}}")
        ));
        lenient().when(session.getCores()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings())
                .filteredOn(w -> "NESTED_TEMPLATE".equals(w.code()))
                .hasSize(1);
    }

    @Test
    @DisplayName("Lone non-paired { inside a template body is not flagged as nested")
    void loneSingleBraceIsFine() {
        // Pinned so a future tightening of the regex (e.g. to require literal { instead of {{)
        // doesn't silently start flagging legitimate template strings carrying a stray brace.
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:say");
        node.put("type", "response");
        node.put("label", "Say");
        node.put("response", Map.of("message", "Hello {{name}} - your code is { not nested }"));
        lenient().when(session.getCores()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Flags TERMINAL_NODE_HAS_OUTGOING_EDGE when an exit node has an outgoing edge")
    void flagsTerminalExitWithOutgoingEdge() {
        Map<String, Object> exit = new LinkedHashMap<>();
        exit.put("id", "core:no_triggered_stock");
        exit.put("type", "exit");
        exit.put("label", "no triggered stock");
        Map<String, Object> merge = new LinkedHashMap<>();
        merge.put("id", "core:rejoin");
        merge.put("type", "merge");
        merge.put("label", "Rejoin");
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", "core:no_triggered_stock");
        edge.put("to", "core:rejoin");
        lenient().when(session.getCores()).thenReturn(List.of(exit, merge));
        lenient().when(session.getEdges()).thenReturn(List.of(edge));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors())
                .as("terminal exit with outgoing edge must be an ERROR, not a warning")
                .filteredOn(e -> "TERMINAL_NODE_HAS_OUTGOING_EDGE".equals(e.code()))
                .hasSize(1);
        assertThat(result.getErrors().get(0).message())
                .contains("exit, end, stop_on_error")
                .contains("core:no_triggered_stock");
    }

    @Test
    @DisplayName("Flags end node with an outgoing edge (parity with exit/stop_on_error)")
    void flagsTerminalEndNodeWithOutgoingEdge() {
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("id", "core:workflow_done");
        end.put("type", "end");
        end.put("label", "Done");
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", "core:workflow_done");
        edge.put("to", "core:next");
        lenient().when(session.getCores()).thenReturn(List.of(end));
        lenient().when(session.getEdges()).thenReturn(List.of(edge));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors())
                .filteredOn(e -> "TERMINAL_NODE_HAS_OUTGOING_EDGE".equals(e.code()))
                .hasSize(1);
    }

    @Test
    @DisplayName("Flags stop_on_error with an outgoing edge too (not just exit)")
    void flagsTerminalStopOnErrorWithOutgoingEdge() {
        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("id", "core:halt");
        stop.put("type", "stop_on_error");
        stop.put("label", "Halt");
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", "core:halt");
        edge.put("to", "core:rejoin");
        lenient().when(session.getCores()).thenReturn(List.of(stop));
        lenient().when(session.getEdges()).thenReturn(List.of(edge));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors())
                .filteredOn(e -> "TERMINAL_NODE_HAS_OUTGOING_EDGE".equals(e.code()))
                .hasSize(1);
    }

    @Test
    @DisplayName("Terminal node with NO outgoing edge passes (the normal case)")
    void terminalExitWithoutOutgoingEdgeIsFine() {
        Map<String, Object> exit = new LinkedHashMap<>();
        exit.put("id", "core:done");
        exit.put("type", "exit");
        exit.put("label", "Done");
        lenient().when(session.getCores()).thenReturn(List.of(exit));
        lenient().when(session.getEdges()).thenReturn(List.of());

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors())
                .filteredOn(e -> "TERMINAL_NODE_HAS_OUTGOING_EDGE".equals(e.code()))
                .isEmpty();
    }

    @Test
    @DisplayName("Sequential {{a}}{{b}} (non-nested) does not trigger NESTED_TEMPLATE")
    void sequentialTemplatesAreFine() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:join");
        node.put("type", "transform");
        node.put("label", "Join");
        node.put("transform", Map.of("input", "{{a.x}}{{b.y}} and {{c.z}}"));
        lenient().when(session.getCores()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
    }
}
