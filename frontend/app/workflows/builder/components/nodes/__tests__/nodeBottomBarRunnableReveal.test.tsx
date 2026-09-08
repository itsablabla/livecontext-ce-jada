// @vitest-environment jsdom
/**
 * Bottom bar force-reveal: in run mode a node whose play button is in the
 * "ready" state reveals its whole bottom bar WITHOUT hover, so the run button
 * shows up on its own (with its blue shimmer). This is what replaced the old
 * green node-body ready shimmer:
 *   - a trigger that is ready (auto or step-by-step mode) -> button visible,
 *   - any ready node in step-by-step mode -> button visible,
 *   - a node that is NOT runnable (pending) -> still hover-gated,
 *   - outside run mode -> still hover-gated even when ready.
 * Reveal is expressed as the row's opacity (1 revealed / 0 hidden).
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

let mockMode: any;

vi.mock('next-intl', () => ({
  useTranslations: () => (k: string) => k,
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => mockMode,
}));
// Keep deriveNodeStatus real (the reveal logic depends on it); stub the heavy
// button so we only exercise the bar's reveal decision.
vi.mock('../../NodePlayButton', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../NodePlayButton')>()),
  NodePlayButton: () => <div data-testid="play" />,
  deriveNodeStatus: (f: any) =>
    f.isRunning ? 'running'
      : f.isFailed ? 'failed'
      : f.isSkipped ? 'skipped'
      : f.isCompleted ? 'completed'
      : f.isReady ? 'ready'
      : 'pending',
}));

import { NodeBottomBar } from '../NodeBottomBar';

const stepStatus = (over: Record<string, boolean> = {}) => ({
  isStepByStepMode: false,
  isSteppedRun: false,
  isReady: false,
  canExecute: false,
  isExecuting: false,
  isRerunning: false,
  isRunning: false,
  isFailed: false,
  isSkipped: false,
  isCompleted: false,
  canRerun: false,
  executeStep: () => {},
  rerunStep: () => {},
  ...over,
});

const play = (over: { isTriggerNode?: boolean; status?: Record<string, boolean> } = {}) => ({
  nodeId: 'n1',
  variant: 'play' as const,
  isAutoMode: false,
  isTriggerNode: over.isTriggerNode ?? false,
  stepByStepStatus: stepStatus(over.status),
});

const opacityOf = (c: ReturnType<typeof render>) =>
  (c.container.firstChild as HTMLElement).style.opacity;

beforeEach(() => {
  mockMode = { isRunMode: true, isPreviewOnly: false };
});

const notHovered = { isVisible: false } as const;
const hovered = { isVisible: true } as const;

describe('NodeBottomBar runnable force-reveal', () => {
  it('reveals a READY trigger button in run mode without hover', () => {
    const c = render(
      <NodeBottomBar borderColor="#000" isRunning={false} hover={notHovered}
        playButton={play({ isTriggerNode: true, status: { isReady: true, canExecute: true } })} />
    );
    expect(opacityOf(c)).toBe('1');
  });

  it('reveals a READY non-trigger (step-by-step) button in run mode without hover', () => {
    const c = render(
      <NodeBottomBar borderColor="#000" isRunning={false} hover={notHovered}
        playButton={play({ isTriggerNode: false, status: { isStepByStepMode: true, isReady: true, canExecute: true } })} />
    );
    expect(opacityOf(c)).toBe('1');
  });

  it('does NOT force-reveal a ready-but-not-executable node (would render an empty button)', () => {
    const c = render(
      <NodeBottomBar borderColor="#000" isRunning={false} hover={notHovered}
        playButton={play({ isTriggerNode: false, status: { isStepByStepMode: true, isReady: true, canExecute: false } })} />
    );
    expect(opacityOf(c)).toBe('0');
  });

  it('keeps a NON-ready (pending) node hover-gated', () => {
    const c = render(
      <NodeBottomBar borderColor="#000" isRunning={false} hover={notHovered}
        playButton={play({ isTriggerNode: true, status: {} })} />
    );
    expect(opacityOf(c)).toBe('0');
  });

  it('still reveals a pending node on hover (normal hover reveal preserved)', () => {
    const c = render(
      <NodeBottomBar borderColor="#000" isRunning={false} hover={hovered}
        playButton={play({ isTriggerNode: true, status: {} })} />
    );
    expect(opacityOf(c)).toBe('1');
  });

  it('does NOT force-reveal a ready node outside run mode', () => {
    mockMode = { isRunMode: false, isPreviewOnly: false };
    const c = render(
      <NodeBottomBar borderColor="#000" isRunning={false} hover={notHovered}
        playButton={play({ isTriggerNode: true, status: { isReady: true, canExecute: true } })} />
    );
    expect(opacityOf(c)).toBe('0');
  });
});

/**
 * A focused epoch is read-only, so the normal play is gone and FlowNode puts back a launcher
 * as a PLAIN button (`focus-trigger-play`). Without a reveal of its own that button, and the
 * pin/unpin sharing the row, appear only on hover - on exactly the view where the all-epochs
 * play shows unprompted. `revealsBar` is what re-aligns the two.
 */
