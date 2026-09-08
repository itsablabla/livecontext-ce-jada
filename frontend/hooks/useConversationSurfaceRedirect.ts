'use client';

import { useEffect } from 'react';
import { conversationKind, conversationRoute, type Conversation } from '@/lib/api/conversationApi';

/**
 * Send a conversation to the surface that can actually serve it.
 *
 * <p>The chat page can be reached with a STUDIO conversation's id: by an old link, a bookmark, a
 * shared link (which records a resource id and no kind), or a history entry from before the studio
 * existed. The chat surface cannot serve one. Its renderer suppresses generation envelopes rather
 * than showing raw JSON, so the thread renders BLANK, and its composer would send the reader's next
 * message to a chat model with those envelopes as prior context - the one state the immutable
 * conversation kind exists to forbid, reached by a URL alone.
 *
 * <p><b>Why this is a hook and not five lines in the page.</b> Inside the chat page it was
 * untestable in practice: mounting that component to exercise one effect is not a test anybody
 * writes, and mutation testing confirmed the effect could be deleted outright with 1861 tests still
 * green. The correction it performs is the last line of defence for a conversation whose kind
 * nobody checked upstream, so it is worth being able to prove.
 *
 * <p>`replace`, not `push`: Back should return the reader wherever they came from rather than
 * bouncing them between the two routes.
 *
 * <p>No request of its own. It reads the conversation the page has already loaded, so an ordinary
 * chat waits on nothing.
 *
 * @param conversation the conversation the page has loaded, if any
 * @param expectedId the id the ROUTE asked for, so a stale load cannot redirect on behalf of a
 *   conversation the reader has already navigated away from
 * @param replace the router's replace, injected so the rule can be exercised without a router
 */
export function useConversationSurfaceRedirect(
  conversation: Pick<Conversation, 'id' | 'kind'> | null | undefined,
  expectedId: string | null | undefined,
  replace: (href: string) => void,
): void {
  useEffect(() => {
    if (!conversation?.id) return;
    // The load can land after the reader moved on. Redirecting then would take them somewhere they
    // did not ask to go, which is worse than the blank thread this exists to prevent.
    if (conversation.id !== expectedId) return;
    // The kind decides whether the current surface can serve it; conversationRoute decides where it
    // belongs. Neither is spelled out here: a hand-written destination is how the two get confused.
    if (conversationKind(conversation) === 'chat') return;
    replace(conversationRoute(conversation));
  }, [conversation, expectedId, replace]);
}
