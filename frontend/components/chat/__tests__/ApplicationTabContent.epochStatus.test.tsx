/**
 * @vitest-environment jsdom
 *
 * The application toolbar's epoch selector badges each fire with its own outcome.
 *
 * This surface matters more than the builder's: an application user only ever sees this
 * dropdown, and "epoch 2 of my 5 runs failed" was simply not representable - the row
 * showed a time range, a duration bar, and a blue dot for anything unclosed.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, cleanup, act, fireEvent } from '@testing-library/react';
import * as React from 'react';

async function flushEffects() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

const renderDataRef = vi.hoisted(() => ({
  current: {
    htmlTemplate: '<div>app</div>',
    items: [{ epoch: 2, spawn: 0, itemIndex: 0, data: {} }],
    pagination: { totalPages: 1 },
  } as Record<string, any>,
}));

const runStateRef = vi.hoisted(() => ({
  current: {} as Record<string, unknown>,
}));

vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/workflow/wf-1' }));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [runStateRef.current, { executeStep: vi.fn() }],
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: true, isPreviewOnly: false }),
}));
vi.mock('@/app/workflows/builder/hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined }),
  useInterfaceRender: () => ({
    data: renderDataRef.current,
    isLoading: false,
    isFetching: false,
    isPlaceholderData: false,
    refetch: vi.fn(),
  }),
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useSharedInterfacePage: () => [0, () => undefined],
}));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { getTokenProvider: () => null, getAuthToken: async () => null } }));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/api/orchestrator/execution.service', () => ({ executionService: {} }));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflow: vi.fn().mockResolvedValue({ plan: { triggers: [] } }) },
}));
vi.mock('../interfaceAwaitingSignal', () => ({
  computeIsAwaitingSignal: () => false,
  isCurrentInterfaceItemPending: () => false,
}));
vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: (props: any) => <div data-testid="toolbar-stub">{props.extraControls}</div>,
}));
vi.mock('@/app/workflows/builder/components/interface/InterfaceIframe', () => ({
  InterfaceIframe: () => <div data-testid="iframe-stub" />,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span /> }));
vi.mock('@/app/workflows/builder/components/TriggerPanel', () => ({
  TriggerPanel: () => <div />,
}));
vi.mock('@/app/workflows/builder/utils/interfaceHtmlUtils', () => ({
  mergeTriggerDataIntoResolved: () => ({}),
}));
vi.mock('@/app/workflows/builder/utils/safeCenteringCss', () => ({
  SAFE_CENTERING_CSS: '',
  centeringCssFor: () => '',
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) => {
    const templates: Record<string, string> = { epochBadge: 'Epoch {number}' };
    const tpl = templates[key] ?? key;
    return tpl.replace(/\{(\w+)\}/g, (_m, k: string) => String(values?.[k] ?? ''));
  },
}));

import { ApplicationTabContent } from '../ApplicationTabContent';
import { resetEpochSelectionState } from '@/components/workflow/run-panel/useDefaultEpochSelection';

const baseConfig = {
  interfaceId: 'iface-1',
  label: 'tab',
  actionMapping: {},
  nodeId: 'interface:app',
};

/**
 * Three fires: one green, one failed, one still open. The open one carries no status -
 * the backend attaches an outcome only to an epoch it can speak for.
 */
const EPOCHS = [
  { epoch: 1, startedAt: '2026-08-01T10:00:00Z', endedAt: '2026-08-01T10:01:00Z', status: 'COMPLETED' },
  { epoch: 2, startedAt: '2026-08-01T11:00:00Z', endedAt: '2026-08-01T11:01:00Z', status: 'FAILED' },
  { epoch: 3, startedAt: '2026-08-01T12:00:00Z', endedAt: null, status: null },
];

function renderApp(viewingEpoch: number | null) {
  return render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={baseConfig as any}
      runId="run_1"
      workflowId="wf-1"
      onAction={() => undefined}
      viewingEpoch={viewingEpoch}
      onViewingEpochChange={() => undefined}
      toolbarOpen
      onToolbarOpenChange={() => undefined}
    />,
  );
}

describe('ApplicationTabContent - per-epoch status in the toolbar', () => {
  beforeEach(() => {
    resetEpochSelectionState();
    runStateRef.current = {
      runStatus: 'waiting_trigger',
      executionTotal: 0,
      pendingSignals: [],
      epochTimestamps: EPOCHS,
    };
  });
  afterEach(() => {
    resetEpochSelectionState();
    cleanup();
  });

  it('badges the collapsed selector with the outcome of the epoch on screen', async () => {
    // The status is the reason to open the list, so it must not require opening it.
    renderApp(2);
    await flushEffects();

    const button = document.querySelector('[data-testid="application-epoch-selector"]')!;
    expect(button.getAttribute('data-epoch-status')).toBe('FAILED');
    expect(button.querySelector('svg.text-red-500')).not.toBeNull();
  });

  it('gives every row of the dropdown its own outcome', async () => {
    renderApp(2);
    await flushEffects();

    fireEvent.click(document.querySelector('[data-testid="application-epoch-selector"]')!);

    const statusOf = (epoch: number) =>
      document.querySelector(`[data-testid="application-epoch-option-${epoch}"]`)?.getAttribute('data-epoch-status');
    expect(statusOf(1)).toBe('COMPLETED');
    expect(statusOf(2)).toBe('FAILED');
    // Epoch 3 is unclosed and the run is parked between fires: nothing is executing, so
    // no live pulse - and nothing is claimed about an epoch the payload cannot describe.
    expect(statusOf(3)).toBeNull();
    expect(document.querySelectorAll('.animate-ping')).toHaveLength(0);
    // The word reaches a screen reader without replacing the row's own content.
    const failedRow = document.querySelector('[data-testid="application-epoch-option-2"]')!;
    expect(failedRow.querySelector('.sr-only')?.textContent).toBe('status.failed');
    expect(failedRow.getAttribute('aria-label')).toBeNull();
  });

  it('pulses instead of claiming an outcome while the run is executing', async () => {
    runStateRef.current = { ...runStateRef.current, runStatus: 'running' };
    renderApp(2);
    await flushEffects();

    fireEvent.click(document.querySelector('[data-testid="application-epoch-selector"]')!);

    expect(
      document.querySelector('[data-testid="application-epoch-option-3"]')?.getAttribute('data-epoch-status'),
    ).toBe('RUNNING');
    // The closed epochs keep their own verdict - the run status describes only the
    // epoch it is currently executing.
    expect(
      document.querySelector('[data-testid="application-epoch-option-2"]')?.getAttribute('data-epoch-status'),
    ).toBe('FAILED');
  });

  it('carries no status attribute when the payload has none', async () => {
    // A showcase snapshot frozen before the field existed.
    runStateRef.current = {
      ...runStateRef.current,
      epochTimestamps: EPOCHS.map(({ status, ...rest }) => rest),
    };
    renderApp(2);
    await flushEffects();

    const button = document.querySelector('[data-testid="application-epoch-selector"]')!;
    expect(button.getAttribute('data-epoch-status')).toBeNull();
    // No status icon (the calendar icon of the button itself stays).
    expect(button.querySelector('svg.text-emerald-500, svg.text-red-500, svg.text-gray-400')).toBeNull();
  });
});
