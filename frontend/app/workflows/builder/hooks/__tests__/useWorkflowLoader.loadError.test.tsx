// @vitest-environment jsdom
/**
 * Regression (2026-06-12 prod): when the plan fetch failed (e.g. the post-create
 * redirect pointed at a 404 workflow), useWorkflowLoader silently returned -
 * workflowLoaded never turned true, dirty-tracking never armed, and the builder
 * showed an empty canvas with a permanently disabled Save button and no error.
 * The hook now surfaces `loadError` and offers `retryLoad`.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import * as React from 'react';

const getWorkflow = vi.fn();
const getRun = vi.fn();
const getLatestWorkflowRun = vi.fn();
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflow: (id: string) => getWorkflow(id),
    getRun: (id: string) => getRun(id),
    getLatestWorkflowRun: (id: string) => getLatestWorkflowRun(id),
  },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({ getActivePublicPreview: () => null }));
vi.mock('../../services/workflowPlanImporter/WorkflowPlanImporter', () => ({
  WorkflowPlanImporter: {
    importPlan: vi.fn().mockResolvedValue({ success: true, nodes: [], edges: [], validation: { isValid: true } }),
  },
}));
vi.mock('../../registry/nodeRegistry', () => ({ nodeRegistry: { isLoopNode: () => false } }));
vi.mock('../../services/statusUpdater', () => ({
  updateNodesFromBatchSteps: (n: unknown) => n,
  updateDecisionNodesFromPredecessors: (n: unknown) => n,
}));
vi.mock('../../services/edgeStatusService', () => ({
  updateEdgesFromBatch: (e: unknown) => e,
  updateLoopInternalEdges: (e: unknown) => e,
}));

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useWorkflowLoader } from '../useWorkflowLoader';
import { WorkflowPlanImporter } from '../../services/workflowPlanImporter/WorkflowPlanImporter';

/**
 * The loader reads the canvas's query client to hand it to the importer (the interface
 * format lookup shares the node's own cache entry), so it needs a provider - as the rest
 * of the builder already did through `useWorkflowEventListeners`.
 */
function withQueryClient({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client }, children);
}

/** Same hook instance across renders, so a ref that should be cleared can be seen not to be. */
function renderHookWithRunId(initialRunId?: string) {
  return renderHook(
    ({ runId }: { runId?: string }) => {
      const nodesRef = React.useRef([]);
      const edgesRef = React.useRef([]);
      return useWorkflowLoader({
        workflowId: 'wf-404',
        runId,
        planOverride: undefined,
        setNodes: vi.fn(),
        setEdges: vi.fn(),
        nodesRef,
        edgesRef,
      } as any);
    },
    { wrapper: withQueryClient, initialProps: { runId: initialRunId } },
  );
}

function renderLoader(runId?: string) {
  return renderHook(() => {
    const nodesRef = React.useRef([]);
    const edgesRef = React.useRef([]);
    return useWorkflowLoader({
      workflowId: 'wf-404',
      runId,
      planOverride: undefined,
      setNodes: vi.fn(),
      setEdges: vi.fn(),
      nodesRef,
      edgesRef,
    } as any);
  }, { wrapper: withQueryClient });
}

beforeEach(() => {
  getWorkflow.mockReset();
  getRun.mockReset();
  vi.mocked(WorkflowPlanImporter.importPlan).mockResolvedValue({
    success: true, nodes: [], edges: [], validation: { isValid: true },
  } as any);
});

describe('useWorkflowLoader - load failure surfaces loadError + retryLoad', () => {
  it('sets loadError when the edit-mode plan fetch fails (pre-fix: silent dead builder)', async () => {
    getWorkflow.mockRejectedValue(new Error('404'));
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.loadError).toBe(true));
    // The old symptom: never "loaded", so dirty-tracking would never arm.
    expect(result.current.workflowLoaded).toBe(false);
    expect(result.current.isLoadingWorkflow).toBe(false);
  });

  it('sets loadError when the run-mode plan fetch (getRun) fails', async () => {
    getRun.mockRejectedValue(new Error('run 404'));
    const { result } = renderLoader('run-123');

    await waitFor(() => expect(result.current.loadError).toBe(true));
    expect(result.current.workflowLoaded).toBe(false);
    expect(getWorkflow).not.toHaveBeenCalled();
  });

  it('sets loadError when the plan import fails (same dead-builder class as a failed fetch)', async () => {
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [{ id: 's1' }], edges: [] } });
    vi.mocked(WorkflowPlanImporter.importPlan).mockResolvedValue({
      success: false, error: 'boom', nodes: [], edges: [], validation: { isValid: false },
    } as any);
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.loadError).toBe(true));
    expect(result.current.workflowLoaded).toBe(false);
  });

  it('sets loadError on an unexpected mid-load throw (outer catch)', async () => {
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [{ id: 's1' }], edges: [] } });
    vi.mocked(WorkflowPlanImporter.importPlan).mockRejectedValue(new Error('unexpected'));
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.loadError).toBe(true));
    expect(result.current.workflowLoaded).toBe(false);
    expect(result.current.isLoadingWorkflow).toBe(false);
  });

  it('retryLoad clears the error and a successful re-fetch marks the workflow loaded', async () => {
    getWorkflow.mockRejectedValueOnce(new Error('transient'));
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [], edges: [] } });
    const { result } = renderLoader();

    await waitFor(() => expect(result.current.loadError).toBe(true));

    act(() => result.current.retryLoad());

    await waitFor(() => expect(result.current.workflowLoaded).toBe(true));
    expect(result.current.loadError).toBe(false);
    expect(getWorkflow).toHaveBeenCalledTimes(2);
  });
});

