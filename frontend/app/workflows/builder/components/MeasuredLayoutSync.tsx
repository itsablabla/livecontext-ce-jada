import * as React from 'react';
import type { NodePositionChange, OnNodesChange, ReactFlowInstance, Node } from 'reactflow';
import { hasMeasuredDimensions, relayoutOnMeasured } from '../services/LayoutService';
import type { WorkflowLayoutDirection } from '@/contexts/WorkflowLayoutDirectionContext';
import { LAYOUT_APPLIED_EVENT, type LayoutAppliedDetail } from '@/lib/workflow/layoutAppliedEvent';
import type { BuilderNodeData } from '../types';

/**
 * Re-runs an automatic layout on the sizes the browser actually painted.
 *
 * <h3>The defect</h3>
 *
 * The builder lays out from deterministic label estimates, never from measured
 * dimensions (`LAYOUT_CONFIG.ignoreMeasured`, which is what makes the auto-layout
 * button reproducible). A wrong estimate lands on the canvas in two ways: on the cross
 * axis the node is centred on a width it does not have, and on the flow axis the NEXT
 * rank is placed at `estimated height + ranksep`, so a node that paints taller than its
 * estimate is overlapped by the one below it. An interface node in preview mode is the
 * extreme case, and the one users hit: it reserves the 400x250 default box until its
 * format loads, then paints 283x400, so it ends up 58px off its own axis with the next
 * node 46px inside it.
 *
 * <h3>Why an event, and why a settle window</h3>
 *
 * The correction must follow a LAYOUT, never a mere change of the graph: an earlier
 * version keyed on the node set and re-centred author-placed nodes on load, armed Save
 * and undo on a workflow nobody had touched, and yanked back a node the user had just
 * dragged. Listening for {@link LAYOUT_APPLIED_EVENT} means it only ever refines a
 * layout the builder itself just produced.
 *
 * One shot is not enough either: an interface node keeps its default box until its
 * format arrives, so a correction computed on the first painted frame is measured
 * against a size the node is about to abandon - which is exactly how that A4 node
 * stayed 58px off its axis after the first version of this component. The sizes are
 * therefore watched for a bounded window and the layout is replayed whenever they
 * settle on something new.
 *
 * <h3>Why only the plan-sync announces</h3>
 *
 * Correcting a LOAD would re-introduce the dirty-on-open bug by another road: the undo
 * baseline is seeded on the commit that flips `workflowLoaded` (`useHistory`) and the
 * dirty baseline two node commits later (`useDirtyState`), and a correction that needs
 * a painted frame cannot land before either. The plan-sync runs long after both, and
 * its own node write already marks the canvas edited.
 */

/** How long to keep watching for a late resize (an interface preview loads its format). */
const SETTLE_WINDOW_MS = 6000;
/** Frames of identical sizes that count as settled - two paints in a row, not one. */
const STABLE_FRAMES = 2;
/** Stop early once nothing has changed for this long after a correction. */
const QUIET_FRAMES_AFTER_EMIT = 90;

type Ctx = {
  direction: string;
  workflowId?: string | null;
  isLocked?: boolean;
  instance: ReactFlowInstance | null;
  onNodesChange: OnNodesChange;
};

/** Sizes only: a position must NOT be in here, or our own correction reads as a change. */
function sizeSignature(nodes: Node<BuilderNodeData>[]): string {
  return nodes.map((n) => `${n.id}:${n.width}x${n.height}`).join('|');
}

