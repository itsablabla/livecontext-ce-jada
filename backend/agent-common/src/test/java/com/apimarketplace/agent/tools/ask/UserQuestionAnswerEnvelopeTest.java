package com.apimarketplace.agent.tools.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The envelope is what travels through the approval-gate key in place of the two legacy
 * words, so it must be recognisable next to them and never mistaken for a "yes".
 */
@DisplayName("UserQuestionAnswerEnvelope - the answer as it travels through the gate key")
class UserQuestionAnswerEnvelopeTest {

    @Test
    @DisplayName("An answered envelope round-trips its answers")
    void answeredRoundTrip() {
        String json = UserQuestionAnswerEnvelope.answered(List.of(
                new UserQuestionAnswer("Tone", List.of("Friendly"), null, false),
                new UserQuestionAnswer("Channels", List.of(), "Newsletter", true)));

        assertThat(UserQuestionAnswerEnvelope.looksLikeEnvelope(json)).isTrue();
        var parsed = UserQuestionAnswerEnvelope.parse(json);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().answered()).isTrue();
        assertThat(parsed.get().answers()).hasSize(2);
        assertThat(parsed.get().answers().get(0).selected()).containsExactly("Friendly");
        assertThat(parsed.get().answers().get(1).freeText()).isEqualTo("Newsletter");
        assertThat(parsed.get().answers().get(1).custom()).isTrue();
    }

    @Test
    @DisplayName("A dismissed envelope parses with no answers and answered()=false")
    void dismissed() {
        var parsed = UserQuestionAnswerEnvelope.parse(UserQuestionAnswerEnvelope.dismissed());

        assertThat(parsed).isPresent();
        assertThat(parsed.get().answered()).isFalse();
        assertThat(parsed.get().answers()).isEmpty();
    }

    @Test
    @DisplayName("The legacy words are not envelopes, so the gate keeps its old parsing for them")
    void legacyWordsAreNotEnvelopes() {
        assertThat(UserQuestionAnswerEnvelope.looksLikeEnvelope("approved")).isFalse();
        assertThat(UserQuestionAnswerEnvelope.looksLikeEnvelope("denied")).isFalse();
        assertThat(UserQuestionAnswerEnvelope.looksLikeEnvelope("pending")).isFalse();
        assertThat(UserQuestionAnswerEnvelope.looksLikeEnvelope(null)).isFalse();
        assertThat(UserQuestionAnswerEnvelope.parse("approved")).isEmpty();
    }

    @Test
    @DisplayName("Malformed or unknown-decision JSON parses to empty, never to an answer")
    void malformedIsEmpty() {
        assertThat(UserQuestionAnswerEnvelope.parse("{not json")).isEmpty();
        assertThat(UserQuestionAnswerEnvelope.parse("{\"v\":1,\"decision\":\"approved\"}")).isEmpty();
        assertThat(UserQuestionAnswerEnvelope.parse("{\"v\":1,\"decision\":\"answered\"}")).isEmpty();
        assertThat(UserQuestionAnswerEnvelope.parse("{\"v\":1,\"decision\":\"answered\",\"answers\":[]}")).isEmpty();
    }

    @Test
    @DisplayName("An envelope over the byte cap is refused by the writer")
    void oversizeRefused() {
        assertThatThrownBy(() -> UserQuestionAnswerEnvelope.answered(List.of(
                new UserQuestionAnswer("Tone", List.of(), "x".repeat(UserQuestionAnswerEnvelope.MAX_BYTES), true))))
                .isInstanceOf(UserQuestionValidator.InvalidQuestionsException.class)
                .hasMessageContaining("too long");
    }
}
