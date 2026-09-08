package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.tools.askuser.AskUserToolsProvider;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.client.StreamRedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The plumbing around the ask_user tool inside agent-service: how its card is published
 * and buffered, how an open question is recognised on a result, and how the executing
 * call's identity reaches the provider.
 */
@DisplayName("ask_user card plumbing - publisher, extractor and the local dispatch")
class AskUserCardPlumbingTest {

    private static final Map<String, Object> QUESTION = Map.of(
            "toolCallId", "call-7",
            "questions", List.of(Map.of("header", "Tone", "question", "Which?",
                    "options", List.of(Map.of("label", "A"), Map.of("label", "B")), "multiSelect", false)));

    @Nested
    @DisplayName("ApprovalCardPublisher.publishUserQuestion")
    class Publisher {

        private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final ListOperations<String, String> listOps = mock(ListOperations.class);
        private final EventBus eventBus = mock(EventBus.class);

        private ApprovalCardPublisher publisher() {
            lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
            lenient().when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);
            return new ApprovalCardPublisher(redisTemplate, eventBus, new ObjectMapper());
        }

        @Test
        @DisplayName("A blocking question card nests askUser with blocking+gateKey, lands on SSE + WS, and is buffered")
        void blockingCardIsBufferedAndCarriesTheGateKey() {
            String json = publisher().publishUserQuestion("stream-1", "conv-1", QUESTION, true, "call-7:ask");

            assertThat(json).contains("\"askUser\"").contains("\"toolCallId\":\"call-7\"")
                    .contains("\"blocking\":true").contains("\"gateKey\":\"call-7:ask\"").contains("Tone");
            verify(redisTemplate).convertAndSend("stream:events:stream-1", json);
            verify(eventBus).publish("ws:conversation:conv-1", json);
            ArgumentCaptor<String> buffered = ArgumentCaptor.forClass(String.class);
            verify(listOps).rightPush(eq(StreamRedisKeys.toolsKey("stream-1")), buffered.capture());
            assertThat(buffered.getValue()).isEqualTo(json);
        }

        @Test
        @DisplayName("A non-blocking question card is published but NOT buffered (the persisted row carries it after the turn)")
        void nonBlockingCardIsNotBuffered() {
            String json = publisher().publishUserQuestion("stream-1", "conv-1", QUESTION, false, null);

            assertThat(json).contains("\"askUser\"").doesNotContain("\"blocking\"").doesNotContain("gateKey");
            verify(listOps, never()).rightPush(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("ApprovalCardExtractor for an open question")
    class Extractor {

        private final ApprovalCardExtractor extractor = new ApprovalCardExtractor(new ObjectMapper());

        private static ToolResult result(Map<String, Object> metadata) {
            return ToolResult.builder()
                    .toolCall(new ToolCall("call-7", "ask_user", Map.of("action", "ask"), null))
                    .success(true).content("{\"status\":\"pending_user\"}").metadata(metadata).build();
        }

        @Test
        @DisplayName("userQuestionRequested + userQuestion yields a USER_QUESTION card keyed ask:<toolCallId>")
        void openQuestionIsRecognised() {
            var card = extractor.extract(result(Map.of(
                    ApprovalCardExtractor.USER_QUESTION_REQUESTED_KEY, true,
                    ApprovalCardExtractor.USER_QUESTION_KEY, QUESTION)));

            assertThat(card).isPresent();
            assertThat(card.get().kind()).isEqualTo(ApprovalCardExtractor.ApprovalCard.Kind.USER_QUESTION);
            assertThat(card.get().dedupKey()).isEqualTo("ask:call-7");
            assertThat(card.get().userQuestion()).containsEntry("toolCallId", "call-7");
        }

        @Test
        @DisplayName("A payload with no toolCallId, or an answered result with no flag, yields no card")
        void noCardWithoutIdentityOrFlag() {
            assertThat(extractor.extract(result(Map.of(
                    ApprovalCardExtractor.USER_QUESTION_REQUESTED_KEY, true,
                    ApprovalCardExtractor.USER_QUESTION_KEY, Map.of("questions", List.of()))))).isEmpty();
            assertThat(extractor.extract(result(Map.of(ApprovalCardExtractor.USER_QUESTION_KEY, QUESTION)))).isEmpty();
        }
    }

    @Nested
    @DisplayName("RemoteToolExecutionService local dispatch")
    class Dispatch {

        @Test
        @DisplayName("ask_user is executed locally with the call's id and start time added to the credentials")
        void askUserIsInterceptedWithCallIdentity() {
            RemoteToolExecutionService service = new RemoteToolExecutionService(new ObjectMapper());
            AskUserToolsProvider provider = mock(AskUserToolsProvider.class);
            when(provider.execute(eq("ask_user"), any(), any())).thenReturn(
                    com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult.success(Map.of("status", "unavailable")));
            service.configureAskUserForTest(provider);

            Map<String, Object> creds = new HashMap<>();
            creds.put("conversationId", "conv-1");
            ToolCall call = new ToolCall("call-7", "ask_user", Map.of("action", "ask"), null);
            long started = 1_700_000_000_000L;

            ToolResult result = service.dispatch(call, null, "tenant-1", creds, started);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("unavailable");
            ArgumentCaptor<com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext> ctx =
                    ArgumentCaptor.forClass(com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext.class);
            verify(provider).execute(eq("ask_user"), any(), ctx.capture());
            assertThat(ctx.getValue().credentials())
                    .containsEntry(AskUserToolsProvider.KEY_TOOL_CALL_ID, "call-7")
                    .containsEntry(AskUserToolsProvider.KEY_CALL_STARTED_EPOCH_MS, started)
                    .containsEntry("conversationId", "conv-1");
        }
    }
}
