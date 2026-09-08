import { describe, expect, it } from 'vitest';
import { resolveDropStart } from '../agendaDrag';
import { zonedParts } from '@/lib/utils/agendaTime';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

/**
 * Where a dragged occurrence lands.
 *
 * The same gesture means two things: the month grid drops on a DAY, the week and day grids
 * drop on a DAY AND HOUR. Confusing them is silently wrong rather than broken - the dialog
 * opens either way, pre-filled with a time that is off by hours, and a user who trusts the
 * pre-fill confirms it. That is why this is worth pinning rather than eyeballing.
 */

function occurrence(startAt: string): AgendaOccurrence {
  return {
    id: 'wf-1:sched-1@1',
    kind: 'PLANNED',
    startAt,
    resourceType: 'WORKFLOW',
    resourceId: 'wf-1',
    name: 'Daily report',
    scheduleId: 'sched-1',
    armed: true,
    isNextFire: true,
    overridden: false,
    moveAllSupported: true,
    status: 'PLANNED',
  };
}

describe('resolveDropStart', () => {
  it('keeps the time of day when the drop carries only a date (month grid)', () => {
    // The user moved it to another DAY and said nothing about the time, so 09:37 stays
    // 09:37. Defaulting to midnight here would quietly reschedule every month-grid drag.
    const start = resolveDropStart(
      occurrence('2026-09-03T09:37:00Z'),
      { day: '2026-09-10T00:00:00Z' },
      'UTC',
    );

    const parts = zonedParts(start as Date, 'UTC');
    expect(parts.day).toBe(10);
    expect(parts.hour).toBe(9);
    expect(parts.minute).toBe(37);
  });

  it('takes the slot hour, on the hour, when the drop carries one (time grids)', () => {
    // Here the user DID aim at a time. Carrying the old minutes over would land at 14:37
    // in a grid whose rows are whole hours.
    const start = resolveDropStart(
      occurrence('2026-09-03T09:37:00Z'),
      { day: '2026-09-04T00:00:00Z', hour: 14 },
      'UTC',
    );

    const parts = zonedParts(start as Date, 'UTC');
    expect(parts.day).toBe(4);
    expect(parts.hour).toBe(14);
    expect(parts.minute).toBe(0);
  });

  it('treats hour 0 as a real choice, not as "no hour given"', () => {
    // `?? ` on a numeric field is the classic trap: midnight is falsy-adjacent enough that
    // a `||` here would silently fall back to the old time for the whole first row.
    const start = resolveDropStart(
      occurrence('2026-09-03T09:37:00Z'),
      { day: '2026-09-04T00:00:00Z', hour: 0 },
      'UTC',
    );

    const parts = zonedParts(start as Date, 'UTC');
    expect(parts.hour).toBe(0);
    expect(parts.minute).toBe(0);
  });

  it('resolves in the DISPLAY zone, not the browser one', () => {
    // A user reading an agenda pinned to New York drops on the cell they can see. Building
    // the instant from local parts would move the run by the offset between the two zones.
    const start = resolveDropStart(
      occurrence('2026-09-03T13:00:00Z'),
      { day: '2026-09-04T12:00:00Z', hour: 9 },
      'America/New_York',
    );

    // 09:00 in New York on 4 Sept (EDT, UTC-4) is 13:00 UTC.
    expect((start as Date).toISOString()).toBe('2026-09-04T13:00:00.000Z');
  });

  it('returns null when the drop carried no day, so nothing opens', () => {
    // A cancelled drag, or a drop outside any cell. Opening the dialog on a guessed date
    // would ask the user to confirm a move they never started.
    expect(resolveDropStart(occurrence('2026-09-03T09:00:00Z'), undefined, 'UTC')).toBeNull();
    expect(resolveDropStart(occurrence('2026-09-03T09:00:00Z'), {}, 'UTC')).toBeNull();
    expect(resolveDropStart(occurrence('2026-09-03T09:00:00Z'), { hour: 9 }, 'UTC')).toBeNull();
  });

  it('returns null when there is no occurrence', () => {
    expect(resolveDropStart(undefined, { day: '2026-09-04T00:00:00Z' }, 'UTC')).toBeNull();
  });
});
