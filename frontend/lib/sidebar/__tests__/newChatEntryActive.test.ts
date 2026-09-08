// @vitest-environment node
/**
 * Which entry the sidebar says you are on, for the one entry that is not a page.
 *
 * `isNewChatEntryActive` is the predicate that DRIFTED: the collapsed rail
 * counted the studio home as the new-chat surface and the expanded panel did
 * not, so the same sidebar on the same page said "you are here" once and "you
 * are nowhere" once, depending only on whether it was open. It is a pure
 * function so that answer can be pinned per clause instead of being inferred
 * from a rendered component - and it is the one thing both shapes now read.
 */
import { describe, expect, it } from 'vitest';
import { isNewChatEntryActive, type NewChatActiveInput } from '../navItems';
import type { AppView } from '@/hooks/useCurrentView';

const on = (over: Partial<NewChatActiveInput> = {}): NewChatActiveInput => ({
  view: 'chat' as AppView,
  isDetailPage: false,
  currentConversationId: null,
  pathname: '/app/chat',
  ...over,
});

describe('is the new-chat entry the surface we are on', () => {
  it('yes on the chat home', () => {
    expect(isNewChatEntryActive(on())).toBe(true);
  });

  it('yes on the studio home - the studio is a MODE of the home, not a page of its own', () => {
    // Nothing else in the sidebar can light up there: the studio has no nav row.
    // This is the clause the expanded panel was missing.
    expect(isNewChatEntryActive(on({ view: 'studio' as AppView, pathname: '/app/studio' }))).toBe(true);
  });

  it('no on a studio THREAD, which is a detail page like any other', () => {
    expect(
      isNewChatEntryActive(on({ view: 'studio' as AppView, pathname: '/app/studio/abc', isDetailPage: true })),
    ).toBe(false);
  });

  it('no on an open conversation, by its detail flag', () => {
    expect(isNewChatEntryActive(on({ pathname: '/app/c/abc', isDetailPage: true }))).toBe(false);
  });

  it('no while a conversation is open without the URL saying so', () => {
    // A conversation created in place on /app/chat: the route has not changed,
    // so `isDetailPage` is still false and only the id says we left the home.
    expect(isNewChatEntryActive(on({ currentConversationId: 'conv-1' }))).toBe(false);
  });

  it('no on an open DM thread, which lives on the home surface', () => {
    // Messages is a VIEW of this sidebar rather than a page, and the mode itself
    // is component state, so the route stays on the chat view: the pathname is
    // the ONLY thing that can say the main panel is showing a thread. There is
    // deliberately no "in Messages mode with nothing open" case - it would be
    // byte-identical to the chat-home case above.
    expect(isNewChatEntryActive(on({ pathname: '/app/messages/42' }))).toBe(false);
  });

  it('no on any other page', () => {
    for (const view of ['agenda', 'agent', 'workflow', 'data', 'files', 'marketplace'] as AppView[]) {
      expect(isNewChatEntryActive(on({ view, pathname: `/app/${view}` })), view).toBe(false);
    }
  });

  it('tolerates a missing pathname rather than throwing on it', () => {
    // `usePathname` can be null on the very first client render.
    expect(isNewChatEntryActive(on({ pathname: null }))).toBe(true);
    expect(isNewChatEntryActive(on({ pathname: undefined }))).toBe(true);
  });
});
