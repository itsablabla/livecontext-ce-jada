/**
 * @vitest-environment jsdom
 *
 * What the user sees while an occurrence is being dragged.
 *
 * Before this component there was nothing: dnd-kit moves no DOM of its own, the chip stays
 * dimmed in its own cell, and the only feedback was a ring on the cell underneath - one
 * square among forty-two on a month grid. The gesture read as "nothing is happening".
 *
 * The claim the preview makes is a TIME, so these tests are about that claim being both
 * present and honest: the old value stays visible next to the proposed one, no proposal is
 * invented while the pointer is over no cell, and the day is spelled out only when the drop
 * would change it.
 */
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as React from 'react';
import { OccurrenceDragPreview } from '../OccurrenceDragPreview';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

const TZ = 'UTC';

function occurrence(overrides: Partial<AgendaOccurrence> = {}): AgendaOccurrence {
  return {
    id: 'wf-1:sched-1@1',
    kind: 'PLANNED',
    startAt: '2026-09-03T09:00:00Z',
    resourceType: 'WORKFLOW',
    resourceId: 'wf-1',
    name: 'Daily report',
    scheduleId: 'sched-1',
    armed: true,
    isNextFire: true,
    overridden: false,
    moveAllSupported: true,
    status: 'PLANNED',
    ...overrides,
  };
}

/** The proposed slot as the card prints it: the emphasised half of "old -> new". */
function proposedText(container: HTMLElement): string {
  const node = container.querySelector('span.font-semibold');
  if (!node) throw new Error('no proposed slot rendered');
  return (node.textContent ?? '').trim();
}

describe('OccurrenceDragPreview', () => {
  it('names the occurrence being dragged, so the card is not an anonymous blob', () => {
    render(
      <OccurrenceDragPreview occurrence={occurrence()} proposedStart={null} timezone={TZ} />,
    );
    expect(screen.getByText('Daily report')).toBeTruthy();
  });

  it('shows the current time alone until the pointer is over a cell', () => {
    // dnd-kit reports nothing under the pointer until the first move. Echoing the chip's own
    // time as if it were the proposal would state a move that has been aimed nowhere.
    const { container } = render(
      <OccurrenceDragPreview occurrence={occurrence()} proposedStart={null} timezone={TZ} />,
    );
    expect(screen.getByText('09:00')).toBeTruthy();
    expect(container.querySelector('.line-through')).toBeNull();
  });

  it('shows old -> new once a cell is under the pointer, keeping BOTH', () => {
    // Keeping the old value is the point: the reader is dragging in order to compare, and a
    // card showing only the new time answers a question they can already see on the grid.
    render(
      <OccurrenceDragPreview
        occurrence={occurrence()}
        proposedStart={new Date('2026-09-03T14:00:00Z')}
        timezone={TZ}
      />,
    );
    const previous = screen.getByText('09:00');
    expect(previous.className, 'the outgoing time is not struck through').toContain('line-through');
    expect(screen.getByText('14:00')).toBeTruthy();
  });

  it('leaves the day out when only the hour changes', () => {
    // In a week or day grid the date is usually the same. Printing it twice buries the hours,
    // which is the only thing the reader is comparing.
    const { container } = render(
      <OccurrenceDragPreview
        occurrence={occurrence()}
        proposedStart={new Date('2026-09-03T14:00:00Z')}
        timezone={TZ}
      />,
    );
    expect(proposedText(container), 'a same-day move printed the date anyway').toBe('14:00');
  });

  it('spells the day out when the drop moves the occurrence to another one', () => {
    // The month grid drops on a DAY and keeps the hour, so without the date the card would
    // read "09:00 -> 09:00" and describe the one move it exists to describe as a no-op.
    const { container } = render(
      <OccurrenceDragPreview
        occurrence={occurrence()}
        proposedStart={new Date('2026-09-10T09:00:00Z')}
        timezone={TZ}
      />,
    );
    const proposed = proposedText(container);
    expect(proposed, 'the day is missing, so the move reads as no move at all').toContain('10');
    expect(proposed).toContain('09:00');
  });

  it('reads the times in the DISPLAY timezone, not the browser one', () => {
    // The whole calendar is pinned to a chosen zone. A preview computed in local time would
    // disagree with the cell the user dropped on, by the offset between the two.
    render(
      <OccurrenceDragPreview
        occurrence={occurrence()}
        proposedStart={new Date('2026-09-03T14:00:00Z')}
        timezone="America/New_York"
      />,
    );
    expect(screen.getByText('05:00')).toBeTruthy();
    expect(screen.getByText('10:00')).toBeTruthy();
  });

  it('never takes the pointer, so it cannot shadow the cell it sits on', () => {
    // The card is rendered under the cursor by construction. Without this it would be the
    // element dnd-kit hit-tests, and no drop would ever find a cell.
    const { container } = render(
      <OccurrenceDragPreview occurrence={occurrence()} proposedStart={null} timezone={TZ} />,
    );
    expect((container.firstElementChild as HTMLElement).className).toContain('pointer-events-none');
  });

  it('is hidden from assistive tech - dnd-kit already announces the drag', () => {
    const { container } = render(
      <OccurrenceDragPreview occurrence={occurrence()} proposedStart={null} timezone={TZ} />,
    );
    expect(container.firstElementChild?.getAttribute('aria-hidden')).toBe('true');
  });

  it('wears the outcome colour of a past fire, like the chip it is a picture of', () => {
    // occurrenceAccent is shared with the chip on purpose: a preview in a different colour
    // from the thing being dragged reads as a different object.
    const { container } = render(
      <OccurrenceDragPreview
        occurrence={occurrence({ kind: 'PAST', status: 'FAILED' })}
        proposedStart={null}
        timezone={TZ}
      />,
    );
    expect(container.innerHTML).toContain('bg-red-50');
  });
});
