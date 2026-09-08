// @vitest-environment jsdom
/**
 * The window that holds a "this just appeared" flag.
 *
 * Two surfaces depend on it doing exactly one thing well: the flag goes up, and it comes back down
 * on its own. A flag that never dropped would animate rows that mount minutes later; a timer that
 * outlived the table would set state on an unmounted tree.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

import { useRevealWindow } from '../useRevealWindow';
import { TABLE_REVEAL_WINDOW_MS } from '../../tableStyles';

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('useRevealWindow', () => {
  it('starts pointing at nothing', () => {
    const { result } = renderHook(() => useRevealWindow<string | null>(null));

    expect(result.current[0]).toBeNull();
  });

  it('holds the flag for the window, then drops it', () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useRevealWindow<string | null>(null));

    act(() => result.current[1]('data.Budget'));
    expect(result.current[0]).toBe('data.Budget');

    act(() => { vi.advanceTimersByTime(TABLE_REVEAL_WINDOW_MS - 1); });
    expect(result.current[0]).toBe('data.Budget');

    act(() => { vi.advanceTimersByTime(1); });
    expect(result.current[0]).toBeNull();
  });

  it('restarts the window on a second reveal instead of inheriting the first timer', () => {
    // Otherwise the first reveal's timer fires mid-cue and takes down the second one, which is the
    // one the user is actually looking for.
    vi.useFakeTimers();
    const { result } = renderHook(() => useRevealWindow<string | null>(null));

    act(() => result.current[1]('first'));
    act(() => { vi.advanceTimersByTime(TABLE_REVEAL_WINDOW_MS - 10); });
    act(() => result.current[1]('second'));

    act(() => { vi.advanceTimersByTime(20); });
    expect(result.current[0]).toBe('second');

    act(() => { vi.advanceTimersByTime(TABLE_REVEAL_WINDOW_MS); });
    expect(result.current[0]).toBeNull();
  });

  it('clears a standing flag when asked to point at nothing', () => {
    // A duplicate that copies nothing must take the previous duplicate's highlight down with it.
    vi.useFakeTimers();
    const empty: ReadonlySet<number> = new Set();
    const { result } = renderHook(() => useRevealWindow<ReadonlySet<number>>(empty));

    act(() => result.current[1](new Set([11])));
    act(() => result.current[1](new Set()));

    expect(result.current[0].size).toBe(0);
  });

  it('drops its timer when the table goes away, so nothing writes to a dead tree', () => {
    vi.useFakeTimers();
    const clearSpy = vi.spyOn(globalThis, 'clearTimeout');
    const { result, unmount } = renderHook(() => useRevealWindow<string | null>(null));

    act(() => result.current[1]('data.Budget'));
    unmount();

    // The timer is the load-bearing half: React makes a post-unmount setState a no-op by itself,
    // it does not cancel a pending timeout.
    expect(clearSpy).toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('takes the cue down when the table it was about changes', () => {
    // The grid is not remounted on navigation, and neither a row id nor a column field is unique
    // across tables: a cue left standing would land on whatever shares that id next.
    vi.useFakeTimers();
    const { result, rerender } = renderHook(
      ({ scope }: { scope: string }) => useRevealWindow<string | null>(null, scope),
      { initialProps: { scope: 'table-1' } },
    );

    act(() => result.current[1]('data.Budget'));
    expect(result.current[0]).toBe('data.Budget');

    rerender({ scope: 'table-2' });

    expect(result.current[0]).toBeNull();
  });

  it('keeps the same setter across renders, so effects that depend on it do not re-run', () => {
    const { result, rerender } = renderHook(() => useRevealWindow<ReadonlySet<number>>(new Set()));
    const first = result.current[1];

    rerender();

    expect(result.current[1]).toBe(first);
  });
});
