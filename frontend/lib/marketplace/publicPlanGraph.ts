/**
 * Turns the sanitized plan a public marketplace listing carries into a flat
 * graph the public pages can draw.
 *
 * <p>Why this exists as its own pure module: the crawlable listing page renders
 * outside the app's provider tree, so it cannot mount the builder's graph
 * pipeline (`WorkflowPlanImporter` enriches each tool through the AUTHENTICATED
 * `/workflow-inspector/tools/*` endpoints, which an anonymous visitor cannot
 * call). The published plan already carries every node's label and type, which
 * is all a read-only visualization needs, so the conversion is a pure function
 * over that payload, testable without React, network or auth.
 *
 * <p>Two properties of the published plan drive the whole design:
 *
 * <ol>
 *   <li><b>Edges reference NORMALIZED LABEL KEYS, not node ids.</b> A node
 *       carries {@code id: "6ff4a548-..."} while the edge pointing at it says
 *       {@code "mcp:generate_image_1"}. So every node's key is derived through
 *       the shared label normalizer (the same rules as the backend
 *       `LabelNormalizer`), never invented here. Cores are the exception: their
 *       id ALREADY is the key, so it is used verbatim.</li>
 *   <li><b>Positions are stripped</b> ({@code position: {}} on every node), so
 *       the caller must lay the graph out itself. Nothing here emits
 *       coordinates.</li>
 * </ol>
 *
 * <p>Defensive like `mapPublication`: these pages render whatever the
 * marketplace happens to contain, including plans written before any given
 * field existed. A node that cannot be keyed is dropped, and an edge whose
 * endpoints do not both resolve is dropped, rather than drawing a graph with
 * dangling arrows.
 */
import {
  agentKey,
  coreKey,
  extractPortFromRef,
  interfaceKey,
  mcpKey,
  tableKey,
  triggerKey,
} from '@/app/workflows/builder/utils/labelNormalizer';
import type { NodeIconData } from '@/lib/api/orchestrator/types';

export type PublicGraphFamily = 'trigger' | 'mcp' | 'core' | 'agent' | 'table' | 'interface';

export interface PublicGraphNode {
  /** Normalized key the edges address this node by, e.g. `mcp:generate_image_1`. */
  key: string;
  label: string;
  family: PublicGraphFamily;
  /** The plan's own `type` for the node (`form`, `transform`, `loop`, ...). */
  type: string;
  /**
   * Props for the builder's `NodeIcon`, so the public canvas shows the SAME
   * glyph the platform draws for that node type instead of a second icon set.
   * Shaped exactly like the `nodeIcons` the backend emits for cards.
   */
  icon: NodeIconData;
}

export interface PublicGraphEdge {
  from: string;
  to: string;
}

export interface PublicGraph {
  nodes: PublicGraphNode[];
  edges: PublicGraphEdge[];
}

const EMPTY_GRAPH: PublicGraph = { nodes: [], edges: [] };

/**
 * Plan sections that take part in the flow, with the key builder each one uses.
 *
 * `notes` is deliberately absent: a note is a canvas annotation, no edge ever
 * references one, and drawing them would add free text to a diagram whose point
 * is the shape of the run.
 */
const SECTIONS: ReadonlyArray<{
  field: string;
  family: PublicGraphFamily;
  toKey: (label: string | null | undefined) => string | null;
}> = [
  { field: 'triggers', family: 'trigger', toKey: triggerKey },
  { field: 'mcps', family: 'mcp', toKey: mcpKey },
  { field: 'cores', family: 'core', toKey: coreKey },
  { field: 'agents', family: 'agent', toKey: agentKey },
  { field: 'tables', family: 'table', toKey: tableKey },
  { field: 'interfaces', family: 'interface', toKey: interfaceKey },
];

function asString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

/**
 * Icon props per family, mirroring the slugs the backend already emits in a
 * publication's `nodeIcons` (`form-trigger`/`entry`, `transform`/`transform`, ...)
 * so both surfaces resolve through the same `NODE_ICON_REGISTRY` entries.
 * `resolveNodeIcon` degrades to a default glyph for an unknown slug, so a node
 * type added later renders as a generic node rather than breaking the canvas.
 */
