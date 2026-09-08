// @vitest-environment jsdom
/**
 * The side panel's transcript empties too when a conversation is wiped.
 *
 * There are TWO surfaces that draw a conversation's messages off their own
 * `useMessages` store: the chat page and this panel. The sidebar's "Clear
 * messages" announces the wipe, and a surface that does not listen keeps showing
 * messages the server no longer has - which is exactly the bug the announcement
 * was introduced to kill, one surface further along.
 *
 * The last test here is the one that matters most: it pins the INVARIANT rather
 * than this instance. It DISCOVERS the surfaces (every file that mounts the
 * store) instead of listing them, so a third transcript surface added later
 * fails here on the day it is written rather than shipping the same stale view.
 *
 * Its ceiling, stated so nobody over-reads it: a source scan proves the
 * listener's NAME is in the file, not that the subscription is live. Renaming
 * the import would slip past it. That is what the three behavioural tests above
 * are for on the surfaces we know about; the scan is what covers the ones we do
 * not.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { readFileSync, readdirSync } from 'node:fs';
import { basename, join, sep } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import { emitConversationMessagesCleared } from '@/lib/chat/conversationMessagesBus';

const setMessages = vi.fn();
const clearMessages = vi.fn();
let messages: Array<Record<string, unknown>> = [];

vi.mock('@/hooks/conversation/useMessages', () => ({
  useMessages: () => ({
    messages,
    messagesLoading: false,
    hasMoreMessages: false,
    loadingOlderMessages: false,
    error: null,
    // The panel chains off this one, so it has to be a promise.
    loadMessages: vi.fn(() => Promise.resolve()),
    loadOlderMessages: vi.fn(() => Promise.resolve()),
    setMessages,
    clearMessages,
  }),
}));
vi.mock('@/lib/websocket/use-conversation-channel', () => ({ useConversationChannel: () => undefined }));
vi.mock('@/components/chat/MessageHistory', () => ({ MessageHistory: () => <div data-testid="messages" /> }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { ConversationPanelContent } from '../ConversationPanelContent';

beforeEach(() => {
  messages = [{ id: 'm1', role: 'user', content: 'hello' }];
  setMessages.mockClear();
  clearMessages.mockClear();
});
afterEach(cleanup);

describe('the side panel listens for a wipe', () => {
  it('empties its transcript when the conversation it shows is cleared', () => {
    render(<ConversationPanelContent conversationId="conv-1" />);

    act(() => emitConversationMessagesCleared('conv-1'));

    // clearMessages, not a bare setMessages([]): the store's own clear also
    // aborts the fetch that may be in flight, which would otherwise land after
    // the wipe and re-fill the panel from a server that has nothing left.
    expect(clearMessages).toHaveBeenCalledTimes(1);
  });

  it('leaves another conversation alone', () => {
    render(<ConversationPanelContent conversationId="conv-1" />);

    act(() => emitConversationMessagesCleared('conv-2'));

    expect(clearMessages).not.toHaveBeenCalled();
  });

  it('stops listening once the panel is closed', () => {
    const { unmount } = render(<ConversationPanelContent conversationId="conv-1" />);

    unmount();
    act(() => emitConversationMessagesCleared('conv-1'));

    expect(clearMessages).not.toHaveBeenCalled();
  });
});

describe('the invariant, not the instance', () => {
  it('every surface that mounts a message store subscribes to the wipe', () => {
    // A behavioural test can only cover the surfaces someone thought to write a
    // test for, and the failure mode here is precisely a surface nobody thought
    // of: it renders correctly, it just never hears that its copy is stale. So
    // the owners are DISCOVERED rather than listed - a third transcript surface
    // added next year fails here on the day it is written.
    // A DENY-list from the app root, not an allow-list of directories. An
    // allow-list stops covering the day someone adds a top-level folder, and
    // says nothing while it happens - the same trap the CE export's root gate
    // exists to catch.
    const SKIP = new Set([
      'node_modules', '.next', '__tests__', 'e2e', 'public', 'messages', 'test-results',
      'playwright-report', 'coverage', 'scripts', 'tmp', 'styles', 'types',
    ]);
    const files: string[] = [];
    const walk = (dir: string) => {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        if (entry.isDirectory()) {
          if (SKIP.has(entry.name) || entry.name.startsWith('.')) continue;
          walk(join(dir, entry.name));
        } else if (/\.tsx?$/.test(entry.name)) {
          files.push(join(dir, entry.name));
        }
      }
    };
    walk(join(__dirname, '../../..'));

    // The hook's own definition is not a surface; everything else that CALLS it
    // renders what it holds. Only that one exemption: the barrel re-exports the
    // hook without calling it, so the filter never selects it, and an exemption
    // that excludes nothing is a hole waiting for the day the file changes.
    const owners = files.filter(
      (file) =>
        /useMessages\s*\(/.test(readFileSync(file, 'utf8'))
        && !file.endsWith(`${sep}useMessages.ts`),
    );

    // Both halves matter. Discovery catches a surface nobody listed; this floor
    // catches the opposite failure - a walk that silently stops covering part of
    // the tree would still find SOME owner and pass, which is how a guard goes
    // quiet without going red.
    expect(
      owners.map((file) => basename(file)).sort(),
      'the walk no longer reaches the surfaces we know mount a store',
    ).toEqual(expect.arrayContaining(['ConversationPanelContent.tsx', 'useConversationHistory.ts']));
    for (const file of owners) {
      expect(
        readFileSync(file, 'utf8'),
        `${file} draws a transcript but never hears that it was cleared`,
      ).toContain('onConversationMessagesCleared');
    }
  });
});
