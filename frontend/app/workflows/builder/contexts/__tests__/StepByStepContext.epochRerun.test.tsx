/**
 * @vitest-environment jsdom
 *
 * REGRESSION: "restart from this node" must be reachable from ANY epoch of an automatic run,
 * and must replay THAT epoch.
 *
 * A rerun used to be gated on `isInteractive`, which is true only in the all-epochs view and
 * on the run's newest epoch. So the one epoch that offered the button was the most recent one,
 * which is rarely the one that needs repairing: a run that fired ten times and failed on the
 * third had no way, from the canvas, to redo the third.
 *
 * The backend has taken the epoch by name (`?epoch=N`) all along and refuses what it cannot
 * replay, so the fix is to offer the button on a focused epoch and NAME that epoch. Two facts
 * become load-bearing, and both are pinned below:
 *
 *   - the epoch has to travel all the way to `onRerunStep`, including THROUGH the automatic
 *     run's confirmation gate (the pending record parks the call; dropping the epoch there
 *     would silently replay the newest epoch after the user confirmed the older one);
 *   - the gate has to read the FOCUSED epoch's own outcome. The context's sets are derived from
 *     cumulative NodeCounts and accumulate across every epoch, so a node whose branch was not
 *     taken in the epoch on screen still reads COMPLETED in them - and offering a rerun there
 *     buys a backend refusal the canvas never shows.
 *
 * Stepped runs stay excluded: the backend refuses a named epoch on one (nothing on a
 * hand-stepped run closes a cycle, so the reopened epoch would stay open indefinitely).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as React from 'react';
import { render, renderHook, screen, cleanup, fireEvent, act } from '@testing-library/react';
import { StepByStepProvider, useNodeExecutionStatus } from '../StepByStepContext';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import type { DerivedNodeStatus } from '../../types';

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: vi.fn(() => ({ viewingEpoch: null, isRunMode: true })),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

const STEP = 'mcp:step_a';
/** The run has fired three times: epoch 1 is history, epoch 3 is the live one. */
const CURRENT_EPOCH = 3;

interface Opts {
  /** false = AUTOMATIC run, the only mode a named epoch is legal in. */
  isEnabled?: boolean;
  /** Which epoch the canvas is reading. null = the all-epochs view. */
  viewingEpoch?: number | null;
  /** What the FOCUSED epoch painted on this node. undefined = that epoch never ran it. */
  epochStatus?: DerivedNodeStatus;
  /** Run-wide sets, accumulated across every epoch. */
  completed?: string[];
  failed?: string[];
  skipped?: string[];
  running?: string[];
  awaitingSignal?: string[];
  /** Epochs of this run still executing. Empty on a run that has gone quiet. */
  activeEpochs?: number[];
  /** The run was stopped, cancelled or timed out - never rerunnable, on any epoch. */
  isRunUnrevivable?: boolean;
  /** A read-only surface: marketplace preview, or an application a visitor has not acquired. */
  isPreviewOnly?: boolean;
  /** The run's newest fire. 0 means it has fired exactly once, into epoch 0. */
  currentEpoch?: number;
  onRerunStep?: (stepId: string, epoch?: number) => Promise<any>;
}

function providerFor({
  isEnabled = false,
  isRunUnrevivable = false,
  completed = [STEP],
  failed = [],
  skipped = [],
  running = [],
  awaitingSignal = [],
  activeEpochs = [],
  currentEpoch = CURRENT_EPOCH,
  onRerunStep = async () => null,
}: Opts) {
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return (
      <StepByStepProvider
        isEnabled={isEnabled}
        isPaused={false}
        isRunUnrevivable={isRunUnrevivable}
        readySteps={new Set<string>()}
        completedSteps={new Set<string>(completed)}
        failedSteps={new Set<string>(failed)}
        skippedSteps={new Set<string>(skipped)}
        runningSteps={new Set<string>(running)}
        awaitingSignalSteps={new Set<string>(awaitingSignal)}
        activeEpochs={activeEpochs}
        onExecuteStep={async () => undefined}
        onRerunStep={onRerunStep}
        currentEpoch={currentEpoch}
      >
        {children}
      </StepByStepProvider>
    );
  };
}

function statusFor(opts: Opts = {}) {
  vi.mocked(useWorkflowMode).mockReturnValue(
    {
      viewingEpoch: opts.viewingEpoch ?? null,
      isRunMode: true,
      isPreviewOnly: opts.isPreviewOnly ?? false,
    } as ReturnType<typeof useWorkflowMode>,
  );
  return renderHook(
    () => useNodeExecutionStatus(STEP, { status: opts.epochStatus }),
    { wrapper: providerFor(opts) },
  ).result.current;
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(useWorkflowMode).mockReturnValue(
    { viewingEpoch: null, isRunMode: true } as ReturnType<typeof useWorkflowMode>,
  );
});
afterEach(cleanup);

