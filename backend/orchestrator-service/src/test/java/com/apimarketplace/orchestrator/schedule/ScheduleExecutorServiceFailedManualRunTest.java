package com.apimarketplace.orchestrator.schedule;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A MANUAL run that did not happen must not leave the schedule looking like it did.
 *
 * <p>{@code executeNow} advances the schedule optimistically, BEFORE the run is attempted:
 * {@code recordExecution} stamps {@code last_execution_at}, moves {@code next_execution_at}
 * on, and increments {@code execution_count}. Only the execution-queue-unavailable failure
 * was ever undone, so every other failure left all three markers advanced for a run that
 * never started.
 *
 * <p>Two of those markers are load-bearing, which is what lifts this above bookkeeping:
 *
 * <ul>
 *   <li>{@code execution_count} feeds {@code hasReachedMaxExecutions()}. A schedule capped
 *       at 5 sitting on 4 was retired PERMANENTLY by one failed click - the agenda then
 *       greys it out and answers {@code NOT_ARMED} to every further action.</li>
 *   <li>{@code last_execution_at} is what the bell and the agenda rail show as "last run",
 *       so the UI reported a run that did not occur.</li>
 * </ul>
 *
 * <p>Every case here fails on the pre-fix code. Note especially which failure each one
 * uses: an earlier version of this suite reached for the queue-unavailable message, which
 * is the ONE mode that was already rolled back - it passed against the defect it was
 * written to catch. These use ordinary failures instead.
 *
 * <p>The cron daemon deliberately keeps its advance on a failed fire (the slot has had its
 * turn, and re-arming it would retry a broken workflow every minute). This is only about
 * the manual paths.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScheduleExecutorService.executeNow - a failed manual run gives the slot back")
class ScheduleExecutorServiceFailedManualRunTest {

    @Mock private TriggerClient triggerClient;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private ReusableTriggerService triggerService;
    @Mock private ProductionRunResolver productionRunResolver;
    @Mock private AgentClient agentClient;
    @Mock private ConversationClient conversationServiceClient;

    private ScheduleExecutorService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final UUID SCHEDULE_ID = UUID.randomUUID();
    private static final String TRIGGER_ID = "trigger:daily_9am";
    private static final String RUN_ID = "run-schedule-1";
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String ORG = "org-1";

    /**
     * Where the schedule stood before the click.
     *
     * <p><b>Relative to now, and it has to be.</b> These were literal 2026-09-04 / -05
     * instants, chosen because they were in the future on the day the suite was written. The
     * behaviour under test reads the clock - the fire time is put back only while it is
     * still AHEAD, since restoring an overdue one would have the daemon claim it within the
     * minute - so the pending fire silently became a PAST instant on 2026-09-04 and six
     * tests started failing, in CI, for a defect that does not exist. A fixture whose
     * meaning is "the future" must be written as the future.
     *
     * <p>Computed once at class load, so every assertion in the run compares the same value.
     */
    private static final Instant PENDING_FIRE =
            Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
    private static final Instant PREVIOUS_LAST_RUN = PENDING_FIRE.minus(Duration.ofDays(1));
    private static final int PREVIOUS_COUNT = 4;

    /** Where the optimistic advance moved it to: one cron slot on, and a later "last run". */
    private static final Instant ADVANCED_FIRE = PENDING_FIRE.plus(Duration.ofDays(1));
    private static final Instant ADVANCED_LAST_RUN = PREVIOUS_LAST_RUN.plus(Duration.ofMinutes(75));

    @BeforeEach
    void setUp() {
        service = new ScheduleExecutorService(triggerClient, workflowRepository, runRepository,
                triggerService, productionRunResolver, agentClient, conversationServiceClient, null);
        service.setSpreadDispatcherForTesting((task, delayMs) -> task.run());
    }

