/**
 * @vitest-environment jsdom
 *
 * The shared ticking clock.
 *
 * Every calendar surface used to read `new Date()` during render, which freezes on the
 * mount instant: an agenda left open all afternoon still drew its now-line where it was
 * at 14:00, and one left open overnight highlighted yesterday as today. The hook exists so
 * one instant drives all of them, and so it MOVES.
 *
 * The alignment is the part worth pinning: a clock printing `hh:mm` has to change on the
 * minute, and a timer started at mount lands up to a full interval late, every time.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useNow, useDayKey } from '../useNow';

describe('useNow', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    // 10 seconds past a 30s boundary: the next tick is owed in 20s, not in 30.
    vi.setSystemTime(new Date('2026-09-03T09:30:10.000Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('starts at the current instant', () => {
    const { result } = renderHook(() => useNow());

    expect(result.current.toISOString()).toBe('2026-09-03T09:30:10.000Z');
  });

  it('ticks on the interval boundary, not one full interval after mount', () => {
    const { result } = renderHook(() => useNow(30_000));

    // 19.9s in: the boundary has not arrived, so nothing has changed.
    act(() => { vi.advanceTimersByTime(19_900); });
    expect(result.current.toISOString()).toBe('2026-09-03T09:30:10.000Z');

    // The boundary itself: :30 exactly, which is what a clock has to show on time.
    act(() => { vi.advanceTimersByTime(100); });
    expect(result.current.toISOString()).toBe('2026-09-03T09:30:30.000Z');
  });

  it('keeps ticking, every interval after the first', () => {
    const { result } = renderHook(() => useNow(30_000));

    act(() => { vi.advanceTimersByTime(20_000); });
    act(() => { vi.advanceTimersByTime(30_000); });

    expect(result.current.toISOString()).toBe('2026-09-03T09:31:00.000Z');
  });

  it('stops when the view unmounts', () => {
    // A timer that outlives its view keeps setting state on it. The agenda is mounted and
    // unmounted on every navigation, so a leak here is one per page visit.
    const { unmount } = renderHook(() => useNow(30_000));
    unmount();

    expect(vi.getTimerCount()).toBe(0);
  });

  it('freezes when asked for a non-positive interval', () => {
    // `intervalMs <= 0` is a caller asking for the mount instant. Scheduling on it would
    // be a zero-delay loop, which is the one way this hook could hang a page.
    const { result } = renderHook(() => useNow(0));

    act(() => { vi.advanceTimersByTime(120_000); });

    expect(result.current.toISOString()).toBe('2026-09-03T09:30:10.000Z');
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('useDayKey', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-03T23:58:00Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('reads the day in the zone it is asked for, not the host zone', () => {
    // 23:58 UTC is already the 4th in Tokyo. A surface drawn in Tokyo has to mark the
    // Tokyo day, or the highlighted cell contradicts every row under it.
    const utc = renderHook(() => useDayKey('UTC'));
    const tokyo = renderHook(() => useDayKey('Asia/Tokyo'));

    expect(utc.result.current).toBe('2026-09-03');
    expect(tokyo.result.current).toBe('2026-09-04');
  });

  it('re-renders NOTHING on a tick that stays inside the same day', () => {
    // This is the whole reason the hook returns a key and not a `Date`. A new Date object
    // is never equal to the last one, so the month grid - up to 42 droppable cells and a
    // few hundred draggable chips, each re-registering with the drag context - would
    // re-render every minute to flip one boolean once a day. An equal string makes React
    // bail out of the render entirely.
    let renders = 0;
    const { result } = renderHook(() => {
      renders += 1;
      return useDayKey('UTC');
    });
    const afterMount = renders;

    act(() => { vi.advanceTimersByTime(60_000); });

    expect(result.current).toBe('2026-09-03');
    expect(renders).toBe(afterMount);
  });

  it('changes when the day turns, with nothing else on the page moving', () => {
    const { result } = renderHook(() => useDayKey('UTC'));

    act(() => {
      vi.setSystemTime(new Date('2026-09-04T00:01:00Z'));
      vi.advanceTimersByTime(3 * 60_000);
    });

    expect(result.current).toBe('2026-09-04');
  });

  it('re-reads immediately when the zone changes, without waiting for a tick', () => {
    // The agenda has a zone picker, and a zone change can move the calendar across a date
    // line. Waiting up to a minute for the next tick would leave "today" on the day the
    // PREVIOUS zone was on, while every occurrence around it had already been redrawn.
    const { result, rerender } = renderHook(({ zone }) => useDayKey(zone), {
      initialProps: { zone: 'UTC' },
    });
    expect(result.current).toBe('2026-09-03');

    rerender({ zone: 'Asia/Tokyo' });

    expect(result.current).toBe('2026-09-04');
  });

  it('stops when the view unmounts', () => {
    const { unmount } = renderHook(() => useDayKey('UTC'));
    unmount();

    expect(vi.getTimerCount()).toBe(0);
  });
});
