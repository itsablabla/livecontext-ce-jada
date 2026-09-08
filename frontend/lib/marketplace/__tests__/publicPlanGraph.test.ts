import { describe, expect, it } from 'vitest';
import { buildPublicGraph } from '../publicPlanGraph';

/**
 * The fixture mirrors the SHAPE a real published plan has, which is the whole
 * point of these tests: every field here was read off the live payload of a
 * production listing (`/api/publications/by-slug/xai-video-sequence`).
 *
 * The two traps it encodes, both of which silently empty the diagram if the
 * builder gets them wrong:
 *   - a node's `id` is a UUID while the edge pointing at it uses the
 *     NORMALIZED LABEL KEY (`mcp:generate_image_1`),
 *   - loop and decision refs carry a PORT (`core:poll:iterate`) that addresses
 *     an output, not a different node.
 */
function livePlan() {
  return {
    triggers: [
      { position: {}, type: 'form', id: '8bdfbb03-5b07-4a84-801e-fe6845bf9f97', label: 'New Theme' },
    ],
    mcps: [
      { position: {}, type: 'mcp', id: '6ff4a548-880b-4efe-84d9-8aed2ae66b60', label: 'Generate Image 1' },
    ],
    cores: [
      { position: {}, type: 'transform', label: 'Compose Prompts', id: 'core:compose_prompts' },
      { position: {}, type: 'loop', label: 'Poll 2', id: 'core:poll_2' },
    ],
    interfaces: [
      { id: '257ad479-4521-4548-8279-ff0ce5672ec6', label: 'Film', position: {} },
    ],
    edges: [
      { from: 'trigger:new_theme', to: 'core:compose_prompts' },
      { from: 'core:compose_prompts', to: 'mcp:generate_image_1' },
      { from: 'mcp:generate_image_1', to: 'core:poll_2' },
      { from: 'core:poll_2:exit', to: 'interface:film' },
    ],
  };
}

describe('buildPublicGraph', () => {
  it('keys a node by its normalized label, because that is what edges address', () => {
    const { nodes } = buildPublicGraph(livePlan());

    // The node's own id is a UUID; nothing in the plan points at that UUID.
    const generateImage = nodes.find((n) => n.label === 'Generate Image 1');
    expect(generateImage?.key).toBe('mcp:generate_image_1');
  });

  it('keeps a core id verbatim, since a core id already IS its key', () => {
    const { nodes } = buildPublicGraph(livePlan());

    expect(nodes.find((n) => n.label === 'Compose Prompts')?.key).toBe('core:compose_prompts');
  });

  it('resolves every edge of a real plan, ports included', () => {
    const { edges } = buildPublicGraph(livePlan());

    // 4 in, 4 out: a single unresolved endpoint would silently drop a link and
    // leave the diagram showing a workflow that falls apart halfway.
    expect(edges).toHaveLength(4);
    expect(edges).toContainEqual({ from: 'trigger:new_theme', to: 'core:compose_prompts' });
  });

  it('strips a loop port so the edge lands on the loop node itself', () => {
    const { edges } = buildPublicGraph(livePlan());

    // `core:poll_2:exit` is the loop's exit PORT, not a second node.
    expect(edges).toContainEqual({ from: 'core:poll_2', to: 'interface:film' });
    expect(edges.some((e) => e.from.includes(':exit'))).toBe(false);
  });

  it('does not mistake a colon inside a label for a port', () => {
    const graph = buildPublicGraph({
      cores: [{ type: 'transform', label: 'Step: cleanup', id: 'core:step_cleanup' }],
      triggers: [{ type: 'manual', id: 't1', label: 'Go' }],
      edges: [{ from: 'trigger:go', to: 'core:step_cleanup' }],
    });

    // `cleanup` is not a known port, so the ref must survive whole.
    expect(graph.edges).toEqual([{ from: 'trigger:go', to: 'core:step_cleanup' }]);
  });

  it('drops an edge whose endpoint is not a drawn node', () => {
    const graph = buildPublicGraph({
      triggers: [{ type: 'manual', id: 't1', label: 'Go' }],
      cores: [{ type: 'transform', id: 'core:keep', label: 'Keep' }],
      edges: [
        { from: 'trigger:go', to: 'core:keep' },
        { from: 'core:keep', to: 'core:deleted_node' },
      ],
    });

    // An arrow into nothing reads as a broken workflow, so it is not drawn.
    expect(graph.edges).toEqual([{ from: 'trigger:go', to: 'core:keep' }]);
  });

  it('draws one line when several ports of one node reach the same target', () => {
    const graph = buildPublicGraph({
      cores: [
        { type: 'decision', id: 'core:check', label: 'Check' },
        { type: 'transform', id: 'core:done', label: 'Done' },
      ],
      edges: [
        { from: 'core:check:if', to: 'core:done' },
        { from: 'core:check:else', to: 'core:done' },
      ],
    });

    expect(graph.edges).toEqual([{ from: 'core:check', to: 'core:done' }]);
  });

  it('gives each family the icon slugs the platform registry already knows', () => {
    const { nodes } = buildPublicGraph(livePlan());
    const byLabel = (label: string) => nodes.find((n) => n.label === label);

    // Same slugs the backend emits in a publication's own `nodeIcons`, so both
    // surfaces resolve through the same registry entries.
    expect(byLabel('New Theme')?.icon).toEqual({ nodeId: 'form-trigger', nodeKind: 'entry' });
    expect(byLabel('Compose Prompts')?.icon).toEqual({ nodeId: 'transform', nodeKind: 'transform' });
    expect(byLabel('Generate Image 1')?.icon).toEqual({
      nodeId: 'mcp-tool',
      nodeKind: 'mcp',
      isMcp: true,
    });
  });

  it('orders nodes with the trigger first, so the diagram reads from its entry point', () => {
    const { nodes } = buildPublicGraph(livePlan());

    expect(nodes[0].family).toBe('trigger');
  });

  it('leaves notes out of the flow diagram', () => {
    const graph = buildPublicGraph({
      cores: [{ type: 'transform', id: 'core:a', label: 'A' }],
      notes: [{ id: 'note:hello', label: 'Remember to set the key' }],
      edges: [],
    });

    expect(graph.nodes.map((n) => n.key)).toEqual(['core:a']);
  });

  it('drops a node that no edge could ever address', () => {
    const graph = buildPublicGraph({
      // No label and no key-shaped id: nothing can point at it.
      cores: [{ type: 'transform', id: 'e2f0f3aa-0000-4000-8000-000000000001' }],
    });

    expect(graph.nodes).toEqual([]);
  });

  it('survives a payload that is not a plan at all', () => {
    // These pages render whatever the marketplace contains, including rows
    // written before any given field existed.
    expect(buildPublicGraph(null)).toEqual({ nodes: [], edges: [] });
    expect(buildPublicGraph('nope')).toEqual({ nodes: [], edges: [] });
    expect(buildPublicGraph({ cores: 'not-an-array', edges: 7 })).toEqual({ nodes: [], edges: [] });
  });
});
