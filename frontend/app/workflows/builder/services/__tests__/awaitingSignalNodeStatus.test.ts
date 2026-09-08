/**
 * A node parked on a signal has to SAY so, live.
 *
 * Verified against a real CE run (CE-WAIT-CUE-001): a `core:wait` of 120s yields a
 * WAIT_TIMER signal, and the run snapshot then reports `awaitingSignalStepIds:
 * ["core:hold"]`. Nothing on the live canvas read that list, and the step stream cannot
 * replace it: yielding never rewrites the node's RUNNING step row, so the node kept
 * reporting `running`. The result on screen was a node contradicting itself - a blue
 * "running" border and overlay, next to its own amber pause chip counted from the
 * AWAITING_SIGNAL statusCounts.
 */

import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import { applyAwaitingSignalToNodes } from '../statusUpdater';
import type { BuilderNodeData, DerivedNodeStatus } from '../../types';

function node(id: string, label: string, status?: DerivedNodeStatus): Node<BuilderNodeData> {
  return { id, type: 'flowNode', position: { x: 0, y: 0 }, data: { id, label, kind: 'wait', status } as BuilderNodeData };
}

describe('applyAwaitingSignalToNodes', () => {
  it('marks the node named by the run snapshot, overriding a stale running', () => {
    const result = applyAwaitingSignalToNodes([node('n1', 'Hold', 'running')], ['core:hold']);
    expect(result[0].data.status).toBe('awaiting_signal');
  });

  it('leaves nodes the snapshot does not name untouched', () => {
    const nodes = [node('n1', 'Hold', 'running'), node('n2', 'After', 'pending')];
    const result = applyAwaitingSignalToNodes(nodes, ['core:hold']);
    expect(result[1].data.status).toBe('pending');
  });

  it.each<DerivedNodeStatus>(['completed', 'failed', 'skipped', 'partial_success'])(
    'never drags a %s node back to waiting (a non-blocking signal outlives its node)',
    (terminal) => {
      const result = applyAwaitingSignalToNodes([node('n1', 'Hold', terminal)], ['core:hold']);
      expect(result[0].data.status).toBe(terminal);
    },
  );

  it('returns the SAME array when the snapshot names nothing (no canvas re-render)', () => {
    const nodes = [node('n1', 'Hold', 'running')];
    expect(applyAwaitingSignalToNodes(nodes, [])).toBe(nodes);
    expect(applyAwaitingSignalToNodes(nodes, undefined)).toBe(nodes);
  });

  it('is idempotent - a second pass changes nothing', () => {
    const once = applyAwaitingSignalToNodes([node('n1', 'Hold', 'running')], ['core:hold']);
    expect(applyAwaitingSignalToNodes(once, ['core:hold'])).toBe(once);
  });
});
