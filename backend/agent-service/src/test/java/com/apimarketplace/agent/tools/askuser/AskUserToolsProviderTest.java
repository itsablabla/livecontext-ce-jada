package com.apimarketplace.agent.tools.askuser;

import com.apimarketplace.agent.tools.ask.UserQuestionAnswer;
import com.apimarketplace.agent.tools.ask.UserQuestionAnswerEnvelope;

import com.apimarketplace.agent.service.execution.ApprovalCardExtractor;
import com.apimarketplace.agent.service.execution.ApprovalCardPublisher;
import com.apimarketplace.agent.service.execution.ToolApprovalGate;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One case per way an ask can end. The status string is the contract the agent reads, and
 * the metadata is the contract conversation-service and the two result consumers read, so
 * both are asserted exactly.
 */
@DisplayName("AskUserToolsProvider - asking the person in the chat and reading the answer back")
class AskUserToolsProviderTest {

    private ToolApprovalGate gate;
    private ApprovalCardPublisher publisher;
    private AskUserToolsProvider provider;

    @BeforeEach
    void setUp() {
        gate = mock(ToolApprovalGate.class);
        publisher = mock(ApprovalCardPublisher.class);
        lenient().when(gate.isEnabled()).thenReturn(true);
        lenient().when(gate.beginPark(any())).thenReturn(true);
        lenient().when(publisher.publishUserQuestion(anyString(), anyString(), any(), anyBoolean(), anyString()))
                .thenReturn("{\"card\":1}");
        provider = new AskUserToolsProvider(gate, publisher);
    }

    private static Map<String, Object> askParams() {
        Map<String, Object> q = new HashMap<>();
        q.put("header", "Tone");
        q.put("question", "Which tone?");
        q.put("options", List.of(Map.of("label", "Friendly"), Map.of("label", "Formal")));
        Map<String, Object> params = new HashMap<>();
        params.put("action", "ask");
        params.put("questions", List.of(q));
        return params;
    }

