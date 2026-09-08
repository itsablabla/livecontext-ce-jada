import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import { nodeRegistry } from '../registry/nodeRegistry';
import { matchNodeClass } from '../nodes/nodeClasses';
import { isAiReasoningNode, getAgentType } from '../utils/planHelpers';
import type { BuilderNodeData } from '../types';

/**
 * The generate node's FAMILY, asserted at every place that answers the question
 * independently.
 *
 * <p>Nothing in the builder stores "which family is this node". Half a dozen
 * predicates each work it out from the node's kind or id, and the answers feed
 * different things: which inspector form opens, which prefix an EDGE is written
 * with, which prefix the output panel offers for dragging, and which array the
 * exporter files the node in.
 *
 * <p>Every disagreement between two of them is silent. An edge written
 * `core:make_clip` against a node registered `agent:make_clip` produces no error
 * when saved, none when loaded, and a run in which the node is never reached; a
 * dragged reference with the wrong prefix resolves to an empty string rather
 * than failing. So the answers are pinned together here, in one file, instead of
 * being left to agree by luck.
 */

function generateNode(): Node<BuilderNodeData> {
  return {
    id: 'generate-1',
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id: 'generate-1731-abc',
      label: 'Make Clip',
      kind: 'generate',
    } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

/** A real core, carried alongside so every assertion below has a counter-example. */
function mediaNode(): Node<BuilderNodeData> {
  return {
    id: 'media-1',
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id: 'media-1731-abc',
      label: 'Add Voice',
      kind: 'media',
    } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

describe('the generate node belongs to the AI family, everywhere that decides it', () => {
  it('is not a control node, which is what the EDGE writer asks', () => {
    // getNodeTypeWithContext returns 'core' for anything isControlNode accepts,
    // and that answer becomes the prefix of every edge touching the node. While
    // generate was still in this list the plan carried edges pointing at
    // core:make_clip while the node itself exported as agent:make_clip: the node
    // was simply never reached, with nothing reporting it.
    expect(nodeRegistry.isControlNode(generateNode())).toBe(false);
    expect(nodeRegistry.isControlNode(mediaNode())).toBe(true);
  });

  it('is an agent node, which is what the KEY builder asks', () => {
    // computeBackendKey walks isTrigger -> isAgentNode -> isCoreNode -> ... and
    // falls through to `mcp:` when none matches. Removing generate from the core
    // list without adding it here left it answering `mcp:make_clip`.
    expect(nodeRegistry.isAgentNode(generateNode())).toBe(true);
    expect(nodeRegistry.isAgentNode(mediaNode())).toBe(false);
  });

  it('is still recognised as itself, so the inspector opens the generation form', () => {
    // isGenerateNode has to be checked BEFORE the family-wide isAgentNode
    // wherever a form is chosen; this pins that it can be.
    expect(nodeRegistry.isGenerateNode(generateNode())).toBe(true);
    expect(nodeRegistry.isGenerateNode(mediaNode())).toBe(false);
  });

  it('computes the agent: key, which is what a run and every {{...}} reference use', () => {
    expect(nodeRegistry.computeBackendKey(generateNode(), 'make_clip')).toBe('agent:make_clip');
    expect(nodeRegistry.computeBackendKey(mediaNode(), 'add_voice')).toBe('core:add_voice');
  });

  it('maps its kind to the agent prefix in the registry\'s single source of truth', () => {
    // kindToPrefix is built from the node definitions, and several predicates
    // read it rather than a list of their own.
    expect(nodeRegistry.getPrefixForKind('generate')).toBe('agent');
    expect(nodeRegistry.getPrefixForKind('media')).toBe('core');
  });

  it('is filed with the AI family in the palette class, which drives the family colour and grouping', () => {
    expect(matchNodeClass(generateNode().data)?.family).toBe('ai');
    expect(matchNodeClass(mediaNode().data)?.family).toBe('core');
  });

  it('is an AI node to the plan exporter, and reports its own agent type', () => {
    // isAiReasoningNode decides which processor emits the node, and getAgentType
    // decides the `type` written into the plan entry.
    expect(isAiReasoningNode(generateNode())).toBe(true);
    expect(getAgentType(generateNode())).toBe('generate');
    expect(isAiReasoningNode(mediaNode())).toBe(false);
  });
});
/**
 * The ONE prefix an output reference to a node must carry.
 *
 * <p>Three inspector surfaces build the {{...}} string a reader drags into a
 * downstream field, and each one used to decide the prefix with its own
 * ternary. That is how the generate node had to be added to all three
 * separately when it moved, and why missing one of them would have been
 * invisible: an unresolved template is an empty string, so the field just
 * receives nothing and the run reports success.
 */
describe('nodeRegistry.getReferencePrefixForNode', () => {
  it('addresses a generate node as agent:, the family it belongs to', () => {
    expect(nodeRegistry.getReferencePrefixForNode(generateNode())).toBe('agent');
  });

  it('still addresses an ordinary core as core:', () => {
    expect(nodeRegistry.getReferencePrefixForNode(mediaNode())).toBe('core');
  });

  it('addresses a table operation as table:, which no ternary may lose', () => {
    const crud = {
      id: 'n3',
      position: { x: 0, y: 0 },
      type: 'flowNode',
      data: { id: 'crud-1', label: 'Save Row', dataSourceData: { crudOperation: 'create-row' } },
    } as any;
    expect(nodeRegistry.getReferencePrefixForNode(crud)).toBe('table');
  });

  it('falls back to core: rather than to nothing for a node it cannot place', () => {
    // The surfaces that ask render the control-flow family, so an unplaceable
    // node there is a core. Answering `mcp` from a definitions lookup would be
    // worse than useless: it is a plausible prefix that addresses a different
    // node, so the reference resolves to nothing and looks correct.
    const unknown = {
      id: 'n4',
      position: { x: 0, y: 0 },
      type: 'flowNode',
      data: { id: 'x', label: 'X' },
    } as any;
    expect(nodeRegistry.getReferencePrefixForNode(unknown)).toBe('core');
  });
});
/**
 * Matching a canvas node against the rows a PAST run wrote.
 *
 * <p>Every run before the move keyed this node core:<label>, and its step data
 * and edge keys are still stored that way. This is the one place the old key
 * still has to answer: reading history, never writing it. Without it a
 * historical run shows the generate step and its edges with no status, no
 * output and no timing, as if it had never executed.
 */
describe('nodeRegistry.getPrefixesForNode for a generate node', () => {
  it('matches the rows a past run wrote under the old key', () => {
    expect(nodeRegistry.getPrefixesForNode(generateNode())).toContain('core');
  });

  it('and its current key, which is what every new run writes', () => {
    expect(nodeRegistry.getPrefixesForNode(generateNode())).toContain('agent');
  });

  it('does not hand that old key to an author writing a NEW reference', () => {
    // The read-side concession must not leak into the write side: the string a
    // reader drags into a downstream field comes from here, and core: resolves
    // to an empty string at run time.
    expect(nodeRegistry.getReferencePrefixForNode(generateNode())).toBe('agent');
  });

  it('leaves an ordinary core node with its own prefix alone', () => {
    expect(nodeRegistry.getPrefixesForNode(mediaNode())).toEqual(['core']);
  });
});
