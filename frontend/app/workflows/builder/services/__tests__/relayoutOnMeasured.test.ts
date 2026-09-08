/**
 * `relayoutOnMeasured` replays the layout on the sizes ReactFlow has MEASURED, so an
 * automatic layout ends up where the auto-layout BUTTON would have put it.
 *
 * The defect it exists for: the builder lays out from label estimates and never from
 * measured dims (`LAYOUT_CONFIG.ignoreMeasured`), which keeps the button reproducible.
 * A wrong estimate then lands on the canvas twice - as a bend on the cross axis, and as
 * an OVERLAP on the flow axis, because the next rank is placed at `estimated height +
 * ranksep`. These tests pin both divergences AND the parity that closes them.
 */
import { describe, it, expect } from 'vitest';
import type { Node, Edge } from 'reactflow';
import { applyDagreLayout, layoutConfigForDirection, relayoutOnMeasured, RELAYOUT_MIN_SHIFT_PX } from '../LayoutService';
import type { BuilderNodeData } from '../../types';

const VERTICAL = layoutConfigForDirection('vertical');
const PAINTED = 200;

/** Both labels paint at 200; only their ESTIMATES differ (208.5 vs 200). */
const chain = (measured: boolean): Node<BuilderNodeData>[] => [
  { id: 'a', position: { x: 0, y: 0 }, data: { label: 'Invoice Details' } as any,
    ...(measured ? { width: PAINTED, height: 80 } : {}) },
  { id: 'b', position: { x: 0, y: 0 }, data: { label: 'Build Invoice' } as any,
    ...(measured ? { width: PAINTED, height: 80 } : {}) },
];
const chainEdges: Edge[] = [{ id: 'e', source: 'a', target: 'b' }];
const centreOf = (n: Node<BuilderNodeData>) => n.position.x + (n.width ?? PAINTED) / 2;

/**
 * The production shape of the bug, in the order it actually happens.
 *
 * The plan-sync lays the graph out the instant the nodes exist: the interface node has
 * not been told its format, so it reserves the 400x250 drop default. The format then
 * arrives, the node persists its real box (A4 portrait contained in 400x400 = 283x400)
 * and paints it - taller than what the layout reserved, so the rank below starts inside
 * it. `beforeFormat` is what the first pass sees, `afterFormat` what the correction sees.
 */
const previewChain = (stage: 'beforeFormat' | 'afterFormat'): Node<BuilderNodeData>[] => {
  const painted = stage === 'afterFormat';
  return [
    { id: 'above', position: { x: 0, y: 0 }, width: PAINTED, height: 80, data: { label: 'Build Invoice' } as any },
    {
      id: 'interface-preview',
      type: 'interfaceNode',
      position: { x: 0, y: 0 },
      ...(painted ? { width: 283, height: 400 } : {}),
      data: {
        label: 'Invoice Preview',
        interfaceData: {
          interfaceId: 'iface-1',
          showPreview: true,
          ...(painted ? { previewWidth: 283, previewHeight: 400 } : {}),
        },
      } as any,
    },
    { id: 'below', position: { x: 0, y: 0 }, width: 339, height: 129, data: { label: 'Approve Invoice' } as any },
  ];
};

/** The canvas as it stands after the first pass: estimate positions, real sizes. */
const previewChainAfterPaint = (config: Parameters<typeof applyDagreLayout>[2]) => {
  const laid = applyDagreLayout(previewChain('beforeFormat'), previewEdges, config);
  const painted = previewChain('afterFormat');
  return laid.map((n) => {
    const p = painted.find((x) => x.id === n.id)!;
    return { ...p, position: n.position };
  });
};

const previewEdges: Edge[] = [
  { id: 'e1', source: 'above', target: 'interface-preview' },
  { id: 'e2', source: 'interface-preview', target: 'below' },
];

/** Apply what the function returns, so assertions read the canvas the user would see. */
const applyMoves = (
  nodes: Node<BuilderNodeData>[],
  moved: Array<{ id: string; position: { x: number; y: number } }>,
) => nodes.map((n) => {
  const m = moved.find((x) => x.id === n.id);
  return m ? { ...n, position: m.position } : n;
});

