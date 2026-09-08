/**
 * Edge coherence regression: a FAILED source node's outgoing edges must render
 * as `failed` (red), not `skipped` (grey).
 *
 * Found in the 2026-06-23 prod test session: when a node errors (e.g. a failing
 * HTTP node, or a guardrail that hit a credit/error), the engine persists its
 * outgoing edges as `skipped` in StateSnapshot (that skipped count is
 * load-bearing for merge convergence and MUST stay skipped there). But a grey
 * "skipped" edge is visually identical to a branch a SUCCEEDING node simply
 * didn't take, so a failed node looked indistinguishable from a passing one and
 * a user asked "why is the edge [near the failure] coloured like a normal skip".
 *
 * Fix: in updateEdgesFromBatch, when the source node itself failed, recolour its
 * skipped outgoing edges to `failed`. Pure presentation - convergence untouched.
 */

import { describe, it, expect } from 'vitest';
import type { Node, Edge } from 'reactflow';
import {
  updateEdgesFromBatch,
  updateLoopInternalEdges,
  computeNodeBackendKey,
  coerceStatusForFailedSource,
  applyAwaitingSourceToEdges,
  type BatchEdgeData,
} from '../edgeStatusService';
import type { BuilderNodeData, NodeStatus } from '../../types';

function makeNode(id: string, label: string, kind: string, status?: NodeStatus, type = 'flowNode'): Node<BuilderNodeData> {
  return {
    id,
    type,
    position: { x: 0, y: 0 },
    data: { id, label, kind, status } as BuilderNodeData,
  };
}

function makeEdge(source: string, target: string): Edge {
  return { id: `${source}->${target}`, source, target };
}

describe('updateEdgesFromBatch - failed source node edge coherence', () => {
  const target = makeNode('save-1', 'Save Record', 'action');

  // The backend marks a failed node's outgoing edge as skipped:1. Derive the
  // from/to refs from the real node-key mapper so the match is faithful.
  function skippedBatch(source: Node<BuilderNodeData>): BatchEdgeData[] {
    const from = computeNodeBackendKey(source);
    const to = computeNodeBackendKey(target);
    // Guard: if the node shape ever stops producing a key, fail loudly rather
    // than silently no-matching (which would make the assertions meaningless).
    expect(from).toBeTruthy();
    expect(to).toBeTruthy();
    return [{ id: 'be1', from: from!, to: to!, running: 0, completed: 0, skipped: 1 }];
  }

  it('renders a FAILED source node\'s skipped outgoing edge as `failed` (red)', () => {
    const source = makeNode('fetch-1', 'Fetch Data', 'action', 'failed');
    const edges = [makeEdge('fetch-1', 'save-1')];
    const result = updateEdgesFromBatch(edges, skippedBatch(source), [source, target]);
    expect(result[0].data?.status).toBe('failed');
  });

  it('keeps a skipped edge `skipped` (grey) when the source node SUCCEEDED (normal unmet branch)', () => {
    const source = makeNode('fetch-1', 'Fetch Data', 'action', 'completed');
    const edges = [makeEdge('fetch-1', 'save-1')];
    const result = updateEdgesFromBatch(edges, skippedBatch(source), [source, target]);
    expect(result[0].data?.status).toBe('skipped');
  });

  it('does not touch a completed edge even when the source failed (only skipped edges recolour)', () => {
    const source = makeNode('fetch-1', 'Fetch Data', 'action', 'failed');
    const from = computeNodeBackendKey(source)!;
    const to = computeNodeBackendKey(target)!;
    const completedBatch: BatchEdgeData[] = [{ id: 'be1', from, to, running: 0, completed: 1, skipped: 0 }];
    const edges = [makeEdge('fetch-1', 'save-1')];
    const result = updateEdgesFromBatch(edges, completedBatch, [source, target]);
    expect(result[0].data?.status).toBe('completed');
  });
});

/**
 * The rule above only ever ran in ONE of the three passes that write an edge
 * status. The While/loop-internal pass wrote its own statuses straight from the
 * batch counts, so the body edge leaving a failed loop node stayed grey while
 * every other edge of the same failure was red.
 */
describe('updateLoopInternalEdges - failed source node edge coherence', () => {
  const whileNode = makeNode('while-1', 'Retry Loop', 'while', 'failed', 'whileGroupNode');
  const body = makeNode('call-1', 'Call API', 'action');

  function bodyEdge(): Edge {
    return { id: 'e1', source: 'while-1', target: 'call-1', sourceHandle: 'while-while-1-body' };
  }

  function batch(counts: { completed?: number; skipped?: number }): BatchEdgeData[] {
    const from = computeNodeBackendKey(whileNode);
    const to = computeNodeBackendKey(body);
    expect(from).toBeTruthy();
    expect(to).toBeTruthy();
    return [{ id: 'be1', from: `${from}:body`, to: to!, running: 0, completed: counts.completed ?? 0, skipped: counts.skipped ?? 0 }];
  }

  it('renders a FAILED While node\'s skipped body edge as `failed` (red)', () => {
    const result = updateLoopInternalEdges([bodyEdge()], batch({ skipped: 1 }), [whileNode, body]);
    expect(result[0].data?.status).toBe('failed');
  });

  it('keeps the body edge `skipped` when the While node itself SUCCEEDED', () => {
    const passing = makeNode('while-1', 'Retry Loop', 'while', 'completed', 'whileGroupNode');
    const result = updateLoopInternalEdges([bodyEdge()], batch({ skipped: 1 }), [passing, body]);
    expect(result[0].data?.status).toBe('skipped');
  });
});

