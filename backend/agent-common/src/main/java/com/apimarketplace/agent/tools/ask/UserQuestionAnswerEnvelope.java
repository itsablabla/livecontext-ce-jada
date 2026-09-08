package com.apimarketplace.agent.tools.ask;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The value written into the approval-gate key when the person answers a question card.
 *
 * <p>The gate's verdict used to be one of two words, {@code approved} or {@code denied}.
 * A question needs a payload, so the answer side writes this JSON envelope instead and the
 * gate recognises it by its leading brace. Everything else about the handoff (the pending
 * marker, {@code SET XX}, the atomic last look, the stop-wins rule, the TTL) is untouched,
 * which is the whole point of reusing the key rather than adding a sibling.
 *
 * <pre>{"v":1,"decision":"answered","answers":[{"header":"Target","selected":["Staging"],"freeText":null,"custom":false}]}</pre>
 */
public final class UserQuestionAnswerEnvelope {

    public static final int VERSION = 1;
    public static final String DECISION_ANSWERED = "answered";
    public static final String DECISION_DISMISSED = "dismissed";

    /**
     * Ceiling on the serialised envelope. Enforced by the WRITER, which is the side that can
     * tell the person their answer was too long; the reader only ever sees what fits.
     */
    public static final int MAX_BYTES = 16_384;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UserQuestionAnswerEnvelope() {
    }

    /** What an envelope carries once parsed. {@code answers} is empty for a dismissal. */
    public record Parsed(String decision, List<UserQuestionAnswer> answers) {
        public boolean answered() {
            return DECISION_ANSWERED.equals(decision);
        }
    }

    /** @return the JSON to write for an answer; never larger than {@link #MAX_BYTES} */
    public static String answered(List<UserQuestionAnswer> answers) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("v", VERSION);
        envelope.put("decision", DECISION_ANSWERED);
        envelope.put("answers", answers.stream().map(UserQuestionAnswer::toMap).toList());
        String json = write(envelope);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new UserQuestionValidator.InvalidQuestionsException(
                    "answers are too long once serialised (over " + MAX_BYTES + " bytes).");
        }
        return json;
    }

    /** @return the JSON to write when the person chose not to answer */
    public static String dismissed() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("v", VERSION);
        envelope.put("decision", DECISION_DISMISSED);
        return write(envelope);
    }

    /** True when a stored gate value is an envelope rather than one of the legacy words. */
    public static boolean looksLikeEnvelope(String raw) {
        return raw != null && raw.stripLeading().startsWith("{");
    }

    /**
     * @return the parsed envelope, or empty when the text is not a well-formed envelope.
     *         Malformed input is the reader's cue to treat the park as dismissed: an
     *         unreadable answer is never a licence to invent one.
     */
    public static Optional<Parsed> parse(String raw) {
        if (!looksLikeEnvelope(raw)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> map = MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() { });
            Object decision = map.get("decision");
            if (DECISION_DISMISSED.equals(decision)) {
                return Optional.of(new Parsed(DECISION_DISMISSED, List.of()));
            }
            if (!DECISION_ANSWERED.equals(decision)) {
                return Optional.empty();
            }
            List<UserQuestionAnswer> answers = UserQuestionValidator.parseAnswers(map.get("answers"), null);
            return Optional.of(new Parsed(DECISION_ANSWERED, answers));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String write(Map<String, Object> envelope) {
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the answer envelope", e);
        }
    }
}