const bottomOf = (n: Node<BuilderNodeData>) => n.position.y + (n.height ?? 80);

describe('the divergence this fix exists for', () => {
  it('regression: laying out UNMEASURED leaves two same-width nodes off a shared axis', () => {
    const laid = applyDagreLayout(chain(false), chainEdges, VERTICAL);
    const [a, b] = laid;

    // Both paint at 200, so equal x is the only straight column. The estimate says
    // otherwise, and that gap is exactly what the user sees as a slanted edge.
    expect(Math.abs((a.position.x + PAINTED / 2) - (b.position.x + PAINTED / 2))).toBeGreaterThan(1);
  });

  it('regression: an interface preview reserves 250px of height and then paints 400, so the next node OVERLAPS it', () => {
    // The layout runs before the interface knows its format, so dagre spaces the rank
    // after it by `250 + ranksep` while the node goes on to paint 400 tall.
    const canvas = previewChainAfterPaint(VERTICAL);
    const preview = canvas.find((n) => n.id === 'interface-preview')!;
    const below = canvas.find((n) => n.id === 'below')!;

    expect(below.position.y).toBeLessThan(bottomOf(preview));
  });

  it('laying out MEASURED already produces a straight, non-overlapping column (the button path)', () => {
    const laid = applyDagreLayout(chain(true), chainEdges, VERTICAL);
    expect(centreOf(laid[0])).toBeCloseTo(centreOf(laid[1]), 1);
  });
});

