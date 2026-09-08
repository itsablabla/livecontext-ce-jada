/**
 * @vitest-environment jsdom
 *
 * The epoch has to survive every hop between the node that chose it and the HTTP call.
 *
 * There are four, and each is a pass-through of two or three lines:
 *   useNodeExecutionStatus -> StepByStepProvider.rerunStep -> onRerunStep
 *   = handleRerunStep -> pauseResumeActions.rerunStep -> WorkflowRunContext.rerunStep
 *   -> WorkflowRunManager.rerunStep -> orchestratorApi.rerunFromStep
 *
 * TypeScript cannot help here: every hop takes the epoch as an OPTIONAL argument, so a hop
 * that forgets to forward it still compiles, still runs, and still reports success - after
 * replaying the run's most recent fire instead of the one the user confirmed. The two ends
 * are covered elsewhere (StepByStepContext.epochRerun, WorkflowRunManager.rerunGuard); the
 * middle hops are pinned here.
 *
 * The refusal path is pinned too: the backend owns state the canvas cannot see (a sibling
 * epoch that started between render and click), and a refusal that only reached the console
 * left the user pressing a button that did nothing and said nothing.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import * as React from 'react';
import { renderHook, act } from '@testing-library/react';
import { useStepByStepHandlers } from '../useStepByStepHandlers';
import { useWorkflowPauseResume } from '../useWorkflowPauseResume';
import { WorkflowRunProvider } from '@/contexts/WorkflowRunContext';
import { ApiError } from '@/lib/api/api-client';

/**
 * The manager is stubbed at the module boundary the provider resolves it through, so the two
 * middle hops are exercised for real and only the HTTP layer below them is replaced.
 */
const managerRerunStep = vi.fn(async (_stepId: string, _epoch?: number) => ({ ok: true }));
vi.mock('@/contexts/workflow-run', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/contexts/workflow-run')>();
  return {
    ...actual,
    getWorkflowRunManager: () => ({
      rerunStep: (stepId: string, epoch?: number) => managerRerunStep(stepId, epoch),
      subscribe: () => () => {},
      getState: () => undefined,
      initialize: async () => {},
      destroy: () => {},
      setCurrentPlanGetter: () => {},
      setStateUpdateCallback: () => {},
    }),
    deleteWorkflowRunManager: () => {},
  };
});
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ viewingEpoch: null, isRunMode: true }),
}));

// ---------------------------------------------------------------------------
// Hop 1: handleRerunStep -> pauseResumeActions.rerunStep
// ---------------------------------------------------------------------------

function handlersWith(rerunStep: (stepId: string, epoch?: number) => Promise<any>, onExecutionError?: any) {
  const actions = {
    pause: async () => {},
    resume: async () => {},
    reset: () => {},
    setMode: () => {},
    setExecutionMode: async () => {},
    executeStep: async () => {},
    executeCore: async () => null,
    rerunStep,
    resolveApproval: async () => {},
  };
  return renderHook(() => useStepByStepHandlers({
    pauseResumeState: { mode: 'automatic', isPaused: false },
    pauseResumeActions: actions as any,
    onExecutionError,
  })).result;
}

