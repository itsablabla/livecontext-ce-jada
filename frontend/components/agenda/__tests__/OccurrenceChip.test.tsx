/**
 * @vitest-environment jsdom
 *
 * Whether a chip teaches the drag gesture.
 *
 * A gesture the platform will refuse must not be offered - the module's own rule, and the
 * one the menu already follows. The chip enforced only half of it: `isMovable` answers
 * whether the PLATFORM can express the move, and a read-only member fails the OTHER half.
 * So the one surface still teaching the drag was the one with no menu to explain why it
 * would bounce. The server boundary holds either way; this is about not lying with a cursor.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as React from 'react';
import { DndContext } from '@dnd-kit/core';
import { OccurrenceChip } from '../OccurrenceChip';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
  useOptionalTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
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
    armed: true,
    isNextFire: true,
    overridden: false,
    moveAllSupported: true,
    status: 'PLANNED',
    ...overrides,
  };
}

/**
 * dnd-kit sets `role="button"` and a grab cursor on a draggable node, so the rendered
 * cursor class is the honest read of "is the gesture being taught".
 */
function renderChip(o: AgendaOccurrence, canMutate: boolean) {
  const { container } = render(
    <DndContext>
      <OccurrenceChip
        occurrence={o}
        timezone="UTC"
        compact={false}
        canMutate={canMutate}
        onSelect={() => {}}
      />
    </DndContext>,
  );
  return container;
}

const teachesDrag = (container: HTMLElement) =>
  Boolean(container.querySelector('.cursor-grab'));

/** The chip element itself; `renderChip` hands back the container. */
function chipOf(container: HTMLElement): HTMLElement {
  return container.querySelector('button') as HTMLElement;
}

describe('OccurrenceChip drag affordance', () => {
  it('teaches the drag on a movable occurrence a member may act on', () => {
    expect(teachesDrag(renderChip(occurrence(), true))).toBe(true);
  });

  it('does NOT teach it to a read-only member', () => {
    // The half that was missing. The menu already withheld its actions here, so the drag
    // was the only surface still promising something the server would refuse.
    expect(teachesDrag(renderChip(occurrence(), false))).toBe(false);
  });

  it('does NOT teach it when no scope can move the occurrence', () => {
    // Chips 2..n of an interval schedule: the platform cannot express the move at all.
    expect(teachesDrag(renderChip(
      occurrence({ isNextFire: false, moveAllSupported: false }), true))).toBe(false);
  });

  it('does NOT teach it on a PAST fire', () => {
    expect(teachesDrag(renderChip(occurrence({ kind: 'PAST' }), true))).toBe(false);
  });

  it('still renders the occurrence, and still opens, when the drag is withheld', () => {
    // Withholding the gesture must not take the chip away: a VIEWER still reads the
    // calendar and still clicks through to the resource.
    const container = renderChip(occurrence(), false);

    expect(screen.getByText('Daily report')).toBeTruthy();
    expect(container.querySelector('button')).toBeTruthy();
  });

  it('carries `touch-manipulation`, which is what makes the hold reachable by finger', () => {
    // The shared sensors give touch a quarter-second HOLD rather than a distance, because a
    // finger cannot out-race the page's own scroll. That hold is only unambiguous if the
    // browser is not still waiting to see whether this is a double-tap, which is what the
    // class settles. Every other draggable in the repo pins it (FileCard, FolderCard,
    // DraggableResourceCard, ResourceFolderTile); without it a chip on a phone is either
    // undraggable or fights the gesture.
    expect(chipOf(renderChip(occurrence(), true)).className).toContain('touch-manipulation');
  });

  it('carries it even where the drag is withheld, since the class costs nothing there', () => {
    // Not conditional on `draggable`: it is on the element unconditionally, and a test that
    // asserted only the enabled case would pass on a version that dropped it for everyone.
    expect(chipOf(renderChip(occurrence(), false)).className).toContain('touch-manipulation');
  });
});

describe('OccurrenceChip - a fire the spending cap will refuse', () => {
  // The grid is the agenda's primary surface, so the greying has to reach it
  // too. Before this, a workflow over its cap drew chips identical to any
  // other and every one of them was silently refused: the month view promised
  // runs that could not occur.

  const chip = (c: HTMLElement) => c.querySelector('button') as HTMLElement;

  it('fades a blocked fire without hiding it, because it comes back on its own', () => {
    const container = renderChip(occurrence({ armed: false }), true);
    expect(chip(container)).not.toBeNull();
    expect(chip(container).className).toContain('opacity-50');
  });

  it('says why in the label, so the fade is not just an unexplained grey chip', () => {
    const container = renderChip(occurrence({ armed: false }), true);
    expect(chip(container).getAttribute('aria-label')).toContain('status.budgetBlocked');
    expect(chip(container).getAttribute('title')).toContain('status.budgetBlocked');
  });

  it('leaves an ordinary chip at full opacity and says nothing extra', () => {
    const container = renderChip(occurrence(), true);
    expect(chip(container).className).toContain('opacity-100');
    expect(chip(container).getAttribute('aria-label')).not.toContain('status.budgetBlocked');
  });

  it('never fades a PAST fire: its armed flag says nothing about a future it already had', () => {
    const container = renderChip(occurrence({ kind: 'PAST', armed: false, status: 'COMPLETED' }), true);
    expect(chip(container).className).not.toContain('opacity-50');
  });
});
