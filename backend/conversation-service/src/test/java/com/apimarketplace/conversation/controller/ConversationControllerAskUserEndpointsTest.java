package com.apimarketplace.conversation.controller;

import com.apimarketplace.agent.tools.ask.UserQuestionAnswer;
import com.apimarketplace.agent.tools.ask.UserQuestionValidator;
import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.exception.GlobalExceptionHandler;
import com.apimarketplace.conversation.service.ConversationCommandService;
import com.apimarketplace.conversation.service.ConversationQueryService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.PendingActionResumeService;
import com.apimarketplace.conversation.service.approval.ServiceApprovalService;
import com.apimarketplace.conversation.service.approval.ToolApprovalGateResolver;
import com.apimarketplace.conversation.service.approval.UserQuestionAnswerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the two question-card endpoints: who may call them, what a bad body
 * gets, and that the release flag the frontend branches on is passed through untouched.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationController - ask-user endpoints")
class ConversationControllerAskUserEndpointsTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private ConversationCommandService conversationCommandService;
    @Mock private ConversationQueryService conversationQueryService;
    @Mock private MessageService messageService;
    @Mock private PendingActionService pendingActionService;
    @Mock private PendingActionResumeService pendingActionResumeService;
    @Mock private ServiceApprovalService serviceApprovalService;
    @Mock private ToolApprovalGateResolver toolApprovalGateResolver;
    @Mock private UserQuestionAnswerService userQuestionAnswerService;

    @InjectMocks
    private ConversationController controller;

    private static final String USER = "user-1";
    private static final String CONV = "conv-1";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private void conversationExists() {
        when(conversationQueryService.getConversationById(eq(CONV), eq(USER), any()))
            .thenReturn(Optional.of(mock(ConversationDto.class)));
    }

    private static Map<String, Object> answerBody(String gateKey) {
        return Map.of(
            "toolCallId", "call-7",
            "gateKey", gateKey,
            "answers", List.of(Map.of("header", "Tone", "selected", List.of("Friendly"))));
    }

    @Test
    @DisplayName("POST /ask-user/answer passes the release flag through, so the frontend can branch on it")
    void answerReturnsReleaseFlag() throws Exception {
        conversationExists();
        when(userQuestionAnswerService.answer(eq(CONV), eq("call-7"), eq("call-7:ask"), any()))
            .thenReturn(new UserQuestionAnswerService.Outcome(true,
                List.of(new UserQuestionAnswer("Tone", List.of("Friendly"), null, false))));

        mockMvc.perform(post("/api/conversations/{id}/ask-user/answer", CONV)
                .header("X-User-ID", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(answerBody("call-7:ask"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.parkedCallReleased").value(true))
            .andExpect(jsonPath("$.toolCallId").value("call-7"))
            .andExpect(jsonPath("$.answerCount").value(1));
    }

    @Test
    @DisplayName("A bad body (wrong gate key, malformed answers) is a 400 carrying the reason, never a 500")
    void badBodyIs400() throws Exception {
        conversationExists();
        when(userQuestionAnswerService.answer(eq(CONV), eq("call-7"), eq("call-7"), any()))
            .thenThrow(new UserQuestionValidator.InvalidQuestionsException("gateKey does not belong to this question"));

        mockMvc.perform(post("/api/conversations/{id}/ask-user/answer", CONV)
                .header("X-User-ID", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(answerBody("call-7"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("gateKey does not belong to this question"));
    }

    @Test
    @DisplayName("A missing toolCallId is refused before the service is touched")
    void missingToolCallIdIs400() throws Exception {
        conversationExists();

        mockMvc.perform(post("/api/conversations/{id}/ask-user/answer", CONV)
                .header("X-User-ID", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("answers", List.of()))))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(userQuestionAnswerService);
    }

    @Test
    @DisplayName("No user header is 401; a conversation the user cannot see is 404")
    void authAndOwnership() throws Exception {
        mockMvc.perform(post("/api/conversations/{id}/ask-user/answer", CONV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(answerBody("call-7:ask"))))
            .andExpect(status().isUnauthorized());

        when(conversationQueryService.getConversationById(eq(CONV), eq(USER), any())).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/conversations/{id}/ask-user/dismiss", CONV)
                .header("X-User-ID", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("toolCallId", "call-7"))))
            .andExpect(status().isNotFound());

        verifyNoInteractions(userQuestionAnswerService);
    }

    @Test
    @DisplayName("POST /ask-user/dismiss releases the park as dismissed and reports whether one was held")
    void dismissReportsRelease() throws Exception {
        conversationExists();
        when(userQuestionAnswerService.dismiss(CONV, "call-7", "call-7:ask")).thenReturn(false);

        mockMvc.perform(post("/api/conversations/{id}/ask-user/dismiss", CONV)
                .header("X-User-ID", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("toolCallId", "call-7", "gateKey", "call-7:ask"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.parkedCallReleased").value(false));

        verify(userQuestionAnswerService).dismiss(CONV, "call-7", "call-7:ask");
    }

    @Test
    @DisplayName("POST /ask-user/dismiss with a foreign gate key is a 400 too")
    void dismissForeignGateKeyIs400() throws Exception {
        conversationExists();
        when(userQuestionAnswerService.dismiss(CONV, "call-7", "call-9"))
            .thenThrow(new UserQuestionValidator.InvalidQuestionsException("gateKey does not belong to this question"));

        mockMvc.perform(post("/api/conversations/{id}/ask-user/dismiss", CONV)
                .header("X-User-ID", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("toolCallId", "call-7", "gateKey", "call-9"))))
            .andExpect(status().isBadRequest());
    }
}
