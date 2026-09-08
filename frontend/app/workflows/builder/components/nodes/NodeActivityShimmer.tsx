'use client';

import * as React from 'react';
import type { DerivedNodeStatus } from '../../types';

/**
 * The node statuses that ANIMATE, with the tint and tempo each one carries.
 *
 * <p>Two live states exist on the canvas and they must not read the same. `running`
 * is the node working (blue, the colour of its border and of its status badge).
 * `awaiting_signal` is the node PAUSED on something outside the engine: a wait
 * timer, a user approval, an interface `__continue`. It is amber everywhere else
 * already (border in {@link getStatusBorderColor}, badge in `NodeStatusBadge`,
 * edge stroke in `edgeStatusVisuals`), so the shimmer follows the same colour.
 *
 * <p>The tempo differs on purpose: colour alone is a weak cue in peripheral vision
 * on a canvas of 30 nodes, and a waiting node should not look as busy as a working
 * one. Slower for amber mirrors the convention already set by `.shimmer-text` (1.5s)
 * vs `.shimmer-text-amber` (2.5s) in globals.css.
 *
 * <p>Every other status is terminal: it is told by the border and the badge, and a
 * finished node must be visually still.
 */
const ACTIVITY_SHIMMER: Partial<Record<DerivedNodeStatus, { tint: string; durationSeconds: number }>> = {
  running: { tint: 'rgba(59, 130, 246, 0.15)', durationSeconds: 2.5 }, // blue-500
  awaiting_signal: { tint: 'rgba(245, 158, 11, 0.15)', durationSeconds: 3.5 }, // amber-500
};

export interface NodeActivityShimmerProps {
  /** The node's effective status. Anything that is not live renders nothing. */
  status?: DerivedNodeStatus;
  /**
   * Shape classes matching the HOST node's own box, typically its border radius
   * plus a stacking class (e.g. `rounded-[26px] z-[5]`). The overlay is absolutely
   * positioned over the whole node, so a mismatched radius shows as a square
   * corner bleeding past a rounded one.
   */
  className?: string;
  /** Optional test hook (the agent-fleet canvas asserts on a per-agent id). */
  testId?: string;
}

/**
 * Left-to-right scan overlay that tells a node is LIVE.
 *
 * <p>Single source of truth: this block used to be copy-pasted into ~17 node
 * components, which is why only the approval node ever learned about
 * `awaiting_signal` - every other node went visually still the moment it started
 * waiting, indistinguishable from one that had never run.
 */
export function NodeActivityShimmer({ status, className = 'rounded-[26px]', testId }: NodeActivityShimmerProps) {
  const shimmer = status ? ACTIVITY_SHIMMER[status] : undefined;
  if (!shimmer) return null;

  return (
    <div
      data-testid={testId}
      data-shimmer-status={status}
      className={`absolute inset-0 pointer-events-none ${className}`}
      style={{
        background: `linear-gradient(90deg, transparent 0%, ${shimmer.tint} 50%, transparent 100%)`,
        backgroundSize: '200% 100%',
        animation: `shimmer-scan ${shimmer.durationSeconds}s ease-in-out infinite`,
      }}
    />
  );
}

/**
 * Is this status one the node animates for? Callers that need the flag for
 * something else (the bottom bar's button shimmer, a preview that hides the
 * overlay) read it here instead of re-testing `=== 'running'`.
 */
export function isLiveNodeStatus(status?: DerivedNodeStatus): boolean {
  return !!status && status in ACTIVITY_SHIMMER;
}
