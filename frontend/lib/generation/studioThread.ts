import {
  parseStudioEnvelope,
  type StudioRequestEnvelope,
  type StudioResultEnvelope,
} from '@/lib/generation/studioMessage';

/**
 * A conversation's messages, read as studio turns.
 *
 * <p><b>Why pairing is a function and not a render loop.</b> A studio turn is two messages - the
 * request and the answer - and the pairing has to survive states that only happen in production: an
 * answer that never arrived (the connection dropped while the server finished and charged), a
 * conversation that is half chat and half studio because it was migrated or written to by something
 * else, an answer with no request before it. Each of those has a right rendering, and none of them
 * is reachable from a hand-written loop that assumes messages come in pairs.
 *
 * <p>The rule is deliberately simple and forward-only: a request opens a turn, the NEXT result
 * closes it. A second request before any result closes the first as unanswered rather than
 * discarding it, because an unanswered turn is exactly the one the reader most needs to see.
 */

export interface StudioTurn {
  /** Stable across reloads: the id of the message that opened the turn. */
  id: string;
  request: StudioRequestEnvelope;
  /** Absent when the answer never arrived. */
  result?: StudioResultEnvelope;
}

/** The shape this reader needs from a message. Anything with an id and content qualifies. */
export interface StudioThreadMessage {
  id?: string;
  content?: string | null;
}

/**
 * Turn a message list into turns, oldest first.
 *
 * <p>Messages that are not studio envelopes are skipped rather than rendered: a studio conversation
 * can hold a stray message (a title synthesis, a system note), and there is nothing about it a
 * studio card could show. They are not dropped from the CONVERSATION, only from this view.
 *
 * <p>A result with no open request is skipped too. It cannot be rendered - a card with no prompt and
 * no parameters says nothing about what produced the asset - and inventing an empty request to hang
 * it on would put a card on screen claiming a generation nobody asked for.
 */
export function buildStudioTurns(messages: readonly StudioThreadMessage[]): StudioTurn[] {
  const turns: StudioTurn[] = [];
  let open: StudioTurn | null = null;

  messages.forEach((message, index) => {
    const envelope = parseStudioEnvelope(message.content);
    if (!envelope) return;

    if (envelope.role === 'request') {
      // A new request while one is still open means the previous answer never arrived. Closing it
      // unanswered is what puts the "this may still have been charged" notice on screen.
      if (open) turns.push(open);
      open = {
        id: message.id ?? `studio-turn-${index}`,
        request: envelope,
      };
      return;
    }

    if (!open) return;
    open.result = envelope;
    turns.push(open);
    open = null;
  });

  if (open) turns.push(open);
  return turns;
}

/*
 * There is deliberately no "is this turn running" helper.
 *
 * A turn in flight is not in the conversation yet - the thread is refreshed only once the ANSWER is
 * written - so the surface draws it from what it submitted. A helper that inferred "running" from
 * "last turn with no result" spun on the wrong thing: an EARLIER turn whose answer was lost is also
 * the last one without a result, so submitting a new turn painted a spinner on a turn that had
 * finished being anything at all.
 */
