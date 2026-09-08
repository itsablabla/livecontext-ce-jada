import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../../types';
import { collectDeclaredParams, planEntryForNode } from '../declaredParams';
import { buildParamAlignment } from '../runParamAlignment';

// The palette id is what nodeRegistry keys node-type detection on, so a
// fixture that omits it is not the node the builder would produce.
function node(partial: Record<string, unknown>, id = 'filter-1', type = 'filterNode'): Node<BuilderNodeData> {
  return {
    id,
    type,
    position: { x: 0, y: 0 },
    data: { id, kind: 'core', ...partial } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

describe('planEntryForNode', () => {
  it('finds the entry the generator emits for a lone node', () => {
    const entry = planEntryForNode(
      node({
        label: 'Filter Ops',
        filterConditions: [{ field: 'category', operator: 'equals', value: 'ops' }],
        filterMode: 'and',
        filterInput: '{{core:x.output.items}}',
      }),
    );
    expect(entry?.type).toBe('filter');
    expect(entry?.label).toBe('Filter Ops');
  });

  it('returns null rather than throwing for a node the generator emits nothing for', () => {
    expect(planEntryForNode(node({ label: 'A note' }, 'note-1', 'noteNode'))).toBeNull();
  });
});

describe('collectDeclaredParams', () => {
  it('flattens the typed block and the flat params into one key set', () => {
    const declared = collectDeclaredParams(
      node({
        label: 'Filter Ops',
        filterConditions: [{ field: 'category', operator: 'equals', value: 'ops' }],
        filterMode: 'and',
        filterInput: '{{core:x.output.items}}',
      }),
    );
    // `conditions` and `mode` come from the typed `filter` block, `input` from params.
    expect(Object.keys(declared).sort()).toEqual(['conditions', 'input', 'mode']);
  });

  it('never reports the entry\'s own metadata as a configured parameter', () => {
    const declared = collectDeclaredParams(
      node({ label: 'Limit Top', limitCount: 2, limitFrom: 'first', limitOffset: 0 }, 'limit-1', 'limitNode'),
    );
    for (const metadataKey of ['id', 'graphNodeId', 'type', 'label', 'position']) {
      expect(declared).not.toHaveProperty(metadataKey);
    }
  });

  it('is empty for a null node instead of throwing', () => {
    expect(collectDeclaredParams(null)).toEqual({});
  });

  it('lines a real filter node up against what the backend reports for it', () => {
    // The keys FilterNode echoes under resolved_params (verified live).
    const reported = { input: '{{core:x.output.items}}', conditions: [], mode: 'and', input_count: 3 };
    const declared = collectDeclaredParams(
      node({
        label: 'Filter Ops',
        filterConditions: [{ field: 'category', operator: 'equals', value: 'ops' }],
        filterMode: 'and',
        filterInput: '{{core:x.output.items}}',
      }),
    );
    expect(buildParamAlignment(declared, reported).mismatches).toEqual([]);
  });
});

/**
 * The control cores are where the mismatch banner would cry wolf: their plan
 * entry names a structural block (`decisionConditions`, `switchCases`, …) while
 * the node reports the resolved values. These pin declared-vs-reported for each
 * of them against the keys the backend actually emits, so a rename on either
 * side surfaces here rather than as an amber warning on a healthy run.
 */
describe('control cores: declared vs reported', () => {
  function alignmentFor(
    nodeFixture: Node<BuilderNodeData>,
    reported: Record<string, unknown>,
    nodeType: string,
  ) {
    return buildParamAlignment(collectDeclaredParams(nodeFixture), reported, nodeType);
  }

  it('a decision reports its branches, not the structural condition list', () => {
    const decision = node(
      {
        label: 'Route',
        decisionConditions: [
          { id: 'if', label: 'if', expression: '{{trigger:start.output.type}} == "A"' },
          { id: 'else', label: 'else', expression: 'true' },
        ],
      },
      'decision-1',
      'decisionNode',
    );
    // DecisionNode reports one key per branch plus a count.
    const reported = { if: false, else: true, branches: 2 };
    expect(alignmentFor(decision, reported, 'decision').mismatches).toEqual([]);
  });

  it('a switch reports its expression and case count under the plan names', () => {
    const switchNode = node(
      {
        label: 'Pick',
        switchExpression: '{{trigger:start.output.type}}',
        switchCases: [{ id: 'case_1', label: 'A', value: 'A' }],
      },
      'switch-1',
      'switchNode',
    );
    const reported = { switchExpression: 'B', resolved_value: 'B', switchCases: 1 };
    expect(alignmentFor(switchNode, reported, 'switch').mismatches).toEqual([]);
  });

  it('declares nothing for a loop read on its own, because the plan registers a loop from its EDGES', () => {
    // A known blind spot, and the safe direction: the banner stays silent
    // rather than warning about parameters it cannot see. The e2e compares
    // against the SAVED plan, where the loop's configuration does exist.
    const loop = node(
      { label: 'Repeat', loopCondition: '{{core:x.output.more}}', maxIterations: 3 },
      'loop-1',
      'loopNode',
    );
    expect(planEntryForNode(loop)).toBeNull();
    expect(collectDeclaredParams(loop)).toEqual({});
    expect(alignmentFor(loop, { maxIterations: 3 }, 'loop').mismatches).toEqual([]);
  });

  it('a fork reports its branch count under the plan name', () => {
    const fork = node({ label: 'Split work', forkOutputs: ['a', 'b'] }, 'fork-1', 'forkNode');
    const reported = { forkOutputs: 2 };
    expect(alignmentFor(fork, reported, 'fork').mismatches).toEqual([]);
  });

  it('still flags a core that genuinely stops reporting a parameter', () => {
    const switchNode = node(
      {
        label: 'Pick',
        switchExpression: '{{trigger:start.output.type}}',
        switchCases: [{ id: 'case_1', label: 'A', value: 'A' }],
      },
      'switch-1',
      'switchNode',
    );
    const mismatches = alignmentFor(switchNode, { switchCases: 1 }, 'switch').mismatches;
    expect(mismatches.map((m) => m.key)).toEqual(['switchExpression']);
  });
});
