'use client';

/**
 * "This conversation's messages were just wiped."
 *
 * The sidebar's row menu can clear an agent conversation's history while that
 * same conversation is open in the main panel. Those are two separate React
 * trees holding two separate copies of the message list, so the sidebar cannot
 * reach into the panel's copy: it used to call the `clearMessages` of its OWN
 * message store - a store nothing renders - and the visible transcript stayed
 * on screen until a reload. Green button, nothing cleared.
 *
 * One event, emitted by whoever wipes and consumed by whoever is showing it.
 * Deliberately a browser event rather than another shared store: it is a fact
 * that happened, not state to keep, and it must reach a listener mounted in a
 * different part of the tree.
 */
export const CONVERSATION_MESSAGES_CLEARED = 'conversation-messages-cleared';

export function emitConversationMessagesCleared(conversationId: string): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(
    new CustomEvent<string>(CONVERSATION_MESSAGES_CLEARED, { detail: conversationId }),
  );
}

/**
 * Listen for a wipe. Returns the unsubscribe, so an effect can `return` it.
 */
export function onConversationMessagesCleared(
  handler: (conversationId: string) => void,
): () => void {
  if (typeof window === 'undefined') return () => {};
  const listener = (event: Event) => {
    const id = (event as CustomEvent<string>).detail;
    if (typeof id === 'string' && id) handler(id);
  };
  window.addEventListener(CONVERSATION_MESSAGES_CLEARED, listener);
  return () => window.removeEventListener(CONVERSATION_MESSAGES_CLEARED, listener);
}
