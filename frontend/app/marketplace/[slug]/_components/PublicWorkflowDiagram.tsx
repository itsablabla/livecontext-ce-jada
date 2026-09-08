'use client';

import { useCallback, useMemo, useRef } from 'react';
import ReactFlow, { Background, BackgroundVariant, Controls, Handle, Position } from 'reactflow';
import type { Edge, Node, NodeProps, ReactFlowInstance } from 'reactflow';
import 'reactflow/dist/style.css';

import { NodeHeader } from '@/app/workflows/builder/components/nodes/shared';
import { applyDagreLayout, estimateNodeWidth } from '@/app/workflows/builder/services/LayoutService';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import type { PublicGraph } from '@/lib/marketplace/publicPlanGraph';
import type { NodeIconData } from '@/lib/api/orchestrator/types';

/**
 * Read-only picture of a published workflow, for the crawlable listing page.
 *
 * <p><b>What it is not.</b> This is not the builder canvas and must not become
 * it. The real canvas (`WorkflowRunCanvas` under `WorkflowModeProvider`) pulls
 * in next-intl, the workflow-mode and run contexts, the org store and a
 * websocket event bridge - none of which an anonymous visitor on a page outside
 * the app's provider tree can supply. What a visitor needs here is one question
 * answered, "what does this thing actually do", so this draws the SHAPE of the
 * run: the same node glyphs the platform uses, in the same left-to-right
 * reading order, connected the same way.
 *
 * <p><b>Live, not a picture.</b> The canvas pans, zooms and lets nodes be picked
 * up and moved, exactly like the platform's own, because a 37-node workflow is
 * unreadable in a fixed 420px frame: exploring it IS how a visitor understands
 * what the thing does. The one interaction deliberately left off is wheel-zoom,
 * which on an ordinary web page swallows the scroll a visitor meant for the
 * document; the zoom controls do that job explicitly instead.
 *
 * <p>Read-only stays true in the sense that matters: nothing here writes. There
 * are no editing affordances, no node menus, and dragging a node moves it in
 * this browser only, since the page has nowhere to save to and no session.
 *
 * <p><b>Positions.</b> A published plan is position-stripped, so coordinates are
 * computed here with the platform's own Dagre pass rather than invented, which
 * is what makes the picture read like a canvas from the app.
 */

interface PublicNodeData extends Record<string, unknown> {
  label: string;
  icon: NodeIconData;
}

/**
 * The real node geometry, taken from `FlowNode` - the component the canvas
 * actually mounts for an ordinary step (`constants/graphTypes.ts` maps
 * `flowNode` to it). `WorkflowNode.tsx` looks like the obvious reference and is
 * NOT in that registry, which is the whole reason this diagram first came out
 * wrong: it is squarer (`rounded-2xl`, 16px) and tighter (`p-3`) than what the
 * builder draws.
 *
 * A real node is `rounded-[28px]` with `px-5 py-4`, and its width is not fixed:
 * it starts at 200px and GROWS with the label rather than truncating it.
 */
const NODE_MIN_WIDTH = 200;
/** `py-4` (16 top and bottom) around a 44px `lg` icon row, plus `border-2` each side. */
const NODE_HEIGHT = 16 + 44 + 16 + 2 * 2;

/** Breathing room between the graph bounding box and the figure edges, in px. */
const PADDING = 24;
/** Never open zoomed past 1:1, and never below a zoom where labels can be read. */
const MAX_INITIAL_ZOOM = 1;
const MIN_INITIAL_ZOOM = 0.45;

/**
 * The same arrowhead the builder draws. Its edges point at `url(#arrow-default)`,
 * a marker defined by the canvas that owns them, so a canvas which does not
 * define one renders lines with no heads and loses the direction of the flow.
 */
const ARROW_MARKER_ID = 'lc-public-arrow';

