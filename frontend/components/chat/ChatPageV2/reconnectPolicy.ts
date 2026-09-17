export function shouldAttemptConversationReconnect(params: {
  isExistingConversation: boolean;
  conversationId: string | null | undefined;
  serverStreamsLoaded: boolean;
  attemptedConversationId: string | null;
  localStreamStatus?: string | null;
}): boolean {
  const {
    isExistingConversation,
    conversationId,
    serverStreamsLoaded,
    attemptedConversationId,
    localStreamStatus,
  } = params;

  if (!isExistingConversation || !conversationId) return false;
  if (!serverStreamsLoaded) return false;
  if (attemptedConversationId === conversationId) return false;

  // The active-stream inventory only lists STREAMING conversations. A stream that
  // finished while the user was away can still have recoverable buffered state
  // (final content, approval cards, terminal snapshot) even though it no longer
  // appears in that live list, so reopening an existing conversation gets one
  // reconnect check unless a LOCAL stream is already running.
  return localStreamStatus !== 'streaming';
}
