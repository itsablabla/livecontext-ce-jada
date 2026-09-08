'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { TABLE_REVEAL_WINDOW_MS } from '../tableStyles';

/**
 * Hold a "this just appeared" flag for {@link TABLE_REVEAL_WINDOW_MS}, then drop it.
 *
 * The grid points at what an action just produced (the column an add created, the rows a duplicate
 * copied) by rendering a CSS class while the flag names it. The flag has to come OFF on its own:
 * a row that mounts much later (an infinite-scroll page, a row the user adds) would otherwise
 * animate long after the thing stopped being new.
 *
 * @param empty the value that means "point at nothing" - captured once, so a caller may pass a
 *              fresh collection literal without restarting anything.
 * @param scope  what the flag is about (a table, a nested path). The flag names a row id or a
 *               column field, and neither is unique across tables: the grid is NOT remounted when
 *               the user navigates, so a cue left STANDING would land on whatever happens to share
 *               that id or field next. It does not cover an action still in flight across the
 *               change - that continuation re-arms the cue, exactly as the refetch it rides on
 *               re-populates the grid.
 * @returns the current flag, and a setter that starts (or restarts) the window.
 */
export function useRevealWindow<T>(empty: T, scope?: unknown): [T, (next: T) => void] {
  const emptyRef = useRef(empty);
  const [revealed, setRevealed] = useState<T>(emptyRef.current);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Dropping the timer is the load-bearing half (an unmounted tree's setState is a no-op, a stray
  // timer is not). Changing scope runs the same cleanup for the same reason it runs on unmount:
  // the cue is about something that is no longer on screen.
  useEffect(() => () => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = null;
    setRevealed(emptyRef.current);
  }, [scope]);

  const reveal = useCallback((next: T) => {
    // Clear first: a second action inside the window must not be cut short by the first one's
    // timer, which would end the cue early on the thing the user is actually looking for.
    if (timerRef.current) clearTimeout(timerRef.current);
    setRevealed(next);
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      setRevealed(emptyRef.current);
    }, TABLE_REVEAL_WINDOW_MS);
  }, []);

  return [revealed, reveal];
}
