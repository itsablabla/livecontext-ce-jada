// @vitest-environment jsdom
/**
 * The pill on an edge and the badge on its source node must tell the same story.
 *
 * `awaiting_signal` was missing from BOTH branches of the label: the counts branch
 * never read AWAITING_SIGNAL (so an edge carrying only awaiting items computed every
 * chip as zero and rendered an EMPTY white pill floating on the line), and the
 * icon fallback had no case for it (same empty pill when the status arrived without
 * counts). `NodeStatusBadge` has shown an amber pause icon for it all along.
 */

import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { EdgeStatusLabel } from '../EdgeStatusLabel';
import type { DerivedNodeStatus, StatusCounts } from '../../types';

const messages = {
  nodeStatus: {
    pending: 'Pending',
    ready: 'Ready',
    running: 'Running',
    completed: 'Completed',
    failed: 'Failed',
    skipped: 'Skipped',
    partial_success: 'Partial Success',
    awaiting_signal: 'Awaiting Action',
  },
};

function renderLabel(status: DerivedNodeStatus, statusCounts?: StatusCounts) {
  return render(
    <NextIntlClientProvider locale="en" messages={messages}>
      <EdgeStatusLabel status={status} statusCounts={statusCounts} />
    </NextIntlClientProvider>,
  );
}

describe('EdgeStatusLabel - awaiting_signal', () => {
  it('shows the awaiting chip with its count instead of an empty pill', () => {
    const { container, getByTestId } = renderLabel('awaiting_signal', { AWAITING_SIGNAL: 3 });
    expect(getByTestId('edge-status-awaiting')).toBeTruthy();
    expect(container.textContent).toContain('3');
  });

  it('shows the awaiting icon when the status arrives with no counts at all', () => {
    const { getByTestId } = renderLabel('awaiting_signal');
    expect(getByTestId('edge-status-awaiting')).toBeTruthy();
  });

  it('names the awaiting items in the tooltip alongside the other tallies', () => {
    const { container } = renderLabel('awaiting_signal', { COMPLETED: 2, AWAITING_SIGNAL: 1 });
    const title = (container.firstElementChild as HTMLElement).getAttribute('title') || '';
    expect(title).toContain('Awaiting Action');
    expect(title).toContain('1 awaiting action');
    expect(title).toContain('2 completed');
  });

  it('leaves the other statuses rendering exactly as before', () => {
    const { queryByTestId, container } = renderLabel('completed', { COMPLETED: 4 });
    expect(queryByTestId('edge-status-awaiting')).toBeNull();
    expect(container.textContent).toContain('4');
  });
});

/**
 * The line and the pill must not tell two different stories. A failed node's outgoing
 * edges are persisted skipped:1 and the LINE is recoloured red
 * (coerceStatusForFailedSource), so the pill beside that red line used to show a lone
 * grey "skipped 1" - the only visible marker contradicting the colour of the edge it
 * sits on.
 */
describe('EdgeStatusLabel - failed edge whose counts only say "skipped"', () => {
  it('names the failure beside the truthful skipped count', () => {
    const { getByTestId, container } = renderLabel('failed', { SKIPPED: 1 });
    expect(getByTestId('edge-status-failed-headline')).toBeTruthy();
    // The count itself is NOT rewritten: it stays the skipped tally the engine wrote.
    expect(container.textContent).toContain('1');
    const title = (container.firstElementChild as HTMLElement).getAttribute('title') || '';
    expect(title).toContain('Failed');
    expect(title).toContain('1 skipped');
  });

  it('does not double up when the counts already carry the failure', () => {
    const { queryByTestId } = renderLabel('failed', { FAILED: 2 });
    expect(queryByTestId('edge-status-failed-headline')).toBeNull();
  });

  it('adds no marker to a plain skipped edge (a branch simply not taken)', () => {
    const { queryByTestId } = renderLabel('skipped', { SKIPPED: 1 });
    expect(queryByTestId('edge-status-failed-headline')).toBeNull();
  });
});