    @Test
    @DisplayName("restores all three dispatch markers when the run is refused")
    void restoresEveryMarkerOnFailure() {
        // A schedule capped at 5 that has run 4 times. Before the fix this single failed
        // click pushed it to 5, and hasReachedMaxExecutions() retired it for good.
        givenAWorkflowRunIsAvailable();
        givenTheAdvanceSucceeds();
        when(triggerService.executeTrigger(any(), eq(TRIGGER_ID), eq(TriggerType.SCHEDULE), any()))
                .thenReturn(TriggerExecutionResult.failure(RUN_ID, TRIGGER_ID, TriggerType.SCHEDULE,
                        "Workflow has no runnable steps"));

        service.executeNow(workflowSchedule());

        verify(triggerClient).restoreScheduleDispatch(
                eq(SCHEDULE_ID),
                eq(PENDING_FIRE),        // the occurrence the user had scheduled
                eq(PREVIOUS_LAST_RUN),   // the last run that actually happened
                eq(ADVANCED_FIRE),
                eq(ADVANCED_LAST_RUN),
                eq(PREVIOUS_COUNT),      // 4, not 5 - nothing ran
                eq(PREVIOUS_COUNT + 1));
    }

    @Test
    @DisplayName("restores them when the schedule was disabled between the advance and the run")
    void restoresWhenTheScheduleTurnedInactiveMidFlight() {
        // This exit returns before the run is even attempted, and it sits AFTER the
        // advance, so it burned an execution just as thoroughly. It was missed the first
        // time because it does not look like a failure path - it looks like a guard.
        givenAWorkflowRunIsAvailable();
        ScheduledExecutionDto advanced = advancedSchedule();
        advanced.setEnabled(false);
        when(triggerClient.recordScheduleExecution(SCHEDULE_ID)).thenReturn(advanced);

        service.executeNow(workflowSchedule());

        verify(triggerClient).restoreScheduleDispatch(
                eq(SCHEDULE_ID), eq(PENDING_FIRE), eq(PREVIOUS_LAST_RUN),
                any(), any(), eq(PREVIOUS_COUNT), eq(PREVIOUS_COUNT + 1));
    }

