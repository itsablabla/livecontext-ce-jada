// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { renderHook } from '@testing-library/react';
import type { Node } from 'reactflow';
import { useInspectorNodeMeta } from '../useInspectorNodeMeta';
import type { BuilderNodeData } from '../../../types';

/**
 * How the inspector classifies a generate node, and why one flag decides the
 * whole panel.
 *
 * <p>`isAiGenericNode` does not mean "an AI node". It means "an AI node whose
 * TYPE has not been chosen yet": the placeholder dropped on the canvas from the
 * AI category, which is a navigation step rather than a configurable node. The
 * inspector treats such a node as small and transient, so it forces small mode
 * and refuses fullscreen.
 *
 * <p>Generate joined the AI family with a `family: 'ai'` node class, which put
 * it in that bucket by default, and the symptom is not a crash: advanced mode
 * and fullscreen switch back off as fast as the reader turns them on, on a form
 * that is one of the longest in the builder. Classify, guardrail and
 * browser_agent each needed the same exclusion before it, which is why the test
 * pins them alongside.
 */

function generateNode(overrides: Partial<BuilderNodeData> = {}): Node<BuilderNodeData> {
  return {
    id: 'n1',
    position: { x: 0, y: 0 },
    data: {
      id: 'generate-1717171717-abc12',
      label: 'Make Clip',
      kind: 'generate',
      ...overrides,
    } as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

function nodeWith(data: Partial<BuilderNodeData>): Node<BuilderNodeData> {
  return {
    id: 'n1',
    position: { x: 0, y: 0 },
    data: { label: 'X', ...data } as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

const meta = (node: Node<BuilderNodeData> | null) =>
  renderHook(() => useInspectorNodeMeta(node)).result.current;

describe('useInspectorNodeMeta - the generate node', () => {
  it('is an AI node', () => {
    expect(meta(generateNode()).isAiNode).toBe(true);
  });

  it('is NOT the unchosen-AI-type placeholder, which is what forces small mode', () => {
    expect(
      meta(generateNode()).isAiGenericNode,
      'read as a placeholder, the panel refuses fullscreen and drops out of advanced '
      + 'mode on every render of one of the longest forms in the builder',
    ).toBe(false);
  });

  it('is recognised from its kind alone, since the minted id is not the only source', () => {
    // A node created through the palette carries the `generate-<ts>-<rand>` id;
    // one read back from a plan is identified by kind. Both have to answer the
    // same way or the panel behaves differently depending on how it was opened.
    expect(meta(nodeWith({ id: 'something-else', kind: 'generate' })).isAiGenericNode).toBe(false);
    expect(meta(nodeWith({ id: 'something-else', kind: 'generate' })).isAiNode).toBe(true);
  });

  it('leaves the siblings that needed the same exclusion untouched', () => {
    expect(meta(nodeWith({ id: 'classify-1', kind: 'classify' })).isAiGenericNode).toBe(false);
    expect(meta(nodeWith({ id: 'guardrail-1', kind: 'guardrail' })).isAiGenericNode).toBe(false);
    expect(meta(nodeWith({ id: 'browser_agent-1', kind: 'browser_agent' })).isAiGenericNode).toBe(false);
  });

  it('still calls the real placeholder a placeholder, so the exclusion is not a blanket one', () => {
    // The node this flag exists for: dropped from the AI category, no type
    // chosen. If nothing answers true here the small-mode behaviour is dead
    // rather than fixed.
    expect(meta(nodeWith({ id: 'ai' })).isAiGenericNode).toBe(true);
  });
});
