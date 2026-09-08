/**
 * @vitest-environment jsdom
 *
 * A rerun on an AUTOMATIC run must be confirmed first.
 *
 * On a stepped run the rerun stops at the target and waits for the user, so it costs
 * nothing. In automatic mode the SAME click reruns the target and then lets the whole
 * downstream chain run again unattended, spending paid calls and sending real messages.
 * The gate lives in the provider so every rerun surface (canvas bar, context menu,
 * inspector) inherits it from the one `rerunStep` they all call.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as React from 'react';
import { render, screen, cleanup, fireEvent, act, waitFor } from '@testing-library/react';
import { StepByStepProvider, useStepByStep } from '../StepByStepContext';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: vi.fn(() => ({ viewingEpoch: null, isRunMode: true })),
}));

// Key-echo translations (same pattern as the other component tests).
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

const STEP = 'mcp:step_a';

/** Captures the context so the test can drive `rerunStep` exactly like a UI surface does. */
let captured: ReturnType<typeof useStepByStep> = null;
function Probe() {
  captured = useStepByStep();
  return null;
}

function renderWith(isEnabled: boolean, onRerunStep: (stepId: string, epoch?: number) => Promise<any>) {
  return render(
    <StepByStepProvider
      isEnabled={isEnabled}
      isPaused={false}
      readySteps={new Set<string>()}
      completedSteps={new Set<string>([STEP])}
      failedSteps={new Set<string>()}
      onExecuteStep={async () => undefined}
      onRerunStep={onRerunStep}
      currentEpoch={1}
    >
      <Probe />
    </StepByStepProvider>
  );
}