    @Test
    @DisplayName("leaves the advance alone when the run actually started")
    void successfulRunKeepsItsAdvance() {
        // The other half of the contract. A run DID happen, so the count and the last-run
        // stamp are correct and must stand - undoing them would hand out a free run and
        // make the cap meaningless in the opposite direction.
        givenAWorkflowRunIsAvailable();
        givenTheAdvanceSucceeds();
        when(triggerService.executeTrigger(any(), eq(TRIGGER_ID), eq(TriggerType.SCHEDULE), any()))
                .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.SCHEDULE, Set.of(), 1));

        service.executeNow(workflowSchedule());

        verify(triggerClient, never()).restoreScheduleDispatch(
                any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("writes nothing back when the run was refused BEFORE the advance")
    void noRestoreWhenNothingWasAdvanced() {
        // No production run to fire: executeNow bails out before recordScheduleExecution,
        // so there is nothing to undo. Restoring here would write a fire time onto a row
        // that never moved, which is a different way of corrupting it.
        when(productionRunResolver.resolve(eq(WORKFLOW_ID), any()))
                .thenReturn(new ProductionRunResolver.Resolution(
                        Optional.empty(), ProductionRunResolver.Outcome.NO_PRODUCTION_RUN, "test-workflow"));
        when(productionRunResolver.resolveStepByStepRun(WORKFLOW_ID)).thenReturn(Optional.empty());

        service.executeNow(workflowSchedule());

        verify(triggerClient, never()).restoreScheduleDispatch(
                any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("does not write back a fire time that is already due")
    void overdueFireIsNotHandedToTheDaemon() {
        // The counters still go back - nothing ran, so they must not record a run - but an
        // overdue fire time does not. The daemon's claim query is `next_execution_at <= now`,
        // so restoring one would have the next tick fire it within the minute: one failed
        // click becoming a run the user never asked for. The docs described this guard as
        // covering this path; it only existed one layer up.
        Instant overdue = Instant.now().minusSeconds(120);
        ScheduledExecutionDto before = workflowSchedule();
        before.setNextExecutionAt(overdue);
        givenAWorkflowRunIsAvailable();
        givenTheAdvanceSucceeds();
        when(triggerService.executeTrigger(any(), eq(TRIGGER_ID), eq(TriggerType.SCHEDULE), any()))
                .thenReturn(TriggerExecutionResult.failure(RUN_ID, TRIGGER_ID, TriggerType.SCHEDULE,
                        "Workflow has no runnable steps"));

        service.executeNow(before);

        verify(triggerClient).restoreScheduleDispatch(
                eq(SCHEDULE_ID),
                eq(ADVANCED_FIRE),        // the advance STANDS - the overdue time is not restored
                eq(PREVIOUS_LAST_RUN),
                eq(ADVANCED_FIRE), eq(ADVANCED_LAST_RUN),
                eq(PREVIOUS_COUNT),       // but the count still goes back: nothing ran
                eq(PREVIOUS_COUNT + 1));
    }

    @Nested
    @DisplayName("agent schedules - the same contract, the other resource kind")
    class AgentSchedules {

        /*
         * The agent branch of executeNow does its own optimistic advance and used to keep it
         * on every failure. It was missed because the fix that closed this for workflows read
         * as complete: one method, one restore, tests green. Agents are a first-class agenda
         * resource with their own occurrences, so "run early" on one had the full defect -
         * a schedule capped at 5 sitting on 4 was retired permanently by an unreachable
         * conversation-service, and the rail then reported a run that never happened.
         */

        @Test
        @DisplayName("restores every marker when conversation-service refuses the run")
        void restoresWhenTheAgentRunFails() {
            givenAgentReachable();
            when(conversationServiceClient.sendChatSync(anyString(), anyString(), anyString(),
                    anyString(), any(), any(), anyString(), any(), any()))
                    .thenReturn(Map.of("success", false, "error", "Provider deepseek is not configured"));

            TriggerExecutionResult result = service.executeNow(agentSchedule());

            assertThat(result.success()).isFalse();
            verify(triggerClient).restoreScheduleDispatch(
                    eq(SCHEDULE_ID), eq(PENDING_FIRE), eq(PREVIOUS_LAST_RUN),
                    eq(ADVANCED_FIRE), eq(ADVANCED_LAST_RUN),
                    eq(PREVIOUS_COUNT), eq(PREVIOUS_COUNT + 1));
        }

        @Test
        @DisplayName("restores when the conversation cannot even be opened")
        void restoresWhenTheConversationCannotBeOpened() {
            // A different exit, past the same advance. Covering only the chat-failure one
            // would repeat the mistake this whole suite exists to document.
            givenAgentReachable();
            when(conversationServiceClient.findOrCreateAgentConversation(anyString(), anyString(),
                    anyString(), any())).thenReturn(null);

            service.executeNow(agentSchedule());

            verify(triggerClient).restoreScheduleDispatch(
                    eq(SCHEDULE_ID), eq(PENDING_FIRE), eq(PREVIOUS_LAST_RUN),
                    any(), any(), eq(PREVIOUS_COUNT), eq(PREVIOUS_COUNT + 1));
        }

        @Test
        @DisplayName("restores when the chat call throws")
        void restoresWhenTheChatCallThrows() {
            givenAgentReachable();
            when(conversationServiceClient.sendChatSync(anyString(), anyString(), anyString(),
                    anyString(), any(), any(), anyString(), any(), any()))
                    .thenThrow(new RuntimeException("conversation-service unreachable"));

            service.executeNow(agentSchedule());

            verify(triggerClient).restoreScheduleDispatch(
                    eq(SCHEDULE_ID), eq(PENDING_FIRE), eq(PREVIOUS_LAST_RUN),
                    any(), any(), eq(PREVIOUS_COUNT), eq(PREVIOUS_COUNT + 1));
        }

        @Test
        @DisplayName("restores when the schedule was disabled between the advance and the run")
        void restoresWhenDisabledMidFlight() {
            givenAgentReachable();
            ScheduledExecutionDto advanced = advancedAgentSchedule();
            advanced.setEnabled(false);
            when(triggerClient.recordScheduleExecution(SCHEDULE_ID)).thenReturn(advanced);

            service.executeNow(agentSchedule());

            verify(triggerClient).restoreScheduleDispatch(
                    eq(SCHEDULE_ID), eq(PENDING_FIRE), eq(PREVIOUS_LAST_RUN),
                    any(), any(), eq(PREVIOUS_COUNT), eq(PREVIOUS_COUNT + 1));
        }

        @Test
        @DisplayName("leaves the advance alone when the agent actually ran")
        void successKeepsItsAdvance() {
            givenAgentReachable();
            when(conversationServiceClient.sendChatSync(anyString(), anyString(), anyString(),
                    anyString(), any(), any(), anyString(), any(), any()))
                    .thenReturn(Map.of("success", true));

            assertThat(service.executeNow(agentSchedule()).success()).isTrue();
            verify(triggerClient, never()).restoreScheduleDispatch(
                    any(), any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("writes nothing back when the run was refused BEFORE the advance")
        void noRestoreWhenNothingWasAdvanced() {
            // An inactive agent is refused before Phase 2 runs, so there is nothing to undo.
            // Restoring here would write a fire time onto a row that never moved.
            AgentDto inactive = new AgentDto();
            inactive.setId(AGENT_ID);
            inactive.setName("Reporter");
            inactive.setIsActive(false);
            inactive.setOrganizationId(ORG);
            when(agentClient.buildScheduledPrompt(any(), anyString(), any(), any()))
                    .thenReturn("Do the thing");
            when(agentClient.getAgent(any(), anyString(), any())).thenReturn(inactive);

            service.executeNow(agentSchedule());

            verify(triggerClient, never()).restoreScheduleDispatch(
                    any(), any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        }

        private void givenAgentReachable() {
            when(agentClient.buildScheduledPrompt(any(), anyString(), any(), any()))
                    .thenReturn("Do the thing");
            when(agentClient.getAgent(any(), anyString(), any())).thenReturn(activeAgent());
            when(triggerClient.recordScheduleExecution(SCHEDULE_ID)).thenReturn(advancedAgentSchedule());
            when(conversationServiceClient.findOrCreateAgentConversation(anyString(), anyString(),
                    anyString(), any())).thenReturn("conv-1");
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
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────

    private void givenAWorkflowRunIsAvailable() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(RunStatus.WAITING_TRIGGER);
        run.setExecutionMode(ExecutionMode.AUTOMATIC);
        run.setPlanVersion(5);
        when(productionRunResolver.resolve(eq(WORKFLOW_ID), any()))
                .thenReturn(new ProductionRunResolver.Resolution(
                        Optional.of(run), ProductionRunResolver.Outcome.FOUND, "test-workflow"));
    }

    private void givenTheAdvanceSucceeds() {
        when(triggerClient.recordScheduleExecution(SCHEDULE_ID)).thenReturn(advancedSchedule());
    }

    /** The row as the user left it: one occurrence pending, four runs behind it. */
    private ScheduledExecutionDto workflowSchedule() {
        ScheduledExecutionDto dto = baseSchedule();
        dto.setNextExecutionAt(PENDING_FIRE);
        dto.setLastExecutionAt(PREVIOUS_LAST_RUN);
        dto.setExecutionCount(PREVIOUS_COUNT);
        return dto;
    }

    /** The row after the optimistic advance, which is what trigger-service returns. */
    private ScheduledExecutionDto advancedSchedule() {
        ScheduledExecutionDto dto = baseSchedule();
        dto.setNextExecutionAt(ADVANCED_FIRE);
        dto.setLastExecutionAt(ADVANCED_LAST_RUN);
        dto.setExecutionCount(PREVIOUS_COUNT + 1);
        return dto;
    }

    /** The agent twin of workflowSchedule(): same markers, an agent instead of a workflow. */
    private ScheduledExecutionDto agentSchedule() {
        ScheduledExecutionDto dto = baseSchedule();
        dto.setWorkflowId(null);
        dto.setTriggerId(null);
        dto.setAgentEntityId(AGENT_ID);
        dto.setSchedulePrompt("Do the thing");
        dto.setNextExecutionAt(PENDING_FIRE);
        dto.setLastExecutionAt(PREVIOUS_LAST_RUN);
        dto.setExecutionCount(PREVIOUS_COUNT);
        return dto;
    }

    private ScheduledExecutionDto advancedAgentSchedule() {
        ScheduledExecutionDto dto = agentSchedule();
        dto.setNextExecutionAt(ADVANCED_FIRE);
        dto.setLastExecutionAt(ADVANCED_LAST_RUN);
        dto.setExecutionCount(PREVIOUS_COUNT + 1);
        return dto;
    }

    private ScheduledExecutionDto baseSchedule() {
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(SCHEDULE_ID);
        dto.setWorkflowId(WORKFLOW_ID);
        dto.setTriggerId(TRIGGER_ID);
        dto.setTenantId("user-1");
        dto.setOrganizationId("org-1");
        dto.setCronExpression("0 9 * * *");
        dto.setTimezone("UTC");
        dto.setMaxExecutions(5);
        dto.setEnabled(true);
        dto.setIsActive(true);
        return dto;
    }
}
