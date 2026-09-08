package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.state.BackEdgeState;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * How a LOOP NODE (While) ends, now that reaching the cap can fail the run.
 *
 * <p>The rule has one exception, and it is the one that protects existing workflows: a loop node
 * with no condition means "repeat N times", so its cap IS the declared way out and reaching it is
 * success. With a condition, the cap is a safety net - reaching it means the author's stop signal
 * never came, and finishing green would hide that.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Loop node termination semantics")
class BackEdgeTerminationSemanticsTest {

    @Mock private TemplateEngine templateEngine;
    @Mock private EdgeStatusService edgeStatusService;
    @Mock private WorkflowEventPublisher eventPublisher;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private V2ExecutionEventService eventService;
    @Mock private WorkflowExecution execution;

    private BackEdgeHandler handler;

    private static final Edge ITERATE = new Edge("mcp:body_last", "core:retry:iterate");

    @BeforeEach
    void setUp() {
        handler = new BackEdgeHandler(templateEngine, edgeStatusService, eventPublisher, workflowEpochService);
        when(execution.getRunId()).thenReturn("run-" + System.identityHashCode(this));
        when(edgeStatusService.flushEdgeBatch(anyString())).thenReturn(Map.of());
    }

    private WorkflowPlan hubPlan(String condition, int maxIterations) {
        Core loopCore = mock(Core.class);
        when(loopCore.loopCondition()).thenReturn(condition);
        when(loopCore.maxIterations()).thenReturn(maxIterations);
        when(loopCore.label()).thenReturn("retry");
        when(loopCore.isLoop()).thenReturn(true);
        when(loopCore.getNormalizedKey()).thenReturn("core:retry");

        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(ITERATE));
        when(plan.getIterateEdges()).thenReturn(List.of(ITERATE));
        when(plan.getEdges()).thenReturn(List.of());
        when(plan.getCores()).thenReturn(List.of(loopCore));
        when(plan.findLoopCoreForIterateEdge(ITERATE)).thenReturn(Optional.of(loopCore));
        when(plan.findLoopBodyTarget("core:retry")).thenReturn("mcp:body_first");
        when(plan.findLoopExitTarget("core:retry")).thenReturn("mcp:after_loop");
        return plan;
    }

    private ExecutionContext contextAtCap(WorkflowPlan plan, String condition, int max) {
        // iteration = max-1: the last admissible body run has completed.
        BackEdgeState state = BackEdgeState.create(ITERATE.getEdgeId(), max, condition);
        for (int i = 0; i < max - 1; i++) {
            state = state.incrementIteration();
        }

        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.plan()).thenReturn(plan);
        when(ctx.itemIndex()).thenReturn(0);
        when(ctx.getGlobalData(anyString())).thenReturn(Optional.empty());
        when(ctx.getGlobalData("back_edge_state:" + ITERATE.getEdgeId())).thenReturn(Optional.of(state));
        when(ctx.getGlobalDataKeys()).thenReturn(java.util.Set.of());
        when(ctx.getAllStepOutputs()).thenReturn(Map.of());
        when(ctx.triggerData()).thenReturn(null);
        when(ctx.withGlobalData(anyString(), any())).thenReturn(ctx);
        when(ctx.withStepOutput(anyString(), anyMap())).thenReturn(ctx);
        when(ctx.withoutNodes(anySet())).thenReturn(ctx);
        return ctx;
    }

    private ExecutionNode bodyLast() {
        ExecutionNode node = mock(ExecutionNode.class);
        when(node.getNodeId()).thenReturn("mcp:body_last");
        when(node.isBranchingNode()).thenReturn(false);
        return node;
    }

    @Test
    @DisplayName("'repeat N times' (no condition): reaching the cap succeeds and takes the exit")
    void capIsTheDeclaredExitWhenTheLoopHasNoCondition() {
        WorkflowPlan plan = hubPlan(null, 3);
        ExecutionContext ctx = contextAtCap(plan, null, 3);
        ExecutionNode exitNode = mock(ExecutionNode.class);
        ExecutionNode loopCoreNode = mock(ExecutionNode.class);

        String[] traversed = { null };
        handler.handleBackEdge(bodyLast(), ctx, execution, eventService, null,
            id -> switch (id) {
                case "mcp:after_loop" -> exitNode;
                case "core:retry" -> loopCoreNode;
                default -> null;
            },
            (n, c, e, es, i) -> { traversed[0] = "exit"; return c; });

        assertEquals("exit", traversed[0],
            "The loop did exactly what it was asked to do, so the workflow must carry on");

        ArgumentCaptor<NodeExecutionResult> published = ArgumentCaptor.forClass(NodeExecutionResult.class);
        verify(eventService).rePublishNodeOutput(any(), any(), published.capture(), any(), anyInt(), any(), anyInt());
        assertFalse(published.getValue().isFailure());
        assertEquals("iterations_exhausted", published.getValue().output().get("reason"));

        // The termination row is re-persisted after the loop ends and is the LAST
        // row for the node, so it is the one the inspector reads. Without the
        // node's parameters on it, every terminated loop showed an empty Params
        // column, reading as "this loop was never configured".
        @SuppressWarnings("unchecked")
        Map<String, Object> resolvedParams =
            (Map<String, Object>) published.getValue().output().get("resolved_params");
        assertNotNull(resolvedParams, "the loop's termination row must carry its configuration");
        assertEquals(3, resolvedParams.get("maxIterations"));
    }

    @Test
    @DisplayName("with a condition still true: reaching the cap fails and does NOT take the exit")
    void capIsAnOverflowWhenTheConditionIsStillTrue() {
        WorkflowPlan plan = hubPlan("{{keep_going}}", 3);
        when(templateEngine.evaluateConditionWithDetailsWithMap(eq("{{keep_going}}"), anyMap()))
            .thenReturn(new TemplateEngine.ConditionEvaluationResult("true", "true", true, null));
        ExecutionContext ctx = contextAtCap(plan, "{{keep_going}}", 3);
        ExecutionNode exitNode = mock(ExecutionNode.class);
        ExecutionNode loopCoreNode = mock(ExecutionNode.class);
        ExecutionNode source = bodyLast();

        String[] traversed = { null };
        handler.handleBackEdge(source, ctx, execution, eventService, null,
            id -> switch (id) {
                case "mcp:after_loop" -> exitNode;
                case "core:retry" -> loopCoreNode;
                default -> null;
            },
            (n, c, e, es, i) -> { traversed[0] = "exit"; return c; });

        assertNull(traversed[0],
            "Continuing past a budget overflow would run the rest of the workflow on unfinished work");

        ArgumentCaptor<NodeExecutionResult> published = ArgumentCaptor.forClass(NodeExecutionResult.class);
        verify(eventService, atLeastOnce()).rePublishNodeOutput(any(), any(), published.capture(),
            any(), anyInt(), any(), anyInt());
        assertTrue(published.getAllValues().stream().anyMatch(NodeExecutionResult::isFailure),
            "The run must land FAILED, not green");

        // And the persisted loop-core row must not re-arm the exit. Step-by-step rebuilds
        // routing by feeding that row back through LoopNode.getNextNodes, so this is the
        // assertion that actually pins the behaviour - the row's own fields are just data.
        NodeExecutionResult loopCoreRow = published.getAllValues().stream()
            .filter(r -> !r.isFailure())
            .reduce((first, second) -> second)
            .orElseThrow();
        assertTrue(routingFor(loopCoreRow).isEmpty(),
            "Step-by-step would carry on past a failed loop and run the rest of the workflow "
            + "on work the loop never finished");
    }

    /** What the engine would route to when it replays a persisted loop-core row. */
    private List<ExecutionNode> routingFor(NodeExecutionResult loopCoreRow) {
        com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode loop =
            com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode.builder()
                .nodeId("core:retry")
                .loopCondition("{{keep_going}}")
                .maxIterations(3)
                .templateEngine(templateEngine)
                .build();
        loop.addLoopExitTarget(mock(ExecutionNode.class));
        return loop.getNextNodes(loopCoreRow);
    }

    @Test
    @DisplayName("with a condition that has become false: the cap is irrelevant, the run stays green")
    void conditionFalseAtTheCapIsNotAnOverflow() {
        // The cap and the condition both expire on the same pass. Failing here would punish a
        // workflow that actually finished what it set out to do.
        WorkflowPlan plan = hubPlan("{{keep_going}}", 3);
        when(templateEngine.evaluateConditionWithDetailsWithMap(eq("{{keep_going}}"), anyMap()))
            .thenReturn(new TemplateEngine.ConditionEvaluationResult("false", "false", false, null));
        ExecutionContext ctx = contextAtCap(plan, "{{keep_going}}", 3);
        ExecutionNode exitNode = mock(ExecutionNode.class);
        ExecutionNode loopCoreNode = mock(ExecutionNode.class);

        String[] traversed = { null };
        handler.handleBackEdge(bodyLast(), ctx, execution, eventService, null,
            id -> switch (id) {
                case "mcp:after_loop" -> exitNode;
                case "core:retry" -> loopCoreNode;
                default -> null;
            },
            (n, c, e, es, i) -> { traversed[0] = "exit"; return c; });

        assertEquals("exit", traversed[0]);

        ArgumentCaptor<NodeExecutionResult> published = ArgumentCaptor.forClass(NodeExecutionResult.class);
        verify(eventService).rePublishNodeOutput(any(), any(), published.capture(), any(), anyInt(), any(), anyInt());
        assertFalse(published.getValue().isFailure());
        assertEquals("condition_false", published.getValue().output().get("reason"));
    }

    @Test
    @DisplayName("A real LoopNode's termination row carries its own loopCondition and maxIterations")
    void terminationRowCarriesTheLoopNodesOwnConfiguration() {
        WorkflowPlan plan = hubPlan(null, 3);
        ExecutionContext ctx = contextAtCap(plan, null, 3);
        ExecutionNode exitNode = mock(ExecutionNode.class);
        com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode loopCoreNode =
            com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode.builder()
                .nodeId("core:retry")
                .loopCondition("{{keep_going}}")
                .maxIterations(3)
                .templateEngine(templateEngine)
                .build();

        handler.handleBackEdge(bodyLast(), ctx, execution, eventService, null,
            id -> switch (id) {
                case "mcp:after_loop" -> exitNode;
                case "core:retry" -> loopCoreNode;
                default -> null;
            },
            (n, c, e, es, i) -> c);

        ArgumentCaptor<NodeExecutionResult> published = ArgumentCaptor.forClass(NodeExecutionResult.class);
        verify(eventService).rePublishNodeOutput(any(), any(), published.capture(), any(), anyInt(), any(), anyInt());

        @SuppressWarnings("unchecked")
        Map<String, Object> params =
            (Map<String, Object>) published.getValue().output().get("resolved_params");
        // The CONFIGURED expression, not a re-resolved one: this row is written
        // after the loop ended, and resolving then would show a value from a
        // context the loop no longer runs in.
        assertEquals("{{keep_going}}", params.get("loopCondition"));
        assertEquals(3, params.get("maxIterations"));
    }

    @Test
    @DisplayName("The step-by-step re-publish carries the same configuration as the automatic one")
    void stepByStepTerminationRowCarriesTheSameConfiguration() {
        // Two call sites re-persist the loop's termination row, one per execution
        // mode. Fixing only the automatic one would leave step-by-step users with
        // the empty Params column this work removed.
        WorkflowPlan plan = hubPlan(null, 3);
        ExecutionContext ctx = contextAtCap(plan, null, 3);
        com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode loopCoreNode =
            com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode.builder()
                .nodeId("core:retry")
                .loopCondition("{{keep_going}}")
                .maxIterations(3)
                .templateEngine(templateEngine)
                .build();

        handler.executeBackEdgeIteration(
            bodyLast(), "mcp:body_last",
            NodeExecutionResult.success("mcp:body_last", Map.of()),
            ctx, execution, eventService, mock(TriggerItem.class), 0,
            Map.of("core:retry", loopCoreNode));

        ArgumentCaptor<NodeExecutionResult> published = ArgumentCaptor.forClass(NodeExecutionResult.class);
        verify(eventService, atLeastOnce()).rePublishNodeOutput(any(), any(), published.capture(),
            any(), anyInt(), any(), anyInt());

        // Selected BY NODE ID, not by "the last row that happens to carry
        // parameters": this branch's whole direction is more nodes reporting
        // resolved_params, so a heuristic would silently start asserting on
        // someone else's row the day one is added to this path.
        NodeExecutionResult loopCoreRow = published.getAllValues().stream()
            .filter(r -> "core:retry".equals(r.nodeId()))
            .filter(r -> r.output().containsKey("resolved_params"))
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError(
                "the step-by-step termination row for core:retry must carry the loop's configuration"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) loopCoreRow.output().get("resolved_params");
        assertEquals("{{keep_going}}", params.get("loopCondition"));
        assertEquals(3, params.get("maxIterations"));
    }
}
