// @vitest-environment jsdom
/**
 * The sidebar's own conversation hook.
 *
 * The sidebar used to call `useConversationHistory` - the CHAT PAGE's hook,
 * which is the list PLUS a message store PLUS the mutations. The sidebar has
 * never drawn a message, so the message half was a second, invisible copy of
 * the transcript that the sidebar paid for on every render.
 *
 * Invisible is what made it dangerous: "clear this conversation's messages"
 * emptied that private copy, and since nothing rendered it, the visible
 * transcript stayed on screen and the bug could not show. These tests pin both
 * halves - the list behaviour that had to survive the move, and the wipe now
 * being ANNOUNCED so the surface actually showing the messages hears it.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, renderHook } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { CONVERSATION_MESSAGES_CLEARED } from '@/lib/chat/conversationMessagesBus';

let listConversations: Array<Record<string, unknown>> = [];
let sharedConversations: Array<Record<string, unknown>> = [];
let sharedHasMore: boolean | undefined = false;
const setConversations = vi.fn();
const deleteConversationMutation = vi.fn(() => Promise.resolve());
let mutationError: string | null = null;
let listError: string | null = null;
const clearConversationMessages = vi.fn((_conversationId: string) => Promise.resolve());

vi.mock('@/hooks/conversation/useConversationList', () => ({
  useConversationList: () => ({
    conversations: listConversations,
    loading: false,
    error: listError,
    hasMore: false,
    loadMoreConversations: vi.fn(),
    loadConversationById: vi.fn(),
    forceRefreshConversations: vi.fn(),
    setConversations,
  }),
}));
vi.mock('@/hooks/conversation/useConversationMutations', () => ({
  useConversationMutations: () => ({
    loading: false,
    error: mutationError,
    createConversation: vi.fn(),
    updateConversation: vi.fn(),
    deleteConversation: deleteConversationMutation,
    clearError: vi.fn(),
  }),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({
  useUnifiedApp: () => ({ state: { conversations: sharedConversations, hasMore: sharedHasMore } }),
}));
vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: { clearConversationMessages: (conversationId: string) => clearConversationMessages(conversationId) },
}));

import { useSidebarConversations } from '../useSidebarConversations';

const render = (currentConversationId: string | null = null) =>
  renderHook(() => useSidebarConversations({ autoLoad: true, currentConversationId }));

beforeEach(() => {
  listConversations = [];
  sharedConversations = [];
  sharedHasMore = false;
  mutationError = null;
  listError = null;
  vi.clearAllMocks();
});
afterEach(cleanup);

describe('the list the sidebar draws', () => {
  it('orders by most recent activity, whatever order it was handed', () => {
    listConversations = [
      { id: 'a', title: 'Alpha', updatedAt: '2026-06-01T10:00:00Z' },
      { id: 'b', title: 'Beta', updatedAt: '2026-06-03T10:00:00Z' },
      { id: 'c', title: 'Gamma', updatedAt: '2026-06-02T10:00:00Z' },
    ];

    const { result } = render();

    expect(result.current.conversations.map((c) => c.id)).toEqual(['b', 'c', 'a']);
  });

  it('falls back to createdAt when a row has no updatedAt', () => {
    listConversations = [
      { id: 'b', title: 'Beta', updatedAt: '2026-06-04T10:00:00Z' },
      { id: 'a', title: 'Alpha', createdAt: '2026-06-05T10:00:00Z' },
    ];

    const { result } = render();

    expect(result.current.conversations.map((c) => c.id)).toEqual(['a', 'b']);
  });

  it('keeps a conversation that exists only in the shared cache, and dates it correctly', () => {
    // The just-created case: the chat page writes an optimistic row into the
    // shared cache before the server list knows about it.
    listConversations = [{ id: 'r1', title: 'Server One', updatedAt: '2026-06-02T10:00:00Z' }];
    sharedConversations = [
      { id: 'r1', title: 'Server One' },
      { id: 's1', title: 'Optimistic', updatedAt: '2026-06-05T10:00:00Z' },
    ];

    const { result } = render();

    expect(result.current.conversations.map((c) => c.id)).toEqual(['s1', 'r1']);
  });

  it('prefers the full server row over the compact cached one', () => {
    // The shared cache carries no provider/messageCount. Preferring it would
    // make the hover pill read a placeholder instead of the real timestamps.
    listConversations = [
      { id: 'r1', title: 'Server One', updatedAt: '2026-06-02T10:00:00Z', userId: 'u1', provider: 'openai', messageCount: 7 },
    ];
    sharedConversations = [{ id: 'r1', title: 'Server One' }];

    const { result } = render();

    expect(result.current.conversations[0].messageCount).toBe(7);
    expect(result.current.conversations[0].provider).toBe('openai');
  });

  it('keeps the server row when the two stores disagree, so order follows the fetched timestamps', () => {
    // Deliberately NOT an overlay of the cache's copy. The cache is insert-only
    // for timestamps, so its `updatedAt` is frozen at first sight; letting it win
    // pins the list to that frozen value and undoes the refetch that exists to
    // heal ordering after a chat exit.
    listConversations = [
      { id: 'a', title: 'Alpha', updatedAt: '2026-06-05T10:00:00Z', userId: 'u1' },
      { id: 'b', title: 'Beta', updatedAt: '2026-06-06T10:00:00Z', userId: 'u1' },
    ];
    sharedConversations = [
      { id: 'a', title: 'Alpha', updatedAt: '2026-06-05T10:00:00Z' },
      // Frozen at the moment it was first seen, hours behind the server row.
      { id: 'b', title: 'Beta', updatedAt: '2026-06-01T10:00:00Z' },
    ];

    const { result } = render();

    expect(result.current.conversations.map((c) => c.id)).toEqual(['b', 'a']);
  });

  it('never lists the same conversation twice', () => {
    listConversations = [{ id: 'x', title: 'X', updatedAt: '2026-06-02T10:00:00Z' }];
    sharedConversations = [
      { id: 'x', title: 'X' },
      { id: 'y', title: 'Y', updatedAt: '2026-06-01T10:00:00Z' },
    ];

    const { result } = render();

    expect(result.current.conversations.map((c) => c.id)).toEqual(['x', 'y']);
  });

  it('takes hasMore from the shared cache when it has an answer', () => {
    sharedHasMore = true;

    const { result } = render();

    expect(result.current.hasMore).toBe(true);
  });
});

describe('wiping an agent conversation', () => {
  it('announces the wipe so the surface showing the transcript can empty it', async () => {
    // The bug this replaces: the sidebar cleared a message store nothing
    // rendered, so the open conversation kept its messages until a reload.
    const heard: string[] = [];
    const listener = (e: Event) => heard.push((e as CustomEvent<string>).detail);
    window.addEventListener(CONVERSATION_MESSAGES_CLEARED, listener);

    const { result } = render('conv-1');
    await act(async () => {
      await result.current.clearMessages('conv-1');
    });

    expect(clearConversationMessages).toHaveBeenCalledWith('conv-1');
    expect(heard).toEqual(['conv-1']);
    window.removeEventListener(CONVERSATION_MESSAGES_CLEARED, listener);
  });

  it('says nothing when the server refused', async () => {
    // Announcing a wipe that did not happen would empty the visible transcript
    // and leave it empty until a reload - the same bug, pointing the other way.
    clearConversationMessages.mockRejectedValueOnce(new Error('boom'));
    const heard: string[] = [];
    const listener = (e: Event) => heard.push((e as CustomEvent<string>).detail);
    window.addEventListener(CONVERSATION_MESSAGES_CLEARED, listener);

    const { result } = render('conv-1');
    await act(async () => {
      await expect(result.current.clearMessages('conv-1')).rejects.toThrow('boom');
    });

    expect(heard).toEqual([]);
    window.removeEventListener(CONVERSATION_MESSAGES_CLEARED, listener);
  });
});

describe('deleting', () => {
  it('goes through the shared mutation, which also drops it from the cache', async () => {
    listConversations = [{ id: 'gone', title: 'Gone', updatedAt: '2026-06-02T10:00:00Z' }];

    const { result } = render('gone');
    await act(async () => {
      await result.current.deleteConversation('gone');
    });

    expect(deleteConversationMutation).toHaveBeenCalledWith('gone', listConversations, 'gone');
  });
});

describe('when something fails, the sidebar can say so', () => {
  it('surfaces a failed delete', () => {
    // `useConversationMutations` swallows a server refusal into its own error
    // state instead of throwing, so the sidebar's confirmation modal closes on
    // the failure path too. If this error is not surfaced the user clicks
    // delete, the row stays, and nothing anywhere says why.
    mutationError = 'Failed to delete conversation';

    const { result } = render();

    expect(result.current.error).toBe('Failed to delete conversation');
  });

  it('still surfaces a failed list load', () => {
    listError = 'Server error (500)';

    const { result } = render();

    expect(result.current.error).toBe('Server error (500)');
  });

  it('leads with the list error when both failed, since that is the one blanking the list', () => {
    listError = 'Server error (500)';
    mutationError = 'Failed to delete conversation';

    const { result } = render();

    expect(result.current.error).toBe('Server error (500)');
  });

  it('is quiet when nothing failed', () => {
    const { result } = render();

    expect(result.current.error).toBeNull();
  });
});

describe('what the hook is allowed to pull in', () => {
  it('does not mount the chat page message store', () => {
    // The whole point: a sidebar that draws titles must not carry a second copy
    // of the transcript, its abort controller and its two 60-second timers. A
    // behavioural assertion cannot see this - the extra store is invisible
    // precisely because nothing renders it - so the import list is the test.
    const source = readFileSync(join(__dirname, '../useSidebarConversations.ts'), 'utf8');
    // Imports only: the file's own comments name both hooks, explaining why it
    // does not use them.
    const imports = source
      .split(String.fromCharCode(10))
      .filter((line) => line.trimStart().startsWith('import '))
      .join(String.fromCharCode(10));

    expect(imports).not.toContain('useMessages');
    expect(imports).not.toContain('useConversationHistory');
  });
});
