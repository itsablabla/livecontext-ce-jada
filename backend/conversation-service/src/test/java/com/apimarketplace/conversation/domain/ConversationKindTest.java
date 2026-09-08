package com.apimarketplace.conversation.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The accepted values of {@code conversations.kind}, and what happens to anything else.
 *
 * <p>The point of these tests is the ASYMMETRY: an absent kind falls back to chat, because every
 * row that predates the column is one; an unrecognised kind does NOT, because defaulting there
 * hands a caller who asked for a studio conversation an ordinary chat and says nothing.
 */
@DisplayName("ConversationKind")
class ConversationKindTest {

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("null means chat - every row that predates the column is one")
        void nullIsChat() {
            assertThat(ConversationKind.parse(null)).isEqualTo(ConversationKind.CHAT);
        }

        @ParameterizedTest(name = "blank value [{0}] means chat")
        @ValueSource(strings = {"", "   ", "\t"})
        @DisplayName("blank means chat")
        void blankIsChat(String raw) {
            assertThat(ConversationKind.parse(raw)).isEqualTo(ConversationKind.CHAT);
        }

        @Test
        @DisplayName("reads the wire values")
        void readsWireValues() {
            assertThat(ConversationKind.parse("chat")).isEqualTo(ConversationKind.CHAT);
            assertThat(ConversationKind.parse("studio")).isEqualTo(ConversationKind.STUDIO);
        }

        @ParameterizedTest(name = "accepts [{0}] as studio")
        @ValueSource(strings = {"STUDIO", "Studio", "  studio  "})
        @DisplayName("tolerates case and surrounding space - a stored value is compared, not retyped")
        void toleratesCaseAndSpace(String raw) {
            assertThat(ConversationKind.parse(raw)).isEqualTo(ConversationKind.STUDIO);
        }

        @ParameterizedTest(name = "refuses [{0}]")
        @ValueSource(strings = {"generate", "generation", "studios", "agent", "chat-studio"})
        @DisplayName("refuses an unrecognised kind instead of defaulting to chat")
        void refusesUnknown(String raw) {
            assertThatThrownBy(() -> ConversationKind.parse(raw))
                    .isInstanceOf(IllegalArgumentException.class)
                    // Naming the value is what makes the message actionable: the caller sent it.
                    .hasMessageContaining(raw)
                    .hasMessageContaining("chat, studio");
        }
    }

    @Nested
    @DisplayName("sameKind")
    class SameKind {

        @Test
        @DisplayName("compares kinds, not the strings that spell them")
        void comparesKindsNotSpelling() {
            assertThat(ConversationKind.sameKind("STUDIO", "studio")).isTrue();
            assertThat(ConversationKind.sameKind("chat", "chat")).isTrue();
        }

        @Test
        @DisplayName("an absent kind reads as chat on either side")
        void absentReadsAsChat() {
            assertThat(ConversationKind.sameKind(null, "chat")).isTrue();
            assertThat(ConversationKind.sameKind(null, "studio")).isFalse();
        }

        @Test
        @DisplayName("tells the two kinds apart")
        void tellsKindsApart() {
            assertThat(ConversationKind.sameKind("chat", "studio")).isFalse();
        }

        @Test
        @DisplayName("a STORED value this build does not know is simply not the requested kind")
        void unknownStoredValueDoesNotThrow() {
            // The stored side is the one that can drift: the schema carries no CHECK, so a rolling
            // deploy on a newer build, or a rollback after a third kind ships, leaves rows holding
            // a value this build has never heard of. Parsing it would throw, and the throw would
            // surface on the UPDATE path as a 400 on an ordinary title rename - a row made
            // unrenamable by a value nobody is touching.
            assertThat(ConversationKind.sameKind("studio", "canvas")).isFalse();
            assertThat(ConversationKind.sameKind("chat", "canvas")).isFalse();
        }

        @Test
        @DisplayName("echoing an unknown stored kind back is NOT a change, so it does not throw")
        void echoingAnUnknownStoredKindIsNotAChange() {
            // This method answers "is a CHANGE being asked for?", and echoing a value back
            // unchanged is not a change - whatever that value is. Parsing the requested side first
            // made an unrecognised stored kind unechoable, which broke every caller that round-trips
            // the whole conversation: renaming a title sends the stored kind back in the requested
            // slot, so the rename was refused and swallowed by its own catch-all. A kind this build
            // does not know is exactly what a row written by a NEWER build looks like.
            assertThat(ConversationKind.sameKind("canvas", "canvas")).isTrue();
            // Case is not a change either: the column is text, and nothing normalises it on write.
            assertThat(ConversationKind.sameKind("CANVAS", "canvas")).isTrue();
        }

        @Test
        @DisplayName("two DIFFERENT unknown kinds are still a change, and still refused")
        void twoDifferentUnknownKindsStillThrow() {
            // The short-circuit above must not become "anything unknown is allowed": that would let
            // a caller move a conversation to a kind neither side understands.
            assertThatThrownBy(() -> ConversationKind.sameKind("canvas", "easel"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("still refuses an unknown REQUESTED kind, which is a caller mistake")
        void unknownRequestedValueStillThrows() {
            assertThatThrownBy(() -> ConversationKind.sameKind("canvas", "studio"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an empty stored value reads as chat, like every row that predates the column")
        void emptyStoredReadsAsChat() {
            assertThat(ConversationKind.sameKind("chat", "")).isTrue();
            assertThat(ConversationKind.sameKind("chat", null)).isTrue();
            assertThat(ConversationKind.sameKind("studio", "")).isFalse();
        }
    }

    @Test
    @DisplayName("the default is chat, and the wire values are the lowercase names")
    void defaultAndWireValues() {
        assertThat(ConversationKind.defaultKind()).isEqualTo(ConversationKind.CHAT);
        assertThat(ConversationKind.CHAT.wireValue()).isEqualTo("chat");
        assertThat(ConversationKind.STUDIO.wireValue()).isEqualTo("studio");
    }
}
