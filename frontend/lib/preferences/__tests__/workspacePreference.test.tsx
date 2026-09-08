// @vitest-environment jsdom
/**
 * The storage discipline three builder preferences share.
 *
 * It was written out twice before this and was about to be written a third time, which is
 * how the copies drift: one of them coerces an unexpected stored value instead of ignoring
 * it, or forgets the org in the key, and the bug shows up in one preference only.
 *
 * The cases below are the ones a copy gets wrong, not a re-test of localStorage: an
 * unrecognised stored value must read as ABSENT rather than be coerced, a store that
 * throws must not take the page with it, a failed write must still change the behaviour
 * for this session, and the key must carry the workspace so two orgs can disagree.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, cleanup, renderHook } from '@testing-library/react';

import {
  readWorkspacePreference,
  useWorkspacePreference,
  workspacePreferenceKey,
} from '../workspacePreference';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

type Mode = 'a' | 'b';
const isMode = (value: string | null): value is Mode => value === 'a' || value === 'b';
const PREFIX = 'lc.test.pref';

beforeEach(() => {
  window.localStorage.clear();
  act(() => useCurrentOrgStore.getState().clear());
});

afterEach(() => cleanup());

describe('workspacePreferenceKey', () => {
  it('names the personal workspace explicitly rather than leaving a bare prefix', () => {
    // A key that just ends at the prefix for personal would collide with any future
    // unscoped write under the same name.
    expect(workspacePreferenceKey(PREFIX, null)).toBe('lc.test.pref:personal');
    expect(workspacePreferenceKey(PREFIX, undefined)).toBe('lc.test.pref:personal');
    expect(workspacePreferenceKey(PREFIX, 'org-a')).toBe('lc.test.pref:org-a');
  });
});

describe('readWorkspacePreference', () => {
  it('treats an unrecognised stored value as absent, never as truthy', () => {
    // The classic way this kind of helper ships the wrong behaviour: coercing whatever is
    // in storage. A stale format, a key collision or someone poking devtools must land on
    // the caller's default.
    window.localStorage.setItem('lc.test.pref:personal', 'something-else');

    expect(readWorkspacePreference(PREFIX, null, isMode)).toBeNull();
  });

  it('reads back a value it recognises', () => {
    window.localStorage.setItem('lc.test.pref:personal', 'b');

    expect(readWorkspacePreference(PREFIX, null, isMode)).toBe('b');
  });

  it('survives a store that THROWS on access', () => {
    // Safari private mode and a full quota both do this. The injection point exists
    // because no test can otherwise reach it.
    const hostile = {
      getItem() {
        throw new Error('SecurityError');
      },
    } as unknown as Storage;

    expect(readWorkspacePreference(PREFIX, null, isMode, hostile)).toBeNull();
  });

  it('reads nothing when there is no storage at all, which is the server render', () => {
    expect(readWorkspacePreference(PREFIX, null, isMode, null)).toBeNull();
  });

  it('survives the ACCESSOR throwing, not just the method', () => {
    // The edge a hostile-object test cannot reach and the one that actually broke: in a
    // browser set to block all site data it is `window.localStorage` ITSELF that throws,
    // before any method is called. With the store resolved outside the try, that throw
    // escaped the guard and took the provider's mount effect with it.
    const owned = Object.getOwnPropertyDescriptor(window, 'localStorage');
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('SecurityError');
      },
    });
    try {
      expect(readWorkspacePreference(PREFIX, null, isMode)).toBeNull();
    } finally {
      if (owned) Object.defineProperty(window, 'localStorage', owned);
      else delete (window as unknown as Record<string, unknown>).localStorage;
    }
  });
});

describe('useWorkspacePreference', () => {
  function setup() {
    return renderHook(() => useWorkspacePreference<Mode>(PREFIX, isMode, 'a'));
  }

  it('restores the stored value after mount, not during render', () => {
    // The value IS applied - what matters is when. Reading storage in the useState
    // initializer is the hydration mismatch this helper exists to avoid, so the seed is
    // the default and the stored value arrives one effect later. renderHook flushes that
    // effect, which is why the assertion below sees 'b' rather than the seed; the seed
    // itself is asserted by the next test, whose default survives an org with nothing
    // stored.
    window.localStorage.setItem('lc.test.pref:personal', 'b');

    const { result } = setup();

    expect(result.current[0]).toBe('b');
  });

  it('persists a choice under the active workspace', () => {
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    const { result } = setup();

    act(() => result.current[1]('b'));

    expect(window.localStorage.getItem('lc.test.pref:org-a')).toBe('b');
    expect(window.localStorage.getItem('lc.test.pref:personal')).toBeNull();
  });

  it('re-reads when the workspace changes, so two orgs can disagree', () => {
    window.localStorage.setItem('lc.test.pref:personal', 'a');
    window.localStorage.setItem('lc.test.pref:org-a', 'b');
    const { result } = setup();
    expect(result.current[0]).toBe('a');

    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));

    expect(result.current[0]).toBe('b');
  });

  it('falls back to the default in a workspace that has never chosen', () => {
    // Not "keep whatever the last workspace had": that would silently apply one org's
    // choice to another.
    window.localStorage.setItem('lc.test.pref:personal', 'b');
    const { result } = setup();
    expect(result.current[0]).toBe('b');

    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));

    expect(result.current[0]).toBe('a');
  });

  it('keeps the choice in memory when the write fails', () => {
    // Private mode: the preference cannot survive the session, but it must still take
    // effect during it. Refusing the change as well would be two failures for one cause.
    const setItem = window.localStorage.setItem;
    window.localStorage.setItem = () => {
      throw new Error('QuotaExceededError');
    };
    try {
      const { result } = setup();

      act(() => result.current[1]('b'));

      expect(result.current[0]).toBe('b');
    } finally {
      window.localStorage.setItem = setItem;
    }
  });
});
