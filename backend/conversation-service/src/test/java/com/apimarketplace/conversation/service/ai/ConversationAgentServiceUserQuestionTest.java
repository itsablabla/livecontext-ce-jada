package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.service.ai.callback.AgentContextBuilder;
import com.apimarketplace.conversation.service.ai.schema.HelpSeenRegistry;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * End-of-turn handling of an ask_user result: an OPEN question (pending_user) must survive a
 * reload and be replayed live on the bridge path; a SETTLED one (answered, dismissed,
 * stopped) must leave nothing behind.
 */
@DisplayName("ConversationAgentService - persisting and replaying an open question card")
class ConversationAgentServiceUserQuestionTest {

    private static final List<Map<String, Object>> QUESTIONS = List.of(Map.of(
            "header", "Tone", "question", "Which?",
            "options", List.of(Map.of("label", "A"), Map.of("label", "B")), "multiSelect", false));

    private EventBus eventBus;
    private PendingActionService pendingActionService;
    private ConversationAgentService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        eventBus = mock(EventBus.class);
        pendingActionService = mock(PendingActionService.class);
        service = new ConversationAgentService(
            mock(AgentContextBuilder.class),
            mock(AgentObservabilityClient.class),
            mock(AgentConfigProvider.class),
            mock(CreditConsumptionClient.class),
            mock(MessageService.class),
            pendingActionService,
            mock(ToolResultService.class),
            new ObjectMapper(),
            mock(AgentClient.class),
            mock(StreamStateService.class),
            eventBus,
            mock(HelpSeenRegistry.class),
            mock(MessageRepository.class),
            "http://localhost:8087"
        );
    }

    private static AgentExecutionResponseDto response(Map<String, Object> metadata) {
        Map<String, Object> toolResult = new HashMap<>();
        toolResult.put("metadata", metadata);
        return new AgentExecutionResponseDto(
            true, null, null, List.of(toolResult), 0, null, null, 0L, null, null,
            null, null, null, null, null, null, null, null, null);
    }

    private static Map<String, Object> openQuestion() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("userQuestionRequested", true);
        meta.put("userQuestion", Map.of("toolCallId", "call-7", "questions", QUESTIONS));
        meta.put("approvalCardEmitted", true);
        return meta;
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("An open question is persisted as waiting_for=user_question keyed ask:<toolCallId> with its questions")
    void openQuestionIsPersisted() {
        service.persistPendingActionIfNeeded("conv-1", response(openQuestion()));

        ArgumentCaptor<List<Map<String, Object>>> actions = ArgumentCaptor.forClass(List.class);
        verify(pendingActionService).addPendingActions(eq("conv-1"), actions.capture());
        Map<String, Object> action = actions.getValue().get(0);
        assertThat(action).containsEntry("waiting_for", "user_question").containsEntry("tool_call_id", "call-7");
        assertThat((List<?>) action.get("questions")).hasSize(1);
        assertThat(PendingActionService.pendingActionKey(action)).isEqualTo("ask:call-7");
    }

    @Test
    @DisplayName("A dismissed or stopped question is settled: nothing is persisted")
    void settledQuestionIsNotPersisted() {
        Map<String, Object> dismissed = new HashMap<>();
        dismissed.put("approvalCardEmitted", true);
        dismissed.put("approvalGateDecision", "denied");
        service.persistPendingActionIfNeeded("conv-1", response(dismissed));

        Map<String, Object> stopped = new HashMap<>();
        stopped.put("userQuestionRequested", true);
        stopped.put("userQuestion", Map.of("toolCallId", "call-7", "questions", QUESTIONS));
        stopped.put("approvalGateDecision", "stopped");
        service.persistPendingActionIfNeeded("conv-1", response(stopped));

        verify(pendingActionService, never()).addPendingActions(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("An answered question carries no flag, so nothing is persisted")
    void answeredQuestionIsNotPersisted() {
        service.persistPendingActionIfNeeded("conv-1", response(new HashMap<>()));

        verifyNoInteractions(pendingActionService);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Bridge path: an open question is replayed live as an askUser event on the conversation's WS channel")
    void openQuestionIsEmittedLive() throws Exception {
        service.emitPendingApprovalIfPresent("conv-1", response(openQuestion()));

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(eventBus).publish(channel.capture(), payload.capture());
        assertThat(channel.getValue()).isEqualTo("ws:conversation:conv-1");
        Map<String, Object> event = mapper.readValue(payload.getValue(), Map.class);
        assertThat(event).containsKey("askUser").doesNotContainKey("toolAuthorization").doesNotContainKey("services");
        Map<String, Object> askUser = (Map<String, Object>) event.get("askUser");
        assertThat(askUser).containsEntry("toolCallId", "call-7").doesNotContainKey("blocking");
        assertThat((List<?>) askUser.get("questions")).hasSize(1);
    }

    @Test
    @DisplayName("A payload with no questions is ignored rather than persisted as an empty card")
    void emptyQuestionsIgnored() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("userQuestionRequested", true);
        meta.put("userQuestion", Map.of("toolCallId", "call-7", "questions", List.of()));

        service.persistPendingActionIfNeeded("conv-1", response(meta));

        verifyNoInteractions(pendingActionService);
    }
}
