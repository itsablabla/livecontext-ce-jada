package com.apimarketplace.conversation.domain;

import java.util.Locale;

/**
 * What a conversation IS, and the only values {@code conversations.kind} may hold.
 *
 * <p>Two kinds today, and they are different things rather than two settings of one thing:
 *
 * <ul>
 *   <li>{@link #CHAT} - a thread with a model or an agent. Each turn is answered in the context of
 *       the ones before it, which is what makes it a conversation.</li>
 *   <li>{@link #STUDIO} - pure generation. Every message is submitted to a generation model on its
 *       own, with no context carried from the message before it. The conversation exists so the
 *       work can be found again, not so the model can remember it.</li>
 * </ul>
 *
 * <p><b>Why this is an enum in Java rather than a CHECK in the schema.</b> The kind is decided at
 * the write boundary, and that is the only place able to refuse an unknown value with a sentence
 * that names what was sent. A database constraint answers the same question with a violation the
 * caller cannot read, and turns every future kind into a migration.
 *
 * <p><b>The kind is immutable.</b> It is chosen when the conversation is created and there is no
 * path that changes it afterwards - see {@code ConversationCommandService#updateConversation}. That
 * is not a UI preference: the two kinds hold different message shapes and are dispatched to
 * different back ends, so a chat re-labelled studio would be a thread whose history the studio
 * cannot run and whose next message the chat pipeline would answer with prose.
 */
public enum ConversationKind {

    CHAT("chat"),
    STUDIO("studio");

    /** How the kind is written on the wire and in the column. Lowercase, stable, never localised. */
    private final String wireValue;

    ConversationKind(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /** What an unset kind means. A row that does not say is a chat, which is what every row was. */
    public static ConversationKind defaultKind() {
        return CHAT;
    }

    /**
     * Parse a wire value, falling back to {@link #defaultKind()} for null or blank ONLY.
     *
     * <p>An unrecognised non-blank value throws rather than defaulting. Defaulting there is the
     * expensive failure: a caller that sends {@code "Studio"} or {@code "generate"} would be told
     * nothing, get an ordinary chat, and discover it when the thread never appears under the filter
     * it was created for.
     *
     * @throws IllegalArgumentException naming the value and the accepted ones
     */
    public static ConversationKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultKind();
        }
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        for (ConversationKind kind : values()) {
            if (kind.wireValue.equals(normalised)) {
                return kind;
            }
        }
        throw new IllegalArgumentException(
                "Unknown conversation kind '" + raw + "'. Accepted values: chat, studio");
    }

    /**
     * True when a REQUESTED kind names the kind a row already has.
     *
     * <p>Only the requested side is parsed. The stored side is compared as text, case-insensitively,
     * because it is precisely the value that can drift: the schema deliberately carries no CHECK, so
     * a rolling deploy on a newer build, or a rollback after a third kind ships, can leave a row
     * holding something this build does not know. Parsing it would throw, and the throw would come
     * out of the UPDATE path as a 400 on an ordinary title rename - a row made unrenamable by a
     * value nobody is touching.
     *
     * <p>An unrecognised stored value is therefore simply "not the requested kind", which is the
     * honest answer: the update is refused only because a CHANGE was asked for.
     */
    public static boolean sameKind(String requested, String stored) {
        String current = stored == null ? null : stored.trim();
        // Identical text is the same kind, decided BEFORE parsing and without it.
        //
        // This method answers "is a CHANGE being asked for?", and echoing a value back unchanged is
        // not a change - whatever that value is. Parsing first made an unrecognised STORED kind
        // unechoable: every caller that round-trips the whole conversation (renaming a title is the
        // live example) puts the stored kind into the requested slot, so parse() threw on a row it
        // had merely read back, and the rename was silently dropped by the caller's catch-all. A
        // value this build does not know is exactly what a row written by a newer one looks like.
        if (requested != null && current != null && requested.trim().equalsIgnoreCase(current)) {
            return true;
        }
        ConversationKind wanted = parse(requested);
        if (current == null || current.isEmpty()) {
            return wanted == defaultKind();
        }
        return wanted.wireValue.equalsIgnoreCase(current);
    }
}
