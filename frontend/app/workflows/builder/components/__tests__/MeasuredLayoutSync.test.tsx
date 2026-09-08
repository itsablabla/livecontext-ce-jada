// @vitest-environment jsdom
/**
 * MeasuredLayoutSync replays an automatic layout on the sizes the browser painted.
 *
 * The defect: the builder lays out from label estimates, never from measured dims
 * (`LAYOUT_CONFIG.ignoreMeasured`). A wrong estimate bends the column on the cross axis
 * AND overlaps the next rank on the flow axis, because that rank is placed at
 * `estimated height + ranksep`. An interface node in preview mode is the case users
 * hit: 400x250 reserved, 283x400 painted.
 *
 * The risk this component carries is the opposite one: moving nodes it must not move.
 * Most of what follows is therefore about what it leaves ALONE, and about the timing -
 * a correction computed before the sizes settle is measured against a size the node is
 * about to abandon.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render } from '@testing-library/react';
import * as React from 'react';

import { MeasuredLayoutSync } from '../MeasuredLayoutSync';
import { dispatchLayoutApplied, LAYOUT_APPLIED_EVENT } from '@/lib/workflow/layoutAppliedEvent';

let nodes: any[] = [];
let edges: any[] = [];
const onNodesChange = vi.fn();
const instance: any = { getNodes: () => nodes, getEdges: () => edges };

// Keyed by handle, so a cancelAnimationFrame in the component actually drops the frame
// here: the restart behaviour is otherwise untestable.
let rafQueue = new Map<number, () => void>();
let rafSeq = 0;
const pendingFrames = () => rafQueue.size;
const flushFrame = () => {
  const batch = [...rafQueue.entries()];
  batch.forEach(([handle]) => rafQueue.delete(handle));
  batch.forEach(([, cb]) => cb());
};
/** Frames of identical sizes needed before a correction: two paints in a row, plus the first. */
const flushUntilSettled = () => { flushFrame(); flushFrame(); flushFrame(); };
/** Run frames until nothing is scheduled, capped so a polling bug fails instead of hanging. */
const flushUntilIdle = (max = 400) => {
  let frames = 0;
  while (pendingFrames() > 0 && frames < max) {
    flushFrame();
    frames++;
  }
  return frames;
};
const announceLayout = (workflowId: string | null = 'wf-1') => dispatchLayoutApplied(workflowId);

const renderSync = (direction = 'vertical', inst: any = instance, extra: any = {}) =>
  render(
    <MeasuredLayoutSync
      direction={direction}
      workflowId="wf-1"
      {...extra}
      instance={inst}
      onNodesChange={onNodesChange as any}
    />,
  );

beforeEach(() => {
  rafQueue = new Map();
  rafSeq = 0;
  onNodesChange.mockClear();
  vi.stubGlobal('requestAnimationFrame', (cb: () => void) => {
    rafQueue.set(++rafSeq, cb);
    return rafSeq;
  });
  vi.stubGlobal('cancelAnimationFrame', (handle: number) => rafQueue.delete(handle));
  // A straight two-node chain laid out from ESTIMATES: the 15-char label was measured
  // as 208.5 wide by the estimator, so it sits 4.25 to the left of its neighbour,
  // although both PAINT at 200.
  nodes = [
    { id: 'a', position: { x: 149.75, y: 0 }, width: 200, height: 80, data: { label: 'Invoice Details' } },
    { id: 'b', position: { x: 154, y: 184 }, width: 200, height: 80, data: { label: 'Build Invoice' } },
  ];
  edges = [{ id: 'e', source: 'a', target: 'b' }];
});

afterEach(() => vi.unstubAllGlobals());

