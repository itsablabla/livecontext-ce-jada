package com.apimarketplace.orchestrator.schedule;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard: an agent schedule run MANUALLY must report what actually happened.
 *
 * <p>Found live on a CE stack (2026-08-31). The agenda's run-early action returned HTTP 200
 * and told the user "Started. The scheduled run still happens", while the same request had
 * logged {@code [Schedule] Agent ... execution failed: Provider deepseek is not configured}.
 * The cause was structural: {@code executeAgentSchedule} logged its failures and returned
 * {@code void}, so {@code executeAgentNow} had nothing to inspect and reported success
 * unconditionally.
 *
 * <p>The failure is silent by construction, which is what makes it worth a test: nothing
 * about the response tells the user their run did not happen, and the schedule's own state
 * looks untouched afterwards. These cases fail on the pre-fix code, which returned success
 * for every one of them.
 *
 * <p>The cron daemon deliberately still ignores the outcome - a scheduled fire that cannot
 * run is logged and the tick moves on - so this is only about the manual paths.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScheduleExecutorService.executeNow - agent schedule outcome")
class ScheduleExecutorServiceAgentRunNowOutcomeTest {

    @Mock private TriggerClient triggerClient;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private ReusableTriggerService triggerService;
    @Mock private ProductionRunResolver productionRunResolver;
    @Mock private AgentClient agentClient;
    @Mock private ConversationClient conversationServiceClient;

    private ScheduleExecutorService service;

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID SCHEDULE_ID = UUID.randomUUID();
    private static final String TENANT = "user-1";
    private static final String ORG = "org-1";

    @BeforeEach
    void setUp() {
        service = new ScheduleExecutorService(triggerClient, workflowRepository, runRepository,
                triggerService, productionRunResolver, agentClient, conversationServiceClient, null);
        service.setSpreadDispatcherForTesting((task, delayMs) -> task.run());
    }

    private ScheduledExecutionDto agentSchedule() {
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(SCHEDULE_ID);
        dto.setAgentEntityId(AGENT_ID);
        dto.setTenantId(TENANT);
        dto.setOrganizationId(ORG);
        dto.setCronExpression("0 9 * * *");
        dto.setTimezone("UTC");
        dto.setEnabled(true);
        dto.setIsActive(true);
        dto.setSchedulePrompt("Do the thing");
        dto.setNextExecutionAt(Instant.now().plusSeconds(3600));
        return dto;
    }

    private AgentDto activeAgent() {
        AgentDto agent = new AgentDto();
        agent.setId(AGENT_ID);
        agent.setName("Reporter");
        agent.setIsActive(true);
        agent.setOrganizationId(ORG);
        agent.setModelName("deepseek-chat");
        agent.setModelProvider("deepseek");
        return agent;
    }

    /** Everything up to the chat call resolves; the caller decides what the chat returns. */
    private void givenAgentReachable() {
        when(agentClient.buildScheduledPrompt(any(), anyString(), any(), any())).thenReturn("Do the thing");
        when(agentClient.getAgent(any(), anyString(), any())).thenReturn(activeAgent());
        when(triggerClient.recordScheduleExecution(any())).thenReturn(agentSchedule());
        when(conversationServiceClient.findOrCreateAgentConversation(anyString(), anyString(), anyString(), any()))
                .thenReturn("conv-1");
    }

    @Test
    @DisplayName("reports FAILURE, with the provider's reason, when the agent run fails")
    void reportsFailureWhenTheAgentRunFails() {
        // The exact shape seen live: conversation-service answers success=false with a
        // reason, the schedule executor logs it, and the user is told the run started.
        givenAgentReachable();
        when(conversationServiceClient.sendChatSync(anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any(), any()))
                .thenReturn(Map.of("success", false, "error", "Provider deepseek is not configured"));

        TriggerExecutionResult result = service.executeNow(agentSchedule());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Provider deepseek is not configured");
    }

