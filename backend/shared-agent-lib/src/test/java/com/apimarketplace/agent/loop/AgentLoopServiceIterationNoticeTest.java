package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.streaming.StreamingCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentLoopServiceIterationNoticeTest {
    private final LLMProviderFactory factory = mock(LLMProviderFactory.class);
    private final LLMProvider provider = mock(LLMProvider.class);
    private final AgentLoopExecutor executor = mock(AgentLoopExecutor.class);
    private final StreamingCallback callback = mock(StreamingCallback.class);
    private AgentLoopService service;

    @BeforeEach
    void setup() {
        service = new AgentLoopService(factory, null, null, null);
        ReflectionTestUtils.setField(service, "loopExecutor", executor);
        when(factory.getProvider("openai")).thenReturn(provider);
        when(provider.getProviderName()).thenReturn("openai");
        when(provider.isConfigured()).thenReturn(true);
        when(provider.getDefaultModel()).thenReturn("garza-auto");
    }

    private AgentLoopContext context() {
        return AgentLoopContext.builder().provider("openai").model("garza-auto")
                .userPrompt("test").maxIterations(2).build();
    }

    private void exhaust(String partial) {
        when(executor.processIteration(any(), anyString(), any(), anyList(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    LoopExecutionState state = invocation.getArgument(4);
                    state.incrementIterations();
                    state.setLastResponse(CompletionResponse.builder().content(partial).model("garza-auto").build());
                    if (state.getIterations() == 1) state.getFullContent().append(partial);
                    return AgentLoopExecutor.IterationResult.continueLoop();
                });
    }

    @Test
    void streamingLimitEmitsAndPersistsANoticeInsteadOfAnEmptyReply() {
        exhaust("");
        AgentLoopResult result = service.executeStreaming(context(), callback);
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.MAX_ITERATIONS);
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("limit of 2 iterations", "may be incomplete");
        verify(callback).onChunk(result.content());
        ArgumentCaptor<CompletionResponse> completion = ArgumentCaptor.forClass(CompletionResponse.class);
        verify(callback).onComplete(completion.capture());
        assertThat(completion.getValue().content()).isEqualTo(result.content());
        assertThat(completion.getValue().finishReason()).isEqualTo("max_iterations");
        assertThat(result.conversationHistory()).anyMatch(m -> result.content().equals(m.content()));
        verify(provider, never()).complete(any());
    }

    @Test
    void synchronousLimitAlsoReturnsTheNotice() {
        exhaust("");
        AgentLoopResult result = service.execute(context(), callback);
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.MAX_ITERATIONS);
        assertThat(result.content()).contains("limit of 2 iterations");
        assertThat(result.response().content()).isEqualTo(result.content());
    }

    @Test
    void preservesUsefulPartialContent() {
        exhaust("Completed the first part.");
        AgentLoopResult result = service.executeStreaming(context(), callback);
        assertThat(result.content()).isEqualTo("Completed the first part.");
        verify(callback, never()).onChunk(anyString());
    }

    @Test
    void userStopIsNotReplacedByAnIterationNotice() {
        when(callback.shouldStop()).thenReturn(true);
        AgentLoopResult result = service.executeStreaming(context(), callback);
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.STOPPED_BY_USER);
        assertThat(result.content()).isEmpty();
    }
}