export function MeasuredLayoutSync({
  direction,
  workflowId,
  isLocked,
  instance,
  onNodesChange,
}: {
  direction: string;
  /** Refuse an announcement that names a DIFFERENT workflow: several canvases listen. */
  workflowId?: string | null;
  /** Run mode / preview-only: the same surfaces the dispatcher itself refuses to act on. */
  isLocked?: boolean;
  /** Read MEASURED nodes AND the committed edges from here, never from props. */
  instance: ReactFlowInstance | null;
  onNodesChange: OnNodesChange;
}) {
  // Props, not `useReactFlow`: reaching for the hook here would force every existing
  // BuilderCanvas test to mock it, and the canvas already holds both of these.
  const ref = React.useRef<Ctx>({ direction, workflowId, isLocked, instance, onNodesChange });
  // Synced in an effect, not during render, matching DirectionHandleSync.
  React.useEffect(() => {
    ref.current = { direction, workflowId, isLocked, instance, onNodesChange };
  });

  React.useEffect(() => {
    let raf = 0;

    // Set while a watch is running, so the effect's cleanup can stop listening for the
    // user's hands whichever way the watch ends.
    let stopWatching: (() => void) | null = null;

    const watch = (deadline: number) => {
      let lastSignature: string | null = null;
      let appliedSignature: string | null = null;
      let stableFor = 0;
      let quietFrames = 0;
      let corrected = false;

      // The window is seconds long, because it waits out an interface loading its
      // format. That is long enough for the user to start moving things, and a
      // correction recomputes EVERY node, so a later tick would snap a node they just
      // dragged back onto the algorithmic layout. Their hands win: the first pointer
      // press ends the watch, and the layout stays as it is.
      let abandoned = false;
      const onUserInput = () => { abandoned = true; };
      window.addEventListener('pointerdown', onUserInput, true);
      const stop = () => {
        window.removeEventListener('pointerdown', onUserInput, true);
        stopWatching = null;
      };
      stopWatching = stop;

      const frame = () => {
        const { instance: inst, isLocked: locked, direction: dir } = ref.current;
        // Re-read every frame, not once: the canvas can enter run mode or a preview
        // while this is still waiting, and the guarded change channel filters removals,
        // not position writes.
        if (!inst || locked) return stop();
        if (abandoned) return stop();
        if (Date.now() > deadline) return stop();

        const nodes = inst.getNodes() as Node<BuilderNodeData>[];
        if (!hasMeasuredDimensions(nodes)) {
          // A node that has not painted has no size to settle on yet.
          lastSignature = null;
          stableFor = 0;
          raf = requestAnimationFrame(frame);
          return;
        }

        const signature = sizeSignature(nodes);
        stableFor = signature === lastSignature ? stableFor + 1 : 0;
        lastSignature = signature;

        if (stableFor >= STABLE_FRAMES && signature !== appliedSignature) {
          // The canvas hands the direction down as a plain string; anything that is not
          // the top-to-bottom canvas lays out left to right.
          const layoutDirection: WorkflowLayoutDirection = dir === 'vertical' ? 'vertical' : 'horizontal';
          // Edges come from the instance, inside the frame: the announcement is
          // dispatched synchronously right after the plan-sync's setNodes/setEdges, so
          // an array captured at handler entry is the PREVIOUS topology, under which the
          // node just added is an isolated component and is left on its estimate.
          const moved = relayoutOnMeasured(nodes, inst.getEdges(), layoutDirection);
          appliedSignature = signature;
          corrected = true;
          quietFrames = 0;
          if (moved.length > 0) {
            const changes: NodePositionChange[] = moved.map((m) => ({
              id: m.id,
              type: 'position',
              position: m.position,
            }));
            ref.current.onNodesChange(changes);
          }
        } else if (corrected) {
          // Give a late-loading preview room to change size, then stop rather than poll
          // to the end of the window for nothing.
          quietFrames += 1;
          if (quietFrames >= QUIET_FRAMES_AFTER_EMIT) return stop();
        }

        raf = requestAnimationFrame(frame);
      };

      raf = requestAnimationFrame(frame);
    };

    const onLayoutApplied = (event: Event) => {
      const { workflowId: id, isLocked: locked, instance: inst } = ref.current;
      if (!inst || locked) return;
      // A sibling canvas (side panel, application tab, a run view) listens too, and one
      // mounted without an id (the standalone builder route) would otherwise answer for
      // everyone. Both dispatchers always name their workflow, so an announcement that
      // does not name OURS is not ours: acting on it would move that canvas's nodes and
      // mark it dirty, on a surface where no layout ever ran.
      const detail = (event as CustomEvent<LayoutAppliedDetail>).detail;
      if (!id || !detail?.workflowId || detail.workflowId !== id) return;

      // A second announcement supersedes the first: restart the watch rather than run
      // two corrections over each other.
      cancelAnimationFrame(raf);
      stopWatching?.();
      watch(Date.now() + SETTLE_WINDOW_MS);
    };

    window.addEventListener(LAYOUT_APPLIED_EVENT, onLayoutApplied);
    return () => {
      window.removeEventListener(LAYOUT_APPLIED_EVENT, onLayoutApplied);
      cancelAnimationFrame(raf);
      stopWatching?.();
    };
  }, []);

  return null;
}
