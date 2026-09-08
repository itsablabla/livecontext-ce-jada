'use client';

import { useEffect, useState } from 'react';
import { dayKey } from '@/lib/utils/agendaTime';

/**
 * The current instant, re-read on a tick, so a view that draws "now" does not freeze on
 * the moment it happened to mount.
 *
 * <p>Every calendar surface in the app reads `new Date()` during render, which is right
 * for a page that is looked at and left, and wrong for one that stays open: the agenda is
 * a dashboard, and after an hour its now-line and its clock still describe the moment the
 * tab was opened. A page left open across midnight highlights yesterday.
 *
 * <p>The tick is ALIGNED to the interval rather than started from mount. That buys two
 * things: a clock showing `hh:mm` changes ON the minute instead of up to 59 seconds late,
 * and two surfaces that both tick at 60s change on the SAME boundary, so a header and a
 * grid can each own their clock without ever disagreeing about the minute.
 *
 * <p><b>Call this in the component that needs the minute, not in a shared parent.</b> Every
 * tick is a re-render of the subtree below the caller, and the agenda's subtrees are
 * expensive: a month is up to 42 droppable cells and a WEEK is 168, each a drop target with
 * its own chips. Hoisting this into the page would re-render all of them once a minute to
 * move a line that only exists in the week and day grids. For a surface that only needs to
 * know WHICH DAY it is, use `useDayKey` below, which re-renders once a day.
 *
 * <p><b>Pass a non-positive interval to freeze it</b> when the caller currently displays
 * nothing that depends on the minute - the hour grid does this while the present moment is
 * off screen, which is every period but one. A frozen clock costs nothing, and starting it
 * again is answered on the same render rather than at the next boundary.
 */
export function useNow(intervalMs = 60_000): Date {
  // `live` travels with the instant for the same reason the zone travels with the day key
  // below: a caller may STOP and START the clock (the hour grid freezes it while the
  // present moment is off screen), and a stored instant alone gives no way to tell a value
  // that is current from one that was frozen minutes ago. Without this, un-freezing drew
  // the now-line at a stale minute until the next tick.
  const [entry, setEntry] = useState(() => ({ live: intervalMs > 0, at: new Date() }));

  useEffect(() => {
    // A non-positive interval is a caller asking for a frozen clock - a test, or a surface
    // that currently displays nothing derived from the minute. Scheduling on it would spin.
    if (intervalMs <= 0) return;

    let timer: ReturnType<typeof setTimeout>;
    const schedule = () => {
      // In (0, intervalMs]: the delay to the next boundary, never 0, so no tight loop.
      const delay = intervalMs - (Date.now() % intervalMs);
      timer = setTimeout(() => {
        setEntry({ live: true, at: new Date() });
        schedule();
      }, delay);
    };
    schedule();

    return () => clearTimeout(timer);
  }, [intervalMs]);

  // A clock that has just been started is answered on the spot rather than at the next
  // boundary, by recomputing here rather than by writing state from the effect.
  return entry.live === (intervalMs > 0) ? entry.at : new Date();
}

/**
 * Today's `YYYY-MM-DD` in a zone, re-read on the same aligned tick but CHANGING only when
 * the day does.
 *
 * <p>This is the cheap half of `useNow`, and the difference is not a micro-optimisation.
 * The month grid and the day-grouped list need exactly one fact from the clock - which
 * cell is today - and it changes once every 24 hours. Handing them a `Date` would hand
 * them a new object every minute, and because a new object is never equal to the last one,
 * React would re-render the whole grid (42 `useDroppable` cells, every chip inside them,
 * each re-registering with the drag context) 1,440 times a day to flip one boolean once.
 *
 * <p>Storing the KEY instead is what stops that: the state is a string, so a tick that
 * lands on the same day sets an equal value and React bails out of the render entirely.
 * The comparison the callers then do (`dayKey(cell) === todayKey`) is also a plain string
 * compare, where `isSameDay` built two `Intl.DateTimeFormat` reads per cell.
 */
export function useDayKey(timeZone: string, intervalMs = 60_000): string {
  // The zone travels WITH the key. Storing the key alone leaves no way to tell a stored
  // value that is merely old from one that was computed for a different zone, and the
  // agenda's zone picker can move the calendar across a date line: reading the stale one
  // would mark yesterday as today while every occurrence around it had already moved.
  const [entry, setEntry] = useState(() => ({ zone: timeZone, key: dayKey(new Date(), timeZone) }));

  useEffect(() => {
    if (intervalMs <= 0) return;

    let timer: ReturnType<typeof setTimeout>;
    const schedule = () => {
      const delay = intervalMs - (Date.now() % intervalMs);
      timer = setTimeout(() => {
        // Same value on the same day: React compares the strings, sees no change, and does
        // not re-render. That bail-out is the whole mechanism, so the object is only
        // replaced when the day (or the zone) actually differs.
        setEntry((current) => {
          const key = dayKey(new Date(), timeZone);
          return current.zone === timeZone && current.key === key ? current : { zone: timeZone, key };
        });
        schedule();
      }, delay);
    };
    schedule();

    return () => clearTimeout(timer);
  }, [timeZone, intervalMs]);

  // A zone change is answered on the spot rather than at the next tick - and by RECOMPUTING
  // here rather than by writing state from the effect, which would cost the cascading render
  // the bail-out above exists to avoid.
  return entry.zone === timeZone ? entry.key : dayKey(new Date(), timeZone);
}
