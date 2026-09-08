/**
 * generate node - plan wiring (frontend layer of the backend contract).
 *
 * The generate config lives in the GENERIC `params` map
 * ({ model, credential_source, ...unified params }). Neither direction was
 * covered: both the export and the import could have been deleted with the
 * whole suite still green, and the field they carry is the one that decides
 * WHICH provider key runs the node and therefore whether the customer is
 * charged at all.
 *
 * The node belongs to the AI family: it is filed under `agents[]` and keyed
 * `agent:<label>`, alongside agent, classify and guardrail. Its output is
 * referenced `{{agent:make_clip.output.file}}`, so a plan that filed it under
 * `cores[]` would be read by an executor that never builds it.
 *
 * These tests pin:
 *  - export: builder data (generateModel + generateCredentialSource + generateParams) -> agents[]
 *  - import: agents[] -> builder data
 *  - roundtrip: export -> import -> export leaves the node unchanged
 */
import { describe, it, expect, vi } from 'vitest';
import type { Node } from 'reactflow';
import { processAgents } from '../agentProcessor';
import { createAgentNodes } from '../../services/workflowPlanImporter/AgentNodeCreator';
import { DEFAULT_CREDENTIAL_SOURCE } from '../generateParams';
import type { BuilderNodeData } from '../../types';

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), getTokenProvider: () => null, getAuthToken: async () => null },
}));
vi.mock('../../services/workflowPlanImporter/ToolDataService', () => ({
  ToolDataService: { fetchToolsBatch: vi.fn().mockResolvedValue(new Map()), getToolData: vi.fn() },
}));

function generateNode(extraData: Record<string, unknown>): Node<BuilderNodeData> {
  return {
    id: 'generate-1',
    type: 'flowNode',
    position: { x: 10, y: 20 },
    data: {
      id: 'generate-1',
      label: 'Make Clip',
      kind: 'generate',
      ...extraData,
    } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

function runAgentProcessor(node: Node<BuilderNodeData>) {
  const ctx: any = {
    nodes: [node],
    edges: [],
    plan: { agents: [], cores: [] },
    stepLabelMap: new Map<string, string>(),
    stepPlanByNodeId: new Map<string, any>(),
    agentPlanByNodeId: new Map<string, any>(),
  };
  processAgents(ctx);
  // Filed with the AI family, never among the cores: the executor builds it
  // from `agents[]`, so a node left in `cores[]` would be silently absent.
  expect(ctx.plan.cores).toHaveLength(0);
  const agent = ctx.plan.agents.find((a: any) => a.type === 'generate');
  expect(agent).toBeDefined();
  return agent;
}

function importAgentNode(agentNode: any) {
  const result = createAgentNodes([agentNode], 100, 100, 0);
  return result.nodes[0];
}

describe('agentProcessor - generate export (builder data -> plan params map)', () => {
  it('emits the config under the generic params map with the contract field names', () => {
    const agent = runAgentProcessor(generateNode({
      generateModel: 'seedance-2.0-fast',
      generateCredentialSource: 'user',
      generateParams: { prompt: 'a boat', duration_seconds: 10, aspect_ratio: '16:9' },
    }));

    expect(agent.params).toEqual({
      model: 'seedance-2.0-fast',
      credential_source: 'user',
      prompt: 'a boat',
      duration_seconds: 10,
      aspect_ratio: '16:9',
    });
    // No dedicated config key like download_file's `download`
    expect(agent).not.toHaveProperty('generate');
  });

  it('states the credential source even when the author never touched the control', () => {
    // Absent is not "the default" downstream, it is a DIFFERENT arrangement:
    // the author's own key first, billed by nobody, after the inspector had
    // quoted the platform price beside the node.
    const agent = runAgentProcessor(generateNode({
      generateModel: 'seedance-2.0-fast',
      generateParams: { prompt: 'a boat' },
    }));

    expect(agent.params.credential_source).toBe(DEFAULT_CREDENTIAL_SOURCE);
  });

  it('keeps numbers as real JSON types, since a stringified size changes what is billed', () => {
    const agent = runAgentProcessor(generateNode({
      generateModel: 'seedance-2.0',
      generateParams: { prompt: 'x', duration_seconds: 10, n: 2, seed: 7 },
    }));

    expect(typeof agent.params.duration_seconds).toBe('number');
    expect(agent.params.duration_seconds).toBe(10);
    expect(agent.params.n).toBe(2);
  });
});

describe('AgentNodeCreator - generate import (plan params map -> builder data)', () => {
  it('reads the model, the credential source and the remaining params back out', () => {
    const node = importAgentNode({
      id: 'agent:make_clip',
      type: 'generate',
      label: 'Make Clip',
      params: {
        model: 'seedance-2.0-fast',
        credential_source: 'user',
        prompt: 'a boat',
        duration_seconds: 10,
      },
    });

    const d = node.data as any;
    expect(d.generateModel).toBe('seedance-2.0-fast');
    expect(d.generateCredentialSource).toBe('user');
    expect(d.generateParams).toEqual({ prompt: 'a boat', duration_seconds: 10 });
  });

  it('does not invent a credential source when the plan states none', () => {
    // A hand-written plan may legitimately omit it. The importer must not
    // fabricate a value here, because that would silently rewrite somebody
    // else's plan the first time it is opened in the builder.
    const node = importAgentNode({
      id: 'agent:make_clip',
      type: 'generate',
      label: 'Make Clip',
      params: { model: 'seedance-2.0-fast', prompt: 'a boat' },
    });

    expect((node.data as any).generateCredentialSource).toBeUndefined();
  });
});

describe('generate roundtrip', () => {
  it('export -> import -> export leaves the node unchanged', () => {
    const first = runAgentProcessor(generateNode({
      generateModel: 'seedance-2.0-fast',
      generateCredentialSource: 'user',
      generateParams: { prompt: 'a boat', duration_seconds: 10 },
    }));

    const imported = importAgentNode(first);
    const second = runAgentProcessor({ ...generateNode({}), data: imported.data } as Node<BuilderNodeData>);

    expect(second.params).toEqual(first.params);
  });
});