describe('MeasuredLayoutSync - what it corrects', () => {
  it('regression: after a layout, the graph is re-laid on MEASURED sizes', () => {
    renderSync();
    announceLayout();
    // The boundary is deliberate: TWO identical paints, not one. A single frame proves
    // nothing about a node whose preview is still resolving its format, and correcting
    // on it is how the A4 node ended up placed against a size it was about to abandon.
    flushFrame();
    expect(onNodesChange).not.toHaveBeenCalled();
    flushFrame();
    expect(onNodesChange).not.toHaveBeenCalled();
    flushFrame();

    expect(onNodesChange).toHaveBeenCalledTimes(1);
    const changes = onNodesChange.mock.calls[0][0];
    expect(changes[0]).toMatchObject({ type: 'position' });
    const finalX = (id: string) =>
      changes.find((c: any) => c.id === id)?.position.x ?? nodes.find((n: any) => n.id === id)!.position.x;
    // Both paint at 200, so a straight column is the only correct answer.
    expect(finalX('a')).toBeCloseTo(finalX('b'), 1);
  });

  it('regression: corrects again when a node RESIZES after the first correction', () => {
    // An interface node keeps its default box until its format loads. A one-shot
    // correction is computed against a size the node is about to abandon, which is
    // exactly how an A4 preview stayed 58px off its own axis.
    nodes = [
      { id: 'a', position: { x: 0, y: 0 }, width: 200, height: 80, data: { label: 'Build Invoice' } },
      { id: 'iface', position: { x: 0, y: 184 }, width: 400, height: 250, data: { label: 'Invoice Preview' } },
    ];
    edges = [{ id: 'e', source: 'a', target: 'iface' }];
    renderSync();
    announceLayout();
    flushUntilSettled();
    const firstCalls = onNodesChange.mock.calls.length;

    // The preview loads its format and snaps to the real A4 box.
    nodes = nodes.map((n) => (n.id === 'iface' ? { ...n, width: 283, height: 400 } : n));
    flushUntilSettled();

    expect(onNodesChange.mock.calls.length).toBeGreaterThan(firstCalls);
    const last = onNodesChange.mock.calls[onNodesChange.mock.calls.length - 1][0];
    const finalX = (id: string) =>
      last.find((c: any) => c.id === id)?.position.x ?? nodes.find((n: any) => n.id === id)!.position.x;
    // Centres, not left edges: the two nodes have different widths.
    expect(finalX('a') + 100).toBeCloseTo(finalX('iface') + 141.5, 1);
  });

  it('regression: reads the topology INSIDE the frame, so the node just added is placed', () => {
    // The announcement is synchronous, before React re-renders. The store catches up
    // between frames. A handler that captured the edges at entry would see the OLD
    // topology, treat the new node as an isolated component and leave it on its
    // estimate - the very node this exists to straighten.
    renderSync();
    announceLayout();
    flushFrame();

    nodes = [...nodes, { id: 'c', position: { x: 149.75, y: 368 }, width: 200, height: 80, data: { label: 'Invoice Details' } }];
    edges = [...edges, { id: 'e2', source: 'b', target: 'c' }];

    flushUntilSettled();

    expect(onNodesChange).toHaveBeenCalled();
    const last = onNodesChange.mock.calls[onNodesChange.mock.calls.length - 1][0];
    const finalX = (id: string) =>
      last.find((c: any) => c.id === id)?.position.x ?? nodes.find((n: any) => n.id === id)!.position.x;
    expect(finalX('a')).toBeCloseTo(finalX('c'), 1);
    expect(finalX('b')).toBeCloseTo(finalX('c'), 1);
  });

  it('corrects a HORIZONTAL canvas too: the rank spacing comes from the same estimate', () => {
    renderSync('horizontal');
    announceLayout();
    flushUntilSettled();

    expect(onNodesChange).toHaveBeenCalledTimes(1);
  });
});

