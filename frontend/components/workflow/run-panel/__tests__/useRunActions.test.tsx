/**
 * @vitest-environment jsdom
 *
 * Stopping a run must WORK, from any surface.
 *
 * The regression this pins: the panel asked the canvas to stop the run by
 * dispatching a `CustomEvent`, and a `CustomEvent` nobody listens to is a
 * no-op. On every surface with no canvas mounted - and in the window between a
 * canvas unmounting and the click landing - pressing stop did nothing at all:
 * no request, no error, no status change, and a run the user could not stop.
 *
 * The protocol that replaces it has three rules, and all three are load-bearing:
 *   1. a canvas that CAN act claims the request and hands back its promise, so
 *      the caller knows when the work is done and whether it worked;
 *   2. a canvas that claims it and DECLINES (marketplace preview) is still an
 *      answer, so the fallback never works around a read-only surface;
 *   3. anything else falls through to the REST call rather than being swallowed.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

const api = vi.hoisted(() => ({
  stopWorkflow: vi.fn(async () => ({}) as never),
  cancelWorkflow: vi.fn(async () => ({}) as never),
  reactivateWorkflow: vi.fn(async () => ({}) as never),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: api }));

import {
  RUN_PANEL_ACTION_EVENT,
  clearRunPanelCache,
  makeEmptyRunPanelData,
  publishRunPanelData,
  type RunPanelActionDetail,
} from '../runPanelBus';
import { performRunAction, useRunActions } from '../useRunActions';

type CanvasOptions = {
  /** Claim the request and refuse it, the way a marketplace preview does. */
  decline?: boolean;
  /** Hear the request but be unable to act - no run bound, no provider. */
  incapable?: boolean;
  /** Reject the promise handed back, the way a failing REST call would. */
  fail?: Error;
};

/** Stand-in for a mounted canvas, following the real one's claim rules. */
function mountCanvas(workflowId: string, opts: CanvasOptions = {}) {
  const seen: RunPanelActionDetail[] = [];
  const handler = (e: Event) => {
    const detail = (e as CustomEvent<RunPanelActionDetail>).detail;
    if (detail.workflowId && detail.workflowId !== workflowId) return;
    if (detail.handled) return;
    if (opts.decline) { detail.handled = true; return; }
    if (opts.incapable) return;
    detail.handled = true;
    seen.push(detail);
    detail.result = opts.fail ? Promise.reject(opts.fail) : Promise.resolve();
  };
  window.addEventListener(RUN_PANEL_ACTION_EVENT, handler);
  return { seen, unmount: () => window.removeEventListener(RUN_PANEL_ACTION_EVENT, handler) };
}

beforeEach(() => {
  api.stopWorkflow.mockClear();
  api.cancelWorkflow.mockClear();
  api.reactivateWorkflow.mockClear();
  clearRunPanelCache();
});
afterEach(() => { clearRunPanelCache(); cleanup(); });