describe('rerun on a historical epoch of an automatic run', () => {
  it('offers the rerun on a node that epoch completed', () => {
    // The headline: before the fix this was false on every epoch but the newest.
    expect(statusFor({ viewingEpoch: 1, epochStatus: 'completed' }).canRerun).toBe(true);
  });

  it('offers the rerun on a node that epoch FAILED, the case that needs it most', () => {
    expect(
      statusFor({ viewingEpoch: 1, epochStatus: 'failed', completed: [], failed: [STEP] }).canRerun,
    ).toBe(true);
  });

  it('offers the rerun on a PARTIAL_SUCCESS node: it finished, carrying a failure in its tally', () => {
    // Same bucket the run-wide sets put it in, and what keeps its rerun button everywhere else.
    expect(statusFor({ viewingEpoch: 1, epochStatus: 'partial_success' }).canRerun).toBe(true);
  });

  it('refuses a node that epoch SKIPPED, even though the run-wide sets still say completed', () => {
    // The exact shape the accumulated sets get wrong: the branch was not taken in epoch 1, the
    // node completed in epoch 3, and `completedSteps` cannot tell the two apart. The backend
    // refuses this replay ("its branch was not taken in that epoch"), silently as far as the
    // canvas is concerned, so the button must not be there at all.
    expect(statusFor({ viewingEpoch: 1, epochStatus: 'skipped' }).canRerun).toBe(false);
  });

  it('refuses a node the epoch never reached (no status painted on it)', () => {
    expect(statusFor({ viewingEpoch: 1, epochStatus: undefined }).canRerun).toBe(false);
  });

  it('refuses a node still RUNNING in that epoch', () => {
    expect(statusFor({ viewingEpoch: 1, epochStatus: 'running' }).canRerun).toBe(false);
  });

  it('refuses the rerun on a STEPPED run: the backend will not reopen an epoch there', () => {
    expect(
      statusFor({ isEnabled: true, viewingEpoch: 1, epochStatus: 'completed' }).canRerun,
    ).toBe(false);
  });

  it('still requires the run-wide state to allow a rerun at all', () => {
    // The epoch status says "this finished here", the run says "this node never settled".
    // Both have to agree, so a stale epoch paint cannot promise a restart the run refuses.
    expect(
      statusFor({ viewingEpoch: 1, epochStatus: 'completed', completed: [] }).canRerun,
    ).toBe(false);
  });

  it('refuses while ANOTHER epoch of the run is still executing', () => {
    // Reopening an older epoch then runs two fires of the same DAG at once, and the backend
    // refuses exactly that. Offering the button here produces a click that can only ever
    // answer with an error - the "watch a scheduled run, scroll back, restart" case.
    expect(
      statusFor({ viewingEpoch: 1, epochStatus: 'completed', activeEpochs: [3] }).canRerun,
    ).toBe(false);
  });

  it('allows it when the only active epoch is the one being read', () => {
    // Not a sibling: nothing else is executing, so nothing collides with the replay.
    expect(
      statusFor({ viewingEpoch: 1, epochStatus: 'completed', activeEpochs: [1] }).canRerun,
    ).toBe(true);
  });

  it('refuses a node executing RIGHT NOW on the all-epochs view too, not just on a focused epoch', () => {
    // The refusal moved into `canRerunStep`, so every surface inherits it. The canvas bar was
    // already covered by `showsNodeRunActions`'s own `!isRunning`, but the context menu and the
    // inspector header gate on `canRerun` alone and offered a restart the backend refuses.
    expect(
      statusFor({ viewingEpoch: null, completed: [STEP], running: [STEP] }).canRerun,
    ).toBe(false);
  });

  it('still offers it for a node PARKED on a signal, which the run-wide set also calls running', () => {
    // Yielding never rewrites the RUNNING step row, so an awaiting node sits in BOTH sets on
    // this side. The backend's own runningNodeIds drops it (markNodeAwaitingSignal removes
    // it), so it accepts the restart - and a guard that read `runningSteps` alone would take
    // away the restart on every approval waiting for its answer.
    expect(
      statusFor({
        viewingEpoch: null,
        completed: [STEP],
        running: [STEP],
        awaitingSignal: [STEP],
      }).canRerun,
    ).toBe(true);
  });

  it('keeps the RUNNING escape hatch on a STEPPED run, where the user is the scheduler', () => {
    // A stuck while-loop or a long agent: the backend allows it there, and taking it away
    // would strand the one run shape that has no other way forward.
    expect(
      statusFor({ isEnabled: true, viewingEpoch: null, completed: [STEP], running: [STEP] }).canRerun,
    ).toBe(true);
  });

  it('refuses a node executing RIGHT NOW in another epoch', () => {
    // The epoch on screen finished it; epoch 3 is running it this second. Restarting would
    // race an execution that still writes its own completion (double execution, lost write),
    // which is why the backend refuses it on an automatic run - and why the epoch-scoped
    // `isRunning` cannot be the only thing guarding this.
    expect(
      statusFor({ viewingEpoch: 1, epochStatus: 'completed', running: [STEP] }).canRerun,
    ).toBe(false);
  });

  it('treats the newest EXECUTED epoch of a settled run as focused, because currentEpoch is past it', () => {
    // The shape production presents most of the time, and the one a fixture is most likely to
    // get wrong: once a cycle closes, DagState.prepareNextCycle points currentEpoch at the
    // epoch STAGED for the next fire, which has no results in it. So the last epoch the
    // selector offers is NOT currentEpoch - it reads as focused, its restart names it, and the
    // epoch's own paint is what the controls speak about.
    const status = statusFor({ viewingEpoch: 3, currentEpoch: 4, epochStatus: 'completed', skipped: [STEP] });
    expect(status.canRerun).toBe(true);
    expect(status.isCompleted).toBe(true);
    expect(status.isSkipped).toBe(false);
  });

  it('treats the live epoch of a once-fired run as live, not as history', () => {
    // currentEpoch 0 is a real first fire, not "no epoch". Deciding by epoch identity keeps
    // epoch 0 out of the focused-epoch path; going through `isInteractive` (which demands
    // currentEpoch > 0) would classify the live state as history and name it on the wire.
    const status = statusFor({ viewingEpoch: 0, currentEpoch: 0, epochStatus: 'skipped' });
    // Read from the run-wide sets, like any other live view...
    expect(status.isCompleted).toBe(true);
    expect(status.isSkipped).toBe(false);
    // ...and the ordinary live rerun is offered, which is the whole reason the epoch-identity
    // comparison replaced the `currentEpoch > 0` test.
    expect(status.canRerun).toBe(true);
  });

  it('refuses on a read-only surface, where a restart would reset the PUBLISHER run', () => {
    // FlowNode drops its whole bar under preview, but ten other node renderers gate theirs on
    // `showsNodeRunActions` alone - and the anonymous showcase does load run state. The refusal
    // therefore belongs on the flag every surface reads, not on one renderer.
    expect(
      statusFor({ viewingEpoch: 1, epochStatus: 'completed', isPreviewOnly: true }).canRerun,
    ).toBe(false);
    expect(
      statusFor({ viewingEpoch: null, isPreviewOnly: true }).canRerun,
    ).toBe(false);
  });

  it('refuses on a run that was stopped or cancelled, focused epoch or not', () => {
    // The run-wide refusal outranks the epoch: reviving a run that was put down is a
    // re-trigger decision, and the backend says so before it ever looks at the epoch.
    expect(
      statusFor({ viewingEpoch: 1, epochStatus: 'completed', isRunUnrevivable: true }).canRerun,
    ).toBe(false);
  });
});

