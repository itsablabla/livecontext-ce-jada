/**
 * @vitest-environment jsdom
 *
 * That the offer to schedule something is the cell's LEFTOVER SPACE.
 *
 * It started life on empty cells only, as an `inset-0` overlay. That was wrong in the way
 * that matters: a day holding a single 09:00 run has white space under it, the user points
 * at that white space exactly as they point at an empty day, and restricting the offer to
 * empty cells meant such a day could not be scheduled into from the calendar at all.
 *
 * Every cell offers now, and the mechanism is what keeps it honest: a flow child after the
 * chips, `flex-1 min-h-0`, so it takes only what the chips did not. It cannot sit on top of
 * a chip and swallow its click (the failure the empty-only rule was avoiding), and it
 * collapses to nothing in a cell the chips already fill, so the grid gains no height.
 *
 * A viewer gets none of it, for the same reason they cannot drag a chip.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';
import { DndContext } from '@dnd-kit/core';
import { TimeGridView } from '../TimeGridView';
import { MonthView } from '../MonthView';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

const THURSDAY = new Date('2026-09-03T00:00:00Z');
const NOW = new Date('2026-09-03T09:30:00Z');

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
});
afterEach(() => {
  vi.useRealTimers();
});

function occurrence(): AgendaOccurrence {
  return {
    id: 'occ-1', kind: 'PLANNED', startAt: '2026-09-03T09:15:00Z',
    resourceType: 'WORKFLOW', resourceId: 'wf-1', name: 'Run',
    scheduleId: 'sched-1', cronExpression: '0 * * * *', timezone: 'UTC',
    armed: true, isNextFire: false, overridden: false, moveAllSupported: true,
    status: 'PLANNED',
  } as AgendaOccurrence;
}

// Each offer names its own slot: an identical label on 168 buttons is a screen reader
// reading the same six words 168 times with no way to tell which hour it is on.
const offers = () => screen.queryAllByRole('button', { name: /create.slotAction/ });

function renderHours(onCreate?: (day: Date, hour: number) => void) {
  return render(
    <DndContext>
      <TimeGridView
        days={[THURSDAY]}
        timezone="UTC"
        startHour={9}
        endHour={11}
        compact={false}
        occurrencesByDay={new Map([['2026-09-03', [occurrence()]]])}
        canMutate
        onSelect={() => {}}
        onCreate={onCreate}
      />
    </DndContext>,
  );
}

describe('the hour grid offers the space it has left', () => {
  it('names each offer by its own day and hour, with no two alike', () => {
    // 168 controls called "Schedule something here" are 168 controls a keyboard user cannot
    // tell apart. Containing the slot is not enough - the property is that they DIFFER, so
    // this renders a grid with many empty cells and counts distinct names.
    render(
      <DndContext>
        <TimeGridView
          days={[THURSDAY, new Date('2026-09-04T00:00:00Z')]}
          timezone="UTC"
          startHour={9}
          endHour={13}
          compact={false}
          occurrencesByDay={new Map()}
          canMutate
          onSelect={() => {}}
          onCreate={() => {}}
        />
      </DndContext>,
    );

    const labels = offers().map((el) => el.getAttribute('aria-label'));
    expect(labels).toHaveLength(8);
    expect(new Set(labels).size).toBe(labels.length);
    expect(labels[0]).toContain('"time":"09:00"');
  });

  it('offers the occupied hour too, under the run it holds', () => {
    // 09:00 holds a run; 10:00 does not. Both offer, because both have space left - that
    // is the whole change: a day with one run used to be unschedulable from the calendar.
    renderHours(() => {});

    expect(offers()).toHaveLength(2);
  });

  it('takes only the leftover space, so it can never swallow a chip\'s click', () => {
    // The property the empty-only rule was protecting, kept by construction instead: the
    // offer is a SIBLING that comes after the chips and grows into what is left, not an
    // overlay stretched across the cell.
    renderHours(() => {});
    const offer = offers()[0];

    expect(offer.className).toContain('flex-1');
    expect(offer.className).toContain('min-h-0');
    expect(offer.className).not.toContain('absolute');

    // In the 09:00 cell the chip is rendered BEFORE the offer; DOM order is what decides
    // which one the pointer reaches on the pixels they share (none, but this is the rule).
    const cell = offer.parentElement!;
    const children = [...cell.children];
    const chip = children.find((el) => el.textContent?.includes('Run'));
    expect(chip).toBeDefined();
    expect(children.indexOf(chip!)).toBeLessThan(children.indexOf(offer));
  });

  it('hands back the day and the hour that was clicked', () => {
    const clicks: Array<{ day: string; hour: number }> = [];
    renderHours((day, hour) => clicks.push({ day: day.toISOString(), hour }));

    // The second offer is the empty 10:00 cell; the first sits under the 09:00 run.
    fireEvent.click(offers()[1]);

    expect(clicks).toEqual([{ day: THURSDAY.toISOString(), hour: 10 }]);
  });

  it('offers nothing at all to a viewer', () => {
    // The page passes no handler when the user may not change anything, so the affordance
    // is absent rather than present-and-refused.
    renderHours(undefined);

    expect(offers()).toHaveLength(0);
  });

  it('offers nothing on an hour the chips have already filled', () => {
    // "Leftover space" means what it says. Past the scroll threshold there is none, a
    // `flex-1` button with no room resolves to zero height and cannot be pointed at anyway,
    // and rendering it regardless would still spend a 2px gap after the last chip on every
    // busy cell in the grid.
    const many = Array.from({ length: 6 }, (_, i) => ({ ...occurrence(), id: `occ-${i}` }));
    render(
      <DndContext>
        <TimeGridView
          days={[THURSDAY]}
          timezone="UTC"
          startHour={9}
          endHour={10}
          compact={false}
          occurrencesByDay={new Map([['2026-09-03', many]])}
          canMutate
          onSelect={() => {}}
          onCreate={() => {}}
        />
      </DndContext>,
    );

    expect(offers()).toHaveLength(0);
  });
});

describe('the month grid offers its empty days', () => {
  function renderMonth(onCreate?: (day: Date) => void) {
    return render(
      <DndContext>
        <MonthView
          anchor={THURSDAY}
          timezone="UTC"
          weekStartsOn={1}
          showWeekends
          compact={false}
          occurrencesByDay={new Map([['2026-09-03', [occurrence()]]])}
          canMutate
          onSelect={() => {}}
          onCreate={onCreate}
        />
      </DndContext>,
    );
  }

  it('offers all 42 days of the six-week grid, occupied one included', () => {
    renderMonth(() => {});

    expect(offers()).toHaveLength(42);
  });

  it('leaves the date visible, because it is no longer an overlay', () => {
    // The overlay had to be pushed below the date row by hand (`top-7`), because
    // `--bg-hover` is opaque and sweeping the pointer across a week blanked every number on
    // the way, today's accent circle included. A flow child cannot reach the date at all.
    const { container } = renderMonth(() => {});

    expect(offers()[0].className).not.toContain('absolute');
    expect(container.querySelector('.top-7')).toBeNull();
  });

  it('names each day offer for its own date', () => {
    renderMonth(() => {});
    const labels = offers().map((el) => el.getAttribute('aria-label'));

    expect(new Set(labels).size).toBe(labels.length);
  });

  it('hands back the day, with no hour for the dialog to pretend was chosen', () => {
    const clicks: string[] = [];
    renderMonth((day) => clicks.push(day.toISOString().slice(0, 10)));

    fireEvent.click(offers()[0]);

    // The grid starts on the Monday before the 1st: 2026-08-31.
    expect(clicks).toEqual(['2026-08-31']);
  });

  it('offers nothing at all to a viewer', () => {
    renderMonth(undefined);

    expect(offers()).toHaveLength(0);
  });
});
