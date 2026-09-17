import { describe, expect, it } from 'vitest';
import { shouldAttemptConversationReconnect } from '../reconnectPolicy';

describe('shouldAttemptConversationReconnect', () => {
  it('retries once for an existing conversation after the stream inventory loads even without a live-server hint', () => {
    expect(shouldAttemptConversationReconnect({
      isExistingConversation: true,
      conversationId: 'conv-1',
      serverStreamsLoaded: true,
      attemptedConversationId: null,
      localStreamStatus: 'completed',
    })).toBe(true);
  });

  it('skips when a local stream is already running', () => {
    expect(shouldAttemptConversationReconnect({
      isExistingConversation: true,
      conversationId: 'conv-1',
      serverStreamsLoaded: true,
      attemptedConversationId: null,
      localStreamStatus: 'streaming',
    })).toBe(false);
  });

  it('skips after the one reconnect attempt already ran for that conversation', () => {
    expect(shouldAttemptConversationReconnect({
      isExistingConversation: true,
      conversationId: 'conv-1',
      serverStreamsLoaded: true,
      attemptedConversationId: 'conv-1',
      localStreamStatus: 'completed',
    })).toBe(false);
  });
});