function iconFor(family: PublicGraphFamily, type: string): NodeIconData {
  switch (family) {
    case 'trigger':
      return { nodeId: `${type}-trigger`, nodeKind: 'entry' };
    // The `mcp-` prefix is what resolveNodeIcon keys the tool glyph off. The real
    // brand logos live in the publication's own `nodeIcons` and are shown as an
    // integrations row, because the plan carries no per-node icon slug.
    case 'mcp':
      return { nodeId: 'mcp-tool', nodeKind: 'mcp', isMcp: true };
    case 'interface':
      return { nodeId: 'interface', nodeKind: 'interface' };
    default:
      return { nodeId: type, nodeKind: type };
  }
}

/**
 * The key an edge would use for this node. A core's id already IS its key
 * (`core:compose_prompts`), so it wins verbatim: re-deriving it from the label
 * would round-trip through normalization for nothing, and would disagree with
 * the wiring for a label the publisher renamed after connecting the node.
 */
function keyFor(
  raw: Record<string, unknown>,
  family: PublicGraphFamily,
  toKey: (label: string | null | undefined) => string | null,
): string | null {
  const id = asString(raw.id);
  if (id && id.startsWith(`${family}:`)) return id;
  return toKey(asString(raw.label));
}

/**
 * Strip a trailing port from an edge reference so it addresses the NODE.
 * `core:check:if` and `agent:sorter:category_0` both point at their node; the
 * port only says which output the flow leaves by, which a static picture does
 * not draw. Uses the shared port vocabulary rather than cutting at the last
 * colon, so a label that legitimately contains one survives.
 */
function refToNodeKey(ref: unknown): string | null {
  const raw = asString(ref);
  if (!raw) return null;
  const withoutFragment = raw.split('#')[0];
  const port = extractPortFromRef(withoutFragment);
  if (!port) return withoutFragment;
  return withoutFragment.slice(0, -(port.length + 1));
}

/**
 * Build the drawable graph from a publication's `planSnapshot`.
 *
 * @param plan the sanitized plan as the public payload carries it
 * @returns nodes in plan order (triggers first) and the edges that connect them
 */
export function buildPublicGraph(plan: unknown): PublicGraph {
  if (typeof plan !== 'object' || plan === null) return EMPTY_GRAPH;
  const sections = plan as Record<string, unknown>;

  const nodes: PublicGraphNode[] = [];
  const seen = new Set<string>();

  for (const { field, family, toKey } of SECTIONS) {
    const list = sections[field];
    if (!Array.isArray(list)) continue;

    for (const entry of list) {
      if (typeof entry !== 'object' || entry === null) continue;
      const raw = entry as Record<string, unknown>;

      const key = keyFor(raw, family, toKey);
      // A node with neither a usable label nor a key-shaped id cannot be
      // addressed by any edge, so it would float unconnected. Drop it.
      if (!key || seen.has(key)) continue;
      seen.add(key);

      const type = asString(raw.type) ?? family;
      nodes.push({
        key,
        label: asString(raw.label) ?? key,
        family,
        type,
        icon: iconFor(family, type),
      });
    }
  }

  const edges: PublicGraphEdge[] = [];
  const edgeSeen = new Set<string>();
  const rawEdges = sections.edges;

  if (Array.isArray(rawEdges)) {
    for (const entry of rawEdges) {
      if (typeof entry !== 'object' || entry === null) continue;
      const raw = entry as Record<string, unknown>;

      const from = refToNodeKey(raw.from);
      const to = refToNodeKey(raw.to);
      // Both ends must exist as drawn nodes. A half-resolved edge would render
      // as an arrow into nothing, which reads as a broken workflow.
      if (!from || !to || !seen.has(from) || !seen.has(to)) continue;

      // Several ports of one decision can reach the same target; the picture
      // draws one line.
      const id = `${from}->${to}`;
      if (edgeSeen.has(id)) continue;
      edgeSeen.add(id);
      edges.push({ from, to });
    }
  }

  return { nodes, edges };
}
