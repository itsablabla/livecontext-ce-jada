package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod;
import com.apimarketplace.orchestrator.services.credit.WorkflowBudgetState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The workflow spending cap, checked BEFORE the agent call.
 *
 * <p>This is the enforcement point that actually prevents the spend. The epoch
 * gate in {@code ReusableTriggerService} only runs between trigger fires, so it
 * cannot stop the case the whole feature exists for: one epoch looping on the
 * same agent all night. Every branch below is about money either being spent or
 * not, so "the agent client was never called" is the assertion that matters.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentNode - workflow spending cap (pre-flight)")
class AgentNodeWorkflowBudgetGateTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private AgentClient mockAgentClient;
    @Mock private WorkflowRunRepository mockRunRepository;

    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("user_input", "Analyze");
        context = ExecutionContext.create(
            "run-budget-1", "workflow-run-budget-1",
            "tenant-budget-1", "item-0", 0,
            triggerData, mockPlan);
    }

    private Agent agent() {
        return new Agent(
            "agent-config-1", "agent", "Nightly Digest", null, null,
            "openai", "gpt-4o",
            "You are a test agent", "Analyze this",
            0.7, 4096, 10, 5,
            List.of(), null, Map.of(), List.of(),
            null, List.of(), null, null
        );
    }

    private AgentNode node() {
        AgentNode node = new AgentNode("agent:nightly_digest", agent());
        ServiceRegistry registry = ServiceRegistry.builder()
            .agentClient(mockAgentClient)
            .workflowRunRepository(mockRunRepository)
            .build();
        node.acceptServices(registry);
        return node;
    }

    private AgentExecutionResponseDto successResponse() {
        return new AgentExecutionResponseDto(
            true, "ok", "ok", List.of(), 1, Map.of(), null, 100L,
            "openai", "gpt-4o",
            List.of(), null, Map.of(), List.of(), List.of(), List.of(),
            null, null, null);
    }

    private void budgetState(boolean productionRun, String cap, String spent, Instant periodStart) {
        budgetState(productionRun, !productionRun, cap, spent, periodStart);
    }

    private void budgetState(boolean productionRun, boolean editorRun,
                             String cap, String spent, Instant periodStart) {
        when(mockRunRepository.findBudgetStateByRunIdPublic("run-budget-1"))
            .thenReturn(Optional.of(new WorkflowBudgetState(
                WORKFLOW_ID, productionRun, editorRun,
                cap == null ? null : new BigDecimal(cap),
                "monthly",
                spent == null ? null : new BigDecimal(spent),
                periodStart)));
    }

    private static Instant thisPeriod() {
        return WorkflowBudgetPeriod.periodStart("monthly", Instant.now());
    }

    @Test
    @DisplayName("refuses to start the agent once the production run has reached the cap")
    void blocksProductionRunOverCap() {
        budgetState(true, "10", "10", thisPeriod());

        NodeExecutionResult result = node().execute(context);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorMessage().get()).contains("WORKFLOW_BUDGET_REACHED");
        // The whole point: no provider call, so no credits are spent.
        verify(mockAgentClient, never()).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("the failure names the agent and the period, so the step row explains itself")
    void failureMessageIsActionable() {
        budgetState(true, "10", "12", thisPeriod());

        NodeExecutionResult result = node().execute(context);

        String message = result.errorMessage().get();
        assertThat(message).contains("Nightly Digest").contains("monthly").contains("10");
        // The inspector's "Resolved parameters" panel must not be blank on this
        // path, or a blocked step looks like a crash with no context.
        assertThat(result.output()).containsKey("resolved_params").containsKey("error");
    }

    @Test
    @DisplayName("refuses a run that is neither production nor a builder fire: the rule is not FK-only")
    void blocksUnpinnedNonEditorRun() {
        // This guard asks appliesToRun(), not "is this the production run".
        // A trigger cannot currently START such a run (the chokepoint in
        // ReusableTriggerService refuses a non-editor run of an unpinned
        // workflow first), so this is about the rule, not a live hole: the
        // guard must not grow its own private answer to a question that has
        // exactly one owner.
        budgetState(false, false, "10", "10", thisPeriod());

        NodeExecutionResult result = node().execute(context);

        assertThat(result.isFailure()).isTrue();
        verify(mockAgentClient, never()).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("refuses a PROMOTED editor run: pinning leaves the flag on, so the FK has to win")
    void blocksPromotedEditorRun() {
        // Pinning promotes an editor run to production without stripping
        // __editorRun__. Excusing a run on the flag alone would exempt the
        // production run of every pinned workflow.
        budgetState(true, true, "10", "10", thisPeriod());

        NodeExecutionResult result = node().execute(context);

        assertThat(result.isFailure()).isTrue();
        verify(mockAgentClient, never()).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("lets a BUILDER test fire through even when the workflow is over its cap")
    void editorRunIsNeverBlocked() {
        // The period counter belongs to the workflow, and an editor run never
        // fed it. Blocking here would freeze every test fire the moment
        // production reached its cap, which is the opposite of the promise.
        budgetState(false, "10", "50", thisPeriod());
        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(successResponse());

        NodeExecutionResult result = node().execute(context);

        assertThat(result.isSuccess()).isTrue();
        verify(mockAgentClient).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("lets a production run through once its period has rolled over")
    void rolloverUnblocksTheNextPeriod() {
        // The cap is a periodic allowance, not a death sentence: last period's
        // spend must not keep the workflow down.
        budgetState(true, "10", "50", thisPeriod().minus(40, ChronoUnit.DAYS));
        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(successResponse());

        NodeExecutionResult result = node().execute(context);

        assertThat(result.isSuccess()).isTrue();
        verify(mockAgentClient).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("an uncapped workflow is never blocked, whatever it has spent")
    void noCapNeverBlocks() {
        budgetState(true, null, "9999", thisPeriod());
        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(successResponse());

        NodeExecutionResult result = node().execute(context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("a production run under its cap runs normally")
    void underCapRuns() {
        budgetState(true, "10", "4", thisPeriod());
        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(successResponse());

        NodeExecutionResult result = node().execute(context);

        assertThat(result.isSuccess()).isTrue();
        verify(mockAgentClient).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("fails OPEN when the budget lookup throws: bookkeeping must not freeze production")
    void failsOpenOnLookupError() {
        when(mockRunRepository.findBudgetStateByRunIdPublic("run-budget-1"))
            .thenThrow(new RuntimeException("db down"));
        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(successResponse());

        NodeExecutionResult result = node().execute(context);

        assertThat(result.isSuccess()).isTrue();
        verify(mockAgentClient).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("no run state (a run with no workflow row) does not block")
    void missingStateDoesNotBlock() {
        when(mockRunRepository.findBudgetStateByRunIdPublic("run-budget-1")).thenReturn(Optional.empty());
        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(successResponse());

        NodeExecutionResult result = node().execute(context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("skips the gate entirely when no run repository is wired (CE / unit paths)")
    void skipsGateWithoutRepository() {
        AgentNode node = new AgentNode("agent:nightly_digest", agent());
        node.acceptServices(ServiceRegistry.builder().agentClient(mockAgentClient).build());
        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(successResponse());

        assertThat(node.execute(context).isSuccess()).isTrue();
    }
}
