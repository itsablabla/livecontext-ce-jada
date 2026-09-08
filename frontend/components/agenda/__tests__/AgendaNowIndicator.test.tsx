/**
 * @vitest-environment jsdom
 *
 * The present moment, drawn on the hour grid.
 *
 * A calendar that cannot say where "now" is makes the reader do the arithmetic: the grid
 * shows 09:00 and 10:00, and whether the 09:40 run has already gone is left to them. The
 * line answers it at a glance, which is only true if it lands in the right COLUMN and at
 * the right MINUTE - a line drawn on every day, or pinned to the top of its hour, would
 * be worse than none, because it is read as fact.
 *
 * jsdom lays nothing out, so these assertions are about which cell carries the marker and
 * what offset it declares, not about pixels. Both are exactly where this can go wrong.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render } from '@testing-library/react';
import * as React from 'react';
import { DndContext } from '@dnd-kit/core';
import { TimeGridView } from '../TimeGridView';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

const MONDAY = new Date('2026-08-31T00:00:00Z');
const THURSDAY = new Date('2026-09-03T00:00:00Z');
const NOW = new Date('2026-09-03T09:30:00Z');

function grid(props: { days: Date[]; startHour?: number; endHour?: number; recenterSignal?: number }) {
  return (
    <DndContext>
      <TimeGridView
        days={props.days}
        timezone="UTC"
        recenterSignal={props.recenterSignal ?? 0}
        startHour={props.startHour ?? 9}
        endHour={props.endHour ?? 11}
        compact={false}
        occurrencesByDay={new Map()}
        canMutate
        onSelect={() => {}}
      />
    </DndContext>
  );
}

function renderGrid(props: { days: Date[]; startHour?: number; endHour?: number; recenterSignal?: number }) {
  return render(grid(props));
}

// The grid reads the clock itself - a tick has to re-render THIS component and not the
// whole page - so the clock is controlled here rather than injected as a prop. Freezing
// the system time also keeps these renders identical from one run to the next.
beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
});

const lines = (container: HTMLElement) =>
  Array.from(container.querySelectorAll('[data-agenda-now-line]'));

describe('TimeGridView now-line', () => {
  it('draws the line at the minute inside its hour, not at the top of the row', () => {
    // 09:30 is half way through the 09:00 row. A marker that ignored the minutes would
    // still be "in the right hour" and still tell the user the wrong thing.
    const { container } = renderGrid({ days: [THURSDAY] });

    expect(lines(container)).toHaveLength(1);
    expect((lines(container)[0] as HTMLElement).style.top).toBe('50%');
  });

  it('draws it in exactly one column of a full week', () => {
    // The offset is per-hour, so every cell in the 09:00 row is a candidate. Only today's
    // is the present moment; a line across the whole row would date every day at once.
    const days = Array.from({ length: 7 }, (_, i) => new Date(MONDAY.getTime() + i * 86_400_000));
    const { container } = renderGrid({ days });

    expect(lines(container)).toHaveLength(1);
  });

  it('draws nothing when the visible period does not contain today', () => {
    const { container } = renderGrid({ days: [MONDAY] });

    expect(lines(container)).toHaveLength(0);
  });

  it('draws nothing when the current hour is cropped out of the grid', () => {
    // The hour range is a preference. Clamping the line to the edge of a crop that does
    // not contain it would claim it is 12:00 all morning.
    const { container } = renderGrid({ days: [THURSDAY], startHour: 12, endHour: 18 });

    expect(lines(container)).toHaveLength(0);
  });

  it('prints the time in the gutter, off the day column', () => {
    // The label rides the hour column, not the line: on the line it would cover whatever
    // runs at that minute, which is the thing the reader came for.
    const { container } = renderGrid({ days: [THURSDAY] });
    const gutterCells = Array.from(container.querySelectorAll('div')).filter((el) =>
      el.className.includes('border-r') && el.textContent?.startsWith('09:00'),
    );

    expect(gutterCells.length).toBeGreaterThan(0);
    expect(gutterCells[0].textContent).toContain('09:30');
  });

  it('keeps that label out of the hour cell it sits in, for a screen reader', () => {
    // It is positioned inside the 09:00 cell, so left audible the cell reads "09:0009:30" -
    // two times glued into a number that is neither. The bar's clock is the accessible
    // statement of what time it is.
    const { container } = renderGrid({ days: [THURSDAY] });
    const pill = Array.from(container.querySelectorAll('span')).find(
      (el) => el.textContent === '09:30',
    );

    expect(pill).toBeDefined();
    expect(pill!.getAttribute('aria-hidden')).toBe('true');
  });

  it('marks today in the column headers, and follows the clock past midnight', () => {
    // The mark itself is not new; that it MOVES is. Read from a `new Date()` evaluated
    // during render it never moved, because nothing re-renders a calendar left open, and
    // the column marked "today" was still yesterday's in the morning.
    const days = Array.from({ length: 7 }, (_, i) => new Date(MONDAY.getTime() + i * 86_400_000));
    const { container } = renderGrid({ days });
    const marked = () =>
      Array.from(container.querySelectorAll('div'))
        .filter((el) => el.className.includes('font-medium')
          && el.className.includes('text-[var(--accent-primary)]'))
        .map((el) => el.textContent ?? '');

    expect(marked()).toHaveLength(1);
    expect(marked()[0]).toContain('3');

    act(() => {
      vi.setSystemTime(new Date('2026-09-04T00:01:00Z'));
      vi.advanceTimersByTime(3 * 60_000);
    });

    expect(marked()).toHaveLength(1);
    expect(marked()[0]).toContain('4');
  });

  it('walks the line down its hour as the minutes pass', () => {
    // The one thing the feature is FOR, and the only proof that the grid is reading a
    // clock rather than the instant it mounted. 09:30 is half way through the 09:00 row;
    // fifteen minutes later it is three quarters of the way down the same row.
    const { container } = renderGrid({ days: [THURSDAY] });

    expect((lines(container)[0] as HTMLElement).style.top).toBe('50%');

    act(() => { vi.advanceTimersByTime(15 * 60_000); });

    expect((lines(container)[0] as HTMLElement).style.top).toBe('75%');
  });

  it('stops drawing the line when the clock leaves the visible hours', () => {
    // The crop is a preference, and time keeps moving: a line left over from an hour that
    // has scrolled out of the range would sit at the bottom edge claiming to be now.
    const { container } = renderGrid({ days: [THURSDAY], startHour: 9, endHour: 10 });

    expect(lines(container)).toHaveLength(1);

    act(() => { vi.advanceTimersByTime(31 * 60_000); });

    expect(lines(container)).toHaveLength(0);
  });

  it('lets a chip still be dropped on the current hour', () => {
    // The line lies over a drop target. Taking pointer events would make the one cell a
    // user most often reaches for - the next hour - refuse every drag.
    const { container } = renderGrid({ days: [THURSDAY] });

    expect((lines(container)[0] as HTMLElement).className).toContain('pointer-events-none');
  });
});

/**
 * When the grid may move the user's scroll, and when it must not.
 *
 * <p>jsdom lays nothing out, so geometry is stubbed on the prototype BEFORE the first
 * render: the scroll container reports a 600px viewport and the now-line reports itself
 * 900px down. Every write to a scroll position is recorded, which is what makes "it moved
 * the page" and "it left the page alone" two different, observable facts rather than a
 * value that happens to be zero either way.
 */