describe('MeasuredLayoutSync - what it must leave alone', () => {
  it('regression: does nothing without a layout, so opening a saved workflow is untouched', () => {
    renderSync();
    flushUntilIdle();

    expect(onNodesChange).not.toHaveBeenCalled();
  });

  it('regression: a drag DURING the settle window wins, the correction stands down', () => {
    // The window is seconds long (an interface node loads its format over the network),
    // and a correction recomputes EVERY node, so a later tick would snap a node the user
    // just dragged back onto the algorithmic layout. The first pointer press ends it.
    renderSync();
    announceLayout();
    flushFrame();

    window.dispatchEvent(new Event('pointerdown'));
    flushUntilIdle();

    expect(onNodesChange).not.toHaveBeenCalled();
  });

  it('regression: with no layout announced, a node the user dragged is left where they dropped it', () => {
    nodes[0].position.x = 900; // dragged far off the column
    renderSync();
    // The palette adds a node: the graph changes, but no automatic layout ran, so
    // nothing is announced. An earlier version keyed on the node set and yanked the
    // drag back here.
    nodes.push({ id: 'c', position: { x: 154, y: 368 }, width: 200, height: 80, data: { label: 'C' } });
    flushUntilIdle();

    expect(onNodesChange).not.toHaveBeenCalled();
    expect(nodes[0].position.x).toBe(900);
  });

  it('regression: refuses an announcement naming a DIFFERENT workflow', () => {
    renderSync();
    announceLayout('other-wf');
    flushUntilIdle();

    expect(onNodesChange).not.toHaveBeenCalled();
  });

  it('regression: refuses an announcement that names NO workflow', () => {
    // Anonymous events are the ones that reach every canvas at once.
    renderSync();
    announceLayout(null);
    flushUntilIdle();

    expect(onNodesChange).not.toHaveBeenCalled();
  });

  it('regression: a canvas mounted without an id answers nobody', () => {
    // The standalone builder route mounts one. Under a permissive match it would accept
    // every announcement, including a side panel's, and move nodes nobody laid out.
    render(
      <MeasuredLayoutSync
        direction="vertical"
        instance={instance}
        onNodesChange={onNodesChange as any}
      />,
    );
    announceLayout();
    flushUntilIdle();

    expect(onNodesChange).not.toHaveBeenCalled();
  });

  it('ignores an announcement carrying no detail at all', () => {
    renderSync();
    window.dispatchEvent(new CustomEvent(LAYOUT_APPLIED_EVENT));
    flushUntilIdle();

    expect(onNodesChange).not.toHaveBeenCalled();
  });

  it('regression: does nothing while the canvas is locked (run mode / preview)', () => {
    renderSync('vertical', instance, { isLocked: true });
    announceLayout();
    flushUntilIdle();

    expect(onNodesChange).not.toHaveBeenCalled();
  });

  it('regression: a canvas that locks MID-FLIGHT is not written to', () => {
    // The correction spans several frames, so the canvas can enter run mode or a
    // preview between the announcement and the frame that would apply it. The guarded
    // change channel does not filter position changes (only removals), so this
    // per-frame re-check is the only thing standing between a locked canvas and a
    // write it can neither expect nor undo.
    const view = renderSync('vertical', instance, { isLocked: false });
    announceLayout();
    flushFrame(); // in flight

    view.rerender(
      <MeasuredLayoutSync
        direction="vertical"
        workflowId="wf-1"
        isLocked
        instance={instance}
        onNodesChange={onNodesChange as any}
      />,
    );
    flushUntilIdle();

    expect(onNodesChange).not.toHaveBeenCalled();
  });

  it('emits nothing when the layout is already what the measured sizes ask for', () => {
    nodes = [
      { id: 'a', position: { x: 0, y: 0 }, width: 200, height: 80, data: { label: 'A' } },
      { id: 'b', position: { x: 0, y: 184 }, width: 200, height: 80, data: { label: 'B' } },
    ];
    renderSync();
    announceLayout();
    flushUntilIdle();

    expect(onNodesChange).not.toHaveBeenCalled();
  });

  it('waits rather than lay out a graph where a node is not painted yet', () => {
    nodes[1].width = undefined;
    renderSync();
    announceLayout();
    flushUntilSettled();

    expect(onNodesChange).not.toHaveBeenCalled();
  });

  it('regression: keeps looking, and corrects as soon as the last size lands', () => {
    // A large plan or a busy main thread can leave a node unpainted for several frames.
    // Reading that as "already correct" and giving up is the ORIGINAL bug, intermittently.
    nodes[1].width = undefined;
    renderSync();
    announceLayout();
    flushUntilSettled();
    expect(onNodesChange).not.toHaveBeenCalled();

    nodes = [nodes[0], { ...nodes[1], width: 200 }]; // the paint finally lands
    flushUntilSettled();

    expect(onNodesChange).toHaveBeenCalledTimes(1);
  });

  it('gives up after a bounded window instead of polling for ever', () => {
    const start = Date.now();
    nodes[1].width = undefined; // never painted: the node was removed mid-flight, say
    renderSync();
    announceLayout();
    // Walk the clock past the settle window while the frames run.
    const now = vi.spyOn(Date, 'now').mockImplementation(() => start + 60_000);

    const frames = flushUntilIdle();

    expect(onNodesChange).not.toHaveBeenCalled();
    expect(pendingFrames()).toBe(0);
    expect(frames).toBeLessThan(400);
    now.mockRestore();
  });

  it('a second announcement restarts the watch instead of stacking a second correction', () => {
    renderSync();
    announceLayout();
    flushFrame(); // first watch is one frame in
    announceLayout();

    flushUntilIdle();

    // Both announcements describe the same graph, so the layout is corrected once.
    expect(onNodesChange).toHaveBeenCalledTimes(1);
  });

  it('does nothing without a ReactFlow instance to read measured sizes from', () => {
    renderSync('vertical', null);
    announceLayout();
    flushUntilIdle();

    expect(onNodesChange).not.toHaveBeenCalled();
  });

  it('stops listening once unmounted', () => {
    const { unmount } = renderSync();
    unmount();
    announceLayout();
    flushUntilIdle();

    expect(onNodesChange).not.toHaveBeenCalled();
  });
});