describe('performRunAction', () => {
  it('lets the canvas act when one is mounted, and makes no REST call of its own', async () => {
    const canvas = mountCanvas('wf-1');
    try {
      await performRunAction('stop', { workflowId: 'wf-1', runId: 'run-1' });
    } finally {
      canvas.unmount();
    }

    expect(canvas.seen.map(d => d.action)).toEqual(['stop']);
    expect(api.stopWorkflow).not.toHaveBeenCalled();
  });

  it('waits for the canvas it delegated to, instead of returning before the work', async () => {
    // Without this the caller's "in flight" state cleared in the next microtask
    // and a canvas-side failure surfaced nowhere the user could see it.
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const handler = (e: Event) => {
      const detail = (e as CustomEvent<RunPanelActionDetail>).detail;
      detail.handled = true;
      detail.result = gate;
    };
    window.addEventListener(RUN_PANEL_ACTION_EVENT, handler);
    let settled = false;
    try {
      const pending = performRunAction('stop', { workflowId: 'wf-1', runId: 'run-1' }).then(() => { settled = true; });
      await Promise.resolve();
      expect(settled).toBe(false);
      release();
      await pending;
    } finally {
      window.removeEventListener(RUN_PANEL_ACTION_EVENT, handler);
    }
    expect(settled).toBe(true);
  });

  it('surfaces a failure from the canvas it delegated to', async () => {
    const canvas = mountCanvas('wf-1', { fail: new Error('canvas said no') });
    try {
      await expect(performRunAction('stop', { workflowId: 'wf-1', runId: 'run-1' }))
        .rejects.toThrow('canvas said no');
    } finally {
      canvas.unmount();
    }
  });

  it('stops the run over REST when no canvas is listening', async () => {
    await performRunAction('stop', { workflowId: 'wf-1', runId: 'run-1' });
    expect(api.stopWorkflow).toHaveBeenCalledWith('run-1');
  });

  it('cancels over REST when no canvas is listening', async () => {
    await performRunAction('cancel', { workflowId: 'wf-1', runId: 'run-1' });
    expect(api.cancelWorkflow).toHaveBeenCalledWith('run-1');
    expect(api.stopWorkflow).not.toHaveBeenCalled();
  });

  it('reactivates over REST when no canvas is listening', async () => {
    await performRunAction('reactivate', { workflowId: 'wf-1', runId: 'run-1' });
    expect(api.reactivateWorkflow).toHaveBeenCalledWith('run-1');
  });

  it('takes over from a canvas that heard the request but could not act on it', async () => {
    // The exact shape of the original bug: a listener that swallows the request
    // and does nothing. It must not count as an answer.
    const canvas = mountCanvas('wf-1', { incapable: true });
    try {
      await performRunAction('stop', { workflowId: 'wf-1', runId: 'run-1' });
    } finally {
      canvas.unmount();
    }
    expect(api.stopWorkflow).toHaveBeenCalledWith('run-1');
  });

  it('does NOT work around a canvas that claimed the run and declined it', async () => {
    // A marketplace preview declines: acting anyway would fire the publisher's
    // workflow from a surface that is read-only by contract.
    const canvas = mountCanvas('wf-1', { decline: true });
    try {
      await performRunAction('stop', { workflowId: 'wf-1', runId: 'run-1' });
    } finally {
      canvas.unmount();
    }
    expect(api.stopWorkflow).not.toHaveBeenCalled();
  });

  it('is taken once when two canvases of the same workflow are mounted', async () => {
    // A self-referencing sub-workflow mounts two. One action, not two.
    const first = mountCanvas('wf-1');
    const second = mountCanvas('wf-1');
    try {
      await performRunAction('stop', { workflowId: 'wf-1', runId: 'run-1' });
    } finally {
      first.unmount();
      second.unmount();
    }
    expect(first.seen.length + second.seen.length).toBe(1);
    expect(api.stopWorkflow).not.toHaveBeenCalled();
  });

  it('ignores a canvas bound to another workflow and still stops the run', async () => {
    const other = mountCanvas('wf-OTHER');
    try {
      await performRunAction('stop', { workflowId: 'wf-1', runId: 'run-1' });
    } finally {
      other.unmount();
    }
    expect(other.seen).toHaveLength(0);
    expect(api.stopWorkflow).toHaveBeenCalledWith('run-1');
  });

  it('falls back to the run the bus knows when the caller names none', async () => {
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-bus'), runId: 'run-from-bus' });
    await performRunAction('stop', { workflowId: 'wf-bus' });
    expect(api.stopWorkflow).toHaveBeenCalledWith('run-from-bus');
  });

  it('refuses rather than calling the API with no run at all', async () => {
    await expect(performRunAction('stop', { workflowId: 'wf-nothing' })).rejects.toThrow();
    expect(api.stopWorkflow).not.toHaveBeenCalled();
  });

  it('is a no-op outside the browser, rather than throwing on the server', async () => {
    // The bus is imported by components that render on the server; a dispatch
    // there must fall through to the REST branch, not blow up the render.
    const win = globalThis.window;
    delete (globalThis as { window?: unknown }).window;
    try {
      await performRunAction('stop', { workflowId: 'wf-ssr', runId: 'run-ssr' });
    } finally {
      globalThis.window = win;
    }
    expect(api.stopWorkflow).toHaveBeenCalledWith('run-ssr');
  });

  it('refuses to dispatch an UNADDRESSED request, which every canvas would claim', async () => {
    const foreign = mountCanvas('wf-SOMEONE-ELSE');
    try {
      await expect(performRunAction('stop', { workflowId: '', runId: 'run-1' })).rejects.toThrow();
    } finally {
      foreign.unmount();
    }
    expect(foreign.seen).toHaveLength(0);
    expect(api.stopWorkflow).not.toHaveBeenCalled();
  });
});