describe('the focused epoch drives the status flags the run buttons read', () => {
  it('reports the EPOCH outcome, not the run-wide one', () => {
    // deriveNodeStatus() feeds off these flags, and it is what decides whether the bottom bar
    // draws a rerun at all: left on the run-wide sets, a node skipped in the newest epoch
    // rendered as 'skipped' (no button) while the user read an epoch that completed it.
    const status = statusFor({
      viewingEpoch: 1,
      epochStatus: 'completed',
      completed: [STEP],
      skipped: [STEP],
    });
    expect(status.isCompleted).toBe(true);
    expect(status.isSkipped).toBe(false);
  });

  it('leaves the all-epochs view on the run-wide sets', () => {
    // No epoch is focused there, so there is no per-epoch fact to prefer, and the node's
    // painted status is the live one, which must not start overriding the run state.
    const status = statusFor({ viewingEpoch: null, epochStatus: 'skipped' });
    expect(status.isCompleted).toBe(true);
    expect(status.isSkipped).toBe(false);
  });

  it('leaves the run NEWEST epoch on the run-wide sets: it is the live state, not history', () => {
    const status = statusFor({ viewingEpoch: CURRENT_EPOCH, epochStatus: 'skipped' });
    expect(status.isCompleted).toBe(true);
    expect(status.isSkipped).toBe(false);
  });

  it('reports RUNNING from the epoch, not from the run', () => {
    // Asserted on its own rather than through canRerun: a version that read the epoch for
    // `isCompleted` and the run for `isRunning` would still pass every rerun test, and would
    // paint a node blue on an epoch where it finished hours ago.
    const status = statusFor({ viewingEpoch: 1, epochStatus: 'completed', running: [STEP] });
    expect(status.isRunning).toBe(false);
    const stillRunningHere = statusFor({ viewingEpoch: 1, epochStatus: 'running', running: [] });
    expect(stillRunningHere.isRunning).toBe(true);
  });

  it('reports FAILED from the epoch, not from the run', () => {
    // The run remembers a failure from another fire; this epoch completed cleanly.
    const status = statusFor({
      viewingEpoch: 1,
      epochStatus: 'completed',
      completed: [STEP],
      failed: [STEP],
    });
    expect(status.isFailed).toBe(false);
    expect(status.isCompleted).toBe(true);
  });

  it('reports AWAITING_SIGNAL from the epoch, not from the run', () => {
    // UserApprovalNode and InterfacePreviewNode read this flag; left on the run-wide set, an
    // approval still parked in the LIVE epoch claimed the finished epoch was waiting too.
    const parkedElsewhere = statusFor({
      viewingEpoch: 1,
      epochStatus: 'completed',
      awaitingSignal: [STEP],
    });
    expect(parkedElsewhere.isAwaitingSignal).toBe(false);

    const parkedHere = statusFor({ viewingEpoch: 1, epochStatus: 'awaiting_signal' });
    expect(parkedHere.isAwaitingSignal).toBe(true);
    // ...and a parked node is not restartable: the all-epochs view offers no restart for one
    // either, so a focused epoch must not become the only place it can be done.
    expect(parkedHere.canRerun).toBe(false);
  });

  it('says NOTHING about a node the focused epoch never ran, rather than borrowing the run state', () => {
    // The epoch's answer is "no result here". Falling back to the run-wide sets would let
    // another fire's outcome paint a node this one never reached - InterfacePreviewNode reads
    // these flags with no viewingEpoch short-circuit of its own.
    const status = statusFor({
      viewingEpoch: 1,
      epochStatus: undefined,
      completed: [STEP],
      running: [STEP],
      awaitingSignal: [STEP],
    });
    expect(status.isCompleted).toBe(false);
    expect(status.isRunning).toBe(false);
    expect(status.isAwaitingSignal).toBe(false);
  });
});