describe('relayoutOnMeasured', () => {
  it('regression: brings the estimate-laid column onto the same axis as the button', () => {
    const estimateLaid = applyDagreLayout(chain(false), chainEdges, VERTICAL)
      .map((n) => ({ ...n, width: PAINTED, height: 80 }));
    const fixed = applyMoves(estimateLaid, relayoutOnMeasured(estimateLaid, chainEdges, 'vertical'));

    expect(centreOf(fixed[0])).toBeCloseTo(centreOf(fixed[1]), 1);
  });

  it('regression: re-spaces the ranks so the node under a tall preview no longer sits inside it', () => {
    const canvas = previewChainAfterPaint(VERTICAL);
    const fixed = applyMoves(canvas, relayoutOnMeasured(canvas, previewEdges, 'vertical'));
    const preview = fixed.find((n) => n.id === 'interface-preview')!;
    const below = fixed.find((n) => n.id === 'below')!;

    expect(below.position.y).toBeGreaterThanOrEqual(bottomOf(preview));
    // And the preview joins the column instead of being centred on a box it does not have.
    expect(centreOf(preview)).toBeCloseTo(centreOf(below), 1);
  });

  it('regression: corrects the same wrong reservation on a HORIZONTAL canvas', () => {
    // Left-to-right never had the centring problem (its cross axis is a height
    // constant), but the rank spacing comes from the same reservation. There the
    // preview's 400px default is WIDER than the 283 it paints, so the error shows up as
    // a gap the graph does not need rather than as an overlap - same cause, other sign.
    const canvas = previewChainAfterPaint({});
    const gapOf = (ns: Node<BuilderNodeData>[]) =>
      ns.find((n) => n.id === 'below')!.position.x
      - (ns.find((n) => n.id === 'interface-preview')!.position.x + 283);

    const fixed = applyMoves(canvas, relayoutOnMeasured(canvas, previewEdges, 'horizontal'));

    expect(gapOf(canvas)).toBeGreaterThan(gapOf(fixed));
    expect(gapOf(fixed)).toBeGreaterThan(0);
  });

  it('returns ONLY the nodes that move, never the whole graph', () => {
    const laid = applyDagreLayout(chain(false), chainEdges, VERTICAL)
      .map((n) => ({ ...n, width: PAINTED, height: 80 }));
    const moved = relayoutOnMeasured(laid, chainEdges, 'vertical');

    // Every id returned becomes a position change that marks the workflow dirty, so a
    // node already in the right place must not be in there. Asserting the COUNT alone
    // would pass on an implementation that returns everything.
    expect(moved.length).toBeLessThan(laid.length);
    for (const m of moved) {
      const before = laid.find((n) => n.id === m.id)!.position;
      expect(Math.hypot(m.position.x - before.x, m.position.y - before.y)).toBeGreaterThan(RELAYOUT_MIN_SHIFT_PX);
    }
  });

  it('regression: returns a node whose FLOW axis alone is wrong', () => {
    // The overlap this fix exists for is a flow-axis error: a node can be perfectly
    // centred and still sit inside the one above it. A filter that only looked at the
    // cross axis would drop it silently and leave the overlap on screen.
    const corrected = applyMoves(
      previewChainAfterPaint(VERTICAL),
      relayoutOnMeasured(previewChainAfterPaint(VERTICAL), previewEdges, 'vertical'),
    );
    const nudged = corrected.map((n) =>
      n.id === 'below' ? { ...n, position: { x: n.position.x, y: n.position.y - 50 } } : n,
    );

    const moved = relayoutOnMeasured(nudged, previewEdges, 'vertical');

    expect(moved.map((m) => m.id)).toEqual(['below']);
    const before = nudged.find((n) => n.id === 'below')!.position;
    expect(moved[0].position.x).toBeCloseTo(before.x, 1);
    expect(moved[0].position.y).toBeCloseTo(before.y + 50, 1);
  });

  it('is idempotent: applying it once leaves nothing to correct, so it cannot settle-loop', () => {
    const laid = applyDagreLayout(chain(false), chainEdges, VERTICAL)
      .map((n) => ({ ...n, width: PAINTED, height: 80 }));
    const once = applyMoves(laid, relayoutOnMeasured(laid, chainEdges, 'vertical'));

    expect(relayoutOnMeasured(once, chainEdges, 'vertical')).toEqual([]);
  });

  it('returns nothing when the layout already used the measured sizes', () => {
    const straight = applyDagreLayout(chain(true), chainEdges, { ...VERTICAL, ignoreMeasured: false });
    expect(relayoutOnMeasured(straight, chainEdges, 'vertical')).toEqual([]);
  });

  it('returns nothing while any node is still unpainted, rather than mix sizes with estimates', () => {
    const half = previewChainAfterPaint(VERTICAL)
      .map((n, i) => (i === 0 ? n : { ...n, width: undefined, height: undefined }));
    expect(relayoutOnMeasured(half, previewEdges, 'vertical')).toEqual([]);
  });

  it('returns nothing for an empty canvas', () => {
    expect(relayoutOnMeasured([], [], 'vertical')).toEqual([]);
  });

  it('lays each connected component out on its OWN edges, like the layout does', () => {
    // Two independent chains. Treating them as one graph would group nodes of different
    // components that share a rank and push the lanes apart.
    const nodes: Node<BuilderNodeData>[] = [
      { id: 'a1', position: { x: 0, y: 0 }, width: PAINTED, height: 80, data: { label: 'Aaa' } as any },
      { id: 'a2', position: { x: 20, y: 200 }, width: PAINTED, height: 80, data: { label: 'Aaa two' } as any },
      // Deliberately CLOSE: the two lanes are 210 apart, under the 244 px at which a
      // single global rank (200 wide + 44 nodesep) would start pushing them apart.
      { id: 'b1', position: { x: 210, y: 0 }, width: PAINTED, height: 80, data: { label: 'Bbb' } as any },
      { id: 'b2', position: { x: 230, y: 200 }, width: PAINTED, height: 80, data: { label: 'Bbb two' } as any },
    ];
    const eds: Edge[] = [
      { id: 'ea', source: 'a1', target: 'a2' },
      { id: 'eb', source: 'b1', target: 'b2' },
    ];
    const fixed = applyMoves(nodes, relayoutOnMeasured(nodes, eds, 'vertical'));
    const at = (id: string) => fixed.find((n) => n.id === id)!.position.x;

    // Each chain closes onto its OWN axis, and the two lanes stay side by side rather
    // than being merged into one column.
    expect(at('a1')).toBeCloseTo(at('a2'), 1);
    expect(at('b1')).toBeCloseTo(at('b2'), 1);
    expect(Math.abs(at('b1') - at('a1'))).toBeGreaterThan(PAINTED);
  });
});
