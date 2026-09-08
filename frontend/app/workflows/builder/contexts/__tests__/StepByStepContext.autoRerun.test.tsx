/**
 * @vitest-environment jsdom
 *
 * REGRESSION: "restart from this node" must be reachable in AUTOMATIC mode.
 *
 * The backend rerun path has always been mode-blind, and in automatic mode it reruns the
 * target then drives the rest of the chain itself. The frontend nevertheless returned false
 * from canRerunStep whenever the run was not step-by-step, so the affordance existed and was
 * unreachable: no bottom-bar button, no context-menu item, no inspector button.
 *
 * The replacement rule: a node is restartable whenever it has SETTLED (completed / failed /
 * running), in either mode, on any run that was not deliberately put down.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import * as React from 'react';
import { renderHook } from '@testing-library/react';
import { StepByStepProvider, useNodeExecutionStatus } from '../StepByStepContext';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { UNREVIVABLE_STATUSES } from '@/contexts/workflow-run/RunStateStore';

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: vi.fn(() => ({ viewingEpoch: null, isRunMode: true })),
}));

vi.mock('../../utils/labelNormalizer', () => ({
  normalizeLabel: (label: string) => label.toLowerCase().replace(/\s+/g, '_'),
  extractLabelFromKey: (key: string) => (key.includes(':') ? key.slice(key.indexOf(':') + 1) : null),
}));

const STEP = 'mcp:step_a';

interface Opts {
  /** false = AUTOMATIC mode (the case that was locked out). */
  isEnabled?: boolean;
  isRunTerminal?: boolean;
  isRunUnrevivable?: boolean;
  completed?: string[];
  failed?: string[];
  skipped?: string[];
  running?: string[];
  /** Which epoch the canvas is reading. Non-null and older than currentEpoch = history. */
  viewingEpoch?: number | null;
}

function statusFor({
  isEnabled = false,
  isRunTerminal = false,
  isRunUnrevivable = false,
  completed = [STEP],
  failed = [],
  skipped = [],
  running = [],
  viewingEpoch = null,
}: Opts = {}) {
  vi.mocked(useWorkflowMode).mockReturnValue(
    { viewingEpoch, isRunMode: true } as ReturnType<typeof useWorkflowMode>,
  );
  const Wrapper = ({ children }: { children: React.ReactNode }) => (
    <StepByStepProvider
      isEnabled={isEnabled}
      isPaused={false}
      isRunTerminal={isRunTerminal}
      isRunUnrevivable={isRunUnrevivable}
      readySteps={new Set<string>()}
      completedSteps={new Set<string>(completed)}
      failedSteps={new Set<string>(failed)}
      skippedSteps={new Set<string>(skipped)}
      runningSteps={new Set<string>(running)}
      onExecuteStep={async () => undefined}
      onRerunStep={async () => null}
      currentEpoch={2}
    >
      {children}
    </StepByStepProvider>
  );
  return renderHook(() => useNodeExecutionStatus(STEP), { wrapper: Wrapper }).result.current;
}

describe('UNREVIVABLE_STATUSES', () => {
  it('covers only the statuses a rerun must not revive', () => {
    // Swapping TERMINAL_STATUSES back in at the WorkflowBuilder call site would re-break the
    // headline feature silently, so the membership itself is pinned.
    expect([...UNREVIVABLE_STATUSES].sort()).toEqual(['cancelled', 'stopped', 'timeout']);
    for (const finished of ['completed', 'failed', 'partial_success', 'skipped']) {
      expect(UNREVIVABLE_STATUSES.has(finished as never)).toBe(false);
    }
  });
});

describe('canRerun outside step-by-step mode', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useWorkflowMode).mockReturnValue(
      { viewingEpoch: null, isRunMode: true } as ReturnType<typeof useWorkflowMode>,
    );
  });

  it('offers a rerun on a completed node in AUTOMATIC mode', () => {
    // The whole point: this returned false before, which is why the feature was invisible.
    expect(statusFor({ isEnabled: false }).canRerun).toBe(true);
  });

  it('still offers a rerun in step-by-step mode', () => {
    expect(statusFor({ isEnabled: true }).canRerun).toBe(true);
  });

  it('offers a rerun on a FAILED node in AUTOMATIC mode', () => {
    expect(statusFor({ isEnabled: false, completed: [], failed: [STEP] }).canRerun).toBe(true);
  });

  it('offers a rerun on a run that merely FINISHED', () => {
    // A completed run is the common restart-from-here case; blocking it on isRunTerminal
    // is what hid the affordance on every finished automatic run.
    expect(statusFor({ isRunTerminal: true }).canRerun).toBe(true);
  });

  it('refuses a rerun on a run that was stopped, cancelled or timed out', () => {
    // Reviving one of those is a re-trigger decision, not a rerun.
    expect(statusFor({ isRunTerminal: true, isRunUnrevivable: true }).canRerun).toBe(false);
  });

  it('refuses a rerun on a node that never settled', () => {
    expect(statusFor({ completed: [] }).canRerun).toBe(false);
  });

  it('refuses a rerun on a SKIPPED node: retry from the decision instead', () => {
    expect(statusFor({ completed: [], skipped: [STEP] }).canRerun).toBe(false);
  });

  it('refuses a rerun on a node still RUNNING in AUTOMATIC mode', () => {
    // The invocation is genuinely in flight and will write its own completion. The backend
    // accepts the rerun anyway when an earlier epoch completed the node, so offering it buys
    // a double execution and a lost write rather than a clean refusal.
    expect(statusFor({ isEnabled: false, completed: [], running: [STEP] }).canRerun).toBe(false);
  });

  it('keeps the RUNNING escape hatch when the user drives the run by hand', () => {
    // A stuck while-loop or long agent in step-by-step: the user is the scheduler there.
    expect(statusFor({ isEnabled: true, completed: [], running: [STEP] }).canRerun).toBe(true);
  });

  it('refuses a rerun on a HISTORICAL epoch when nothing says the node ran in it', () => {
    // A past epoch DOES offer a rerun now (it replays that epoch by name), but only for a node
    // the epoch itself finished - and this caller passes no per-epoch status, so there is no
    // such fact. The run-wide sets say COMPLETED and must not be enough on their own: they
    // accumulate across every epoch. Full rules in StepByStepContext.epochRerun.test.tsx.
    expect(statusFor({ viewingEpoch: 1 }).canRerun).toBe(false);
  });

  it('allows a rerun while reading the run NEWEST epoch', () => {
    expect(statusFor({ viewingEpoch: 2 }).canRerun).toBe(true);
  });

  it('reports the run persisted mode separately from the stepping affordance', () => {
    // isStepByStepMode folds in terminality; a FINISHED stepped run is still stepped, and
    // labels describing what a rerun WILL DO must key off isSteppedRun.
    const finishedStepped = statusFor({ isEnabled: true, isRunTerminal: true });
    expect(finishedStepped.isStepByStepMode).toBe(false);
    expect(finishedStepped.isSteppedRun).toBe(true);
  });
});