describe('rerun confirmation on an automatic run', () => {
  beforeEach(() => {
    captured = null;
    vi.mocked(useWorkflowMode).mockReturnValue(
      { viewingEpoch: null, isRunMode: true } as ReturnType<typeof useWorkflowMode>,
    );
  });
  afterEach(cleanup);

  it('asks for confirmation instead of rerunning immediately', async () => {
    const onRerunStep = vi.fn(async () => ({ ok: true }) as any);
    renderWith(false, onRerunStep);

    act(() => { void captured!.rerunStep(STEP); });

    await screen.findByRole('dialog');
    // The whole point: nothing has been sent to the backend yet.
    expect(onRerunStep).not.toHaveBeenCalled();
  });

  it('names the step the restart would start from', async () => {
    renderWith(false, vi.fn(async () => null));
    act(() => { void captured!.rerunStep(STEP); });

    await screen.findByRole('dialog');
    // Humanized from the backend step id so the user can vet WHICH node restarts.
    expect(screen.getByText('step a')).toBeTruthy();
  });

  it('names WHICH fire of the run it redoes when an epoch was chosen', async () => {
    // A run keeps one set of results per fire, so the node alone does not say what is about to
    // be re-executed: the same restart redoes different work on epoch 2 than on epoch 9. On a
    // run that then continues unattended, that is the fact the confirmation exists to show.
    renderWith(false, vi.fn(async () => null));
    act(() => { void captured!.rerunStep(STEP, 2); });

    await screen.findByRole('dialog');
    // next-intl is stubbed to echo the key, so this pins the key rather than the English text.
    expect(screen.getByTestId('rerun-confirm-scope').textContent).toBe('scopeEpoch');
  });

  it('says so when it redoes the run latest fire instead', async () => {
    // Both branches are named: silence would read as "epoch unknown" on the view where the
    // answer is knowable and matters.
    renderWith(false, vi.fn(async () => null));
    act(() => { void captured!.rerunStep(STEP); });

    await screen.findByRole('dialog');
    expect(screen.getByTestId('rerun-confirm-scope').textContent).toBe('scopeLatestEpoch');
  });

  it('confirms the SECOND click epoch when a second rerun supersedes the first', async () => {
    // The gate holds one pending rerun. A user who clicks epoch 2, then navigates and clicks
    // epoch 1 before confirming, must not have epoch 2 replayed by the button they are
    // looking at - the record carries the epoch, so it has to be replaced with the step.
    const onRerunStep = vi.fn(async () => null);
    renderWith(false, onRerunStep);
    act(() => { void captured!.rerunStep(STEP, 2); });
    await screen.findByRole('dialog');
    act(() => { void captured!.rerunStep(STEP, 1); });

    await act(async () => { fireEvent.click(screen.getByTestId('rerun-confirm-accept')); });

    expect(onRerunStep).toHaveBeenCalledTimes(1);
    expect(onRerunStep).toHaveBeenCalledWith(STEP, 1);
  });

  it('confirms the epoch the user was shown, not the run newest one', async () => {
    // The pending record parks the caller's promise; an epoch dropped there would replay the
    // most recent fire AFTER the user approved an older one, and report success.
    const onRerunStep = vi.fn(async () => null);
    renderWith(false, onRerunStep);
    act(() => { void captured!.rerunStep(STEP, 2); });

    await screen.findByRole('dialog');
    await act(async () => { fireEvent.click(screen.getByTestId('rerun-confirm-accept')); });

    expect(onRerunStep).toHaveBeenCalledWith(STEP, 2);
  });

  it('runs the rerun once the user confirms, and returns its result', async () => {
    const response = { runId: 'r1' } as any;
    const onRerunStep = vi.fn(async () => response);
    renderWith(false, onRerunStep);

    let result: unknown = 'not-settled';
    act(() => { void captured!.rerunStep(STEP).then((r) => { result = r; }); });
    await screen.findByRole('dialog');

    await act(async () => { fireEvent.click(screen.getByTestId('rerun-confirm-accept')); });

    expect(onRerunStep).toHaveBeenCalledTimes(1);
    // undefined epoch = "replay the run's most recent fire", the caller's own value carried
    // through the confirmation gate unchanged.
    expect(onRerunStep).toHaveBeenCalledWith(STEP, undefined);
    await waitFor(() => expect(result).toBe(response));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('reruns nothing when the user cancels, and settles the caller with null', async () => {
    const onRerunStep = vi.fn(async () => ({}) as any);
    renderWith(false, onRerunStep);

    let result: unknown = 'not-settled';
    act(() => { void captured!.rerunStep(STEP).then((r) => { result = r; }); });
    await screen.findByRole('dialog');

    await act(async () => { fireEvent.click(screen.getByTestId('rerun-confirm-cancel')); });

    expect(onRerunStep).not.toHaveBeenCalled();
    // A dismissed rerun is a no-op, not an error: callers already handle null.
    await waitFor(() => expect(result).toBeNull());
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('dismisses on a click outside the card', async () => {
    const onRerunStep = vi.fn(async () => null);
    renderWith(false, onRerunStep);
    act(() => { void captured!.rerunStep(STEP); });
    await screen.findByRole('dialog');

    await act(async () => { fireEvent.click(screen.getByTestId('rerun-confirm-overlay')); });

    expect(onRerunStep).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('dismisses on Escape', async () => {
    const onRerunStep = vi.fn(async () => null);
    renderWith(false, onRerunStep);
    act(() => { void captured!.rerunStep(STEP); });
    await screen.findByRole('dialog');

    await act(async () => { fireEvent.keyDown(document, { key: 'Escape' }); });

    expect(onRerunStep).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('propagates a backend failure to the caller, exactly like the ungated path', async () => {
    const boom = new Error('rerun refused');
    const onRerunStep = vi.fn(async () => { throw boom; });
    renderWith(false, onRerunStep);

    let caught: unknown = null;
    act(() => { void captured!.rerunStep(STEP).catch((e) => { caught = e; }); });
    await screen.findByRole('dialog');

    await act(async () => { fireEvent.click(screen.getByTestId('rerun-confirm-accept')); });

    await waitFor(() => expect(caught).toBe(boom));
  });
});

describe('rerun on a stepped run', () => {
  beforeEach(() => {
    captured = null;
    vi.mocked(useWorkflowMode).mockReturnValue(
      { viewingEpoch: null, isRunMode: true } as ReturnType<typeof useWorkflowMode>,
    );
  });
  afterEach(cleanup);

  it('reruns straight away with no confirmation', async () => {
    const response = { runId: 'r2' } as any;
    const onRerunStep = vi.fn(async () => response);
    renderWith(true, onRerunStep);

    let result: unknown = 'not-settled';
    await act(async () => { result = await captured!.rerunStep(STEP); });

    // The stepped rerun stops at the target and waits for the user: nothing to confirm.
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(onRerunStep).toHaveBeenCalledWith(STEP, undefined);
    expect(result).toBe(response);
  });
});