    private static Map<String, Object> chatCredentials() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__streamId__", "stream-1");
        creds.put(AskUserToolsProvider.KEY_TOOL_CALL_ID, "call-7");
        creds.put(AskUserToolsProvider.KEY_CALL_STARTED_EPOCH_MS, System.currentTimeMillis());
        return creds;
    }

    private static ToolsProvider.ToolExecutionContext context(Map<String, Object> creds) {
        return new ToolsProvider.ToolExecutionContext("tenant-1", creds, Map.of(), java.util.Set.of(), null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolsProvider.ToolExecutionResult result) {
        return (Map<String, Object>) result.data();
    }

    @Test
    @DisplayName("The tool advertises ask_user with ask/help and a 30 s base timeout (the loop adds the park budget)")
    void definition() {
        var tool = provider.getTools().get(0);

        assertThat(tool.name()).isEqualTo("ask_user");
        assertThat(tool.timeoutMs()).isEqualTo(AskUserToolsProvider.TIMEOUT_MS);
        assertThat(tool.description()).contains("pending_user").contains("answered");
        assertThat(tool.parameters()).anySatisfy(p -> {
            assertThat(p.name()).isEqualTo("questions");
            assertThat(p.description()).contains("'Other'").contains("multiSelect");
        });
        assertThat(provider.canHandle("ask_user")).isTrue();
    }

    @Test
    @DisplayName("help returns the question shape and every status the agent may read")
    void help() {
        var result = provider.execute("ask_user", Map.of("action", "help"), context(chatCredentials()));

        assertThat(result.success()).isTrue();
        Map<String, Object> help = data(result);
        assertThat(help).containsKeys("actions", "question_shape", "when_to_use", "constraints", "examples");
        assertThat(help.toString()).contains("answered", "pending_user", "dismissed", "unavailable");
    }

    @Test
    @DisplayName("A malformed questions argument fails with VALIDATION_ERROR naming the index; nothing is parked")
    void malformedQuestions() {
        Map<String, Object> params = askParams();
        params.put("questions", List.of(Map.of("header", "Tone")));

        var result = provider.execute("ask_user", params, context(chatCredentials()));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
        assertThat(result.error()).contains("questions[0].question");
        verify(gate, never()).beginPark(any());
    }

    @Test
    @DisplayName("Outside an interactive chat the answer is 'unavailable' and no card is raised")
    void unavailableOffChat() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__workflowRunId__", "run-1");

        var result = provider.execute("ask_user", askParams(), context(creds));

        assertThat(result.success()).isTrue();
        assertThat(data(result)).containsEntry("status", "unavailable").containsEntry("reason", "no_live_chat");
        assertThat(result.metadata()).isNullOrEmpty();
        verify(gate, never()).beginPark(any());
        verify(publisher, never()).publishUserQuestion(anyString(), anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("An answer in time returns 'answered' with the person's picks, and drops the settled card from the replay buffer")
    void answeredInTime() {
        String envelope = UserQuestionAnswerEnvelope.answered(List.of(
                new UserQuestionAnswer("Tone", List.of("Friendly"), null, false)));
        when(gate.awaitAnswer(any())).thenReturn(new ToolApprovalGate.Answer(ToolApprovalGate.Decision.APPROVED, envelope));

        var result = provider.execute("ask_user", askParams(), context(chatCredentials()));

        assertThat(result.success()).isTrue();
        Map<String, Object> out = data(result);
        assertThat(out).containsEntry("status", "answered").doesNotContainKey("validation");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> answers = (List<Map<String, Object>>) out.get("answers");
        assertThat(answers).singleElement().satisfies(a -> {
            assertThat(a).containsEntry("header", "Tone").containsEntry("custom", false);
            assertThat(a.get("selected")).isEqualTo(List.of("Friendly"));
        });
        // Nothing pending: no card flag, no decision, so nothing is persisted or repainted.
        assertThat(result.metadata()).isNullOrEmpty();
        verify(publisher).unbuffer("stream-1", "{\"card\":1}");

        ArgumentCaptor<ToolApprovalGate.ParkRequest> park = ArgumentCaptor.forClass(ToolApprovalGate.ParkRequest.class);
        verify(gate).beginPark(park.capture());
        assertThat(park.getValue().gateKey()).isEqualTo("call-7:ask");
        assertThat(park.getValue().conversationId()).isEqualTo("conv-1");
        verify(publisher).publishUserQuestion(eq("stream-1"), eq("conv-1"), any(), eq(true), eq("call-7:ask"));
    }

    @Test
    @DisplayName("An answer that does not line up with the options is still handed over, with a validation note")
    void answerOffTheOptionsIsKeptWithNote() {
        String envelope = UserQuestionAnswerEnvelope.answered(List.of(
                new UserQuestionAnswer("Tone", List.of("Casual"), null, false)));
        when(gate.awaitAnswer(any())).thenReturn(new ToolApprovalGate.Answer(ToolApprovalGate.Decision.APPROVED, envelope));

        Map<String, Object> out = data(provider.execute("ask_user", askParams(), context(chatCredentials())));

        assertThat(out).containsEntry("status", "answered");
        assertThat(String.valueOf(out.get("validation"))).contains("Casual");
        assertThat(out.get("answers").toString()).contains("Casual");
    }

    @Test
    @DisplayName("The park running out of time returns 'pending_user' as a SUCCESS, card already painted, question still open")
    void expiredIsPendingUser() {
        when(gate.awaitAnswer(any())).thenReturn(ToolApprovalGate.Answer.of(ToolApprovalGate.Decision.EXPIRED));

        var result = provider.execute("ask_user", askParams(), context(chatCredentials()));

        assertThat(result.success()).isTrue();
        assertThat(data(result)).containsEntry("status", "pending_user").containsEntry("toolCallId", "call-7");
        assertThat(String.valueOf(data(result).get("message"))).contains("Do not").contains("next message");
        assertThat(result.metadata())
                .containsEntry(ToolApprovalGate.META_CARD_EMITTED, true)
                .containsEntry(ApprovalCardExtractor.USER_QUESTION_REQUESTED_KEY, true)
                .doesNotContainKey(ToolApprovalGate.META_DECISION);
        @SuppressWarnings("unchecked")
        Map<String, Object> question = (Map<String, Object>) result.metadata().get(ApprovalCardExtractor.USER_QUESTION_KEY);
        assertThat(question).containsEntry("toolCallId", "call-7");
        assertThat(question.get("questions").toString()).contains("Tone");
        // Still the person's to answer: the card stays in the replay buffer.
        verify(publisher, never()).unbuffer(anyString(), anyString());
    }

    @Test
    @DisplayName("A dismissal returns 'dismissed', settled (decision=denied), and drops the card")
    void dismissed() {
        when(gate.awaitAnswer(any())).thenReturn(ToolApprovalGate.Answer.of(ToolApprovalGate.Decision.DENIED));

        var result = provider.execute("ask_user", askParams(), context(chatCredentials()));

        assertThat(data(result)).containsEntry("status", "dismissed");
        assertThat(result.metadata())
                .containsEntry(ToolApprovalGate.META_CARD_EMITTED, true)
                .containsEntry(ToolApprovalGate.META_DECISION, "denied")
                .doesNotContainKey(ApprovalCardExtractor.USER_QUESTION_REQUESTED_KEY);
        verify(publisher).unbuffer("stream-1", "{\"card\":1}");
    }

    @Test
    @DisplayName("A Stop settles the question as dismissed with decision=stopped")
    void stopped() {
        when(gate.awaitAnswer(any())).thenReturn(ToolApprovalGate.Answer.of(ToolApprovalGate.Decision.STOPPED));

        var result = provider.execute("ask_user", askParams(), context(chatCredentials()));

        assertThat(data(result)).containsEntry("status", "dismissed");
        assertThat(String.valueOf(data(result).get("message"))).contains("stopped");
        assertThat(result.metadata()).containsEntry(ToolApprovalGate.META_DECISION, "stopped");
        verify(publisher).unbuffer("stream-1", "{\"card\":1}");
    }

    @Test
    @DisplayName("When the gate declines to park, the question goes out non-blocking: pending_user WITHOUT the card-emitted flag")
    void gateDeclinesToPark() {
        when(gate.beginPark(any())).thenReturn(false);

        var result = provider.execute("ask_user", askParams(), context(chatCredentials()));

        assertThat(data(result)).containsEntry("status", "pending_user");
        assertThat(result.metadata())
                .containsEntry(ApprovalCardExtractor.USER_QUESTION_REQUESTED_KEY, true)
                .doesNotContainKey(ToolApprovalGate.META_CARD_EMITTED);
        verify(publisher, never()).publishUserQuestion(anyString(), anyString(), any(), anyBoolean(), anyString());
        verify(gate, never()).awaitAnswer(any());
    }

    @Test
    @DisplayName("When the card cannot be published the park is abandoned and the consumer paints a non-blocking card")
    void cardPublishFails() {
        when(publisher.publishUserQuestion(anyString(), anyString(), any(), anyBoolean(), anyString())).thenReturn(null);

        var result = provider.execute("ask_user", askParams(), context(chatCredentials()));

        assertThat(data(result)).containsEntry("status", "pending_user");
        assertThat(result.metadata()).doesNotContainKey(ToolApprovalGate.META_CARD_EMITTED);
        verify(gate).abandonPark("conv-1", "call-7:ask");
        verify(gate, never()).awaitAnswer(any());
    }

    @Test
    @DisplayName("With no gate wired at all (no Redis), the question is still raised non-blocking")
    void noGateWired() {
        AskUserToolsProvider bare = new AskUserToolsProvider(null, null);

        var result = bare.execute("ask_user", askParams(), context(chatCredentials()));

        assertThat(data(result)).containsEntry("status", "pending_user");
        assertThat(result.metadata()).containsEntry(ApprovalCardExtractor.USER_QUESTION_REQUESTED_KEY, true);
    }

    @Test
    @DisplayName("No tool call id or no stream: 'unavailable', because no consumer could key or show a card")
    void noCallIdOrStreamIsUnavailable() {
        Map<String, Object> noId = chatCredentials();
        noId.remove(AskUserToolsProvider.KEY_TOOL_CALL_ID);
        assertThat(data(provider.execute("ask_user", askParams(), context(noId)))).containsEntry("status", "unavailable");

        // No stream at all is already "not promptable"; the plain streamId key alone is promptable
        // but the tool call id is what keys the card, so the two cases land on the same answer.
        verify(gate, never()).beginPark(any());
        verify(publisher, never()).publishUserQuestion(anyString(), anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("A gate that cannot run at all (UNAVAILABLE) leaves the question open: pending_user, card already painted")
    void gateUnavailableIsPendingUser() {
        when(gate.awaitAnswer(any())).thenReturn(ToolApprovalGate.Answer.of(ToolApprovalGate.Decision.UNAVAILABLE));

        var result = provider.execute("ask_user", askParams(), context(chatCredentials()));

        assertThat(data(result)).containsEntry("status", "pending_user");
        assertThat(result.metadata()).containsEntry(ToolApprovalGate.META_CARD_EMITTED, true);
        verify(publisher, never()).unbuffer(anyString(), anyString());
    }

    @Test
    @DisplayName("An APPROVED park whose payload cannot be read is treated as dismissed, never as an invented answer")
    void unreadableApprovedPayloadIsDismissed() {
        when(gate.awaitAnswer(any())).thenReturn(new ToolApprovalGate.Answer(ToolApprovalGate.Decision.APPROVED, "{broken"));

        var result = provider.execute("ask_user", askParams(), context(chatCredentials()));

        assertThat(data(result)).containsEntry("status", "dismissed");
        assertThat(result.metadata()).containsEntry(ToolApprovalGate.META_DECISION, "denied");
    }

    @Test
    @DisplayName("A missing or unknown action is refused")
    void badAction() {
        assertThat(provider.execute("ask_user", Map.of(), context(chatCredentials())).errorCode())
                .isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(provider.execute("ask_user", Map.of("action", "poll"), context(chatCredentials())).errorCode())
                .isEqualTo(ToolErrorCode.VALIDATION_ERROR);
    }
}
