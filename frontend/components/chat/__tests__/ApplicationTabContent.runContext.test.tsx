/**
 * @vitest-environment jsdom
 *
 * Run-context semantics in the application toolbar:
 *   - When pinned to ONE epoch with several rendered pages, the bare
 *     "{page+1} / {totalPages}" counter becomes "Item X/Y" derived from the
 *     CURRENT page's item triple (itemIndex is 0-based in the API → 1-based
 *     for humans).
 *   - A "Re-execution N" badge is appended ONLY when the displayed item is a
 *     re-run (spawn > 0) - first executions (spawn 0) stay badge-free.
 *   - In "All epochs" mode pages span epochs, so the bare counter is kept.
 *   - The Continue button's tooltip carries "Epoch X · Item Y" so the user
 *     knows exactly what will be continued.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, cleanup, act } from '@testing-library/react';
import * as React from 'react';

async function flushEffects() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

// Mutable render-API response - the item triple under test is changed per test.
const renderDataRef = vi.hoisted(() => ({
  current: {
    htmlTemplate: '<div>app</div>',
    items: [{ epoch: 4, spawn: 0, itemIndex: 1, data: { foo: 'bar' } }],
    pagination: { totalPages: 3 },
  } as Record<string, any>,
}));

const runStateRef = vi.hoisted(() => ({
  current: { runStatus: 'awaiting_signal', executionTotal: 0, pendingSignals: [] } as Record<string, unknown>,
}));

vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/workflow/wf-1' }));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [runStateRef.current, { executeStep: vi.fn() }],
}));

// Mutable so a test can put the surface in EDIT mode, which is where the epoch
// is cleared without being recorded as a choice.
const modeRef = vi.hoisted(() => ({ current: { isRunMode: true, isPreviewOnly: false } }));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => modeRef.current,
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

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getTokenProvider: () => null, getAuthToken: async () => null },
}));

vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/api/orchestrator/execution.service', () => ({ executionService: {} }));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflow: vi.fn().mockResolvedValue({ plan: { triggers: [] } }) },
}));

// The Continue button only renders for a BLOCKING interface awaiting its
// signal with the displayed item still pending - force both helpers true.
vi.mock('../interfaceAwaitingSignal', () => ({
  computeIsAwaitingSignal: () => true,
  isCurrentInterfaceItemPending: () => true,
}));

// Surface the new pageLabel/pageBadge props (and the extraControls hosting the
// Continue button) instead of re-testing InterfaceToolbar internals here.
vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: (props: any) => (
    <div data-testid="toolbar-stub">
      <span data-testid="page-label">
        {props.pageLabel ?? `${props.currentPage + 1} / ${props.totalPages}`}
      </span>
      {props.pageBadge && <span data-testid="page-badge">{props.pageBadge}</span>}
      {props.extraControls}
    </div>
  ),
}));

vi.mock('@/app/workflows/builder/components/interface/InterfaceIframe', () => ({
  InterfaceIframe: () => <div data-testid="iframe-stub" />,
}));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <span data-testid="loading-spinner" />,
}));

vi.mock('@/app/workflows/builder/components/TriggerPanel', () => ({
  TriggerPanel: () => <div data-testid="trigger-panel-stub" />,
}));

vi.mock('@/app/workflows/builder/utils/interfaceHtmlUtils', () => ({
  mergeTriggerDataIntoResolved: () => ({ foo: 'bar' }),
}));

vi.mock('@/app/workflows/builder/utils/safeCenteringCss', () => ({ SAFE_CENTERING_CSS: '', centeringCssFor: () => '' }));

vi.mock('@/lib/utils/dateFormatters', () => ({
  parseUtcAware: (s: string) => new Date(s),
  formatUtcTime: (s: string) => s,
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) => {
    const templates: Record<string, string> = {
      itemOfTotal: 'Item {number}/{total}',
      reExecutionBadge: 'Re-execution {number}',
      epochBadge: 'Epoch {number}',
      itemBadge: 'Item {number}',
      continueWorkflow: 'Continue',
      continueItemRemaining: 'Continue this item ({count} pending)',
      itemAlreadyResolved: 'itemAlreadyResolved',
    };
    const tpl = templates[key] ?? key;
    return tpl.replace(/\{(\w+)\}/g, (_m, k: string) => String(values?.[k] ?? ''));
  },
}));

import { ApplicationTabContent } from '../ApplicationTabContent';
import {
  getPickedEpoch,
  markEpochPickedByUser,
  resetEpochSelectionState,
} from '@/components/workflow/run-panel/useDefaultEpochSelection';

// The picked epoch is a MODULE-GLOBAL keyed by run: a test whose assertion fails
// before its own cleanup would leak a pick into every test after it - exactly
// when the output is being read. Reset from a hook, which runs either way.
beforeEach(() => {
  resetEpochSelectionState();
  modeRef.current = { isRunMode: true, isPreviewOnly: false };
});
afterEach(() => resetEpochSelectionState());

const baseConfig = {
  interfaceId: 'iface-1',
  label: 'tab',
  actionMapping: { submit: '__continue' },
  nodeId: 'interface:app',
};

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

describe('ApplicationTabContent - which epoch the tab opens on', () => {
  beforeEach(() => {
    runStateRef.current = {
      runStatus: 'awaiting_signal',
      executionTotal: 0,
      pendingSignals: [],
      // Three closed fires: the tab used to seed the newest one on mount.
      epochTimestamps: [
        { epoch: 1, startedAt: '2026-08-01T10:00:00Z', endedAt: '2026-08-01T10:01:00Z' },
        { epoch: 2, startedAt: '2026-08-01T11:00:00Z', endedAt: '2026-08-01T11:01:00Z' },
        { epoch: 3, startedAt: '2026-08-01T12:00:00Z', endedAt: null },
      ],
    };
  });
  afterEach(cleanup);

  it('selects no epoch on mount, and tells no other surface to', async () => {
    // The seeding was not just local: `handleViewEpoch` broadcasts the epoch to
    // every surface of the run, so merely opening an application dragged the
    // canvas and the Run tab off the cumulative view.
    const onViewingEpochChange = vi.fn();
    const broadcasts: unknown[] = [];
    const listener = (e: Event) => broadcasts.push((e as CustomEvent).detail);
    window.addEventListener('viewingEpochChanged', listener);

    render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={onViewingEpochChange}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    window.removeEventListener('viewingEpochChanged', listener);
    expect(onViewingEpochChange, 'the tab must not pick an epoch for the user').not.toHaveBeenCalled();
    expect(broadcasts, 'and must not push one onto the other surfaces').toEqual([]);
  });

  it('opens on the newest fire where the application IS the product', async () => {
    // A published app or a shared link: its visitors came for the latest
    // result, not a pager spanning every fire the workflow ever had.
    const onViewingEpochChange = vi.fn();
    const broadcasts: unknown[] = [];
    const listener = (e: Event) => broadcasts.push((e as CustomEvent).detail);
    window.addEventListener('viewingEpochChanged', listener);

    render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={onViewingEpochChange}
        openOnLatestEpoch
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    window.removeEventListener('viewingEpochChanged', listener);
    expect(onViewingEpochChange).toHaveBeenCalledWith(3);
    // Locally: the workflow surfaces of the same run stay on all epochs.
    expect(broadcasts, 'the seeding must not reach the other surfaces').toEqual([]);
  });

  it('records no pick when it seeds, so the canvas keeps its own view', async () => {
    const { getPickedEpoch, resetEpochSelectionState } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    resetEpochSelectionState();

    render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={() => undefined}
        openOnLatestEpoch
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    expect(getPickedEpoch('run_1'), 'a seed is not a choice').toBeUndefined();
    resetEpochSelectionState();
  });

  it('offers a way back to All epochs, and says which view it is on', async () => {
    // The reversibility half of the seeding: pinning a published app to its
    // newest fire is only acceptable because the user can undo it here.
    const { getPickedEpoch, resetEpochSelectionState } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    resetEpochSelectionState();
    const onViewingEpochChange = vi.fn();

    const { getByTestId } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={3}
        onViewingEpochChange={onViewingEpochChange}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    const control = getByTestId('application-epoch-selector');
    expect(control.getAttribute('data-all-epochs'), 'pinned to one fire').toBeNull();

    act(() => { control.click(); });
    act(() => { getByTestId('application-epoch-option-all').click(); });

    expect(onViewingEpochChange).toHaveBeenCalledWith(null);
    expect(getPickedEpoch('run_1'), 'and it is recorded, so nothing restores the epoch').toBeNull();
    resetEpochSelectionState();
  });

  it('says "All epochs" in words when it is showing all of them', async () => {
    const { resetEpochSelectionState } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    resetEpochSelectionState();

    const { getByTestId } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={() => undefined}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    const control = getByTestId('application-epoch-selector');
    expect(control.getAttribute('data-all-epochs')).toBe('true');
    // A bare number there could only be read as "you are on epoch N".
    expect(control.textContent).toContain('allEpochs');
    resetEpochSelectionState();
  });

  it('seeds its own state when no parent owns the epoch (the app side-panel tab)', async () => {
    // ApplicationSidePanel renders the tab uncontrolled: the seed has to land
    // on the local state, or a published app opened from a card shows the
    // cumulative pager after all.
    const { resetEpochSelectionState } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    resetEpochSelectionState();

    const { getByTestId } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        openOnLatestEpoch
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    const control = getByTestId('application-epoch-selector');
    expect(control.getAttribute('data-all-epochs'), 'seeded to the newest fire').toBeNull();
    expect(control.textContent).toContain('3');
    resetEpochSelectionState();
  });

  it('carries a REAL pin to the new fire, and records the move', async () => {
    // The other half of the attribution rule: a user who pinned epoch 3 is
    // carried to 4, and the remembered pick moves with them - otherwise the
    // next surface to mount empty restores the epoch they were carried off.
    const { markEpochPickedByUser, getPickedEpoch, resetEpochSelectionState } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    resetEpochSelectionState();
    markEpochPickedByUser('run_1', 3);

    const { rerender } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={3}
        onViewingEpochChange={() => undefined}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    runStateRef.current = {
      ...runStateRef.current,
      epochTimestamps: [
        ...(runStateRef.current.epochTimestamps as unknown[]),
        { epoch: 4, startedAt: '2026-08-01T13:00:00Z', endedAt: null },
      ],
    };
    rerender(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={3}
        onViewingEpochChange={() => undefined}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    expect(getPickedEpoch('run_1'), 'the pin followed the user').toBe(4);
    resetEpochSelectionState();
  });

  it('does not turn an explicit "All epochs" into a pin when the next fire lands', async () => {
    // The user chose All epochs elsewhere (recorded as null), then opens the
    // published app, which seeds the newest fire LOCALLY. When the next fire
    // closes, that seed must not be promoted into a pick and broadcast: it
    // would drag the canvas onto an epoch the user explicitly left.
    const { markEpochPickedByUser, getPickedEpoch, resetEpochSelectionState } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    resetEpochSelectionState();
    markEpochPickedByUser('run_1', null);

    const { rerender } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={3}
        onViewingEpochChange={() => undefined}
        openOnLatestEpoch
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    // A fourth fire closes.
    runStateRef.current = {
      ...runStateRef.current,
      epochTimestamps: [
        ...(runStateRef.current.epochTimestamps as unknown[]),
        { epoch: 4, startedAt: '2026-08-01T13:00:00Z', endedAt: null },
      ],
    };
    rerender(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={3}
        onViewingEpochChange={() => undefined}
        openOnLatestEpoch
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    expect(getPickedEpoch('run_1'), 'the All-epochs choice survives the new fire').toBeNull();
    resetEpochSelectionState();
  });

  it('opens on the epoch picked in the Run tab, which it was not mounted to hear', async () => {
    // The reported bug: the Run tab and the Application tab are sub-tabs of the
    // same panel, so only one of them is mounted at a time. The pick travels as a
    // one-shot window event, so the tab that was not there when it fired learnt
    // nothing and came up on the cumulative view - the epoch the user had just
    // clicked was gone one click later.
    markEpochPickedByUser('run_1', 2);

    const { getByTestId } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    const control = getByTestId('application-epoch-selector');
    expect(control.getAttribute('data-all-epochs'), 'restored onto the picked fire').toBeNull();
    expect(control.textContent).toContain('2');
  });

  it('lets that pick outrank the newest-fire seed of an application page', async () => {
    // The application page seeds the newest fire, which would otherwise land on
    // top of the restore and silently undo the pick.
    markEpochPickedByUser('run_1', 1);
    const onViewingEpochChange = vi.fn();

    render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={onViewingEpochChange}
        openOnLatestEpoch
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    expect(onViewingEpochChange).toHaveBeenCalledWith(1);
    expect(onViewingEpochChange, 'the seed must not overwrite the choice').not.toHaveBeenCalledWith(3);
  });

  it('restores over the seed on the published-app shape too, where it owns its own state', async () => {
    // ApplicationSidePanel renders the tab UNCONTROLLED and with openOnLatestEpoch:
    // restore and seed then both write the same local state, so this is where a
    // mistake in either path's `onViewingEpochChange ?? setLocal` branching shows.
    markEpochPickedByUser('run_1', 1);

    const { getByTestId } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        openOnLatestEpoch
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    const control = getByTestId('application-epoch-selector');
    expect(control.getAttribute('data-all-epochs'), 'the pick wins over the seed').toBeNull();
    expect(control.textContent, 'epoch 1 (the pick), not 3 (the newest fire)').toContain('1');
  });

  it('honours an explicit "All epochs" choice instead of seeding the newest fire', async () => {
    // `null` is a choice, not an absence: a user who went back to all epochs in
    // the Run tab must not find the newest fire pinned when they look at the app.
    markEpochPickedByUser('run_1', null);
    const onViewingEpochChange = vi.fn();

    render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={onViewingEpochChange}
        openOnLatestEpoch
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    expect(onViewingEpochChange, 'nothing to select: all epochs IS the choice').not.toHaveBeenCalled();
  });

  it('tells the other surfaces about the restored epoch, and records no new pick', async () => {
    // The restore is deliberately routed through the BROADCASTING handler: the
    // canvas beside this tab must land on the same epoch. Its sibling tests pin
    // the absence of a broadcast for the two automatic paths; this one pins its
    // presence, so swapping in a local-only setter cannot stay green. And it goes
    // through the attribution-free handler, so showing a pick is not making one.
    markEpochPickedByUser('run_1', 2);
    const broadcasts: unknown[] = [];
    const listener = (e: Event) => broadcasts.push((e as CustomEvent).detail);
    window.addEventListener('viewingEpochChanged', listener);

    render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={() => undefined}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();
    window.removeEventListener('viewingEpochChanged', listener);

    expect(broadcasts, 'scoped to this run, so a sibling app tab is unmoved')
      .toEqual([{ epoch: 2, runId: 'run_1' }]);
    expect(getPickedEpoch('run_1'), 'showing a pick is not making one').toBe(2);
  });

  it('leaves a tab bound to another run on its own view', async () => {
    // Two published-app tabs stay mounted side by side: each carries its own
    // choice, and a run with no pick of its own must not inherit its neighbour's.
    markEpochPickedByUser('run_1', 2);
    const onViewingEpochChange = vi.fn();

    render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_2"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={onViewingEpochChange}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    expect(onViewingEpochChange, 'another run carries its own choice').not.toHaveBeenCalled();
    expect(getPickedEpoch('run_2')).toBeUndefined();
  });

  it('gives each of two mounted tabs the epoch picked for ITS run', async () => {
    // The keepMounted multi-app side panel: the module-global is keyed by run
    // precisely so two tabs alive at once do not slave each other.
    markEpochPickedByUser('run_1', 1);
    markEpochPickedByUser('run_2', 3);

    const { getAllByTestId } = render(
      <>
        <ApplicationTabContent
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          config={baseConfig as any}
          runId="run_1"
          workflowId="wf-1"
          onAction={() => undefined}
          toolbarOpen
          onToolbarOpenChange={() => undefined}
        />
        <ApplicationTabContent
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          config={{ ...baseConfig, interfaceId: 'iface-2' } as any}
          runId="run_2"
          workflowId="wf-1"
          onAction={() => undefined}
          toolbarOpen
          onToolbarOpenChange={() => undefined}
        />
      </>,
    );
    await flushEffects();

    const controls = getAllByTestId('application-epoch-selector');
    expect(controls, 'both tabs render their own selector').toHaveLength(2);
    expect(controls[0].textContent, "run_1's tab shows run_1's pick").toContain('1');
    expect(controls[1].textContent, "run_2's tab shows run_2's pick").toContain('3');
  });

  it('stays on All epochs after the user clicks it here, instead of snapping back', async () => {
    // Newly reachable now that this tab restores: a surface that reports "nothing
    // selected" is indistinguishable from one that just mounted, so a return to
    // All epochs that was NOT recorded would be undone on the very next commit -
    // the user snapped back onto the epoch they just left. It sticks because the
    // dropdown records the choice (`null` IS a choice) and the restore skips it.
    markEpochPickedByUser('run_1', 2);
    const onViewingEpochChange = vi.fn();

    const { getByTestId, rerender } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={2}
        onViewingEpochChange={onViewingEpochChange}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    act(() => { getByTestId('application-epoch-selector').click(); });
    act(() => { getByTestId('application-epoch-option-all').click(); });
    expect(onViewingEpochChange, 'the click asks for the cumulative view').toHaveBeenCalledWith(null);

    // The parent applies it - the commit where a bounce-back would happen.
    onViewingEpochChange.mockClear();
    rerender(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={onViewingEpochChange}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    expect(onViewingEpochChange, 'nothing pulls the user back onto epoch 2').not.toHaveBeenCalled();
    expect(getByTestId('application-epoch-selector').getAttribute('data-all-epochs'), 'and it shows it')
      .toBe('true');
  });

  it('does not restore in edit mode, where the epoch was cleared on purpose', async () => {
    // The edit/run toggle drops the epoch WITHOUT recording it: that reset is not
    // a choice. A tab still mounted with a run id must not push the old pick back
    // onto the canvas that just dropped it - hence the `enabled: isRunMode` gate,
    // which mirrors the canvas and the Run tab.
    markEpochPickedByUser('run_1', 2);
    modeRef.current = { isRunMode: false, isPreviewOnly: false };
    const onViewingEpochChange = vi.fn();
    const broadcasts: unknown[] = [];
    const listener = (e: Event) => broadcasts.push((e as CustomEvent).detail);
    window.addEventListener('viewingEpochChanged', listener);

    render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={onViewingEpochChange}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();
    window.removeEventListener('viewingEpochChanged', listener);

    expect(onViewingEpochChange, 'edit mode restores nothing').not.toHaveBeenCalled();
    expect(broadcasts, 'and pushes nothing onto the canvas').toEqual([]);
    expect(getPickedEpoch('run_1'), 'the pick is kept for the next run-mode mount').toBe(2);
  });
});

describe('ApplicationTabContent - run-context pagination semantics', () => {
  beforeEach(() => {
    runStateRef.current = { runStatus: 'awaiting_signal', executionTotal: 0, pendingSignals: [] };
    renderDataRef.current = {
      htmlTemplate: '<div>app</div>',
      items: [{ epoch: 4, spawn: 0, itemIndex: 1, data: { foo: 'bar' } }],
      pagination: { totalPages: 3 },
    };
  });
  afterEach(cleanup);

  it('shows "Item X/Y" (1-based itemIndex) instead of the bare page counter when pinned to an epoch', async () => {
    const { getByTestId } = renderApp(4);
    expect(getByTestId('page-label').textContent).toBe('Item 2/3');
    await flushEffects();
  });

  it('keeps the bare page counter in "All epochs" mode (pages span epochs there)', async () => {
    const { getByTestId } = renderApp(null);
    expect(getByTestId('page-label').textContent).toBe('1 / 3');
    await flushEffects();
  });

  it('appends a 1-based "Re-execution N" badge when the displayed item is a re-run (spawn > 0)', async () => {
    renderDataRef.current = {
      ...renderDataRef.current,
      items: [{ epoch: 4, spawn: 1, itemIndex: 1, data: { foo: 'bar' } }],
    };
    const { getByTestId } = renderApp(4);
    expect(getByTestId('page-badge').textContent).toBe('Re-execution 2');
    await flushEffects();
  });

  it('hides the re-execution badge for a first execution (spawn = 0)', async () => {
    const { queryByTestId } = renderApp(4);
    expect(queryByTestId('page-badge')).toBeNull();
    await flushEffects();
  });

  it('Continue button tooltip carries the "Epoch X · Item Y" context of what will be continued', async () => {
    const { getByTitle } = renderApp(4);
    // Raw epoch (matches the epoch selector numbers) + 1-based item.
    expect(getByTitle('Continue - Epoch 4 · Item 2')).toBeTruthy();
    await flushEffects();
  });
});