/**
 * The shared rule itself. It is exported because a THIRD writer - the per-epoch
 * viewing pass in useEpochStateViewing - has to apply it too: without it, reopening
 * a finished run on one of its epoch tabs turned every red edge back to grey.
 */
describe('coerceStatusForFailedSource', () => {
  const failed = makeNode('n', 'N', 'action', 'failed');
  const completed = makeNode('n', 'N', 'action', 'completed');

  it('recolours skipped -> failed only when the source node failed', () => {
    expect(coerceStatusForFailedSource('skipped', failed)).toBe('failed');
    expect(coerceStatusForFailedSource('skipped', completed)).toBe('skipped');
    expect(coerceStatusForFailedSource('skipped', undefined)).toBe('skipped');
  });

  it('leaves every non-skipped status untouched, failed source or not', () => {
    for (const status of ['completed', 'running', 'pending', 'awaiting_signal', 'partial_success'] as const) {
      expect(coerceStatusForFailedSource(status, failed)).toBe(status);
    }
  });
});

/**
 * The waiting counterpart. An edge can NEVER arrive waiting from the backend:
 * `EdgeLifecycle` is RUNNING | COMPLETED | SKIPPED and every one of them is written
 * only after the source node finishes, while waiting is tracked per NODE
 * (`EpochState.awaitingSignalNodeIds` / the AWAITING_SIGNAL step event). Without this
 * derivation the amber the edge renderer knows about would be unreachable - shipped,
 * green, and dead.
 */
describe('applyAwaitingSourceToEdges', () => {
  const waiting = makeNode('wait-1', 'Hold For Approval', 'approval', 'awaiting_signal');
  const done = makeNode('wait-1', 'Hold For Approval', 'approval', 'completed');
  const upstream = makeNode('fetch-1', 'Fetch', 'action', 'completed');
  const next = makeNode('send-1', 'Send', 'action');

  const outgoing = (): Edge => ({ id: 'out', source: 'wait-1', target: 'send-1' });
  const incoming = (status?: string): Edge =>
    ({ id: 'in', source: 'fetch-1', target: 'wait-1', data: status ? { status } : undefined });

  it('paints the un-traversed outgoing edge of a waiting node', () => {
    const result = applyAwaitingSourceToEdges([outgoing()], [waiting, next]);
    expect(result[0].data?.status).toBe('awaiting_signal');
  });

  it('leaves the INCOMING edge alone - data really did flow through it', () => {
    const result = applyAwaitingSourceToEdges([incoming('completed')], [upstream, waiting]);
    expect(result[0].data?.status).toBe('completed');
  });

  it('never overwrites an edge that already carries a real status', () => {
    const already: Edge = { id: 'out', source: 'wait-1', target: 'send-1', data: { status: 'completed' } };
    const result = applyAwaitingSourceToEdges([already], [waiting, next]);
    expect(result[0].data?.status).toBe('completed');
  });

  it('releases its own colour once the node stops waiting, so no edge stays parked', () => {
    const amber: Edge = { id: 'out', source: 'wait-1', target: 'send-1', data: { status: 'awaiting_signal' } };
    const result = applyAwaitingSourceToEdges([amber], [done, next]);
    expect(result[0].data?.status).toBeUndefined();
  });

  it('returns the SAME array when nothing waits and nothing is amber (no canvas re-render)', () => {
    const edges = [outgoing()];
    expect(applyAwaitingSourceToEdges(edges, [done, next])).toBe(edges);
  });

  it('is idempotent - a second pass changes nothing', () => {
    const once = applyAwaitingSourceToEdges([outgoing()], [waiting, next]);
    expect(applyAwaitingSourceToEdges(once, [waiting, next])).toBe(once);
  });

  /**
   * The live path queues the node update and the edge update as two separate React
   * state writes, so the node array this pass reads can still be the pre-wait one.
   * Reading the run snapshot's own step-id list directly is what makes the colour
   * land on the first frame instead of waiting for an unrelated later event.
   */
  it('paints from the run snapshot even when the node array has not caught up', () => {
    const stale = makeNode('wait-1', 'Hold For Approval', 'approval', 'running');
    const result = applyAwaitingSourceToEdges([outgoing()], [stale, next], ['core:hold_for_approval']);
    expect(result[0].data?.status).toBe('awaiting_signal');
  });

  it('ignores a snapshot id that matches no node on the canvas', () => {
    const edges = [outgoing()];
    const stale = makeNode('wait-1', 'Hold For Approval', 'approval', 'running');
    expect(applyAwaitingSourceToEdges(edges, [stale, next], ['core:something_else'])).toBe(edges);
  });
});
