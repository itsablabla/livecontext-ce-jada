// @vitest-environment jsdom
/**
 * A node that is WAITING must animate, and must not look like a running one.
 *
 * Before this component the scan overlay was copy-pasted into ~17 node files and
 * every copy tested `status === 'running'`. Only the approval node had ever been
 * taught about `awaiting_signal`, so a wait node, an interface node parked on a
 * `__continue`, or any node holding on a signal went visually STILL the moment it
 * started waiting - indistinguishable on the canvas from a node that had never run,
 * on a run that was in fact alive and blocked on the user.
 */

import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { NodeActivityShimmer, isLiveNodeStatus } from '../NodeActivityShimmer';
import type { DerivedNodeStatus } from '../../../types';

function overlayOf(status?: DerivedNodeStatus, props: Record<string, unknown> = {}) {
  const { container } = render(<NodeActivityShimmer status={status} {...props} />);
  return container.querySelector('[data-shimmer-status]') as HTMLElement | null;
}

describe('NodeActivityShimmer', () => {
  it('animates a RUNNING node in blue at the working tempo', () => {
    const el = overlayOf('running');
    expect(el).not.toBeNull();
    expect(el!.style.background).toContain('rgba(59, 130, 246, 0.15)');
    expect(el!.style.animation).toContain('shimmer-scan 2.5s');
  });

  it('animates an AWAITING_SIGNAL node in amber, slower than a running one', () => {
    const el = overlayOf('awaiting_signal');
    expect(el).not.toBeNull();
    // Amber-500, the colour this status already carries on the node border and badge.
    expect(el!.style.background).toContain('rgba(245, 158, 11, 0.15)');
    // Slower on purpose: colour alone is a weak cue across a canvas of many nodes,
    // and a node parked on a signal must not read as busy as one doing work.
    expect(el!.style.animation).toContain('shimmer-scan 3.5s');
  });

  it.each<DerivedNodeStatus | undefined>([
    undefined,
    'pending',
    'ready',
    'completed',
    'failed',
    'skipped',
    'partial_success',
  ])('renders nothing for the non-live status %s', (status) => {
    expect(overlayOf(status)).toBeNull();
  });

  it('takes the host node\'s shape classes so the overlay cannot bleed past a rounded corner', () => {
    const el = overlayOf('running', { className: 'rounded-2xl z-[5]' });
    expect(el!.className).toContain('rounded-2xl');
    expect(el!.className).toContain('z-[5]');
    expect(el!.className).toContain('pointer-events-none');
  });

  it('defaults to the canvas node radius when no shape class is given', () => {
    expect(overlayOf('running')!.className).toContain('rounded-[26px]');
  });

  it('forwards a test id (the agent-fleet canvas asserts on a per-agent one)', () => {
    const el = overlayOf('running', { testId: 'fleet-agent-shimmer-42' });
    expect(el!.getAttribute('data-testid')).toBe('fleet-agent-shimmer-42');
  });

  describe('isLiveNodeStatus', () => {
    it('is true only for the two statuses that animate', () => {
      expect(isLiveNodeStatus('running')).toBe(true);
      expect(isLiveNodeStatus('awaiting_signal')).toBe(true);
      expect(isLiveNodeStatus('completed')).toBe(false);
      expect(isLiveNodeStatus('failed')).toBe(false);
      expect(isLiveNodeStatus(undefined)).toBe(false);
    });
  });
});
