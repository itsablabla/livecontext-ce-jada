-- What a conversation IS, so the two kinds can be told apart and listed separately.
--
-- Until now every row here was the same thing: a chat with a model or an agent. Studio
-- conversations are not that. They hold pure generations - a prompt, a model, an asset - with no
-- turn-to-turn context, and the reader browses them for what was MADE rather than for what was
-- said. Nothing on the row could distinguish the two, so the sidebar could not offer them apart
-- and a studio thread would have surfaced among ordinary chats with a title and no answer.
--
-- Deliberately a column and not a flag derived at read time. The alternative was to infer the kind
-- from a message's shape, which is a guess that gets more expensive with every row and is wrong for
-- the state that matters most: a studio conversation whose first generation has not landed yet has
-- no messages at all to infer from.
--
-- No CHECK constraint. The accepted values live in ConversationKind and are enforced at the write
-- boundary, which is where an unknown value can be REFUSED with a sentence naming what was sent.
-- A schema check would answer the same question with a constraint-violation stack trace, and would
-- make each new kind a migration.
--
-- SAFETY: every existing row is a chat, which is exactly what the default writes. Additive,
-- NOT NULL with a default so no row is left undecided, and no existing read changes meaning.

ALTER TABLE conversation.conversations
    ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'chat';

COMMENT ON COLUMN conversation.conversations.kind IS
    'What the conversation is: chat (a thread with a model or an agent) or studio (pure generation, each message independent). Set at creation and never changed - see ConversationKind.';