/**
 * One node, drawn the way the platform draws it.
 *
 * <p>Deliberately NOT a simplified lookalike. It reuses `NodeHeader`, the very
 * component the builder mounts inside `WorkflowNode`, so the icon tile, its
 * resolution from the node type, the gap and the label typography cannot drift
 * from the app. Only the chrome around it is restated, from `WorkflowNode.tsx`:
 * `rounded-2xl`, a 2px border in the neutral colour a node with no run status
 * carries, and the white / gray-800 card.
 *
 * <p>What is left out is only what a published, non-executing plan has nothing
 * to say about: the run-status border colour, the status badge, the shimmer of
 * a running node, and the hover action bar.
 */
function PublicDiagramNode({ data }: NodeProps<PublicNodeData>) {
  return (
    <div
      className="group relative rounded-[28px] border-2 bg-white/95 px-5 py-4 backdrop-blur dark:bg-gray-800/95"
      style={{
        minWidth: NODE_MIN_WIDTH,
        // The neutral border `getStatusBorderColor` returns for a node with no
        // status, which is every node here: nothing has run.
        borderColor: 'var(--border-color)',
        borderStyle: 'solid',
      }}
    >
      {/* Anchors only: ReactFlow attaches edges to handles, so a node without
          them draws no connections at all. Same chrome as the builder handles,
          and not connectable: the graph can be explored, never rewired. */}
      <Handle
        type="target"
        position={Position.Left}
        isConnectable={false}
        className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
        style={{ backgroundColor: 'var(--border-color)' }}
      />

      <NodeHeader
        label={data.label}
        nodeId={data.icon.nodeId}
        nodeKind={data.icon.nodeKind as never}
        nodeFamily={data.icon.isMcp ? 'mcp' : undefined}
        iconSlug={data.icon.iconSlug}
      />

      <Handle
        type="source"
        position={Position.Right}
        isConnectable={false}
        className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
        style={{ backgroundColor: 'var(--border-color)' }}
      />
    </div>
  );
}

// Declared once at module scope: a nodeTypes object rebuilt on every render
// makes ReactFlow remount every node.
const NODE_TYPES = { publicNode: PublicDiagramNode };

export interface PublicWorkflowDiagramProps {
  graph: PublicGraph;
  /** Height of the drawing area. The graph is fitted inside it. */
  className?: string;
}