describe('NodeBottomBar reveal from a button that stands in for the play', () => {
  const button = (over: Partial<{ key: string; revealsBar: boolean }> = {}) => ({
    key: over.key ?? 'focus-trigger-play',
    icon: <span />,
    title: 'run',
    onClick: () => {},
    ...(over.revealsBar === undefined ? {} : { revealsBar: over.revealsBar }),
  });

  it('reveals the bar without hover, with no play button on it at all', () => {
    const c = render(
      <NodeBottomBar borderColor="#000" isRunning={false} hover={notHovered}
        buttons={[button({ revealsBar: true })]} />
    );
    expect(opacityOf(c)).toBe('1');
  });

  it('reveals the pin/unpin sharing the row, not just the flagged button', () => {
    // The user-visible half of the bug: pin/unpin lives in the leading slot and inherits the
    // bar's reveal, so a hover-gated bar hides it too.
    const c = render(
      <NodeBottomBar borderColor="#000" isRunning={false} hover={notHovered}
        leadingSlot={<button data-testid="pin">pin</button>}
        buttons={[button({ revealsBar: true })]} />
    );
    expect(opacityOf(c)).toBe('1');
    // Revealed AND clickable: the row is pointer-events-none, so a visible-but-dead pin is
    // the way to get this half right and still ship a button nobody can press.
    expect(c.getByTestId('pin').parentElement?.className).toContain('pointer-events-auto');
  });

  it('keeps an ordinary contextual button hover-gated', () => {
    // The reveal is the "you can run this now" cue. Spending it on the agent/files/sub-workflow
    // buttons would make the bar permanent on every node and the cue meaningless.
    const c = render(
      <NodeBottomBar borderColor="#000" isRunning={false} hover={notHovered}
        buttons={[button({ key: 'agent-config' })]} />
    );
    expect(opacityOf(c)).toBe('0');
  });

  it('reveals when a flagged button sits beside unflagged ones', () => {
    const c = render(
      <NodeBottomBar borderColor="#000" isRunning={false} hover={notHovered}
        buttons={[button({ key: 'agent-config' }), button({ revealsBar: true })]} />
    );
    expect(opacityOf(c)).toBe('1');
  });

  it('does NOT reveal outside run mode, exactly like the play reveal it mirrors', () => {
    // The bar also carries the edit-only delete/duplicate. A flag honoured in edit mode would
    // pin Trash and Copy permanently visible under every node - the reason `runnableNow` is
    // run-mode-gated in the first place.
    mockMode = { isRunMode: false, isPreviewOnly: false };
    const c = render(
      <NodeBottomBar borderColor="#000" isRunning={false} hover={notHovered}
        buttons={[button({ revealsBar: true })]} />
    );
    expect(opacityOf(c)).toBe('0');
  });
});