    @Test
    @DisplayName("reports SUCCESS when the agent actually ran")
    void reportsSuccessWhenTheAgentRan() {
        // The other half of the contract: the fix must not turn every run into a failure.
        givenAgentReachable();
        when(conversationServiceClient.sendChatSync(anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any(), any()))
                .thenReturn(Map.of("success", true));

        TriggerExecutionResult result = service.executeNow(agentSchedule());

        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("reports FAILURE when the schedule has no prompt to run")
    void reportsFailureWhenThereIsNoPrompt() {
        // A schedule with neither pending tasks nor a static prompt runs nothing at all.
        when(agentClient.buildScheduledPrompt(any(), anyString(), any(), any())).thenReturn("  ");

        TriggerExecutionResult result = service.executeNow(agentSchedule());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("no prompt");
    }

    @Test
    @DisplayName("reports FAILURE when the agent is gone from the workspace")
    void reportsFailureWhenTheAgentIsMissing() {
        when(agentClient.buildScheduledPrompt(any(), anyString(), any(), any())).thenReturn("Do the thing");
        when(agentClient.getAgent(any(), anyString(), any())).thenReturn(null);

        TriggerExecutionResult result = service.executeNow(agentSchedule());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("no longer exists");
    }

    @Test
    @DisplayName("does NOT archive the schedule when a manual run cannot find the agent")
    void manualRunDoesNotArchiveOnAMissingAgent() {
        // The daemon archives here so an unattended schedule pointing at a deleted agent
        // stops retrying forever. ARCHIVED is permanent - toggle-on refuses it - so doing
        // the same on a user's click turns a transient agent-service RPC failure into
        // irreversible loss of the schedule, reported only as a generic refusal.
        when(agentClient.buildScheduledPrompt(any(), anyString(), any(), any())).thenReturn("Do the thing");
        when(agentClient.getAgent(any(), anyString(), any())).thenReturn(null);

        TriggerExecutionResult result = service.executeNow(agentSchedule());

        assertThat(result.success()).isFalse();
        verify(triggerClient, never()).disableSchedule(any());
    }

    @Test
    @DisplayName("reports FAILURE when the agent is inactive")
    void reportsFailureWhenTheAgentIsInactive() {
        AgentDto inactive = activeAgent();
        inactive.setIsActive(false);
        when(agentClient.buildScheduledPrompt(any(), anyString(), any(), any())).thenReturn("Do the thing");
        when(agentClient.getAgent(any(), anyString(), any())).thenReturn(inactive);

        TriggerExecutionResult result = service.executeNow(agentSchedule());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("inactive");
    }

    @Test
    @DisplayName("reports FAILURE when the agent belongs to another workspace")
    void reportsFailureOnWorkspaceMismatch() {
        // The scope guard already refused to fire; the caller must hear about it rather
        // than be told a run started in a workspace it was never allowed to touch.
        AgentDto foreign = activeAgent();
        foreign.setOrganizationId("org-other");
        when(agentClient.buildScheduledPrompt(any(), anyString(), any(), any())).thenReturn("Do the thing");
        when(agentClient.getAgent(any(), anyString(), any())).thenReturn(foreign);

        TriggerExecutionResult result = service.executeNow(agentSchedule());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("different workspace");
    }

    @Test
    @DisplayName("reports FAILURE when the agent conversation cannot be opened")
    void reportsFailureWhenTheConversationCannotBeOpened() {
        when(agentClient.buildScheduledPrompt(any(), anyString(), any(), any())).thenReturn("Do the thing");
        when(agentClient.getAgent(any(), anyString(), any())).thenReturn(activeAgent());
        when(triggerClient.recordScheduleExecution(any())).thenReturn(agentSchedule());
        when(conversationServiceClient.findOrCreateAgentConversation(anyString(), anyString(), anyString(), any()))
                .thenReturn(null);

        TriggerExecutionResult result = service.executeNow(agentSchedule());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("conversation");
    }

    @Test
    @DisplayName("reports FAILURE when the chat call throws")
    void reportsFailureWhenTheChatCallThrows() {
        givenAgentReachable();
        when(conversationServiceClient.sendChatSync(anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("conversation-service unreachable"));

        TriggerExecutionResult result = service.executeNow(agentSchedule());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("unreachable");
    }
}
