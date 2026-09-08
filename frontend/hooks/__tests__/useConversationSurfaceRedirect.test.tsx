// @vitest-environment jsdom
/**
 * The last line of defence for a conversation whose kind nobody checked upstream.
 *
 * <p>The chat page can be handed a STUDIO conversation's id - an old link, a bookmark, a shared
 * link (which records a resource id and no kind), a history entry from before the studio existed.
 * The chat surface cannot serve one: its renderer suppresses generation envelopes, so the thread
 * renders BLANK, and its composer sends the reader's next message to a chat model with those
 * envelopes as prior context. That is the one state the immutable conversation kind exists to
 * forbid, reached by a URL alone, with no error and nothing in any log.
 *
 * <p>Before this hook existed the rule was a five-line effect inside a component nobody mounts, and
 * deleting it outright left 1861 tests green.
 */
import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { useConversationSurfaceRedirect } from '../useConversationSurfaceRedirect';

type Conv = { id: string; kind?: string };

function render(conversation: Conv | null, expectedId: string | null) {
  const replace = vi.fn();
  renderHook(() => useConversationSurfaceRedirect(conversation as never, expectedId, replace));
  return replace;
}

describe('useConversationSurfaceRedirect', () => {
  it('sends a studio conversation opened at a chat URL to the studio', () => {
    const replace = render({ id: 'c1', kind: 'studio' }, 'c1');

    expect(replace).toHaveBeenCalledWith('/app/studio/c1');
  });

  it('leaves an ordinary chat exactly where it is', () => {
    // The other direction, and the one that would be constant: redirecting a chat would bounce
    // every conversation in the product through a route change on every load.
    const replace = render({ id: 'c2', kind: 'chat' }, 'c2');

    expect(replace).not.toHaveBeenCalled();
  });

  it('treats a conversation with no kind as a chat, so nothing that predates the column moves', () => {
    const replace = render({ id: 'c3' }, 'c3');

    expect(replace).not.toHaveBeenCalled();
  });

  it('does NOT redirect on behalf of a conversation the reader has already left', () => {
    // A load can land after the route moved on. Redirecting then takes the reader somewhere they
    // did not ask to go, which is worse than the blank thread this exists to prevent.
    const replace = render({ id: 'stale', kind: 'studio' }, 'the-one-on-screen');

    expect(replace).not.toHaveBeenCalled();
  });

  it('does nothing while no conversation has loaded yet', () => {
    // The ordinary first render. A redirect built on an absent conversation would fire on every
    // cold load before the page knows what it is showing.
    const replace = render(null, 'c1');

    expect(replace).not.toHaveBeenCalled();
  });

  it('replaces rather than pushes, so Back does not bounce between the two surfaces', () => {
    // Asserted through the injected function's identity: the hook is handed `replace` and must not
    // reach for anything else. A push would trap the reader between chat and studio on Back.
    const replace = vi.fn();
    renderHook(() => useConversationSurfaceRedirect(
      { id: 'c1', kind: 'studio' } as never, 'c1', replace));

    expect(replace).toHaveBeenCalledTimes(1);
  });
});
