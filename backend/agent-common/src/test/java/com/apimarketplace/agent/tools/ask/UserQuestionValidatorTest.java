package com.apimarketplace.agent.tools.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The validator is the contract between what the agent may ask and what the person can
 * answer. Every rejection must name the offending index (the agent fixes the call from the
 * message alone) and nothing may be silently truncated.
 */
@DisplayName("UserQuestionValidator - the shape of a question and of an answer")
class UserQuestionValidatorTest {

    private static Map<String, Object> option(String label, String description) {
        Map<String, Object> o = new HashMap<>();
        o.put("label", label);
        if (description != null) o.put("description", description);
        return o;
    }

    private static Map<String, Object> question(String header, String text, boolean multi, Map<String, Object>... options) {
        Map<String, Object> q = new HashMap<>();
        q.put("header", header);
        q.put("question", text);
        q.put("options", List.of(options));
        q.put("multiSelect", multi);
        return q;
    }

    private static Map<String, Object> toneQuestion() {
        return question("Tone", "Which tone?", false, option("Friendly", "Warm"), option("Formal", null));
    }

    @Nested
    @DisplayName("parseQuestions")
    class ParseQuestions {

        @Test
        @DisplayName("A well-formed call yields the questions in order with their options")
        void wellFormed() {
            List<UserQuestion> parsed = UserQuestionValidator.parseQuestions(List.of(
                    toneQuestion(),
                    question("Channels", "Where?", true, option("X", null), option("LinkedIn", null), option("Mail", null))));

            assertThat(parsed).hasSize(2);
            assertThat(parsed.get(0).header()).isEqualTo("Tone");
            assertThat(parsed.get(0).multiSelect()).isFalse();
            assertThat(parsed.get(0).options()).extracting(UserQuestionOption::label).containsExactly("Friendly", "Formal");
            assertThat(parsed.get(0).options().get(0).description()).isEqualTo("Warm");
            assertThat(parsed.get(1).multiSelect()).isTrue();
            assertThat(parsed.get(1).options()).hasSize(3);
        }

        @Test
        @DisplayName("A bare-string option is accepted as a label without description")
        void bareStringOption() {
            Map<String, Object> q = new HashMap<>();
            q.put("header", "Size");
            q.put("question", "How big?");
            q.put("options", List.of("Small", "Large"));

            List<UserQuestion> parsed = UserQuestionValidator.parseQuestions(List.of(q));

            assertThat(parsed.get(0).options()).extracting(UserQuestionOption::label).containsExactly("Small", "Large");
        }

        @Test
        @DisplayName("multi_select (snake case) is read like multiSelect")
        void snakeCaseMultiSelect() {
            Map<String, Object> q = toneQuestion();
            q.remove("multiSelect");
            q.put("multi_select", "true");

            assertThat(UserQuestionValidator.parseQuestions(List.of(q)).get(0).multiSelect()).isTrue();
        }

