package com.apimarketplace.conversation.service.approval;

import com.apimarketplace.agent.tools.ask.UserQuestionAnswerEnvelope;
import com.apimarketplace.agent.tools.ask.UserQuestionValidator;
import com.apimarketplace.conversation.client.StreamRedisKeys;
import com.apimarketplace.conversation.service.PendingActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The answer side of a question card: offer the answers to the parked call through the
 * gate key, then clear the persisted card whichever way the answer travels.
 */
@DisplayName("UserQuestionAnswerService - recording the person's answer to a question card")
class UserQuestionAnswerServiceTest {

    private static final String KEY = StreamRedisKeys.approvalDecisionKey("conv-1", "call-7:ask");

    private ValueOperations<String, String> valueOps;
    private PendingActionService pendingActionService;
    private UserQuestionAnswerService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        pendingActionService = mock(PendingActionService.class);
        service = new UserQuestionAnswerService(new ToolApprovalGateResolver(redisTemplate), pendingActionService);
    }

    @Test
    @DisplayName("An answer writes an answered envelope under the gate key (SET XX) and clears the ask:<id> card")
    void answerReleasesAndClears() {
        when(valueOps.setIfPresent(eq(KEY), anyString(), eq(StreamRedisKeys.APPROVAL_DECISION_TTL))).thenReturn(true);

        var outcome = service.answer("conv-1", "call-7", "call-7:ask",
                List.of(Map.of("header", "Tone", "selected", List.of("Friendly"))));

        assertThat(outcome.parkedCallReleased()).isTrue();
        assertThat(outcome.answers()).singleElement().satisfies(a -> assertThat(a.selected()).containsExactly("Friendly"));
        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(valueOps).setIfPresent(eq(KEY), written.capture(), eq(StreamRedisKeys.APPROVAL_DECISION_TTL));
        var envelope = UserQuestionAnswerEnvelope.parse(written.getValue());
        assertThat(envelope).isPresent();
        assertThat(envelope.get().answered()).isTrue();
        assertThat(envelope.get().answers().get(0).header()).isEqualTo("Tone");
        verify(pendingActionService).clearOnePendingAction("conv-1", "ask:call-7");
    }

    @Test
    @DisplayName("With nobody parked (no key), the answer reports no release so the frontend sends it as a message")
    void nobodyParkedReportsNoRelease() {
        when(valueOps.setIfPresent(eq(KEY), anyString(), any())).thenReturn(false);

        var outcome = service.answer("conv-1", "call-7", "call-7:ask",
                List.of(Map.of("header", "Tone", "freeText", "Casual please")));

        assertThat(outcome.parkedCallReleased()).isFalse();
        assertThat(outcome.answers().get(0).custom()).isTrue();
        verify(pendingActionService).clearOnePendingAction("conv-1", "ask:call-7");
    }

    @Test
    @DisplayName("No gate key at all (the card was never blocking) skips Redis and still clears the card")
    void noGateKeySkipsRedis() {
        var outcome = service.answer("conv-1", "call-7", null,
                List.of(Map.of("header", "Tone", "selected", List.of("Friendly"))));

        assertThat(outcome.parkedCallReleased()).isFalse();
        verify(valueOps, never()).setIfPresent(anyString(), anyString(), any());
        verify(pendingActionService).clearOnePendingAction("conv-1", "ask:call-7");
    }

    @Test
    @DisplayName("A malformed answer body is rejected before anything is written or cleared")
    void malformedBodyRejected() {
        assertThatThrownBy(() -> service.answer("conv-1", "call-7", "call-7:ask", List.of(Map.of("header", "Tone"))))
                .isInstanceOf(UserQuestionValidator.InvalidQuestionsException.class);

        verify(valueOps, never()).setIfPresent(anyString(), anyString(), any());
        verify(pendingActionService, never()).clearOnePendingAction(anyString(), anyString());
    }

    @Test
    @DisplayName("A gate key that is not this question's own (an authorization park's key) is refused before any write")
    void foreignGateKeyRefused() {
        assertThatThrownBy(() -> service.answer("conv-1", "call-7", "call-7",
                List.of(Map.of("header", "Tone", "selected", List.of("Friendly")))))
                .isInstanceOf(UserQuestionValidator.InvalidQuestionsException.class)
                .hasMessageContaining("gateKey does not belong");
        assertThatThrownBy(() -> service.dismiss("conv-1", "call-7", "call-9:ask"))
                .isInstanceOf(UserQuestionValidator.InvalidQuestionsException.class);

        verify(valueOps, never()).setIfPresent(anyString(), anyString(), any());
        verify(pendingActionService, never()).clearOnePendingAction(anyString(), anyString());
    }

    @Test
    @DisplayName("A dismissal writes the dismissed envelope and clears the card")
    void dismissWritesDismissedEnvelope() {
        when(valueOps.setIfPresent(eq(KEY), eq(UserQuestionAnswerEnvelope.dismissed()), any())).thenReturn(true);

        assertThat(service.dismiss("conv-1", "call-7", "call-7:ask")).isTrue();

        verify(pendingActionService).clearOnePendingAction("conv-1", "ask:call-7");
    }
}
