package com.apimarketplace.conversation.service.approval;

import com.apimarketplace.agent.tools.ask.UserQuestionAnswer;
import com.apimarketplace.agent.tools.ask.UserQuestionAnswerEnvelope;
import com.apimarketplace.agent.tools.ask.UserQuestionGateKeys;
import com.apimarketplace.agent.tools.ask.UserQuestionValidator;
import com.apimarketplace.conversation.service.PendingActionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Records the person's answer to a question card raised by the {@code ask_user} tool.
 *
 * <p>Two things happen, in this order. First the answer is offered to the call that may
 * still be parked on the card, through the same Redis handoff the approval cards use
 * ({@link ToolApprovalGateResolver}); whether anything was released is what the frontend
 * uses to decide between "the turn continues in place" and "start a new turn carrying the
 * answers". Then the persisted card is cleared, so it does not come back on the next
 * page load, whichever of the two paths the answer took.
 *
 * <p>Validation is shape-only here. The questions themselves are not at hand on this side
 * (during a park they live in the stream's replay buffer), so the tool that asked
 * re-checks the answers against its own questions when the park releases.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserQuestionAnswerService {

    /** Prefix of the pending-action key for a question card; see {@code PendingActionService}. */
    public static final String PENDING_KEY_PREFIX = "ask:";

    private final ToolApprovalGateResolver gateResolver;
    private final PendingActionService pendingActionService;

    /** What one submission did. */
    public record Outcome(boolean parkedCallReleased, List<UserQuestionAnswer> answers) {
    }

    /**
     * @param rawAnswers the {@code answers} list as submitted: {@code [{header, selected[], freeText}]}
     * @throws UserQuestionValidator.InvalidQuestionsException when the shape is wrong (a 400)
     */
    public Outcome answer(String conversationId, String toolCallId, String gateKey, Object rawAnswers) {
        requireQuestionGateKey(toolCallId, gateKey);
        List<UserQuestionAnswer> answers = UserQuestionValidator.parseAnswers(rawAnswers, null);
        String envelope = UserQuestionAnswerEnvelope.answered(answers);
        boolean released = gateResolver.resolveAnswer(conversationId, gateKey, envelope);
        clearCard(conversationId, toolCallId);
        log.info("[ASK_USER] Answered question {} in conversation {} ({} answer(s), parkedCallReleased={})",
                toolCallId, conversationId, answers.size(), released);
        return new Outcome(released, answers);
    }

    /** The person chose not to answer: release the park as dismissed and drop the card. */
    public boolean dismiss(String conversationId, String toolCallId, String gateKey) {
        requireQuestionGateKey(toolCallId, gateKey);
        boolean released = gateResolver.resolveAnswer(conversationId, gateKey, UserQuestionAnswerEnvelope.dismissed());
        clearCard(conversationId, toolCallId);
        log.info("[ASK_USER] Dismissed question {} in conversation {} (parkedCallReleased={})",
                toolCallId, conversationId, released);
        return released;
    }

    /**
     * A gate key is optional (a card that never claimed a hold has none), but when present it
     * must be THIS question's key. Anything else is refused as a bad request.
     */
    static void requireQuestionGateKey(String toolCallId, String gateKey) {
        if (gateKey == null || gateKey.isBlank()) {
            return;
        }
        // The gate parses ANY JSON envelope as a verdict, so an answer sent against an
        // authorization park's key would approve a sensitive action with no permission card
        // ever clicked. The shared key shape is what makes this refusal meaningful.
        if (!UserQuestionGateKeys.belongsTo(toolCallId, gateKey)) {
            throw new UserQuestionValidator.InvalidQuestionsException(
                    "gateKey does not belong to this question (expected "
                            + UserQuestionGateKeys.forToolCall(toolCallId) + ").");
        }
    }

    private void clearCard(String conversationId, String toolCallId) {
        if (toolCallId != null && !toolCallId.isBlank()) {
            pendingActionService.clearOnePendingAction(conversationId, PENDING_KEY_PREFIX + toolCallId);
        }
    }
}
