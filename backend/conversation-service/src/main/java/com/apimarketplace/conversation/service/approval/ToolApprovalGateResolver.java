package com.apimarketplace.conversation.service.approval;

import com.apimarketplace.conversation.client.StreamRedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Releases a tool call that agent-service parked while waiting for the user's answer.
 *
 * <p>The parked call lives in a specific agent-service replica, while this write happens
 * on whichever conversation-service replica served the user's click. Redis is therefore
 * the handoff: the verdict is stored under a key derived from the parked call's id and
 * the agent-service gate polls it. Writing the verdict is the ONLY thing that makes a
 * parked call resume.
 *
 * <p>Best-effort by design. If Redis refuses the write, the agent-service gate simply
 * runs to its deadline and falls back to the pre-existing behaviour (the assistant turn
 * ends, the user resumes with a message). A user click must never surface a 500 because
 * the resume optimisation could not be applied.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolApprovalGateResolver {

    private static final String VERDICT_APPROVED = "approved";
    private static final String VERDICT_DENIED = "denied";

    private final StringRedisTemplate redisTemplate;

    /**
     * Record the user's answer for one parked call.
     *
     * @param gateKey the parked call's identifier, echoed by the card that asked. Absent
     *                when the card was raised by the non-blocking path, in which case
     *                there is nothing parked and this is a no-op.
     * @return true when a call was actually holding for this answer. False means nobody was
     *         listening - no park, or one that already gave up - and the caller must fall
     *         back to resuming the agent with a new turn, or the user's click does nothing
     *         at all. Written conditionally ({@code SET ... XX}) for exactly that reason:
     *         an unconditional write would report success against a key nobody polls.
     */
    public boolean resolve(String conversationId, String gateKey, boolean approved) {
        return write(conversationId, gateKey, approved ? VERDICT_APPROVED : VERDICT_DENIED,
                approved ? "approved" : "denied");
    }

    /**
     * Record an answer that carries a payload: a question card, whose verdict is a JSON
     * envelope rather than one of the two words. Same key, same conditional write, same
     * "was anybody listening" return.
     *
     * @param envelopeJson the serialised {@code UserQuestionAnswerEnvelope}
     */
    public boolean resolveAnswer(String conversationId, String gateKey, String envelopeJson) {
        if (envelopeJson == null || envelopeJson.isBlank()) {
            return false;
        }
        String label = com.apimarketplace.agent.tools.ask.UserQuestionAnswerEnvelope.parse(envelopeJson)
                .map(com.apimarketplace.agent.tools.ask.UserQuestionAnswerEnvelope.Parsed::decision)
                .orElse("answered");
        return write(conversationId, gateKey, envelopeJson, label);
    }

    private boolean write(String conversationId, String gateKey, String value, String label) {
        if (conversationId == null || conversationId.isBlank() || gateKey == null || gateKey.isBlank()) {
            return false;
        }
        String key = StreamRedisKeys.approvalDecisionKey(conversationId, gateKey);
        try {
            boolean released = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfPresent(
                    key, value, StreamRedisKeys.APPROVAL_DECISION_TTL));
            if (released) {
                log.info("[APPROVAL_GATE] Released parked call {} in conversation {} as {}",
                        gateKey, conversationId, label);
            } else {
                log.info("[APPROVAL_GATE] No call was parked on {} in conversation {} - "
                        + "the answer resumes the agent instead", gateKey, conversationId);
            }
            return released;
        } catch (Exception e) {
            log.warn("[APPROVAL_GATE] Could not release parked call {}: {}", gateKey, e.getMessage());
            return false;
        }
    }
}
