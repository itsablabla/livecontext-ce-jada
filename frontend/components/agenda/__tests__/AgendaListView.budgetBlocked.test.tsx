// @vitest-environment jsdom
/**
 * A scheduled fire the backend says will not happen.
 *
 * The agenda's whole job is to predict what will run. A workflow over its
 * spending cap keeps an armed schedule with a live cron, so before this the
 * calendar drew its fires exactly like any other and every one of them was
 * silently refused: the page promised runs that could not occur.
 *
 * The fix is deliberately NOT to hide them. A spending block lifts on its own
 * at the next period, so a calendar that dropped the rows would read as "this
 * automation is gone" for something that is merely resting. They are drawn,
 * faded, and labelled.
 */
import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

import { AgendaListView } from '../AgendaListView';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

function occurrence(overrides: Partial<AgendaOccurrence>): AgendaOccurrence {
  return {
    id: 'occ-1',
    kind: 'PLANNED',
    startAt: '2026-09-10T09:00:00Z',
    resourceType: 'WORKFLOW',
    resourceId: 'wf-1',
    name: 'Nightly Digest',
    scheduleId: 'sch-1',
    cronExpression: '0 9 * * *',
    timezone: 'UTC',
    armed: true,
    isNextFire: false,
    overridden: false,
    moveAllSupported: true,
    status: 'PLANNED',
    ...overrides,
  } as AgendaOccurrence;
}

const rows = (c: HTMLElement) => Array.from(c.querySelectorAll('button'));
// Keyed on what a reader actually sees, not on an attribute shipped for tests.
const blocked = (c: HTMLElement) =>
  rows(c).filter((r) => r.className.includes('opacity-50'));

afterEach(() => cleanup());

describe('AgendaListView - a fire the spending cap will refuse', () => {
  it('still draws the occurrence: it comes back on its own, so hiding it would mislead', () => {
    const { container } = render(
      <AgendaListView occurrences={[occurrence({ armed: false })]} timezone="UTC" onSelect={() => {}} />,
    );
    expect(rows(container)).toHaveLength(1);
  });

  it('fades it and says why, instead of showing the cron as if it would run', () => {
    const { container } = render(
      <AgendaListView occurrences={[occurrence({ armed: false })]} timezone="UTC" onSelect={() => {}} />,
    );
    expect(blocked(container)).toHaveLength(1);
    expect(rows(container)[0].className).toContain('opacity-50');
    expect(container.textContent).toContain('status.budgetBlocked');
    expect(container.textContent).not.toContain('0 9 * * *');
  });

  it('leaves an ordinary occurrence completely untouched', () => {
    const { container } = render(
      <AgendaListView occurrences={[occurrence({})]} timezone="UTC" onSelect={() => {}} />,
    );
    expect(blocked(container)).toHaveLength(0);
    expect(rows(container)[0].className).not.toContain('opacity-50');
    expect(container.textContent).toContain('0 9 * * *');
  });

  it('fades only the fires inside the block, not the ones after the allowance resets', () => {
    // The half that makes this worth doing: greying the whole future would say
    // the automation is finished, when it restarts on a known date.
    const { container } = render(
      <AgendaListView
        occurrences={[
          occurrence({ id: 'a', armed: false, startAt: '2026-09-10T09:00:00Z' }),
          occurrence({ id: 'b', armed: true, startAt: '2026-10-02T09:00:00Z' }),
        ]}
        timezone="UTC"
        onSelect={() => {}}
      />,
    );
    expect(rows(container)).toHaveLength(2);
    expect(blocked(container)).toHaveLength(1);
  });

  it('never fades a PAST fire, whichever way its armed flag happens to read', () => {
    // The server sets armed=true on every past fire (it already happened, so
    // there is nothing to predict), but the guard keys on kind rather than
    // trusting that: dimming history would be a different claim entirely, and
    // this row is reached by a different code path in the same component.
    const { container } = render(
      <AgendaListView
        occurrences={[occurrence({ kind: 'PAST', armed: false, status: 'COMPLETED' })]}
        timezone="UTC"
        onSelect={() => {}}
      />,
    );
    expect(blocked(container)).toHaveLength(0);
    expect(container.textContent).toContain('status.completed');
  });
});
