/**
 * An interface node must be imported at the box it is going to PAINT.
 *
 * The box is snapped from the FORMAT of the interface entity, which is not part of the
 * workflow plan, and a plan written by an agent carries no `previewWidth/previewHeight`
 * at all (only a frontend save ever writes them). So the import used the historical
 * 400x250 default and the automatic layout reserved that box for a node that goes on to
 * paint, say, 283x400 for an A4 page: the node sits off its own axis and the rank below
 * starts INSIDE it (58px and 46px, measured on the prod canvas).
 *
 * On the agent plan-sync a post-paint correction fixes that (MeasuredLayoutSync). A
 * plain LOAD has no such second chance - a position write landing after the browser has
 * painted would arm Save and the undo stack on a workflow the user merely opened - so
 * the load's first and only layout has to be right. These tests pin that it is.
 */
import { describe, it, expect } from 'vitest';
import type { Edge, Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { createInterfaceNodes } from '../InterfaceNodeCreator';
import { applyDagreLayout, getNodeDimensions, layoutConfigForDirection } from '../../LayoutService';
import { collectInterfaces } from '../../../utils/interfaceProcessor';

/** What an MCP-built plan entry looks like: a page reference, and no box. */
const AGENT_ENTRY = { id: 'iface-1', label: 'Invoice Preview' };

/** A4 portrait (794x1123) contained in the 400x400 snap box. */
const A4_BOX = { width: 283, height: 400 };

const importOne = (
  entry: Record<string, unknown>,
  formats?: Map<string, string | null>,
): Node<BuilderNodeData> =>
  createInterfaceNodes([entry as any], 0, 0, formats).nodes[0];

describe('the box an imported interface node reserves', () => {
  it('regression: an agent-built entry takes the box its format paints, not the 400x250 default', () => {
    const node = importOne(AGENT_ENTRY, new Map([['iface-1', 'a4_portrait']]));

    expect(node.style).toMatchObject(A4_BOX);
    // The layout reads the box from the DATA, so the style alone would not fix anything.
    expect((node.data as any).interfaceData.previewWidth).toBe(A4_BOX.width);
    expect((node.data as any).interfaceData.previewHeight).toBe(A4_BOX.height);
  });

  it('feeds the layout the painted shape, which is the whole point of resolving the format', () => {
    const node = importOne(AGENT_ENTRY, new Map([['iface-1', 'a4_portrait']]));

    expect(getNodeDimensions(node, true, 'vertical')).toEqual(A4_BOX);
  });

  it('an interface that declares NO format keeps the classic 400x250 box', () => {
    // Resolved-but-empty is not unknown: the node renders such an interface at the
    // classic 1280x800 viewport, which snaps to exactly the historical default.
    const node = importOne(AGENT_ENTRY, new Map([['iface-1', null]]));

    expect(node.style).toMatchObject({ width: 400, height: 250 });
  });

  it('an id the lookup could not resolve keeps the default AND has no box invented for it', () => {
    // A failed fetch, or a marketplace preview where the endpoint is not callable. The
    // absent previewWidth matters beyond the layout: a save must not persist a guess.
    const node = importOne(AGENT_ENTRY, new Map());

    expect(node.style).toMatchObject({ width: 400, height: 250 });
    expect((node.data as any).interfaceData.previewWidth).toBeUndefined();
    expect((node.data as any).interfaceData.previewHeight).toBeUndefined();
  });

  it('a persisted box that already has the format shape is preserved, not re-snapped', () => {
    // The node's own snap rule: only a box of a different RATIO is re-snapped, so a page
    // the user resized keeps its size. 566x800 is A4 portrait, larger.
    const node = importOne(
      { ...AGENT_ENTRY, previewWidth: 566, previewHeight: 800 },
      new Map([['iface-1', 'a4_portrait']]),
    );

    expect(node.style).toMatchObject({ width: 566, height: 800 });
  });

  it('regression: a stored box left over from an older format is re-snapped to the current one', () => {
    // The second act of the reported bug: the workflow was saved while the page was
    // vertical (225x400), the page then became A4, and one positionless node an agent
    // added is enough to re-run the layout over everything. Trusting the stored box
    // there reserves 225x400 for a node that paints 283x400.
    const node = importOne(
      { ...AGENT_ENTRY, previewWidth: 225, previewHeight: 400 },
      new Map([['iface-1', 'a4_portrait']]),
    );

    expect(node.style).toMatchObject(A4_BOX);
  });

  it('a custom WxH format is honoured like a preset', () => {
    const node = importOne(AGENT_ENTRY, new Map([['iface-1', '800x400']]));

    expect(node.style).toMatchObject({ width: 400, height: 200 });
  });

  it('a format this build does not know falls back to the classic box, like the node does', () => {
    // resolveInterfaceFormat returns null for an unknown preset or an out-of-range
    // custom pair, and the node then renders at the classic 1280x800 viewport.
    const unknown = importOne(AGENT_ENTRY, new Map([['iface-1', 'a4']]));
    const oversized = importOne(AGENT_ENTRY, new Map([['iface-1', '9999x9999']]));

    expect(unknown.style).toMatchObject({ width: 400, height: 250 });
    expect(oversized.style).toMatchObject({ width: 400, height: 250 });
  });

  it('regression: a published page is laid out from the snapshot, with no lookup to be had', () => {
    // A marketplace preview and a share link cannot call the interfaces endpoint, so the
    // format travels in the plan. Before this, an A4 showcase laid out at 400x250.
    const node = importOne({ ...AGENT_ENTRY, _snapshot_format: 'a4_portrait' }, new Map());

    expect(node.style).toMatchObject(A4_BOX);
  });

  it('a snapshot that declares no format still answers: the classic box', () => {
    const node = importOne({ ...AGENT_ENTRY, _snapshot_format: null }, new Map());

    expect(node.style).toMatchObject({ width: 400, height: 250 });
  });

  it('regression: a COMPACT node gets no box, which is a size the node itself never writes', () => {
    // getNodeDimensions only reads the box in preview mode, so a box here would be plan
    // content nobody asked for the moment the user saves.
    const node = importOne(
      { ...AGENT_ENTRY, showPreview: false },
      new Map([['iface-1', 'a4_portrait']]),
    );

    expect((node.data as any).interfaceData.previewWidth).toBeUndefined();
    expect((node.data as any).interfaceData.previewHeight).toBeUndefined();
  });
});

describe('the layout the imported box produces', () => {
  const VERTICAL = layoutConfigForDirection('vertical');
  const plain = (id: string, label: string): Node<BuilderNodeData> => ({
    id,
    position: { x: 0, y: 0 },
    data: { label } as any,
  });
  const EDGES: Edge[] = [
    { id: 'e1', source: 'above', target: 'interface-iface-1' },
    { id: 'e2', source: 'interface-iface-1', target: 'below' },
  ];

  /** Lay the chain out the way a LOAD does: one estimate-only pass, nothing measured. */
  const layOut = (formats?: Map<string, string | null>) => {
    const nodes = [
      plain('above', 'Build Invoice'),
      importOne(AGENT_ENTRY, formats),
      plain('below', 'Approve Invoice'),
    ];
    const laid = applyDagreLayout(nodes, EDGES, VERTICAL);
    const preview = laid.find((n) => n.id === 'interface-iface-1')!;
    const below = laid.find((n) => n.id === 'below')!;
    // The node paints A4 whatever the layout reserved: that is the mismatch under test.
    return { paintedBottom: preview.position.y + A4_BOX.height, belowTop: below.position.y };
  };

  it('regression: the FIRST pass no longer drops the next node inside an A4 page node', () => {
    const { paintedBottom, belowTop } = layOut(new Map([['iface-1', 'a4_portrait']]));

    expect(belowTop).toBeGreaterThanOrEqual(paintedBottom);
  });

  it('proves the defect: with the format unknown, that same node still lands 46px inside it', () => {
    const { paintedBottom, belowTop } = layOut(new Map());

    expect(paintedBottom - belowTop).toBe(46);
  });
});

describe('what a later save records', () => {
  const exportOf = (node: Node<BuilderNodeData>) => {
    const ctx: any = { nodes: [node], plan: {}, interfaceNodeIdMap: new Map() };
    collectInterfaces(ctx);
    return ctx.plan.interfaces[0];
  };

  it('persists the resolved box, so the plan records the shape the page paints', () => {
    const entry = exportOf(importOne(AGENT_ENTRY, new Map([['iface-1', 'a4_portrait']])));

    expect(entry.previewWidth).toBe(A4_BOX.width);
    expect(entry.previewHeight).toBe(A4_BOX.height);
  });

  it('writes nothing for a classic page, so an unshaped plan stays unshaped', () => {
    const entry = exportOf(importOne(AGENT_ENTRY, new Map([['iface-1', null]])));

    expect(entry.previewWidth).toBeUndefined();
    expect(entry.previewHeight).toBeUndefined();
  });
});