describe('TimeGridView opens on the present moment', () => {
  const scrollWrites: number[] = [];
  let originalScrollTop: PropertyDescriptor | undefined;
  let originalClientHeight: PropertyDescriptor | undefined;
  let originalRect: typeof Element.prototype.getBoundingClientRect;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    scrollWrites.length = 0;
    originalScrollTop = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollTop');
    originalClientHeight = Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight');
    originalRect = Element.prototype.getBoundingClientRect;

    Object.defineProperty(Element.prototype, 'clientHeight', { configurable: true, get: () => 600 });
    Object.defineProperty(Element.prototype, 'scrollTop', {
      configurable: true,
      get(this: Element & { __top?: number }) { return this.__top ?? 0; },
      set(this: Element & { __top?: number }, value: number) {
        this.__top = value;
        if (this.className.includes('overflow-y-auto')) scrollWrites.push(value);
      },
    });
    // The line is 900px into the grid's CONTENT, so what it reports relative to the
    // viewport moves as the container scrolls - exactly as in a browser, and the reason
    // this stub is not a constant: a fixed rect would make the centring compound on every
    // run, and a test written against that would enshrine a bug rather than catch one.
    Element.prototype.getBoundingClientRect = function (this: Element) {
      if (!this.hasAttribute('data-agenda-now-line')) return { top: 0 } as DOMRect;
      const scroller = this.closest('.overflow-y-auto') as (Element & { __top?: number }) | null;
      return { top: 900 - (scroller?.__top ?? 0) } as DOMRect;
    };
  });

  afterEach(() => {
    if (originalScrollTop) Object.defineProperty(Element.prototype, 'scrollTop', originalScrollTop);
    if (originalClientHeight) Object.defineProperty(Element.prototype, 'clientHeight', originalClientHeight);
    Element.prototype.getBoundingClientRect = originalRect;
    vi.useRealTimers();
  });

  it('centres the now-line instead of opening at midnight', () => {
    // A day is 24 rows tall and the grid opens at the top, so on the default hour range the
    // line - the whole point of drawing it - is below the fold for anyone whose runs are not
    // at 01:00. 900 (the line) minus half of 600 (the viewport) is where it lands.
    renderGrid({ days: [THURSDAY], startHour: 0, endHour: 24 });

    expect(scrollWrites).toEqual([600]);
  });

  it('does not move the scroll when the clock ticks, midnight included', () => {
    // The user scrolls somewhere and reads. An earlier version keyed this on today's date,
    // which looks equivalent and is not: that key changes at 00:00, so someone reading
    // tomorrow afternoon at 23:59 had the grid yanked back on the stroke of twelve.
    renderGrid({ days: [THURSDAY], startHour: 0, endHour: 24 });
    scrollWrites.length = 0;

    act(() => { vi.advanceTimersByTime(20 * 60_000); });
    act(() => { vi.setSystemTime(new Date('2026-09-04T00:00:00Z')); vi.advanceTimersByTime(60_000); });

    expect(scrollWrites).toEqual([]);
  });

  it('takes the user back to now when they ask for it', () => {
    // Pressing Today while already on today leaves the visible period identical, so nothing
    // about the period can express the request. `recenterSignal` is the page saying it.
    const { rerender } = renderGrid({ days: [THURSDAY], startHour: 0, endHour: 24 });
    scrollWrites.length = 0;

    rerender(grid({ days: [THURSDAY], startHour: 0, endHour: 24, recenterSignal: 1 }));

    expect(scrollWrites).toEqual([600]);
  });

  it('leaves the scroll alone on a period with no present moment in it', () => {
    // Paging to a week that does not contain today: there is nothing to centre on, and
    // jumping to the top would be a change the user did not ask for.
    renderGrid({ days: [MONDAY], startHour: 0, endHour: 24 });

    expect(scrollWrites).toEqual([]);
  });
});
