package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.tools.ask.UserQuestionAnswer;
import com.apimarketplace.agent.tools.ask.UserQuestionAnswerEnvelope;
import com.apimarketplace.conversation.client.StreamRedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gate now carries a payload when the card is a question. The two legacy words must
 * keep working unchanged, an envelope must reach the caller intact, and a Stop must still
 * beat a late answer even when that answer is a full form.
 */
@DisplayName("ToolApprovalGate - a verdict that carries the person's answers")
class ToolApprovalGateAnswerTest {

    private static final String CONVERSATION = "conv-1";
    private static final String GATE_KEY = "call-1:ask";
    private static final String STREAM_ID = "stream-1";
    private static final String KEY = StreamRedisKeys.approvalDecisionKey(CONVERSATION, GATE_KEY);
    private static final String CANCEL_KEY = StreamRedisKeys.cancelKey(STREAM_ID);

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ToolApprovalGate gate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.lenient().when(redisTemplate.hasKey(CANCEL_KEY)).thenReturn(false);
        gate = new ToolApprovalGate(redisTemplate);
        gate.configureForTest(true, 2_000, 10);
    }

    private static ToolApprovalGate.ParkRequest park() {
        return new ToolApprovalGate.ParkRequest(CONVERSATION, GATE_KEY, STREAM_ID, 0, 0, 0, 0, false);
    }

    private static String answeredEnvelope() {
        return UserQuestionAnswerEnvelope.answered(List.of(new UserQuestionAnswer("Tone", List.of("Friendly"), null, false)));
    }

    @Test
    @DisplayName("An answered envelope releases as APPROVED and hands the raw envelope to the caller")
    void answeredEnvelopeIsApprovedWithPayload() {
        when(valueOps.get(KEY)).thenReturn(answeredEnvelope());

        ToolApprovalGate.Answer answer = gate.awaitAnswer(park());

        assertThat(answer.decision()).isEqualTo(ToolApprovalGate.Decision.APPROVED);
        assertThat(answer.payloadJson()).isEqualTo(answeredEnvelope());
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("A dismissed envelope releases as DENIED with no payload")
    void dismissedEnvelopeIsDenied() {
        when(valueOps.get(KEY)).thenReturn(UserQuestionAnswerEnvelope.dismissed());

        ToolApprovalGate.Answer answer = gate.awaitAnswer(park());

        assertThat(answer.decision()).isEqualTo(ToolApprovalGate.Decision.DENIED);
        assertThat(answer.payloadJson()).isNull();
    }

    @Test
    @DisplayName("A malformed envelope is a refusal, never an answer")
    void malformedEnvelopeIsDenied() {
        when(valueOps.get(KEY)).thenReturn("{\"v\":1,\"decision\":\"answered\",\"answers\":\"nope\"}");

        assertThat(gate.awaitAnswer(park()).decision()).isEqualTo(ToolApprovalGate.Decision.DENIED);
    }

    @Test
    @DisplayName("The legacy words still work through awaitDecision, with no payload")
    void legacyWordsUnchanged() {
        when(valueOps.get(KEY)).thenReturn("approved");
        assertThat(gate.awaitDecision(park())).isEqualTo(ToolApprovalGate.Decision.APPROVED);
        assertThat(gate.awaitAnswer(park()).payloadJson()).isNull();

        when(valueOps.get(KEY)).thenReturn("denied");
        assertThat(gate.awaitDecision(park())).isEqualTo(ToolApprovalGate.Decision.DENIED);
    }

    @Test
    @DisplayName("An envelope written in the instant before giving up is honoured on the last look")
    void lateEnvelopeOnLastLook() {
        gate.configureForTest(true, 30, 10);
        when(valueOps.get(KEY)).thenReturn(null);
        when(valueOps.getAndDelete(KEY)).thenReturn(answeredEnvelope());

        ToolApprovalGate.Answer answer = gate.awaitAnswer(park());

        assertThat(answer.decision()).isEqualTo(ToolApprovalGate.Decision.APPROVED);
        assertThat(answer.payloadJson()).isEqualTo(answeredEnvelope());
    }

    @Test
    @DisplayName("A Stop wins over an answered envelope: the person's form is discarded on a cancelled turn")
    void stopWinsOverEnvelope() {
        when(redisTemplate.hasKey(CANCEL_KEY)).thenReturn(true);
        when(valueOps.get(KEY)).thenReturn(answeredEnvelope());

        ToolApprovalGate.Answer answer = gate.awaitAnswer(park());

        assertThat(answer.decision()).isEqualTo(ToolApprovalGate.Decision.STOPPED);
        assertThat(answer.payloadJson()).isNull();
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("The pending marker the park wrote for itself is never read as an answer")
    void pendingMarkerIsNotAnAnswer() {
        gate.configureForTest(true, 30, 10);
        when(valueOps.get(KEY)).thenReturn("pending");
        when(valueOps.getAndDelete(KEY)).thenReturn("pending");

        assertThat(gate.awaitAnswer(park()).decision()).isEqualTo(ToolApprovalGate.Decision.EXPIRED);
    }
}
