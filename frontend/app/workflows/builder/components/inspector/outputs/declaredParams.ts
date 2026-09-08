/**
 * What a node declares in EDIT mode, expressed the way the backend receives it.
 *
 * The builder keeps a node's configuration on `node.data` in per-node-type
 * shapes (`filterConditions`, `limitCount`, `codeContent`, …); what the backend
 * actually gets is the plan entry the generator emits for that node. Reading
 * `node.data` here would mean re-deriving that mapping a second time and letting
 * the two drift, so this runs the REAL generator on the single node and reads
 * the entry it produces.
 *
 * That is what makes "the fields declared in edit mode" a precise statement:
 * the same function the save path uses answers it.
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { generateWorkflowPlan } from '../../../utils/workflowPlanGenerator';
import { flattenPlannedParams } from './runParamAlignment';

/**
 * Every list a plan entry can land in. Order does not matter: a node id appears
 * in exactly one of them.
 */
const PLAN_ENTRY_LISTS = ['cores', 'mcps', 'tables', 'agents', 'triggers', 'interfaces'] as const;

/**
 * The plan entry the generator emits for one node, or null when it emits none.
 *
 * A loop is the notable null: the generator registers it from its EDGES
 * (`processEdgesV2`), so reading the node alone declares nothing. That is a
 * blind spot, and the safe one: the mismatch panel stays silent rather than
 * warning about parameters it cannot see. The alignment e2e compares against the
 * SAVED plan, where a loop's configuration does exist, so the check itself is
 * not blind there.
 */
export function planEntryForNode(
  node: Node<BuilderNodeData>,
): Record<string, unknown> | null {
  let plan: Record<string, unknown>;
  try {
    // No edges: this asks "what does THIS node declare", not "what does the
    // graph look like". A generator throwing on an isolated node must not take
    // the inspector down with it.
    plan = generateWorkflowPlan([node], []) as unknown as Record<string, unknown>;
  } catch {
    return null;
  }

  for (const listName of PLAN_ENTRY_LISTS) {
    const list = plan[listName];
    if (!Array.isArray(list)) continue;
    for (const entry of list) {
      if (!entry || typeof entry !== 'object') continue;
      const candidate = entry as Record<string, unknown>;
      if (candidate.graphNodeId === node.id || candidate.id === node.id) {
        return candidate;
      }
    }
  }
  return null;
}

/**
 * The configuration keys this node hands to its backend node, flattened the way
 * the run reports them back. Empty when the node declares nothing.
 */
export function collectDeclaredParams(
  node: Node<BuilderNodeData> | null | undefined,
): Record<string, unknown> {
  if (!node) return {};
  const entry = planEntryForNode(node);
  return entry ? flattenPlannedParams(entry) : {};
}
