// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../../types';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${Object.values(vars).join(',')}` : key,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="spinner" /> }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  isFileRef: () => false,
  normalizeFileRef: (v: unknown) => v,
  getFilePath: () => '',
  fileRefToUrl: () => null,
  fileService: { downloadAndSave: vi.fn(), formatFileSize: () => '1 kB' },
}));
vi.mock('@/lib/utils/url-auth', () => ({ openAuthedFileInNewTab: vi.fn() }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ isRunMode: true }) }));

const runData = vi.hoisted(() => ({
  value: {
    totalItems: 1,
    isLoading: false,
    error: null as string | null,
    currentIndex: 0,
    currentItem: { id: 'row-1' },
    goToIndex: vi.fn(),
    getObjectAtPath: vi.fn(async (): Promise<Record<string, unknown>> => ({ resolved_params: {} })),
    availableStatuses: [],
  },
}));
vi.mock('../../../../hooks/useRunData', () => ({ useRunData: () => runData.value }));

const liveState = vi.hoisted(() => ({
  value: { liveState: null as null | 'running' | 'awaiting', pendingSignals: [] as unknown[] },
}));
vi.mock('../../../../hooks/useNodeLiveState', () => ({ useNodeLiveState: () => liveState.value }));

import { ResolvedParamsView } from '../ResolvedParamsView';

function setNode(): Node<BuilderNodeData> {
  return {
    id: 'set-1',
    type: 'setNode',
    position: { x: 0, y: 0 },
    data: {
      id: 'set-1',
      label: 'Prepare Payload',
      kind: 'core',
      setAssignments: [{ name: 'label', value: 'x', type: 'string' }],
      setKeepOnlySet: true,
    } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

function waitNode(): Node<BuilderNodeData> {
  return {
    id: 'wait-1',
    type: 'waitNode',
    position: { x: 0, y: 0 },
    data: { id: 'wait-1', label: 'Hold', kind: 'core', waitDuration: 5000 } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

function renderView(node = setNode()) {
  return render(
    <ResolvedParamsView workflowId="wf-1" runId="run-1" stepAlias="Prepare Payload" node={node} />,
  );
}

describe('ResolvedParamsView', () => {
  beforeEach(() => {
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
    liveState.value = { liveState: null, pendingSignals: [] };
    runData.value = {
      ...runData.value,
      totalItems: 1,
      isLoading: false,
      error: null,
      getObjectAtPath: vi.fn(async () => ({ resolved_params: { keepOnlySet: true, label: 'x' } })),
    };
  });

  it('shows the reported parameters through their human labels', async () => {
    renderView();
    await waitFor(() => expect(screen.getByText('Keep only set fields')).toBeTruthy());
  });

  it('unwraps the resolved_params envelope rather than showing it as a nested object', async () => {
    renderView();
    await waitFor(() => expect(screen.queryByText('resolved_params')).toBeNull());
  });

  it('accepts a payload that is already the unwrapped map (legacy rows)', async () => {
    runData.value = {
      ...runData.value,
      getObjectAtPath: vi.fn(async () => ({ keepOnlySet: false })),
    };
    renderView();
    await waitFor(() => expect(screen.getByText('Keep only set fields')).toBeTruthy());
  });

  it('says the parameters are empty instead of rendering a blank panel', async () => {
    runData.value = { ...runData.value, getObjectAtPath: vi.fn(async () => ({ resolved_params: {} })) };
    renderView();
    await waitFor(() => expect(screen.getByText('noResolvedParams')).toBeTruthy());
  });

  it('surfaces a fetch error as an error, not as "no parameters"', async () => {
    runData.value = { ...runData.value, error: 'boom' };
    renderView();
    expect(screen.getByText('boom')).toBeTruthy();
  });

  describe('while the node is still working', () => {
    beforeEach(() => {
      runData.value = { ...runData.value, totalItems: 0 };
    });

    it('says the node is executing and shows what it was launched with', async () => {
      liveState.value = { liveState: 'running', pendingSignals: [] };
      renderView(waitNode());
      expect(screen.getByTestId('node-run-state-running')).toBeTruthy();
      expect(screen.getByText('configuredParamsTitle')).toBeTruthy();
      // The wait node's configured duration, read through the plan generator.
      expect(screen.getByText('Duration (ms)')).toBeTruthy();
    });

    it('says the node is parked on a signal', () => {
      liveState.value = { liveState: 'awaiting', pendingSignals: [] };
      renderView(waitNode());
      expect(screen.getByTestId('node-run-state-awaiting')).toBeTruthy();
    });

    it('falls back to the plain empty state when the node is not live', () => {
      liveState.value = { liveState: null, pendingSignals: [] };
      renderView(waitNode());
      expect(screen.queryByTestId('node-run-state-running')).toBeNull();
      expect(screen.getByText('noResolvedParams')).toBeTruthy();
    });
  });

  describe('mismatch panel', () => {
    it('warns when a configured parameter is absent from what the run reported', async () => {
      // The wait node declares `duration`; this run reports something else.
      runData.value = {
        ...runData.value,
        getObjectAtPath: vi.fn(async () => ({ resolved_params: { waited_ms: 5000 } })),
      };
      renderView(waitNode());

      const banner = await screen.findByTestId('param-alignment-mismatches');
      expect(banner.textContent).toContain('mismatchTitle:1');

      fireEvent.click(screen.getByRole('button', { expanded: false }));
      expect(screen.getByText('mismatchNotReported')).toBeTruthy();
    });

    it('names the key the run used when the parameter came back renamed', async () => {
      runData.value = {
        ...runData.value,
        getObjectAtPath: vi.fn(async () => ({ resolved_params: { Duration: 5000 } })),
      };
      renderView(waitNode());

      await screen.findByTestId('param-alignment-mismatches');
      fireEvent.click(screen.getByRole('button', { expanded: false }));
      expect(screen.getByText('mismatchRenamed:Duration')).toBeTruthy();
    });

    it('stays quiet when everything configured came back', async () => {
      runData.value = {
        ...runData.value,
        getObjectAtPath: vi.fn(async () => ({ resolved_params: { duration: 5000 } })),
      };
      renderView(waitNode());

      await waitFor(() => expect(screen.getByText('Duration (ms)')).toBeTruthy());
      expect(screen.queryByTestId('param-alignment-mismatches')).toBeNull();
    });
  });

  it('offers the raw-JSON view of the reported parameters', async () => {
    renderView();
    await waitFor(() => expect(screen.getByText('Keep only set fields')).toBeTruthy());

    fireEvent.click(screen.getByRole('tab', { name: 'viewJson' }));
    expect(screen.getByTestId('run-data-json-view').textContent).toContain('"keepOnlySet"');
  });

  it('copies the whole reported map', async () => {
    renderView();
    await waitFor(() => expect(screen.getByText('Keep only set fields')).toBeTruthy());

    fireEvent.click(screen.getByTestId('run-data-copy-all'));
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(
      JSON.stringify({ keepOnlySet: true, label: 'x' }, null, 2),
    );
  });
});
