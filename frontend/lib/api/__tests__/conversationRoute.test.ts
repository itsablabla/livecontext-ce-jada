/**
 * Where a conversation LIVES, which is the rule three surfaces navigate by.
 *
 * <p>The sidebar, the global search and the chat page's own redirect all have to answer the same
 * question, and every way of getting it wrong is silent:
 *
 * <ul>
 *   <li><b>A studio conversation sent to /app/c</b> renders a BLANK thread - the chat renderer
 *       suppresses generation envelopes rather than showing raw JSON - and the chat composer then
 *       sends the reader's next message to a chat model with those envelopes as prior context.
 *       That is the exact state the conversation kind is immutable to prevent, reached by routing
 *       alone.
 *   <li><b>A chat sent to /app/studio</b> is bounced straight back by the studio's own guard, so
 *       the reader gets a flash and lands where they started, with nothing explaining it.
 *   <li><b>A conversation with no kind at all</b> - every row stored before the column existed -
 *       must read as a chat. Treating an absent kind as unknown, or as studio, moves the whole
 *       history of the product into a surface that cannot render it.
 * </ul>
 *
 * <p>The rule lives in one function for that reason, and this suite is what makes the function
 * worth trusting: the three call sites are inside components too large to mount for the sake of one
 * ternary, which is how all three came to have no test between them.
 */
import { describe, expect, it } from 'vitest';

import { conversationRoute } from '../conversation.types';

describe('conversationRoute', () => {
  it('sends a studio conversation to the studio surface', () => {
    expect(conversationRoute({ id: 'c1', kind: 'studio' })).toBe('/app/studio/c1');
  });

  it('sends a chat to the chat surface', () => {
    // The other direction, so a rule hardcoded to the studio cannot pass: it would bounce every
    // ordinary conversation through a redirect on every click.
    expect(conversationRoute({ id: 'c2', kind: 'chat' })).toBe('/app/c/c2');
  });

  it('treats a conversation with NO kind as a chat', () => {
    // Every conversation created before the column existed has none. This is the fallback that
    // decides where the entire pre-studio history opens.
    // Cast because the TYPE says a kind is always one of two values and the WIRE does not: these
    // rows are real and predate the column.
    expect(conversationRoute({ id: 'c3' } as never)).toBe('/app/c/c3');
  });

  it('treats an unrecognised kind as a chat rather than guessing', () => {
    // A self-hosted install can read a cloud that is a version ahead and hand back a kind this
    // build has never heard of. Routing it to the studio would open a surface that cannot render
    // it; the chat surface at least shows the messages.
    expect(conversationRoute({ id: 'c4', kind: 'transcript' } as never)).toBe('/app/c/c4');
  });

  it('puts the id in the path, so two conversations do not share a route', () => {
    // Guards the shape as well as the branch: a rule that returned the bare surface would send
    // every click to the same empty page.
    expect(conversationRoute({ id: 'abc-123', kind: 'studio' })).toBe('/app/studio/abc-123');
    expect(conversationRoute({ id: 'def-456', kind: 'studio' })).toBe('/app/studio/def-456');
  });
});