/**
 * Renders the hook and exposes its latest value, a render count, and the run id
 * seen at EVERY render.
 *
 * The history is recorded in an EFFECT, not during render, and that is the whole
 * point: a render-phase resync re-invokes the component before React commits, so
 * the discarded pass never reaches an effect and the trail holds only values that
 * were actually committed. An effect-based resync would commit the previous run
 * first, and that entry is what this catches. Reading the value after `act()`
 * cannot tell the two apart, because `act` flushes effects before the assertion.
 */
function renderHook(workflowId: string | null, fallbackRunId?: string | null) {
  const ref: {
    current: ReturnType<typeof useRunActions> | null;
    renders: number;
    runIdHistory: (string | null)[];
    statusHistory: (string | undefined)[];
    rerender: (nextWorkflowId: string | null) => void;
  } = { current: null, renders: 0, runIdHistory: [], statusHistory: [], rerender: () => undefined };

  function Probe({ id }: { id: string | null }) {
    ref.renders += 1;
    const state = useRunActions(id, fallbackRunId);
    ref.current = state;
    // Every commit, with no dependency array: an effect-based resync commits the
    // PREVIOUS run id unchanged before correcting it, and a dep array keyed on the
    // value would skip exactly that entry.
    React.useEffect(() => {
      ref.runIdHistory.push(state.runId);
      // The STATUS too, and it is the one that matters: `runId` is separately
      // guaranteed by the preferred-run expression, so a suite watching only
      // that cannot see the resync disappear.
      ref.statusHistory.push(state.status);
    });
    return null;
  }
  const view = render(<Probe id={workflowId} />);
  ref.rerender = (nextWorkflowId) => { view.rerender(<Probe id={nextWorkflowId} />); };
  return ref;
}

/**
 * A canvas mounted as a CHILD of the hook's host, publishing in its own effect.
 *
 * This is the real mount order on the workflow and application panels, and the
 * reason the hook re-reads the cache before subscribing: child effects run
 * first, so the publish has already happened by the time the parent subscribes.
 */
function ChildCanvas({ workflowId, runId, status }: { workflowId: string; runId: string; status: string }) {
  React.useEffect(() => {
    publishRunPanelData({
      ...makeEmptyRunPanelData(workflowId),
      runId,
      runInfo: { runId, status },
    } as never);
  }, [workflowId, runId, status]);
  return null;
}

/** Renders the hook against a fixed workflow, with a changeable preferred run. */
function renderHookOnRun(workflowId: string, runId: string) {
  const ref: {
    current: ReturnType<typeof useRunActions> | null;
    rerenderRun: (next: string) => void;
  } = { current: null, rerenderRun: () => undefined };
  function Probe({ run }: { run: string }) {
    ref.current = useRunActions(workflowId, run);
    return null;
  }
  const view = render(<Probe run={runId} />);
  ref.rerenderRun = (next) => { view.rerender(<Probe run={next} />); };
  return ref;
}

