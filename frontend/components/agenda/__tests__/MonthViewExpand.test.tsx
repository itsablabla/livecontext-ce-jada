/**
 * @vitest-environment jsdom
 *
 * That a day the month grid could not fit can actually be READ once it is expanded.
 *
 * A month cell keeps its row's height, so "+5 more" was swapping a truncated list for a
 * clipped one: the five chips were rendered, below the fold, inside `overflow-hidden`, with
 * no way to reach them and nothing on screen saying so. The expanded cell has to scroll.
 *
 * <p>And only the expanded one. The hour grid learned this the expensive way: make all the
 * cells scroll containers and the wheel belongs to whichever cell the pointer is over, so
 * the surface behind them - here the month - stops scrolling. jsdom lays nothing out, so
 * these assert which cells DECLARE themselves scrollable, which is exactly where that bug
 * lived.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';
import { DndContext } from '@dnd-kit/core';
import { MonthView } from '../MonthView';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

const ANCHOR = new Date('2026-09-03T12:00:00Z');

function occurrence(index: number): AgendaOccurrence {
  return {
    id: `occ-${index}`,
    kind: 'PLANNED',
    startAt: `2026-09-03T0${index % 8}:15:00Z`,
    resourceType: 'WORKFLOW',
    resourceId: 'wf-1',
    name: `Run ${index}`,
    scheduleId: 'sched-1',
    cronExpression: '0 * * * *',
    timezone: 'UTC',
    armed: true,
    isNextFire: false,
    overridden: false,
    moveAllSupported: true,
    status: 'PLANNED',
  } as AgendaOccurrence;
}

function renderMonth(count: number) {
  const occurrences = Array.from({ length: count }, (_, i) => occurrence(i));
  return render(
    <DndContext>
      <MonthView
        anchor={ANCHOR}
        timezone="UTC"
        weekStartsOn={1}
        showWeekends
        compact={false}
        occurrencesByDay={new Map([['2026-09-03', occurrences]])}
        canMutate
        onSelect={() => {}}
      />
    </DndContext>,
  );
}

/** The chip stacks: one per day cell, whether or not they scroll. */
const stacks = (container: HTMLElement) =>
  Array.from(container.querySelectorAll('div')).filter((el) =>
    el.className.includes('flex min-h-0 flex-1 flex-col'),
  );

const scrollable = (container: HTMLElement) =>
  stacks(container).filter((el) => el.className.includes('overflow-y-auto'));

describe('a month day that holds more than it can show', () => {
  it('scrolls once expanded, instead of clipping what it just revealed', () => {
    const { container } = renderMonth(9);
    expect(scrollable(container)).toHaveLength(0);

    fireEvent.click(screen.getByText(/moreCount/));

    expect(scrollable(container)).toHaveLength(1);
    // All nine are now rendered, which is only useful because the cell scrolls.
    expect(screen.getAllByText(/^Run /)).toHaveLength(9);
  });

  it('keeps the other 41 cells out of the wheel\'s way', () => {
    // The regression this pins is not "the cell does not scroll" but "everything else does":
    // 42 scroll containers means the month itself can no longer be scrolled with the wheel.
    const { container } = renderMonth(9);
    fireEvent.click(screen.getByText(/moreCount/));

    // 42 day cells plus the grid's own container, which shares the class shape: the exact
    // count is what makes this an assertion at all - "more than one stack exists" is true
    // whatever the code does.
    expect(stacks(container)).toHaveLength(43);
    expect(scrollable(container)).toHaveLength(1);
    expect(scrollable(container)[0].className).toContain('overscroll-y-auto');
  });

  it('offers the way back, so a day cannot stay expanded by accident', () => {
    // Expanding removes the "+N more" button (nothing is hidden any more), so without an
    // explicit control the cell stays a scrollbox for the rest of the visit.
    const { container } = renderMonth(9);
    fireEvent.click(screen.getByText(/moreCount/));

    fireEvent.click(screen.getByText('showLess'));

    expect(scrollable(container)).toHaveLength(0);
    expect(screen.getByText(/moreCount/)).toBeTruthy();
  });

  it('does not offer it on a day that fits', () => {
    const { container } = renderMonth(2);

    expect(screen.queryByText(/moreCount/)).toBeNull();
    expect(screen.queryByText('showLess')).toBeNull();
    expect(scrollable(container)).toHaveLength(0);
  });
});
