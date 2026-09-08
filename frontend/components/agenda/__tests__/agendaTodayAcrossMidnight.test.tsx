/**
 * @vitest-environment jsdom
 *
 * That "today" is a fact the page keeps re-reading, not one it recorded when it opened.
 *
 * The month grid and the day-grouped list each highlight one cell as today. Both used to
 * take it from a `new Date()` evaluated during render, which is only ever re-evaluated when
 * something else makes the component render - and on an agenda nothing does. Left open
 * overnight, the page went on marking yesterday, confidently, with no way for the reader to
 * tell. That is the defect these two tests fail on.
 *
 * The other half of the fix - that a tick which stays inside the same day re-renders
 * nothing - is a property of `useDayKey` and is pinned in `hooks/__tests__/useNow.test.tsx`,
 * where the number of renders can actually be counted.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render } from '@testing-library/react';
import * as React from 'react';
import { DndContext } from '@dnd-kit/core';
import { MonthView } from '../MonthView';
import { AgendaListView } from '../AgendaListView';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

/** 23:58 UTC, two minutes before the day turns. */
const BEFORE_MIDNIGHT = new Date('2026-09-03T23:58:00Z');
/** The month grid marks today by filling its day number with the accent. */
const TODAY_PILL = 'bg-[var(--accent-primary)]';
/** The list marks today by colouring the day heading. */
const TODAY_HEADING = 'text-[var(--accent-primary)]';

function occurrence(id: string, startAt: string): AgendaOccurrence {
  return {
    id, kind: 'PLANNED', startAt,
    resourceType: 'WORKFLOW', resourceId: 'wf-1', name: `Run ${id}`,
    scheduleId: 'sched-1', cronExpression: '0 12 * * *', timezone: 'UTC',
    armed: true, isNextFire: false, overridden: false, moveAllSupported: true,
    status: 'PLANNED',
  } as AgendaOccurrence;
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(BEFORE_MIDNIGHT);
});

afterEach(() => {
  vi.useRealTimers();
});

/** The day numbers of the cells the month grid marks as today. */
function markedDays(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('*'))
    .filter((el) => el.className && String(el.className).includes(TODAY_PILL))
    .map((el) => (el.textContent ?? '').trim());
}

describe('the month grid follows the clock past midnight', () => {
  it('marks the day it currently is, and moves when the day turns', () => {
    const { container } = render(
      <DndContext>
        <MonthView
          anchor={BEFORE_MIDNIGHT}
          timezone="UTC"
          weekStartsOn={1}
          showWeekends
          compact={false}
          occurrencesByDay={new Map()}
          canMutate
          onSelect={() => {}}
        />
      </DndContext>,
    );

    expect(markedDays(container)).toEqual(['3']);

    // Two minutes later it is the 4th. Nothing else on the page has changed - no fetch, no
    // navigation, no click - which is precisely the situation the old code could not see.
    act(() => {
      vi.setSystemTime(new Date('2026-09-04T00:01:00Z'));
      vi.advanceTimersByTime(3 * 60_000);
    });

    expect(markedDays(container)).toEqual(['4']);
  });

});

describe('the day-grouped list follows the clock past midnight', () => {
  it('marks the heading of the day it currently is, and moves when the day turns', () => {
    const occurrences = [
      occurrence('a', '2026-09-03T12:00:00Z'),
      occurrence('b', '2026-09-04T12:00:00Z'),
    ];
    const { container } = render(
      <AgendaListView occurrences={occurrences} timezone="UTC" onSelect={() => {}} />,
    );

    const marked = () =>
      Array.from(container.querySelectorAll('h3'))
        .filter((el) => el.className.includes(TODAY_HEADING))
        .map((el) => el.textContent);

    expect(marked()).toHaveLength(1);
    const before = marked()[0];

    act(() => {
      vi.setSystemTime(new Date('2026-09-04T00:01:00Z'));
      vi.advanceTimersByTime(3 * 60_000);
    });

    expect(marked()).toHaveLength(1);
    expect(marked()[0]).not.toBe(before);
  });
});