describe('useRunActions', () => {
  it('reads the bound run and its status off the bus', () => {
    publishRunPanelData({
      ...makeEmptyRunPanelData('wf-2'),
      runId: 'run-2',
      runInfo: { status: 'RUNNING' },
      pinnedVersion: 7,
    });
    const hook = renderHook('wf-2');
    expect(hook.current?.runId).toBe('run-2');
    expect(hook.current?.status).toBe('RUNNING');
    expect(hook.current?.pinnedVersion).toBe(7);
  });

  it('adopts a snapshot published BEFORE it mounted, without waiting for the next one', () => {
    // The canvas is a CHILD of some of these hosts, so it has already published
    // by the time this hook subscribes. A live run self-heals on its next tick; a
    // run parked on an interface never publishes again.
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-early'), runId: 'run-early', runInfo: { status: 'RUNNING' } });
    const hook = renderHook('wf-early');
    expect(hook.current?.status).toBe('RUNNING');
  });

  it('follows the run as it changes status, so the control appears and disappears with it', () => {
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-3'), runId: 'run-3', runInfo: { status: 'WAITING_TRIGGER' } });
    const hook = renderHook('wf-3');
    expect(hook.current?.status).toBe('WAITING_TRIGGER');

    act(() => {
      publishRunPanelData({ ...makeEmptyRunPanelData('wf-3'), runId: 'run-3', runInfo: { status: 'RUNNING' } });
    });
    expect(hook.current?.status).toBe('RUNNING');
  });

  it('does not re-render on the identical snapshots a live run republishes', () => {
    // A running workflow republishes several times a second as its steps stream.
    // Projecting primitives (rather than holding the snapshot) is the whole point,
    // and this is the only thing that would catch a regression to holding it.
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-quiet'), runId: 'run-q', runInfo: { status: 'RUNNING' } });
    const hook = renderHook('wf-quiet');
    const before = hook.renders;

    act(() => {
      for (let i = 0; i < 5; i++) {
        publishRunPanelData({
          ...makeEmptyRunPanelData('wf-quiet'),
          runId: 'run-q',
          runInfo: { status: 'RUNNING' },
          // What actually churns on a live run, and what must NOT reach a surface.
          streamedSteps: [{ alias: `step-${i}` }] as never,
        });
      }
    });

    expect(hook.renders).toBe(before);
    expect(hook.current?.status).toBe('RUNNING');
  });

  it('switches to the new workflow in the SAME render, never showing the previous run', () => {
    // Doing this in an effect would paint one frame offering a stop for a run
    // this surface no longer shows - the same bug class, pointed the other way.
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-a'), runId: 'run-a', runInfo: { status: 'RUNNING' } });
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-b'), runId: 'run-b', runInfo: { status: 'COMPLETED' } });
    const hook = renderHook('wf-a');
    const before = hook.runIdHistory.length;

    act(() => { hook.rerender('wf-b'); });

    // NOT a post-act read: every frame rendered after the switch must already
    // name the new run. An effect-based resync paints 'run-a' once first, and
    // that frame is what this catches.
    expect(hook.runIdHistory.slice(before)).not.toContain('run-a');
    // Without the render-phase resync the first COMMITTED frame after the switch
    // still reports the previous workflow's status - so the surface offers a stop
    // for a run that has already finished.
    expect(hook.statusHistory.slice(before)).not.toContain('RUNNING');
    expect(hook.current?.runId).toBe('run-b');
    expect(hook.current?.status).toBe('COMPLETED');
  });

  it('does not carry a failure onto the next workflow it is pointed at', async () => {
    // The mark describes an attempt on the PREVIOUS run. Left standing, a
    // healthy run of the new workflow paints the red ring and announces "this
    // did not work" the moment the surface switches.
    api.stopWorkflow.mockRejectedValueOnce(new Error('boom'));
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-x'), runId: 'run-x', runInfo: { status: 'RUNNING' } });
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-y'), runId: 'run-y', runInfo: { status: 'RUNNING' } });
    const hook = renderHook('wf-x');
    await act(async () => { hook.current?.perform('stop'); });
    expect(hook.current?.failed).toBe(true);

    act(() => { hook.rerender('wf-y'); });

    expect(hook.current?.failed).toBe(false);
    errors.mockRestore();
  });

  it('does not carry a failure onto another RUN of the same workflow', async () => {
    // Picking a run in the history changes the run without changing the
    // workflow, and the mark describes one attempt on one run.
    api.stopWorkflow.mockRejectedValueOnce(new Error('boom'));
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-same'), runId: 'run-1', runInfo: { status: 'RUNNING' } });
    const hook = renderHookOnRun('wf-same', 'run-1');
    await act(async () => { hook.current?.perform('stop'); });
    expect(hook.current?.failed).toBe(true);

    act(() => { hook.rerenderRun('run-2'); });

    expect(hook.current?.failed).toBe(false);
    errors.mockRestore();
  });

  it('does not carry an in-flight action onto the next workflow either', async () => {
    // Worse than the mark: the new workflow's stop would be disabled and
    // spinning until a promise about a different workflow settles.
    let release!: () => void;
    api.stopWorkflow.mockImplementationOnce(() => new Promise((resolve) => {
      release = () => resolve({} as never);
    }));
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-p'), runId: 'run-p', runInfo: { status: 'RUNNING' } });
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-q'), runId: 'run-q', runInfo: { status: 'RUNNING' } });
    const hook = renderHook('wf-p');
    await act(async () => { hook.current?.perform('stop'); });
    expect(hook.current?.pending).toBe('stop');

    act(() => { hook.rerender('wf-q'); });

    expect(hook.current?.pending).toBeNull();
    await act(async () => { release(); });
  });

  it('does not stop the spinner of the run it moved to, when an old request settles', async () => {
    // The mirror of the late-failure case, and the more harmful one: clearing
    // `pending` re-enables the stop of a run whose own request is still in
    // flight, so the user can fire a second one.
    let releaseFirst!: () => void;
    api.stopWorkflow
      .mockImplementationOnce(() => new Promise((resolve) => { releaseFirst = () => resolve({} as never); }))
      .mockImplementationOnce(() => new Promise(() => { /* never settles */ }));
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-spin'), runId: 'run-1', runInfo: { status: 'RUNNING' } });
    const hook = renderHookOnRun('wf-spin', 'run-1');
    await act(async () => { hook.current?.perform('stop'); });

    act(() => { hook.rerenderRun('run-2'); });
    await act(async () => { hook.current?.perform('stop'); });
    expect(hook.current?.pending).toBe('stop');

    await act(async () => { releaseFirst(); });

    // run-1's request settled; run-2's has not, so its control keeps spinning.
    expect(hook.current?.pending).toBe('stop');
  });

  it('does not paint a LATE failure on the run the surface moved to', async () => {
    // Guarding only the start is not enough: a request that rejects after the
    // switch would mark a healthy run as failed.
    let reject!: (e: Error) => void;
    api.stopWorkflow.mockImplementationOnce(() => new Promise((_resolve, rej) => {
      reject = rej;
    }));
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-late'), runId: 'run-1', runInfo: { status: 'RUNNING' } });
    const hook = renderHookOnRun('wf-late', 'run-1');
    await act(async () => { hook.current?.perform('stop'); });

    act(() => { hook.rerenderRun('run-2'); });
    await act(async () => { reject(new Error('too late')); });

    expect(hook.current?.failed).toBe(false);
    errors.mockRestore();
  });

  it('still paints a failure that settles while the surface has NOT moved', async () => {
    api.stopWorkflow.mockRejectedValueOnce(new Error('nope'));
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-still'), runId: 'run-1', runInfo: { status: 'RUNNING' } });
    const hook = renderHookOnRun('wf-still', 'run-1');

    await act(async () => { hook.current?.perform('stop'); });

    expect(hook.current?.failed).toBe(true);
    errors.mockRestore();
  });

  it('picks up a canvas that published while it was mounting, as a child does', () => {
    // Child effects run before the parent's, so the publish lands between this
    // hook's initial read and its subscription. A live run would self-heal on its
    // next tick; a run parked on an interface never publishes again.
    const ref: { current: ReturnType<typeof useRunActions> | null } = { current: null };
    function Host() {
      ref.current = useRunActions('wf-child');
      return <ChildCanvas workflowId="wf-child" runId="run-child" status="RUNNING" />;
    }
    render(<Host />);

    expect(ref.current?.runId).toBe('run-child');
    expect(ref.current?.status).toBe('RUNNING');
  });

  it('holds no run and does nothing without a workflow', async () => {
    const hook = renderHook(null);
    expect(hook.current?.runId).toBeNull();
    expect(hook.current?.status).toBeUndefined();

    // Awaited: without the guard the request is built and REJECTS, and the mark
    // is written a microtask later - a synchronous act would assert too early.
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    await act(async () => { hook.current?.perform('stop'); });
    errors.mockRestore();

    expect(api.stopWorkflow).not.toHaveBeenCalled();
    // And it does not pretend to have tried: without the guard the request is
    // built, throws, and the control is marked failed for a run that never was.
    expect(hook.current?.failed).toBe(false);
    expect(hook.current?.pending).toBeNull();
  });

  it('ignores snapshots published for another workflow', () => {
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-4'), runId: 'run-4', runInfo: { status: 'RUNNING' } });
    const hook = renderHook('wf-4');

    act(() => {
      publishRunPanelData({ ...makeEmptyRunPanelData('wf-5'), runId: 'run-5', runInfo: { status: 'FAILED' } });
    });
    expect(hook.current?.runId).toBe('run-4');
    expect(hook.current?.status).toBe('RUNNING');
  });

  it('acts on the run the caller named when the bus knows none (an application page)', async () => {
    publishRunPanelData(makeEmptyRunPanelData('wf-6'));
    const hook = renderHook('wf-6', 'run-from-props');

    await act(async () => { hook.current?.perform('stop'); });

    expect(api.stopWorkflow).toHaveBeenCalledWith('run-from-props');
  });

  it('names the action in flight, which is what the control spins on', async () => {
    let release!: () => void;
    api.stopWorkflow.mockImplementationOnce(() => new Promise((resolve) => {
      release = () => resolve({} as never);
    }));
    const hook = renderHook('wf-pending', 'run-pending');

    await act(async () => { hook.current?.perform('stop'); });
    expect(hook.current?.pending).toBe('stop');

    await act(async () => { release(); });
    expect(hook.current?.pending).toBeNull();
  });

  it('carries the frozen-preview flag its surfaces gate on', () => {
    publishRunPanelData({
      ...makeEmptyRunPanelData('wf-frozen'),
      runId: 'run-frozen',
      runInfo: { status: 'RUNNING' },
      isPreviewOnly: true,
    });
    const hook = renderHook('wf-frozen');
    expect(hook.current?.isPreviewOnly).toBe(true);
  });

  it('prefers the run its SURFACE names over the one the bus still holds', () => {
    // The panel binds the run the user just picked before the canvas rebinds, so
    // the bus still names the previous one. Acting on the bus id would stop a
    // different run from the bar the button sits in.
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-pick'), runId: 'run-OLD', runInfo: { status: 'RUNNING' } });
    const hook = renderHook('wf-pick', 'run-JUST-PICKED');
    expect(hook.current?.runId).toBe('run-JUST-PICKED');
  });

  it('acts on the run its surface named, not the stale one on the bus', async () => {
    publishRunPanelData({ ...makeEmptyRunPanelData('wf-pick2'), runId: 'run-OLD', runInfo: { status: 'RUNNING' } });
    const hook = renderHook('wf-pick2', 'run-JUST-PICKED');

    await act(async () => { hook.current?.perform('stop'); });

    expect(api.stopWorkflow).toHaveBeenCalledWith('run-JUST-PICKED');
  });

  it('reports the failure instead of swallowing it, and clears pending either way', async () => {
    api.stopWorkflow.mockRejectedValueOnce(new Error('Run not found'));
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const hook = renderHook('wf-7', 'run-7');

    await act(async () => { hook.current?.perform('stop'); });

    expect(hook.current?.failed).toBe(true);
    expect(hook.current?.pending).toBeNull();
    errors.mockRestore();
  });

  it('clears a previous failure when the next attempt starts', async () => {
    api.stopWorkflow.mockRejectedValueOnce(new Error('boom'));
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const hook = renderHook('wf-8', 'run-8');
    await act(async () => { hook.current?.perform('stop'); });
    expect(hook.current?.failed).toBe(true);

    await act(async () => { hook.current?.perform('stop'); });

    expect(hook.current?.failed).toBe(false);
    errors.mockRestore();
  });
});
