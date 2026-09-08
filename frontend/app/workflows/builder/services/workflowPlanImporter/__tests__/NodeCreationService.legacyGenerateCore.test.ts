import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NodeCreationService } from '../NodeCreationService';
import { EdgeCreationService } from '../EdgeCreationService';

/**
 * A generate node saved before it joined the AI family.
 *
 * <p>Generate used to travel in the plan's `cores` array. It now travels in
 * `agents` and is keyed `agent:<label>`, and there is no compatibility path in
 * the engine: a plan still holding one under `cores` is refused at run time,
 * loudly and by design.
 *
 * <p>The BUILDER cannot answer the same way, because the builder is where the
 * plan gets fixed. When the core branch for `generate` was deleted, the
 * if/else-if chain that creates control nodes simply ran off the end for it:
 * no node, no warning, no log. The author opened the workflow, saw the node
 * gone from the canvas, and the next save regenerated the plan without it,
 * destroying the model, the prompt and the pinned key permanently.
 *
 * <p>So the import adopts it instead: the node appears as the AI node it is,
 * and saving files it where it belongs. One-way, on old data only. Nothing
 * writes a generate core any more.
 */

vi.mock('../ToolDataService', () => ({
  ToolDataService: {
    fetchToolsBatch: vi.fn().mockResolvedValue(new Map()),
    getFromBatchCache: vi.fn(() => undefined),
    fetchToolDataFromPlan: vi.fn().mockResolvedValue({}),
    fetchToolData: vi.fn().mockResolvedValue({}),
    fetchToolDataBySlug: vi.fn().mockResolvedValue({}),
    fetchDataSourceData: vi.fn().mockResolvedValue({}),
    clearCache: vi.fn(),
  },
}));

beforeEach(() => {
  vi.clearAllMocks();
});

/** The node the author configured, as a plan written before the move holds it. */
const legacyGenerateCore = {
  id: 'core:make_clip',
  type: 'generate',
  label: 'Make Clip',
  params: {
    model: 'seedance-2.0-fast',
    prompt: 'a paper boat in a rain gutter',
    duration_seconds: 5,
    credential_source: 'user',
    credential_id: 42,
  },
};

const findByLabel = (nodes: any[], label: string) =>
  nodes.find((n) => n.data?.label === label);

describe('NodeCreationService - a generate node still filed under cores', () => {
  it('creates the node instead of dropping it, so an old workflow still opens whole', async () => {
    const plan: any = { triggers: [], mcps: [], agents: [], cores: [legacyGenerateCore], edges: [] };

    const { nodes } = await NodeCreationService.createNodes(plan);

    const node = findByLabel(nodes, 'Make Clip');
    expect(node, 'the node vanished from the canvas with nothing said').toBeDefined();
    expect(node.data.kind).toBe('generate');
  });

  it('keeps every configured value, since the next save is what rewrites the plan', async () => {
    // This is the part that made the silence expensive: whatever the import
    // drops here is gone from the file the next save writes.
    const plan: any = { triggers: [], mcps: [], agents: [], cores: [legacyGenerateCore], edges: [] };

    const { nodes } = await NodeCreationService.createNodes(plan);
    const data = findByLabel(nodes, 'Make Clip').data;

    expect(data.generateModel).toBe('seedance-2.0-fast');
    expect(data.generateParams.prompt).toBe('a paper boat in a rain gutter');
    expect(data.generateParams.duration_seconds).toBe(5);
    expect(data.generateCredentialSource).toBe('user');
    expect(data.selectedCredentialId, 'the pinned key decides which account is billed').toBe(42);
  });

  it('is addressable by label, so the edges of the old plan still find it', async () => {
    // Its edges say core:make_clip. Edge resolution falls back to the label map
    // when no CORE node matches, and the adopted node registers under both its
    // label and the normalized form.
    const plan: any = { triggers: [], mcps: [], agents: [], cores: [legacyGenerateCore], edges: [] };

    const { labelToNodeIdMap } = await NodeCreationService.createNodes(plan);

    expect(labelToNodeIdMap.get('Make Clip')).toBeDefined();
    expect(labelToNodeIdMap.get('make_clip')).toBeDefined();
  });

  it('leaves the other cores of the same plan alone', async () => {
    // The adoption filters the cores array, so the risk it carries is dropping
    // a neighbour rather than only the generate entry.
    const plan: any = {
      triggers: [],
      mcps: [],
      agents: [],
      cores: [
        legacyGenerateCore,
        { id: 'core:pause', type: 'wait', label: 'Pause', params: { seconds: 3 } },
      ],
      edges: [],
    };

    const { nodes } = await NodeCreationService.createNodes(plan);

    expect(findByLabel(nodes, 'Pause'), 'an unrelated core must survive the filter').toBeDefined();
    expect(findByLabel(nodes, 'Make Clip')).toBeDefined();
  });

  it('creates one node, not two, when the plan already files it correctly', async () => {
    // A current plan holds it under agents. The adoption must not also find it
    // in cores and add it a second time.
    const plan: any = {
      triggers: [],
      mcps: [],
      agents: [{ ...legacyGenerateCore, id: 'agent:make_clip' }],
      cores: [],
      edges: [],
    };

    const { nodes } = await NodeCreationService.createNodes(plan);

    expect(nodes.filter((n: any) => n.data?.label === 'Make Clip')).toHaveLength(1);
  });

  it('an edge naming the OLD key still lands on the adopted node', async () => {
    // The half that makes the repair worth doing, and the one the backend twin
    // asserts. It resolves only because isCoreNode is false for the node now, so
    // edge resolution reaches the alias-map fallback: tighten that fallback and
    // the adopted node loses its edges on the canvas, and the next save writes
    // them away with nothing failing.
    const plan: any = {
      triggers: [{ id: 'trigger:start', type: 'manual', label: 'Start' }],
      mcps: [], agents: [], cores: [legacyGenerateCore],
      edges: [{ from: 'trigger:start', to: 'core:make_clip' }],
    };

    const { nodes, labelToNodeIdMap } = await NodeCreationService.createNodes(plan);
    const generateNode = findByLabel(nodes, 'Make Clip');
    expect(generateNode, 'the node must exist for an edge to land on it').toBeDefined();

    const { edges } = EdgeCreationService.createEdges(
      plan, nodes, labelToNodeIdMap, new Map(), new Map(), new Map());

    expect(
      edges.some((e: any) => e.target === generateNode.id),
      'the edge was dropped, so nothing ever reaches the node at run time',
    ).toBe(true);
  });
});