        @Test
        @DisplayName("No questions at all is rejected, and so is a non-list")
        void emptyOrNotAList() {
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(List.of()))
                    .isInstanceOf(UserQuestionValidator.InvalidQuestionsException.class)
                    .hasMessageContaining("non-empty list");
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions("Tone?"))
                    .isInstanceOf(UserQuestionValidator.InvalidQuestionsException.class);
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(null))
                    .isInstanceOf(UserQuestionValidator.InvalidQuestionsException.class);
        }

        @Test
        @DisplayName("More than the maximum number of questions is rejected with the count, not truncated")
        void tooManyQuestions() {
            List<Map<String, Object>> five = new ArrayList<>();
            for (int i = 0; i < UserQuestionValidator.MAX_QUESTIONS + 1; i++) {
                five.add(question("Q" + i, "?", false, option("a", null), option("b", null)));
            }
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(five))
                    .hasMessageContaining("holds 5 entries")
                    .hasMessageContaining("maximum is " + UserQuestionValidator.MAX_QUESTIONS);
        }

        @Test
        @DisplayName("Option count below 2 or above 4 is rejected naming the question index")
        void optionCountBounds() {
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(List.of(
                    question("Tone", "?", false, option("only", null)))))
                    .hasMessageContaining("questions[0].options holds 1 options");
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(List.of(
                    question("Tone", "?", false, option("a", null), option("b", null), option("c", null),
                            option("d", null), option("e", null)))))
                    .hasMessageContaining("questions[0].options holds 5 options");
        }

        @Test
        @DisplayName("A declared 'Other' option is rejected: the card adds it itself")
        void reservedOtherLabel() {
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(List.of(
                    question("Tone", "?", false, option("Friendly", null), option("Other", null)))))
                    .hasMessageContaining("questions[0].options[1]")
                    .hasMessageContaining("added automatically");
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(List.of(
                    question("Ton", "?", false, option("Amical", null), option("autre", null)))))
                    .hasMessageContaining("added automatically");
        }

        @Test
        @DisplayName("Duplicate headers and duplicate labels are rejected: they are how answers are matched back")
        void duplicates() {
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(List.of(toneQuestion(), toneQuestion())))
                    .hasMessageContaining("questions[1].header 'Tone' is used twice");
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(List.of(
                    question("Tone", "?", false, option("Same", null), option("same", null)))))
                    .hasMessageContaining("duplicate label");
        }

        @Test
        @DisplayName("Missing or blank header/question/label is rejected with its path")
        void missingText() {
            Map<String, Object> noHeader = toneQuestion();
            noHeader.put("header", "  ");
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(List.of(noHeader)))
                    .hasMessageContaining("questions[0].header is required");

            Map<String, Object> noQuestion = toneQuestion();
            noQuestion.remove("question");
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(List.of(noQuestion)))
                    .hasMessageContaining("questions[0].question is required");

            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(List.of(
                    question("Tone", "?", false, option("ok", null), Map.of("description", "no label")))))
                    .hasMessageContaining("questions[0].options[1].label is required");
        }

        @Test
        @DisplayName("Over-long question and option description are rejected rather than cut")
        void questionAndDescriptionTooLong() {
            Map<String, Object> q = toneQuestion();
            q.put("question", "x".repeat(UserQuestionValidator.MAX_QUESTION_LENGTH + 1));
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(List.of(q)))
                    .hasMessageContaining("questions[0].question is longer than " + UserQuestionValidator.MAX_QUESTION_LENGTH);

            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(List.of(question("Tone", "?", false,
                    option("a", "d".repeat(UserQuestionValidator.MAX_DESCRIPTION_LENGTH + 1)), option("b", null)))))
                    .hasMessageContaining("questions[0].options[0].description is longer than");
        }

        @Test
        @DisplayName("Over-long header is rejected rather than cut")
        void headerTooLong() {
            Map<String, Object> q = toneQuestion();
            q.put("header", "x".repeat(UserQuestionValidator.MAX_HEADER_LENGTH + 1));
            assertThatThrownBy(() -> UserQuestionValidator.parseQuestions(List.of(q)))
                    .hasMessageContaining("longer than " + UserQuestionValidator.MAX_HEADER_LENGTH);
        }
    }

    @Nested
    @DisplayName("parseAnswers")
    class ParseAnswers {

        private final List<UserQuestion> questions = UserQuestionValidator.parseQuestions(List.of(
                toneQuestion(),
                question("Channels", "Where?", true, option("X", null), option("LinkedIn", null))));

        @Test
        @DisplayName("A pick is read back as selected labels, custom=false; a typed answer as freeText, custom=true")
        void picksAndFreeText() {
            List<UserQuestionAnswer> answers = UserQuestionValidator.parseAnswers(List.of(
                    Map.of("header", "Tone", "selected", List.of("Friendly")),
                    Map.of("header", "Channels", "selected", List.of(), "freeText", "Newsletter")), questions);

            assertThat(answers.get(0).selected()).containsExactly("Friendly");
            assertThat(answers.get(0).custom()).isFalse();
            assertThat(answers.get(0).freeText()).isNull();
            assertThat(answers.get(1).selected()).isEmpty();
            assertThat(answers.get(1).freeText()).isEqualTo("Newsletter");
            assertThat(answers.get(1).custom()).isTrue();
        }

        @Test
        @DisplayName("The header on the answer is normalised to the question's own spelling")
        void headerNormalised() {
            List<UserQuestionAnswer> answers = UserQuestionValidator.parseAnswers(List.of(
                    Map.of("header", "tone", "selected", List.of("friendly"))), questions);

            assertThat(answers.get(0).header()).isEqualTo("Tone");
        }

        @Test
        @DisplayName("Shape-only validation (no questions at hand) accepts any header and label")
        void shapeOnly() {
            List<UserQuestionAnswer> answers = UserQuestionValidator.parseAnswers(List.of(
                    Map.of("header", "Whatever", "selected", List.of("Anything"))), null);

            assertThat(answers).hasSize(1);
            assertThat(answers.get(0).header()).isEqualTo("Whatever");
        }

        @Test
        @DisplayName("An answer with neither a pick nor free text is rejected")
        void emptyAnswer() {
            assertThatThrownBy(() -> UserQuestionValidator.parseAnswers(List.of(
                    Map.of("header", "Tone", "selected", List.of(), "freeText", "  ")), questions))
                    .hasMessageContaining("answers[0] carries neither");
        }

        @Test
        @DisplayName("Against the questions: unknown header, unknown label and multi-pick on single-select are rejected")
        void mismatches() {
            assertThatThrownBy(() -> UserQuestionValidator.parseAnswers(List.of(
                    Map.of("header", "Nope", "selected", List.of("Friendly"))), questions))
                    .hasMessageContaining("matches no question");
            assertThatThrownBy(() -> UserQuestionValidator.parseAnswers(List.of(
                    Map.of("header", "Tone", "selected", List.of("Casual"))), questions))
                    .hasMessageContaining("selects 'Casual', which is not an option of 'Tone'");
            assertThatThrownBy(() -> UserQuestionValidator.parseAnswers(List.of(
                    Map.of("header", "Tone", "selected", List.of("Friendly", "Formal"))), questions))
                    .hasMessageContaining("picks 2 options but 'Tone' allows one");
        }

        @Test
        @DisplayName("Multi-select accepts several labels")
        void multiSelectAcceptsSeveral() {
            List<UserQuestionAnswer> answers = UserQuestionValidator.parseAnswers(List.of(
                    Map.of("header", "Channels", "selected", List.of("X", "LinkedIn"))), questions);

            assertThat(answers.get(0).selected()).containsExactly("X", "LinkedIn");
        }

        @Test
        @DisplayName("More answers than questions can exist is rejected")
        void tooManyAnswers() {
            List<Map<String, Object>> five = new ArrayList<>();
            for (int i = 0; i < UserQuestionValidator.MAX_QUESTIONS + 1; i++) {
                five.add(Map.of("header", "Q" + i, "selected", List.of("a")));
            }
            assertThatThrownBy(() -> UserQuestionValidator.parseAnswers(five, null))
                    .hasMessageContaining("answers holds 5 entries");
        }

        @Test
        @DisplayName("A multi-select answer may carry both picks and free text; it is not custom")
        void picksAndFreeTextTogether() {
            List<UserQuestionAnswer> answers = UserQuestionValidator.parseAnswers(List.of(
                    Map.of("header", "Channels", "selected", List.of("X"), "freeText", "and the blog")), questions);

            assertThat(answers.get(0).selected()).containsExactly("X");
            assertThat(answers.get(0).freeText()).isEqualTo("and the blog");
            assertThat(answers.get(0).custom()).isFalse();
        }

        @Test
        @DisplayName("Free text over the cap is rejected")
        void freeTextTooLong() {
            assertThatThrownBy(() -> UserQuestionValidator.parseAnswers(List.of(
                    Map.of("header", "Tone", "freeText", "x".repeat(UserQuestionValidator.MAX_FREE_TEXT_LENGTH + 1))), questions))
                    .hasMessageContaining("freeText is longer than");
        }
    }
}
