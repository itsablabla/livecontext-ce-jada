/**
 * Edge colour + arrowhead coherence.
 *
 * Two defects this pins:
 *
 * 1. `awaiting_signal` had no edge colour. `deriveStatusFromCounts` returns it for
 *    an edge whose counts carry AWAITING_SIGNAL, and the per-epoch viewing pass
 *    writes exactly that status onto edges - so an edge out of a node parked on a
 *    signal was painted the neutral grey of an edge nothing had ever flowed through.
 *
 * 2. The arrowhead VANISHED for any status the `<defs>` list did not carry.
 *    `BuilderEdge` built `url(#arrow-${status})` while the marker definitions lived
 *    in `BuilderCanvas` (plus a second copy in the fleet canvas); an SVG marker url
 *    that resolves to nothing renders NOTHING rather than falling back, so the edge
 *    silently lost its arrow. `awaiting_signal` and `ready` both hit this.
 */

import { describe, it, expect } from 'vitest';
import {
  ARROW_MARKERS,
  EDGE_DEFAULT_COLOR,
  EDGE_LOOP_COLOR,
  EDGE_STATUS_COLORS,
  getEdgeArrowMarkerUrl,
  getEdgeStrokeColor,
} from '../edgeStatusVisuals';
import type { DerivedNodeStatus } from '../../types';

describe('getEdgeStrokeColor', () => {
  it('paints a waiting edge amber, the colour the waiting node already carries', () => {
    expect(getEdgeStrokeColor('awaiting_signal')).toBe('#f59e0b');
  });

  it('keeps every previously supported status on its own colour', () => {
    expect(getEdgeStrokeColor('running')).toBe('#3b82f6');
    expect(getEdgeStrokeColor('completed')).toBe('#10b981');
    expect(getEdgeStrokeColor('failed')).toBe('#ef4444');
    expect(getEdgeStrokeColor('skipped')).toBe('#94a3b8');
    expect(getEdgeStrokeColor('partial_success')).toBe('#f59e0b');
  });

  it.each<DerivedNodeStatus | undefined>([undefined, 'pending', 'ready'])(
    'stays neutral for %s (nothing has flowed yet)',
    (status) => {
      expect(getEdgeStrokeColor(status)).toBe(EDGE_DEFAULT_COLOR);
    },
  );

  it('does not reuse the loop orange for any status', () => {
    expect(Object.values(EDGE_STATUS_COLORS)).not.toContain(EDGE_LOOP_COLOR);
  });
});

describe('getEdgeArrowMarkerUrl', () => {
  const definedIds = new Set(ARROW_MARKERS.map((m) => m.id));

  it('never returns a url without a matching <marker> definition', () => {
    const everyStatus: (DerivedNodeStatus | undefined)[] = [
      undefined,
      'pending',
      'ready',
      'running',
      'completed',
      'failed',
      'skipped',
      'partial_success',
      'awaiting_signal',
    ];
    for (const status of everyStatus) {
      const id = /url\(#(.+)\)/.exec(getEdgeArrowMarkerUrl(status))?.[1];
      expect(definedIds.has(id!), `no <marker id="${id}"> for status ${status}`).toBe(true);
    }
  });

  it('gives a waiting edge its own amber arrowhead', () => {
    expect(getEdgeArrowMarkerUrl('awaiting_signal')).toBe('url(#arrow-awaiting_signal)');
    expect(ARROW_MARKERS.find((m) => m.id === 'arrow-awaiting_signal')?.color).toBe('#f59e0b');
  });

  it('falls back to the default arrow for a status with no marker of its own', () => {
    expect(getEdgeArrowMarkerUrl('ready')).toBe('url(#arrow-default)');
    expect(getEdgeArrowMarkerUrl('pending')).toBe('url(#arrow-default)');
    expect(getEdgeArrowMarkerUrl(undefined)).toBe('url(#arrow-default)');
  });
});

describe('ARROW_MARKERS', () => {
  it('carries an arrowhead for every status colour, so a colour cannot ship without one', () => {
    for (const status of Object.keys(EDGE_STATUS_COLORS)) {
      const marker = ARROW_MARKERS.find((m) => m.id === `arrow-${status}`);
      expect(marker, `missing arrow-${status}`).toBeDefined();
      expect(marker!.color).toBe(EDGE_STATUS_COLORS[status as DerivedNodeStatus]);
    }
  });

  it('still defines the selection and loop arrowheads', () => {
    expect(ARROW_MARKERS.map((m) => m.id)).toEqual(
      expect.arrayContaining(['arrow-default', 'arrow-selected', 'arrow-while-body']),
    );
  });

  it('declares each marker id exactly once', () => {
    const ids = ARROW_MARKERS.map((m) => m.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
