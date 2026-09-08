import { describe, expect, it } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import type { PlanGateContext, ValidationContext } from '../core/types';
import { PlanAvailabilityRule } from '../rules-v2/PlanAvailabilityRule';
import { buildValidationCache } from '../core/ValidationCache';

const GATE: PlanGateContext = {
  planCode: 'FREE',
  requirements: {
    'tool:instagram-publish-media': 'STARTER',
    'api:snapchat': 'TEAM',
    'node:browser_agent': 'PRO',
  },
};

function node(id: string, data: Partial<BuilderNodeData>): Node<BuilderNodeData> {
  return { id, position: { x: 0, y: 0 }, data: { id, label: id, ...data } as BuilderNodeData } as Node<BuilderNodeData>;
}

function run(nodes: Node<BuilderNodeData>[], planGate?: PlanGateContext) {
  const edges: never[] = [];
  const context: ValidationContext = {
    nodes,
    edges,
    cache: buildValidationCache(nodes, edges),
    planGate,
  };
  return new PlanAvailabilityRule().validate(context);
}

const mcpNode = (id: string, toolSlug?: string, apiSlug?: string) =>
  node(id, {
    label: id,
    kind: 'tool',
    ...(toolSlug ? { toolData: { toolSlug, apiSlug } } : {}),
    ...(apiSlug ? { apiData: { apiSlug } } : {}),
  } as Partial<BuilderNodeData>);

describe('PlanAvailabilityRule', () => {
  it('flags an endpoint the plan does not include, naming the plan that does', () => {
    const result = run([mcpNode('Publish', 'instagram-publish-media', 'instagram')], GATE);

    expect(result.issues).toHaveLength(1);
    expect(result.issues[0].severity).toBe('error');
    expect(result.issues[0].message).toContain('STARTER');
    expect(result.issues[0].context?.requiredPlan).toBe('STARTER');
  });

  it('flags the node picked through the integration, which is how the block was bypassed', () => {
    // Dragging the integration gives a node with only an apiSlug; choosing the
    // endpoint in the inspector adds the toolSlug. Reading the CURRENT identity
    // is the whole point: keyed off what was dragged, this node looked free.
    const dragged = mcpNode('Instagram', undefined, 'instagram');
    expect(run([dragged], GATE).issues).toHaveLength(0);

    const configured = mcpNode('Instagram', 'instagram-publish-media', 'instagram');
    expect(run([configured], GATE).issues).toHaveLength(1);
  });

  it('leaves the other endpoints of the same integration alone', () => {
    // Gating publishing must not gate reading: they live on one API.
    expect(run([mcpNode('Read', 'instagram-get-media', 'instagram')], GATE).issues).toHaveLength(0);
  });

  it('applies an API-wide requirement to any of its endpoints', () => {
    expect(run([mcpNode('Ad', 'snapchat-create-ad', 'snapchat')], GATE).issues).toHaveLength(1);
  });

  it('prefers the endpoint requirement over its API when both exist', () => {
    const gate: PlanGateContext = {
      planCode: 'FREE',
      requirements: { 'api:instagram': 'STARTER', 'tool:instagram-publish-media': 'TEAM' },
    };
    const result = run([mcpNode('Publish', 'instagram-publish-media', 'instagram')], gate);
    expect(result.issues[0].context?.requiredPlan).toBe('TEAM');
  });

  it('says nothing for a plan that already covers the node', () => {
    const paid: PlanGateContext = { ...GATE, planCode: 'TEAM' };
    expect(run([mcpNode('Publish', 'instagram-publish-media', 'instagram')], paid).issues).toHaveLength(0);
  });

  it('says NOTHING while the gate is unknown, rather than guessing', () => {
    // Loading, a failed request and a CE build all arrive here as `undefined`.
    // Marking a node the account owns is worse than marking nothing.
    expect(run([mcpNode('Publish', 'instagram-publish-media', 'instagram')], undefined).issues).toHaveLength(0);
  });

  it('is not critical, so a restricted node never makes the workflow unsaveable', () => {
    // The user may legitimately build now and subscribe before running.
    expect(new PlanAvailabilityRule().isCritical).toBe(false);
  });

  it('treats a requirement of FREE as no requirement', () => {
    const gate: PlanGateContext = { planCode: 'FREE', requirements: { 'api:instagram': 'FREE' } };
    expect(run([mcpNode('Publish', 'instagram-get-media', 'instagram')], gate).issues).toHaveLength(0);
  });
});
