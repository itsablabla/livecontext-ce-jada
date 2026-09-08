import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { generateWorkflowPlan } from '../workflowPlanGenerator';

/**
 * WHICH of the author's own keys a `agent:` generate node runs on, from the
 * inspector to the saved plan.
 *
 * <p>The inspector has offered this choice since the node shipped and the
 * catalog has always been able to honour it, but the plan carried only the
 * model, the pool and the generation parameters. The picked key therefore never
 * left the browser: every run resolved the integration's DEFAULT key instead,
 * on accounts that hold several, and nothing on screen or in the result said
 * so. This is the seam where that was lost, so it is pinned here rather than
 * only at the helper it calls.
 */
function generateNode(data: Partial<BuilderNodeData> = {}): Node<BuilderNodeData> {
  return {
    id: 'generate-1',
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id: 'generate-1',
      label: 'Make Clip',
      kind: 'generate',
      generateModel: 'seedance-2.0-fast',
      generateCredentialSource: 'user',
      generateParams: { prompt: 'a boat' },
      ...data,
    } as BuilderNodeData,
  };
}

/**
 * The node is filed under `agents`, never `cores`: it belongs to the AI family
 * and is keyed `agent:<label>`, so a reader of the plan finds it beside the
 * agent, classify and guardrail nodes.
 */
function generateAgent(nodes: Node<BuilderNodeData>[]): any {
  const plan = generateWorkflowPlan(nodes, []);
  expect((plan.cores ?? []).some((core: any) => core.type === 'generate')).toBe(false);
  return (plan.agents ?? []).find((agent: any) => agent.type === 'generate');
}

describe('generateWorkflowPlan - the generate node and the key it runs on', () => {
  it('writes the pinned key into the plan, so the run uses the one the inspector shows', () => {
    const core = generateAgent([generateNode({ selectedCredentialId: 42 } as any)]);

    expect(core.params.credential_id).toBe(42);
    expect(core.params.credential_source).toBe('user');
    expect(core.params.model).toBe('seedance-2.0-fast');
  });

  it('omits the key when none is pinned, which is the account default at run time too', () => {
    // Absence is the statement, and writing a null instead would be a value
    // every reader of the plan has to interpret.
    const core = generateAgent([generateNode()]);

    expect(core.params).not.toHaveProperty('credential_id');
  });

  it('never lets the key become a generation parameter the provider would refuse', () => {
    const core = generateAgent([generateNode({ selectedCredentialId: 42 } as any)]);

    // It travels beside the model rather than among the values projected onto
    // the provider's request, which is what keeps a correctly configured call
    // from being refused for a field the author never wrote.
    expect(core.params.prompt).toBe('a boat');
    expect(Object.keys(core.params).sort())
      .toEqual(['credential_id', 'credential_source', 'model', 'prompt']);
  });
});
