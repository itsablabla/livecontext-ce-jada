// @vitest-environment jsdom
/**
 * Restoring a version must not re-import that plan into a DIFFERENT workflow.
 *
 * `workflowPlanRestore` carries a plan and every mounted loader used to import
 * it, unconditionally. That was unambiguous while the version dropdown existed
 * in exactly one place. It no longer does: the side panel's workflow sub-tab
 * carries its own, for its own workflow. Restoring version N of the panel's
 * workflow therefore loaded that plan onto the PAGE's canvas, and the next save
 * there persisted it - one workflow's history silently overwriting another's
 * present.
 *
 * Both directions are pinned: the addressed loader still imports, and the other
 * one does not. An event that names no workflow keeps reaching everyone, which
 * is what the older dispatchers rely on.
 */
import * as React from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const importPlan = vi.hoisted(() => vi.fn());

vi.mock('../../services/workflowPlanImporter/WorkflowPlanImporter', () => ({
  WorkflowPlanImporter: { importPlan },
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflow: vi.fn(),
    getWorkflowRun: vi.fn(),
    getAgents: vi.fn().mockResolvedValue([]),
  },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => null,
  usePublicationSnapshot: () => null,
}));
vi.mock('@/contexts/WorkflowLayoutDirectionContext', () => ({
  useWorkflowLayoutDirectionSafe: () => ({
    direction: 'horizontal',
    setDirection: vi.fn(),
    setWorkflowDirection: vi.fn(),
  }),
}));

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useWorkflowLoader } from '../useWorkflowLoader';

/**
 * The loader reads the canvas's query client to hand it to the importer (the interface
 * format lookup shares the node's own cache entry), so it needs a provider - as the rest
 * of the builder already did through `useWorkflowEventListeners`.
 */
function withQueryClient({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client }, children);
}

function mountLoaderFor(workflowId: string) {
  const setNodes = vi.fn();
  const setEdges = vi.fn();
  renderHook(() =>
    useWorkflowLoader({
      workflowId,
      setNodes,
      setEdges,
      nodesRef: { current: [] },
      edgesRef: { current: [] },
    } as never),
    { wrapper: withQueryClient },
  );
  return { setNodes, setEdges };
}

function dispatchRestore(detail: Record<string, unknown>) {
  window.dispatchEvent(new CustomEvent('workflowPlanRestore', { detail }));
}

beforeEach(() => {
  importPlan.mockReset();
  importPlan.mockResolvedValue({ success: true, nodes: [{ id: 'n1', data: {} }], edges: [] });
});
afterEach(() => { vi.clearAllMocks(); });

describe('useWorkflowLoader - a version restore is scoped to its workflow', () => {
  it('imports a restore addressed to THIS workflow', async () => {
    const { setNodes } = mountLoaderFor('wf-1');

    dispatchRestore({ plan: { name: 'restored' }, workflowId: 'wf-1' });

    await waitFor(() => expect(setNodes).toHaveBeenCalled());
  });

  it('refuses a restore addressed to another workflow', async () => {
    const { setNodes } = mountLoaderFor('wf-1');

    // The side panel restoring ITS workflow's version, while this loader is the
    // page's. Pre-change this imported wf-other's plan onto wf-1's canvas.
    dispatchRestore({ plan: { name: 'restored' }, workflowId: 'wf-other' });

    await new Promise((r) => setTimeout(r, 20));
    expect(importPlan).not.toHaveBeenCalled();
    expect(setNodes).not.toHaveBeenCalled();
  });

  it('still imports a restore that names no workflow', async () => {
    const { setNodes } = mountLoaderFor('wf-1');

    dispatchRestore({ plan: { name: 'restored' } });

    await waitFor(() => expect(setNodes).toHaveBeenCalled());
  });
});
