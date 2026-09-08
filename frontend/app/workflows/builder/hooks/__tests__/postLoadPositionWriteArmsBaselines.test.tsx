// @vitest-environment jsdom
/**
 * Why the measured re-layout is announced on the plan-sync ONLY, never on a load.
 *
 * A load is laid out from label estimates too, so straightening it on measured widths
 * looks like the same win. It is not: the correction can only run once the browser has
 * painted, which is necessarily AFTER both baselines have settled, and both hooks read
 * a position write as a user edit. The two tests below are that fact, executable - they
 * are the reason `useWorkflowLoader` stays silent (pinned in MeasuredLayoutSync.wiring),
 * and they are what a future author will hit if they wire the announcement there.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { Edge, Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { useHistory } from '../useHistory';
import { useDirtyState } from '../useDirtyState';

const node = (id: string, x: number): Node<BuilderNodeData> => ({
  id,
  type: 'flowNode',
  position: { x, y: 0 },
  data: { id, label: id, kind: 'action' } as BuilderNodeData,
});

const LOADED = [node('a', 149.75), node('b', 154)];
/** What the correction would commit: the same graph, one node moved onto the axis. */
const STRAIGHTENED = [node('a', 154), node('b', 154)];
/** ReactFlow's own dimensions write, which lands between the load and any correction. */
const MEASURED = LOADED.map((n) => ({ ...n, width: 200, height: 80 }));
const EDGES: Edge[] = [{ id: 'e', source: 'a', target: 'b' }];

describe('a position write that lands after the load window', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('arms UNDO, because the baseline was seeded on the commit that flipped workflowLoaded', () => {
    const view = renderHook(
      ({ nodes }: { nodes: Node<BuilderNodeData>[] }) =>
        useHistory(nodes, EDGES, vi.fn(), vi.fn(), true),
      { initialProps: { nodes: LOADED } },
    );
    act(() => vi.advanceTimersByTime(400));
    expect(view.result.current.canUndo).toBe(false);

    view.rerender({ nodes: MEASURED }); // not an edit: the signature strips measured dims
    act(() => vi.advanceTimersByTime(400));
    expect(view.result.current.canUndo).toBe(false);

    view.rerender({ nodes: STRAIGHTENED }); // the correction
    act(() => vi.advanceTimersByTime(400));

    // Undo now offers to put the column back the way the estimates bent it.
    expect(view.result.current.canUndo).toBe(true);
  });

  it('arms SAVE and the unload guard, because the dirty baseline locks two commits in', () => {
    const onDirtyChange = vi.fn();
    const view = renderHook(
      ({ nodes }: { nodes: Node<BuilderNodeData>[] }) =>
        useDirtyState({ nodes, edges: EDGES, workflowLoaded: true, isRunMode: false, onDirtyChange }),
      { initialProps: { nodes: LOADED } },
    );
    view.rerender({ nodes: MEASURED });
    expect(view.result.current.isDirty).toBe(false);

    view.rerender({ nodes: STRAIGHTENED });

    expect(view.result.current.isDirty).toBe(true);
    expect(onDirtyChange).toHaveBeenCalledWith(true);
  });
});