describe('which epoch the rerun names', () => {
  /** Drives `rerunStep` exactly like a UI surface does, through the automatic-run gate. */
  async function rerunThroughConfirm(opts: Opts) {
    const onRerunStep = vi.fn(async () => null);
    vi.mocked(useWorkflowMode).mockReturnValue(
      { viewingEpoch: opts.viewingEpoch ?? null, isRunMode: true } as ReturnType<typeof useWorkflowMode>,
    );
    let rerun: (() => void) | null = null;
    function Probe() {
      rerun = useNodeExecutionStatus(STEP, { status: opts.epochStatus }).rerunStep as () => void;
      return null;
    }
    const Wrapper = providerFor({ ...opts, onRerunStep });
    render(<Wrapper><Probe /></Wrapper>);

    act(() => { void rerun!(); });
    // Automatic runs confirm first; the epoch has to survive that park-and-resume.
    await screen.findByRole('dialog');
    await act(async () => { fireEvent.click(screen.getByTestId('rerun-confirm-accept')); });
    return onRerunStep;
  }

  it('names the focused epoch, so the replay lands where the user is reading', async () => {
    const onRerunStep = await rerunThroughConfirm({ viewingEpoch: 1, epochStatus: 'completed' });
    expect(onRerunStep).toHaveBeenCalledWith(STEP, 1);
  });

  it('names no epoch in the all-epochs view: the backend default is what that view has always used', async () => {
    const onRerunStep = await rerunThroughConfirm({ viewingEpoch: null, epochStatus: 'completed' });
    expect(onRerunStep).toHaveBeenCalledWith(STEP, undefined);
  });

  it('names no epoch on the run NEWEST epoch either: naming one sends the backend down another branch', async () => {
    const onRerunStep = await rerunThroughConfirm({ viewingEpoch: CURRENT_EPOCH, epochStatus: 'completed' });
    expect(onRerunStep).toHaveBeenCalledWith(STEP, undefined);
  });

  it('names epoch 0 when THAT is the historical epoch being read', async () => {
    // This is the hop that applies the `?? undefined` coalesce, so it is the one place where
    // writing `|| undefined` turns a legitimate first fire into "no epoch chosen" and replays
    // a different one. The service, manager and handler hops all pin epoch 0 too, and none of
    // them would catch a mistake made here.
    const onRerunStep = await rerunThroughConfirm({ viewingEpoch: 0, epochStatus: 'completed' });
    expect(onRerunStep).toHaveBeenCalledWith(STEP, 0);
  });
});
