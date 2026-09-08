// @vitest-environment jsdom
/**
 * The chat surface empties its transcript when someone else wipes it.
 *
 * The sidebar's row menu can clear an agent conversation's history while that
 * conversation is open in the main panel. The two surfaces hold two independent
 * message stores, so the sidebar cannot reach the panel's copy: it used to call
 * the `clearMessages` of its OWN store - one nothing renders - and the visible
 * transcript stayed on screen until a reload. Green button, nothing cleared.
 *
 * These fail on the pre-change tree, where no such listener exists.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, renderHook } from '@testing-library/react';
import { emitConversationMessagesCleared } from '@/lib/chat/conversationMessagesBus';

const clearMessages = vi.fn();
let sharedCurrentConversationId: string | null = null;

vi.mock('@/hooks/conversation', () => ({
  useConversationList: () => ({
    conversations: [],
    loading: false,
    error: null,
    hasMore: false,
    currentPage: 0,
    isSearching: false,
    loadConversations: vi.fn(),
    loadMoreConversations: vi.fn(),
    searchConversations: vi.fn(),
    clearSearch: vi.fn(),
    loadConversationById: vi.fn(async () => null),
    forceRefreshConversations: vi.fn(),
    refetchConversations: vi.fn(),
    setConversations: vi.fn(),
  }),
  useMessages: () => ({
    messages: [],
    hasMoreMessages: false,
    loadingOlderMessages: false,
    messagesLoading: false,
    sendingMessage: false,
    loadingTimeout: false,
    error: null,
    loadMessages: vi.fn(),
    loadOlderMessages: vi.fn(),
    addMessageLocal: vi.fn(),
    updateMessageLocal: vi.fn(),
    removeMessageLocal: vi.fn(),
    sendMessage: vi.fn(),
    clearMessages,
    clearError: vi.fn(),
    clearLoadingTimeout: vi.fn(),
    setMessages: vi.fn(),
  }),
  useConversationMutations: () => ({
    loading: false,
    error: null,
    createConversation: vi.fn(),
    updateConversation: vi.fn(),
    deleteConversation: vi.fn(),
    clearError: vi.fn(),
  }),
}));
vi.mock('@/hooks/conversation/useDeletedConversationsSync', () => ({
  useDeletedConversationsSync: () => undefined,
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({
  useUnifiedApp: () => ({
    state: { conversations: [], currentConversationId: sharedCurrentConversationId },
    removeConversation: vi.fn(),
  }),
}));

import { useConversationHistory } from '../useConversationHistory';

beforeEach(() => {
  clearMessages.mockClear();
  sharedCurrentConversationId = null;
});
afterEach(cleanup);

describe('a wipe announced from another surface', () => {
  it('empties the transcript when it is the conversation being shown', () => {
    sharedCurrentConversationId = 'conv-1';
    renderHook(() => useConversationHistory());

    act(() => emitConversationMessagesCleared('conv-1'));

    expect(clearMessages).toHaveBeenCalledTimes(1);
  });

  it('empties it when only the hook knows which conversation it is showing', () => {
    // The shared context is not always the answer: the hook tracks its own
    // `currentConversation` (set by loadConversationAndMessages / select), and a
    // surface can be showing one the context has not been told about. Both
    // halves of the OR have to work or the wipe is a no-op on that surface.
    sharedCurrentConversationId = null;
    const { result } = renderHook(() => useConversationHistory());
    act(() => result.current.selectConversation({ id: 'conv-2', title: 'Local only' } as never));

    act(() => emitConversationMessagesCleared('conv-2'));

    // `selectConversation(x)` does not clear; only the announced wipe does.
    expect(clearMessages).toHaveBeenCalledTimes(1);
  });

  it('leaves a different conversation alone', () => {
    // Clearing here would blank a transcript the user is reading, on the say-so
    // of an action aimed at another conversation.
    sharedCurrentConversationId = 'conv-1';
    renderHook(() => useConversationHistory());

    act(() => emitConversationMessagesCleared('some-other-conversation'));

    expect(clearMessages).not.toHaveBeenCalled();
  });

  it('stops listening once the surface is gone', () => {
    sharedCurrentConversationId = 'conv-1';
    const { unmount } = renderHook(() => useConversationHistory());

    unmount();
    act(() => emitConversationMessagesCleared('conv-1'));

    expect(clearMessages).not.toHaveBeenCalled();
  });
});
