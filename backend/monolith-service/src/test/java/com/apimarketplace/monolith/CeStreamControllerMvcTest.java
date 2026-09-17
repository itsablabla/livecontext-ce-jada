package com.apimarketplace.monolith;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.conversation.controller.v3.StreamControllerV3;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.streaming.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CeStreamControllerMvcTest {
    private final StreamStateService state = mock(StreamStateService.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final StreamPubSubService pubSub = mock(StreamPubSubService.class);
    private final AgentClient agents = mock(AgentClient.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(
                new StreamControllerV3(state, pubSub, agents, conversations)).build();
        Conversation conversation = new Conversation();
        conversation.setId("conv-1");
        conversation.setUserId("owner");
        conversation.setOrganizationId("org-1");
        when(conversations.findById("conv-1")).thenReturn(Optional.of(conversation));
    }

    @Test
    void ceMustMountTheRealStreamControllerRatherThanHardcodedStubs() {
        ComponentScan scan = MonolithApplication.class.getAnnotation(ComponentScan.class);
        boolean excluded = Arrays.stream(scan.excludeFilters())
                .flatMap(filter -> Arrays.stream(filter.pattern()))
                .anyMatch(regex -> Pattern.matches(regex, StreamControllerV3.class.getName()));
        assertThat(excluded).as("CE must not exclude its real Redis-backed stream controller").isFalse();
    }

    @Test
    void servletRecoveryReturnsActualBufferedContentAndToolEvents() throws Exception {
        StreamMetadata metadata = StreamMetadata.create("stream-1", "owner", "conv-1",
                "garza-auto", "openai").withState(StreamState.STREAMING);
        when(state.getByConversationId("conv-1")).thenReturn(Mono.just(metadata));
        when(state.getFullContent("stream-1")).thenReturn(Mono.just("Buffered reply"));
        when(state.getToolEvents("stream-1")).thenReturn(Flux.just("{\"type\":\"tool_call\"}"));
        var result = mvc.perform(get("/api/v3/streams/by-conversation/conv-1/state")
                        .header("X-User-ID", "owner").header("X-Organization-ID", "org-1"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(jsonPath("$.streamId").value("stream-1"))
                .andExpect(jsonPath("$.model").value("garza-auto"))
                .andExpect(jsonPath("$.content").value("Buffered reply"))
                .andExpect(jsonPath("$.toolEvents[0]").value("{\"type\":\"tool_call\"}"))
                .andExpect(jsonPath("$.hasActiveStream").value(true));
    }

    @Test
    void completedReplyRemainsRecoverableAfterNavigation() throws Exception {
        StreamMetadata metadata = StreamMetadata.create("stream-1", "owner", "conv-1",
                "garza-auto", "openai").withState(StreamState.COMPLETED);
        when(state.getByConversationId("conv-1")).thenReturn(Mono.just(metadata));
        when(state.getFullContent("stream-1")).thenReturn(Mono.just("Finished while away"));
        when(state.getToolEvents("stream-1")).thenReturn(Flux.empty());
        var result = mvc.perform(get("/api/v3/streams/by-conversation/conv-1/state")
                        .header("X-User-ID", "owner").header("X-Organization-ID", "org-1"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.streamId").value("stream-1"))
                .andExpect(jsonPath("$.content").value("Finished while away"));
    }

    @Test
    void statusReflectsRedisMetadata() throws Exception {
        StreamMetadata metadata = StreamMetadata.create("stream-1", "owner", "conv-1",
                "garza-auto", "openai").withState(StreamState.STREAMING).withContentLength(14);
        when(state.getByConversationId("conv-1")).thenReturn(Mono.just(metadata));
        var result = mvc.perform(get("/api/v3/streams/by-conversation/conv-1/status")
                        .header("X-User-ID", "owner").header("X-Organization-ID", "org-1"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasActiveStream").value(true))
                .andExpect(jsonPath("$.provider").value("openai"))
                .andExpect(jsonPath("$.contentLength").value(14));
    }

    @Test
    void otherUserAndOtherWorkspaceCannotReadBufferedContent() throws Exception {
        for (String[] scope : new String[][]{{"intruder", ""}, {"owner", "other-org"}}) {
            var result = mvc.perform(get("/api/v3/streams/by-conversation/conv-1/state")
                            .header("X-User-ID", scope[0]).header("X-Organization-ID", scope[1]))
                    .andExpect(request().asyncStarted()).andReturn();
            mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasActiveStream").value(false))
                    .andExpect(jsonPath("$.content").value(""))
                    .andExpect(jsonPath("$.streamId").doesNotExist());
        }
        verify(state, never()).getByConversationId(anyString());
    }
}
