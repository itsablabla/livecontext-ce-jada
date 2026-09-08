/**
 * @vitest-environment jsdom
 *
 * The dialog that asks "this run, or the schedule?".
 *
 * It is the only place a destructive choice is made in this feature, and both of its
 * options can be unavailable for reasons the user cannot see: "this occurrence only"
 * addresses the schedule's single pending fire, so it exists on the next fire and nowhere
 * else, and "all occurrences" needs a cron simple enough to rewrite. Getting the enabling
 * wrong is silent - the dialog looks the same, and either bounces on confirm or, worse,
 * sends a scope that does something wider than the user meant.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';
import { MoveOccurrenceDialog } from '../MoveOccurrenceDialog';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

function occurrence(overrides: Partial<AgendaOccurrence> = {}): AgendaOccurrence {
  return {
    id: 'wf-1:sched-1@1',
    kind: 'PLANNED',
    startAt: '2026-09-03T09:00:00Z',
    resourceType: 'WORKFLOW',
    resourceId: 'wf-1',
    name: 'Daily report',
    scheduleId: 'sched-1',
    cronExpression: '0 9 * * *',
    timezone: 'UTC',
    armed: true,
    isNextFire: true,
    overridden: false,
    moveAllSupported: true,
    status: 'PLANNED',
    ...overrides,
  };
}

function renderDialog(
  o: AgendaOccurrence,
  opts: { proposedStart?: Date; submitting?: boolean; error?: string | null } = {},
) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  render(
    <MoveOccurrenceDialog
      occurrence={o}
      proposedStart={opts.proposedStart ?? new Date('2026-09-10T14:00:00Z')}
      timezone="UTC"
      submitting={opts.submitting ?? false}
      error={opts.error ?? null}
      onCancel={onCancel}
      onConfirm={onConfirm}
    />,
  );
  return { onConfirm, onCancel };
}

const confirmButton = () =>
  screen.getByRole('button', { name: /move\.confirm|common\.working/ }) as HTMLButtonElement;
const scopeButton = (label: string) => screen.getByText(label).closest('button') as HTMLButtonElement;

describe('MoveOccurrenceDialog', () => {
  describe('scope availability', () => {
    it('enables both scopes on the next fire of a shiftable schedule', () => {
      renderDialog(occurrence());

      expect(scopeButton('move.scopeNext').disabled).toBe(false);
      expect(scopeButton('move.scopeAll').disabled).toBe(false);
      expect(confirmButton().disabled).toBe(false);
    });

    it('disables "this occurrence" on any chip but the pending fire', () => {
      // A schedule points at ONE pending fire, so writing a later occurrence's time into
      // it would not move that occurrence: it would cancel every run in between.
      renderDialog(occurrence({ isNextFire: false }));

      expect(scopeButton('move.scopeNext').disabled).toBe(true);
      expect(screen.getByText('move.scopeNextUnavailable')).toBeTruthy();
    });

    it('disables "all occurrences" when the cron cannot be rewritten', () => {
      renderDialog(occurrence({ moveAllSupported: false }));

      expect(scopeButton('move.scopeAll').disabled).toBe(true);
    });

    it('names the cron in the reason, so the refusal is about something the user can see', () => {
      renderDialog(occurrence({ moveAllSupported: false, cronExpression: '*/15 * * * *' }));

      expect(screen.getByText(/\*\/15 \* \* \* \*/)).toBeTruthy();
    });
  });

  describe('the default scope', () => {
    it('opens on "this occurrence" for the pending fire', () => {
      // The narrower choice, and the one the gesture implies, whenever it is available.
      const { onConfirm } = renderDialog(occurrence());

      fireEvent.click(confirmButton());

      expect(onConfirm).toHaveBeenCalledWith(expect.any(Date), 'NEXT');
    });

    it('opens on "all occurrences" only when that is the one scope available', () => {
      const { onConfirm } = renderDialog(occurrence({ isNextFire: false }));

      fireEvent.click(confirmButton());

      expect(onConfirm).toHaveBeenCalledWith(expect.any(Date), 'ALL');
    });

    it('never opens pre-set to a scope the confirm button will bounce', () => {
      // The pairing that matters: whichever scope is selected on open must be an enabled
      // one, or the dialog presents a working-looking Confirm that refuses.
      for (const o of [occurrence(), occurrence({ isNextFire: false }),
                       occurrence({ moveAllSupported: false })]) {
        const { unmount } = render(
          <MoveOccurrenceDialog
            occurrence={o}
            proposedStart={new Date('2026-09-10T14:00:00Z')}
            timezone="UTC"
            submitting={false}
            error={null}
            onCancel={() => {}}
            onConfirm={() => {}}
          />,
        );
        expect(confirmButton().disabled).toBe(false);
        unmount();
      }
    });
  });

  it('confirms the time the user typed, in the DISPLAY zone', () => {
    // The drop only carries a day or an hour slot; the time field is how it gets minutes.
    // Reading it as browser-local would move the run by the zone offset.
    const { onConfirm } = renderDialog(occurrence());

    fireEvent.change(screen.getByDisplayValue('14:00'), { target: { value: '08:45' } });
    fireEvent.click(confirmButton());

    const [startAt] = onConfirm.mock.calls[0];
    expect((startAt as Date).toISOString()).toBe('2026-09-10T08:45:00.000Z');
  });

  it('warns, rather than refusing, when the new time is in the past', () => {
    // The daemon treats an overdue fire as due, so this genuinely means "at the next
    // tick". Blocking it would forbid something that works; saying nothing would surprise.
    renderDialog(occurrence(), { proposedStart: new Date(Date.now() - 3_600_000) });

    expect(screen.getByText('move.pastWarning')).toBeTruthy();
    expect(confirmButton().disabled).toBe(false);
  });

  it('shows the server refusal and keeps the dialog open to act on it', () => {
    // Closing on refusal would throw away the context the user needs to choose again.
    renderDialog(occurrence(), { error: 'That is not the next occurrence' });

    expect(screen.getByText('That is not the next occurrence')).toBeTruthy();
    expect(screen.getByText('move.scopeNext')).toBeTruthy();
  });

  it('locks Confirm while the move is in flight', () => {
    renderDialog(occurrence(), { submitting: true });

    expect(confirmButton().disabled).toBe(true);
  });

  it('renders nothing without an occurrence or a proposed start', () => {
    const { container } = render(
      <MoveOccurrenceDialog
        occurrence={null}
        proposedStart={null}
        timezone="UTC"
        submitting={false}
        error={null}
        onCancel={() => {}}
        onConfirm={() => {}}
      />,
    );
    expect(container.textContent).toBe('');
  });
});