describe('useWorkflowLoader - tells the importer what surface it is', () => {
  /**
   * The interface-format lookup is skipped on a canvas showing a RUN: a run paints the
   * format frozen in its render snapshot, so asking for the live one would be a request
   * per page for the wrong answer. The creator honours the flag (covered in
   * NodeCreationService.interfaceBox), but only the loader knows a run is being shown -
   * and a unit test one level down cannot see that it forgot to say so.
   */
  // The LAST call: the shared mock is not cleared between tests in this file, so an
  // earlier one would answer for this test's render.
  const lastContext = () => {
    const calls = vi.mocked(WorkflowPlanImporter.importPlan).mock.calls;
    return calls[calls.length - 1][3];
  };

  it('regression: names the run so the import skips the format lookup', async () => {
    // Run mode reads the plan off the RUN, and still asks for the workflow metadata.
    getRun.mockResolvedValue({ id: 'run-uuid-7', plan: { triggers: [], mcps: [], edges: [] } });
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [], edges: [] } });

    vi.mocked(WorkflowPlanImporter.importPlan).mockClear();
    renderLoader('run-7');

    await waitFor(() => expect(WorkflowPlanImporter.importPlan).toHaveBeenCalled());
    // The id as well as the flag: a run canvas resolves its page formats from the
    // snapshots that run froze, so the flag alone would resolve nothing.
    expect(lastContext()).toMatchObject({ isRunMode: true, workflowRunId: 'run-uuid-7' });
  });

  it('regression: forgets the previous run, so one canvas never gets another run formats', async () => {
    // The id is resolved mid-load and kept in a ref, which survives a re-render. THIS is
    // the leak it must not cause: the same canvas moves from a run to "latest" on a
    // workflow that has none, which still counts as run mode but loads the workflow plan -
    // and would otherwise be laid out on the FORMER run's page formats. Right-shaped
    // boxes, wrong run, nothing to say so.
    getRun.mockResolvedValue({ id: 'run-uuid-7', plan: { triggers: [], mcps: [], edges: [] } });
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [], edges: [] } });
    getLatestWorkflowRun.mockResolvedValue(null);
    vi.mocked(WorkflowPlanImporter.importPlan).mockClear();

    const view = renderHookWithRunId('run-7');
    await waitFor(() => expect(WorkflowPlanImporter.importPlan).toHaveBeenCalled());
    expect(lastContext()).toMatchObject({ workflowRunId: 'run-uuid-7' });

    vi.mocked(WorkflowPlanImporter.importPlan).mockClear();
    view.rerender({ runId: 'latest' });
    await waitFor(() => expect(WorkflowPlanImporter.importPlan).toHaveBeenCalled());

    expect(lastContext()).toMatchObject({ isRunMode: true, workflowRunId: null });
  });

  it('says it is NOT a run when the builder is opened for editing', async () => {
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [], edges: [] } });

    vi.mocked(WorkflowPlanImporter.importPlan).mockClear();
    renderLoader();

    await waitFor(() => expect(WorkflowPlanImporter.importPlan).toHaveBeenCalled());
    expect(lastContext()).toMatchObject({ isRunMode: false });
  });

  it('hands down a query client, so the lookup shares the interface node cache entry', async () => {
    getWorkflow.mockResolvedValue({ plan: { triggers: [], mcps: [], edges: [] } });

    vi.mocked(WorkflowPlanImporter.importPlan).mockClear();
    renderLoader();

    await waitFor(() => expect(WorkflowPlanImporter.importPlan).toHaveBeenCalled());
    expect((lastContext() as { queryClient?: unknown }).queryClient).toBeInstanceOf(QueryClient);
  });
});