export default function PublicWorkflowDiagram({ graph, className }: PublicWorkflowDiagramProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);

  const { nodes, edges } = useMemo(() => {
    const rfEdges: Edge[] = graph.edges.map((edge) => ({
      id: `${edge.from}->${edge.to}`,
      source: edge.from,
      target: edge.to,
      // The builder defaults for an edge with no run status, from
      // `BuilderEdge.tsx`: a bezier curve (its `connectionType` default), the
      // neutral stroke at 1.6px, and the default arrowhead. A smoothstep at 2px
      // is a different diagram language, which is what made this read as
      // another product canvas.
      type: 'default',
      markerEnd: `url(#${ARROW_MARKER_ID})`,
      style: { stroke: 'var(--border-color)', strokeWidth: 1.6 },
    }));

    const rfNodes = graph.nodes.map((node) => ({
      id: node.key,
      type: 'publicNode',
      position: { x: 0, y: 0 },
      // The size the card will actually take. `estimateNodeWidth` is the very
      // function the builder layout uses for the same card, so the boxes Dagre
      // spaces are the boxes drawn. Declared up front rather than left for
      // ReactFlow to measure, because the initial framing runs before a custom
      // node has been measured: against unknown sizes it frames on an arbitrary
      // corner at an arbitrary zoom.
      width: estimateNodeWidth(node.label, NODE_MIN_WIDTH),
      height: NODE_HEIGHT,
      data: { label: node.label, icon: node.icon },
    }));

    // Cast at the boundary: the layout is typed for builder nodes, but it reads
    // only `data.label` and the node type, both of which are supplied here.
    const laidOut = applyDagreLayout(
      rfNodes as unknown as Node<BuilderNodeData>[],
      rfEdges,
    ) as unknown as Node<PublicNodeData>[];

    return { nodes: laidOut, edges: rfEdges };
  }, [graph]);

  /**
   * Frame the first paint ourselves, on the workflow's ENTRY POINT.
   *
   * <p>Two reasons this does not use the `fitView` prop. It CENTRES the graph,
   * so a long workflow whose zoom was floored for legibility opens in the
   * MIDDLE of itself: the visitor meets a fragment with edges running off both
   * sides and no clue the start is somewhere to the left. And correcting it
   * afterwards is a race, since fitView settles the viewport on its own
   * schedule and simply overwrites whatever was set first.
   *
   * <p>So the viewport is computed here instead, once, from the laid-out
   * bounds: the same "fit, but never below a readable zoom" rule, applied with
   * the graph's top-left corner pinned into view rather than its centre.
   */
  const frameOnEntry = useCallback(
    (instance: ReactFlowInstance) => {
      const box = containerRef.current?.getBoundingClientRect();
      if (!box || box.width === 0 || nodes.length === 0) return;

      const minX = Math.min(...nodes.map((n) => n.position.x));
      const minY = Math.min(...nodes.map((n) => n.position.y));
      const maxX = Math.max(...nodes.map((n) => n.position.x + (n.width ?? NODE_MIN_WIDTH)));
      const maxY = Math.max(...nodes.map((n) => n.position.y + (n.height ?? NODE_HEIGHT)));

      const scale = Math.min(
        (box.width - PADDING * 2) / Math.max(maxX - minX, 1),
        (box.height - PADDING * 2) / Math.max(maxY - minY, 1),
      );
      // The floor is the point: a long workflow fits this figure only at a zoom
      // where every label is an unreadable smudge, and a diagram nobody can
      // read says nothing. Below the floor the visitor pans instead, or zooms
      // out with the controls (the canvas allows down to `minZoom`).
      const zoom = Math.min(MAX_INITIAL_ZOOM, Math.max(MIN_INITIAL_ZOOM, scale));

      instance.setViewport({ x: PADDING - minX * zoom, y: PADDING - minY * zoom, zoom });
    },
    [nodes],
  );

  if (nodes.length === 0) return null;

  return (
    <div ref={containerRef} className={className}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        onInit={frameOnEntry}
        // Explorable like the platform's canvas: drag to pan, drag a node, pick
        // one out, pinch or double-click to zoom.
        nodesDraggable
        elementsSelectable
        panOnDrag
        zoomOnPinch
        zoomOnDoubleClick
        minZoom={0.1}
        // Nothing can be rewired: the plan is published, this page cannot save.
        nodesConnectable={false}
        // Wheel-zoom is the one interaction left off. On a full-screen builder it
        // is the natural gesture; inside a figure on a scrolling page it eats the
        // scroll the visitor meant for the document, trapping them on the canvas.
        // `preventScrolling={false}` keeps the wheel going to the page, and the
        // controls below offer zoom explicitly.
        zoomOnScroll={false}
        panOnScroll={false}
        preventScrolling={false}
        proOptions={{ hideAttribution: true }}
      >
        {/* Same marker geometry the builder defines for `arrow-default`. */}
        <svg style={{ position: 'absolute', width: 0, height: 0 }}>
          <defs>
            <marker
              id={ARROW_MARKER_ID}
              viewBox="0 0 10 10"
              refX="8"
              refY="5"
              markerWidth="5"
              markerHeight="5"
              orient="auto-start-reverse"
            >
              <path d="M 0 0 L 10 5 L 0 10 z" style={{ fill: 'var(--border-color)' }} />
            </marker>
          </defs>
        </svg>
        <Background variant={BackgroundVariant.Dots} gap={16} size={1} color="var(--border-color)" />
        {/* No interactivity toggle: it locks the canvas, which is the state this
            page just moved away from. */}
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}