describe('useStepByStepHandlers.handleRerunStep', () => {
  it('forwards the epoch it was given', async () => {
    const rerunStep = vi.fn(async () => ({ ok: true }));
    const result = handlersWith(rerunStep);

    await act(async () => { await result.current.handleRerunStep('mcp:step_a', 2); });

    expect(rerunStep).toHaveBeenCalledWith('mcp:step_a', 2);
  });

  it('forwards epoch 0, which is a real first fire and not a missing value', async () => {
    const rerunStep = vi.fn(async () => ({ ok: true }));
    const result = handlersWith(rerunStep);

    await act(async () => { await result.current.handleRerunStep('mcp:step_a', 0); });

    expect(rerunStep).toHaveBeenCalledWith('mcp:step_a', 0);
  });

  it('forwards no epoch when none was chosen', async () => {
    const rerunStep = vi.fn(async () => ({ ok: true }));
    const result = handlersWith(rerunStep);

    await act(async () => { await result.current.handleRerunStep('mcp:step_a'); });

    expect(rerunStep).toHaveBeenCalledWith('mcp:step_a', undefined);
  });

  /** What apiClient actually throws. Its TYPE is what tells a refusal from a fault. */
  const httpError = (status: number, message: string) => new ApiError(message, status);

  const REFUSAL_TEXT = "Cannot restart epoch 1 while epoch(s) [3] are still executing. Wait for the run to settle (poll workflow(action='get_run', run_id='abc'))";

  it('reports a REFUSAL under its own type, so its agent-facing wording never reaches the user', async () => {
    // Reported, because a refusal that only reached the console left the user pressing a
    // button that did nothing and said nothing. Under `rerun_refused` rather than `generic`,
    // because `generic` renders the server's sentence verbatim - and these sentences are
    // written for the MCP agent: they name tool calls and pass raw run ids. The raw text
    // still travels for the console and support; the toast picks a translated string from
    // the type, so no MCP syntax reaches a builder user.
    const onExecutionError = vi.fn();
    const rerunStep = vi.fn(async () => { throw httpError(409, REFUSAL_TEXT); });
    const result = handlersWith(rerunStep, onExecutionError);

    let returned: unknown = 'not-settled';
    await act(async () => { returned = await result.current.handleRerunStep('mcp:step_a', 1); });

    expect(onExecutionError).toHaveBeenCalledWith({ type: 'rerun_refused', message: REFUSAL_TEXT });
    // Still resolves rather than rejecting: the callers treat null as "nothing happened".
    expect(returned).toBeNull();
  });

  it('reports a rejected REQUEST as a refusal too (400: an epoch this run does not have)', async () => {
    const onExecutionError = vi.fn();
    const rerunStep = vi.fn(async () => { throw httpError(400, 'Epoch 9 does not exist on this run'); });
    const result = handlersWith(rerunStep, onExecutionError);

    await act(async () => { await result.current.handleRerunStep('mcp:step_a', 9); });

    expect(onExecutionError).toHaveBeenCalledWith({
      type: 'rerun_refused',
      message: 'Epoch 9 does not exist on this run',
    });
  });

  it('does NOT dress a server FAULT as a refusal', async () => {
    // The refusal copy tells the user to wait for the run to settle and try again. On a 500 or
    // a dropped connection that is a wrong cause and an invitation to retry something retrying
    // cannot fix, so a fault keeps the generic path and shows what actually happened.
    const onExecutionError = vi.fn();
    const rerunStep = vi.fn(async () => { throw httpError(500, 'Failed to re-run from step: boom'); });
    const result = handlersWith(rerunStep, onExecutionError);

    await act(async () => { await result.current.handleRerunStep('mcp:step_a', 1); });

    expect(onExecutionError).toHaveBeenCalledWith({
      type: 'generic',
      message: 'Failed to re-run from step: boom',
    });
  });

  it('treats a 404 as a fault: the run is gone, waiting for it to settle is nonsense', async () => {
    const onExecutionError = vi.fn();
    const rerunStep = vi.fn(async () => { throw httpError(404, 'HTTP 404: Not Found'); });
    const result = handlersWith(rerunStep, onExecutionError);

    await act(async () => { await result.current.handleRerunStep('mcp:step_a', 1); });

    expect(onExecutionError).toHaveBeenCalledWith({ type: 'generic', message: 'HTTP 404: Not Found' });
  });

  it('does not take a look-alike for a refusal', async () => {
    // A bare Response carries `.status` too, and so does any object someone attaches one to.
    // Only an ApiError means the backend answered; anything else is a fault, and dressing it
    // as a refusal would name a cause nobody checked.
    const onExecutionError = vi.fn();
    const lookAlike = Object.assign(new Error('something with a status'), { status: 409 });
    const rerunStep = vi.fn(async () => { throw lookAlike; });
    const result = handlersWith(rerunStep, onExecutionError);

    await act(async () => { await result.current.handleRerunStep('mcp:step_a', 1); });

    expect(onExecutionError).toHaveBeenCalledWith({
      type: 'generic',
      message: 'something with a status',
    });
  });

  it('treats an error with no status as a fault, not a refusal', async () => {
    // Offline, aborted, thrown before the response existed: nothing refused anything.
    const onExecutionError = vi.fn();
    const rerunStep = vi.fn(async () => { throw new Error('Network request failed'); });
    const result = handlersWith(rerunStep, onExecutionError);

    await act(async () => { await result.current.handleRerunStep('mcp:step_a', 1); });

    expect(onExecutionError).toHaveBeenCalledWith({
      type: 'generic',
      message: 'Network request failed',
    });
  });
});

// ---------------------------------------------------------------------------
// Hop 2 + 3: useWorkflowPauseResume.rerunStep -> WorkflowRunContext.rerunStep
// ---------------------------------------------------------------------------

describe('useWorkflowPauseResume.rerunStep -> WorkflowRunContext -> manager', () => {
  const RUN_ID = 'run-1';

  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <WorkflowRunProvider>{children}</WorkflowRunProvider>
  );

  const rerunVia = async (...args: [string] | [string, number]) => {
    const { result } = renderHook(() => useWorkflowPauseResume(RUN_ID), { wrapper });
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    await act(async () => { await (result.current[1].rerunStep as any)(...args); });
    return managerRerunStep;
  };

  beforeEach(() => {
    managerRerunStep.mockClear();
  });

  it('carries the epoch through both hops down to the manager', async () => {
    expect(await rerunVia('mcp:step_a', 2)).toHaveBeenCalledWith('mcp:step_a', 2);
  });

  it('carries epoch 0 down to the manager', async () => {
    expect(await rerunVia('mcp:step_a', 0)).toHaveBeenCalledWith('mcp:step_a', 0);
  });

  it('sends no epoch when none was chosen', async () => {
    expect(await rerunVia('mcp:step_a')).toHaveBeenCalledWith('mcp:step_a', undefined);
  });
});
