/**
 * @vitest-environment jsdom
 *
 * Contract for the two "template" actions in the application's bottom toolbar:
 * "load the example values" and "reset the data".
 *
 * The three properties worth pinning, because each one silently breaks in a way
 * that still LOOKS like a working button:
 *
 *  1. **Availability.** Both actions belong to an INSTALLED application. Leaking
 *     them into the marketplace preview would offer an anonymous visitor a write
 *     against the publisher's tenant; leaking the RESET into a cloud-sourced
 *     (remote) install would call a local endpoint that has no publication row to
 *     read, so it 404s with no useful message.
 *
 *  2. **The showcase read must NOT pass our interfaceId.** Ours belongs to the
 *     installed CLONE and does not exist in the publisher's frozen workflow, so
 *     passing it silently misses. The publication's landing render is the correct
 *     source, and it works for every page because `triggerData` is keyed by
 *     trigger key (a normalized label), not by interface.
 *
 *  3. **A partial reset must not read as a clean success.** The backend reports
 *     the tables it could not restore; dropping that on the floor is how a user
 *     ends up believing their data was restored when half of it was not.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, waitFor, act } from '@testing-library/react';
import * as React from 'react';

// ── Capture handles ─────────────────────────────────────────────────────────
const getShowcaseRenderMock = vi.hoisted(() => vi.fn());
const resetApplicationDataMock = vi.hoisted(() => vi.fn());
const refetchMock = vi.hoisted(() => vi.fn());
const getWorkflowMock = vi.hoisted(() => vi.fn());
/** Last `prefillTriggerData` the iframe received - proves the seed reached the forms. */
const iframePrefillRef = vi.hoisted(() => ({ current: undefined as unknown }));
/** Last `prefillValues` the trigger panel received. */
const panelPrefillRef = vi.hoisted(() => ({ current: undefined as unknown }));
/** The trigger panel's execute callback - the OTHER submit path that must drop the seed. */
const panelExecuteRef = vi.hoisted(() => ({
  current: undefined as ((id: string, type: string, payload: Record<string, unknown>) => Promise<unknown>) | undefined,
}));
/** The iframe bridge's action callback - lets a test drive a real user submit. */
const iframeOnActionRef = vi.hoisted(() => ({
  current: undefined as ((ref: string, data: Record<string, unknown>) => Promise<void> | void) | undefined,
}));

vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/workflow/wf-1' }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getShowcaseRender: getShowcaseRenderMock,
    resetApplicationData: resetApplicationDataMock,
  },
}));

vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflow: getWorkflowMock },
}));

vi.mock('@/lib/api/orchestrator/execution.service', () => ({
  executionService: {
    scheduleExecuteNow: vi.fn(),
    triggerSpecific: vi.fn(),
    triggerManual: vi.fn(),
  },
}));

vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [{ executionTotal: 0 }, { executeStep: vi.fn() }],
}));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: false, isPreviewOnly: false }),
}));

/**
 * The render result. A non-empty `htmlTemplate` is required for the iframe to
 * mount at all (an empty one renders the "no template" placeholder), and the
 * iframe is where the seeded values have to land.
 */
const renderDataRef = vi.hoisted(() => ({
  current: { htmlTemplate: '<form></form>', items: [] } as Record<string, unknown> | undefined,
}));

vi.mock('@/app/workflows/builder/hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined }),
  useInterfaceRender: () => ({
    data: renderDataRef.current,
    isLoading: false,
    isFetching: false,
    isPlaceholderData: false,
    refetch: refetchMock,
  }),
}));

vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useSharedInterfacePage: () => [0, () => undefined],
}));

vi.mock('@/components/app/WorkflowPanelContent', () => ({
  setPendingActivateTab: () => undefined,
}));

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), getTokenProvider: () => null, getAuthToken: async () => null },
}));

vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));

vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: (props: { extraControls?: React.ReactNode }) => (
    <div data-testid="toolbar-stub">{props.extraControls}</div>
  ),
}));

vi.mock('@/app/workflows/builder/components/interface/InterfaceIframe', () => ({
  InterfaceIframe: (props: {
    prefillTriggerData?: unknown;
    onAction?: (ref: string, data: Record<string, unknown>) => Promise<void> | void;
  }) => {
    iframePrefillRef.current = props.prefillTriggerData;
    iframeOnActionRef.current = props.onAction;
    return <div data-testid="iframe-stub" />;
  },
}));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <span data-testid="loading-spinner" />,
}));

vi.mock('@/app/workflows/builder/components/TriggerPanel', () => ({
  TriggerPanel: (props: {
    prefillValues?: unknown;
    onExecuteTrigger?: (id: string, type: string, payload: Record<string, unknown>) => Promise<unknown>;
  }) => {
    panelPrefillRef.current = props.prefillValues;
    panelExecuteRef.current = props.onExecuteTrigger;
    return <div data-testid="trigger-panel-stub" />;
  },
}));

vi.mock('@/app/workflows/builder/utils/interfaceHtmlUtils', () => ({
  mergeTriggerDataIntoResolved: () => ({}),
}));

vi.mock('@/app/workflows/builder/utils/safeCenteringCss', () => ({
  SAFE_CENTERING_CSS: '',
  centeringCssFor: () => '',
}));

vi.mock('@/lib/utils/dateFormatters', () => ({
  parseUtcAware: (s: string) => new Date(s),
  formatUtcTime: (s: string) => s,
}));

// Echo the key back so assertions can name the message that was shown.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { ApplicationTabContent } from '../ApplicationTabContent';

const baseConfig = { interfaceId: 'iface-clone-1', label: 'tab', actionMapping: {} };

function renderApp(props: {
  templateSource?: { publicationId: string; remote?: boolean; canReset?: boolean };
  previewMode?: boolean;
} = {}) {
  return render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={baseConfig as any}
      runId="run_abc"
      workflowId="wf-1"
      onAction={() => undefined}
      // Force the toolbar open so the actions land in the DOM (same rationale as
      // the sibling schedule-launch test).
      carouselControls={<span data-testid="carousel-controls-stub" />}
      toolbarOpen
      templateSource={props.templateSource}
      previewMode={props.previewMode}
    />,
  );
}

/**
 * A surface bound to the caller's own install: the reset rewrites the tables that
 * are on screen. `canReset` is explicit because an omitted flag now withholds the
 * reset - it wipes real data, so the safe answer is the default one.
 */
const INSTALLED = { publicationId: 'pub-1', canReset: true };

describe('ApplicationTabContent - template actions', () => {
  beforeEach(() => {
    // jsdom ships no ResizeObserver; the preview branch measures its container
    // with one. Absent this stub the preview test dies in a layout effect.
    vi.stubGlobal('ResizeObserver', class {
      observe() { /* no-op */ }
      unobserve() { /* no-op */ }
      disconnect() { /* no-op */ }
    });
    getShowcaseRenderMock.mockReset();
    resetApplicationDataMock.mockReset();
    refetchMock.mockReset();
    getWorkflowMock.mockReset();
    iframePrefillRef.current = undefined;
    panelPrefillRef.current = undefined;
    iframeOnActionRef.current = undefined;
    panelExecuteRef.current = undefined;
    getWorkflowMock.mockResolvedValue({ plan: { triggers: [] } });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // ── Availability ──────────────────────────────────────────────────────────

  it('shows neither action when the app was not installed from a publication', async () => {
    const { queryByTestId } = renderApp();
    await act(async () => { await Promise.resolve(); });

    expect(queryByTestId('application-load-template-values')).toBeNull();
    expect(queryByTestId('application-reset-data')).toBeNull();
  });

  it('withholds the reset when the surface did not say whether it is an install', async () => {
    // Fail closed on an omitted flag: a caller that forgets it must not get a
    // data-wiping button by default.
    const { queryByTestId } = renderApp({ templateSource: { publicationId: 'pub-1' } });
    await act(async () => { await Promise.resolve(); });

    expect(queryByTestId('application-load-template-values')).not.toBeNull();
    expect(queryByTestId('application-reset-data')).toBeNull();
  });

  it('shows both actions for an installed application', async () => {
    const { queryByTestId } = renderApp({ templateSource: INSTALLED });
    await act(async () => { await Promise.resolve(); });

    expect(queryByTestId('application-load-template-values')).not.toBeNull();
    expect(queryByTestId('application-reset-data')).not.toBeNull();
  });

  it('hides both actions in the marketplace preview, even with a publication in scope', async () => {
    // An anonymous visitor must never be able to write against the publisher's
    // tenant, and there is no install of theirs to reset.
    const { queryByTestId } = renderApp({ templateSource: INSTALLED, previewMode: true });
    await act(async () => { await Promise.resolve(); });

    expect(queryByTestId('application-load-template-values')).toBeNull();
    expect(queryByTestId('application-reset-data')).toBeNull();
  });

  it('keeps the example values for the publisher\'s own view, hiding only the reset', async () => {
    // The publisher's page opens on a data-less run just like an acquirer's, so the
    // example values are exactly as useful there. The reset is the one that depends on
    // the binding: this surface shows the SOURCE workflow while the endpoint rewrites
    // the APPLICATION clone's tables, so it would write where the screen cannot show it.
    const { queryByTestId } = renderApp({
      templateSource: { publicationId: 'pub-1', canReset: false },
    });
    await act(async () => { await Promise.resolve(); });

    expect(queryByTestId('application-load-template-values')).not.toBeNull();
    expect(queryByTestId('application-reset-data')).toBeNull();
  });

  it('hides only the RESET for a cloud-sourced install, keeping the values action', async () => {
    // A remote acquisition has no local publication row, so reset-data has
    // nothing to read; the showcase READ still resolves through the cloud proxy.
    const { queryByTestId } = renderApp({
      templateSource: { publicationId: 'pub-1', remote: true },
    });
    await act(async () => { await Promise.resolve(); });

    expect(queryByTestId('application-load-template-values')).not.toBeNull();
    expect(queryByTestId('application-reset-data')).toBeNull();
  });

  // ── Loading the template values ───────────────────────────────────────────

  it('reads the publication landing render WITHOUT our interfaceId and seeds the forms', async () => {
    const templateTriggerData = { 'trigger:my_form': { city: 'Lyon' } };
    getShowcaseRenderMock.mockResolvedValue({ items: [{ triggerData: templateTriggerData }] });

    const { getByTestId } = renderApp({ templateSource: INSTALLED });
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(getByTestId('application-load-template-values'));

    await waitFor(() => expect(getShowcaseRenderMock).toHaveBeenCalledTimes(1));
    // Passing `interfaceId: 'iface-clone-1'` here would silently return nothing:
    // that id belongs to OUR clone, not to the publisher's frozen workflow.
    expect(getShowcaseRenderMock).toHaveBeenCalledWith('pub-1', { authenticated: true }, false);

    await waitFor(() => expect(iframePrefillRef.current).toEqual(templateTriggerData));
    expect(panelPrefillRef.current).toEqual(templateTriggerData);
  });

  it('drops the seed once the user submits, so their own input is not reverted to the example', async () => {
    // The seed exists to help fill the form. Left in place it OUTRANKS the run's real
    // trigger data in the iframe (prefillTriggerData wins over triggerData by design),
    // so every later render would silently show the publisher's example again instead of
    // what the user actually submitted.
    const onAction = vi.fn(async () => undefined);
    getShowcaseRenderMock.mockResolvedValue({ items: [{ triggerData: { 'trigger:f': { city: 'Lyon' } } }] });

    const { getByTestId } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_abc"
        workflowId="wf-1"
        onAction={onAction}
        carouselControls={<span />}
        toolbarOpen
        templateSource={INSTALLED}
      />,
    );
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(getByTestId('application-load-template-values'));
    await waitFor(() => expect(iframePrefillRef.current).toBeDefined());

    // The iframe bridge fires the user's submit through onAction.
    await act(async () => {
      await iframeOnActionRef.current?.('trigger:f:submit', { city: 'Paris' });
    });

    await waitFor(() => expect(iframePrefillRef.current).toBeUndefined());
    expect(panelPrefillRef.current).toBeNull();
  });

  it('drops the seed when the submit comes from the trigger panel rather than the iframe', async () => {
    // The two submit paths are separate call sites; covering only the iframe would leave
    // half the fix unverified.
    getShowcaseRenderMock.mockResolvedValue({ items: [{ triggerData: { 'trigger:f': { city: 'Lyon' } } }] });

    const { getByTestId } = renderApp({ templateSource: INSTALLED });
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(getByTestId('application-load-template-values'));
    await waitFor(() => expect(iframePrefillRef.current).toBeDefined());

    await act(async () => {
      await panelExecuteRef.current?.('trigger:f', 'form', { city: 'Paris' });
    });

    await waitFor(() => expect(iframePrefillRef.current).toBeUndefined());
  });

  it('KEEPS the seed across a pure navigate action, so a multi-page app can carry it to the form', async () => {
    // Regression: the seed-clearing above must not fire for a `:navigate` ref. That action
    // is a frontend page switch with no API call, and on a multi-page app the menu page is
    // routinely where the user loads the values while the form lives one navigate away -
    // clearing there turns the whole feature into a silent no-op the moment they turn the
    // page.
    const onAction = vi.fn(async () => undefined);
    getShowcaseRenderMock.mockResolvedValue({ items: [{ triggerData: { 'trigger:f': { city: 'Lyon' } } }] });

    const { getByTestId } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_abc"
        workflowId="wf-1"
        onAction={onAction}
        carouselControls={<span />}
        toolbarOpen
        templateSource={INSTALLED}
      />,
    );
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(getByTestId('application-load-template-values'));
    await waitFor(() => expect(iframePrefillRef.current).toBeDefined());

    // The canonical navigate shape: `navigate` is reserved for the `interface:` prefix
    // (a trigger ref only ever ends in submit / click / message), so this is what the
    // platform actually emits for a page switch.
    await act(async () => {
      await iframeOnActionRef.current?.('interface:details:navigate', {});
    });

    expect(iframePrefillRef.current).toBeDefined();
  });

  it('drops the seed when the toolbar Launch fires a manual trigger', async () => {
    // Launch bypasses both submit paths, but it still opens a new epoch whose real trigger
    // data the seed would keep overriding in the interface.
    getWorkflowMock.mockResolvedValue({
      plan: { triggers: [{ id: 't1', label: 'Run It', type: 'manual' }] },
    });
    getShowcaseRenderMock.mockResolvedValue({ items: [{ triggerData: { 'trigger:f': { city: 'Lyon' } } }] });

    const { getByTestId, getByTitle } = renderApp({ templateSource: INSTALLED });
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(getByTestId('application-load-template-values'));
    await waitFor(() => expect(iframePrefillRef.current).toBeDefined());

    fireEvent.click(await waitFor(() => getByTitle('launchTrigger')));

    await waitFor(() => expect(iframePrefillRef.current).toBeUndefined());
  });

  it('drops the seed when the toolbar Launch force-fires a schedule trigger', async () => {
    // The schedule branch is a separate call site from the manual one; covering only the
    // latter would leave half of the Launch fix resting on inspection.
    getWorkflowMock.mockResolvedValue({
      plan: { triggers: [{ id: 's1', label: 'Daily Scan', type: 'schedule' }] },
    });
    getShowcaseRenderMock.mockResolvedValue({ items: [{ triggerData: { 'trigger:f': { city: 'Lyon' } } }] });

    const { getByTestId, getByTitle } = renderApp({ templateSource: INSTALLED });
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(getByTestId('application-load-template-values'));
    await waitFor(() => expect(iframePrefillRef.current).toBeDefined());

    fireEvent.click(await waitFor(() => getByTitle('launchTrigger')));

    await waitFor(() => expect(iframePrefillRef.current).toBeUndefined());
  });

  it('routes the showcase read through the cloud proxy for a remote install', async () => {
    getShowcaseRenderMock.mockResolvedValue({ items: [{ triggerData: { 'trigger:f': { a: '1' } } }] });

    const { getByTestId } = renderApp({
      templateSource: { publicationId: 'pub-1', remote: true },
    });
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(getByTestId('application-load-template-values'));

    await waitFor(() =>
      expect(getShowcaseRenderMock).toHaveBeenCalledWith('pub-1', { authenticated: true }, true));
  });

  it('says so when the app ships no example values, and seeds nothing', async () => {
    getShowcaseRenderMock.mockResolvedValue({ items: [{ triggerData: {} }] });

    const { getByTestId, findByText } = renderApp({ templateSource: INSTALLED });
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(getByTestId('application-load-template-values'));

    expect(await findByText('templateValuesEmpty')).toBeTruthy();
    // An empty seed must stay undefined rather than blanking the forms with {}.
    expect(iframePrefillRef.current).toBeUndefined();
  });

  it('surfaces a failed showcase read instead of failing silently', async () => {
    getShowcaseRenderMock.mockRejectedValue(new Error('boom'));

    const { getByTestId, findByText } = renderApp({ templateSource: INSTALLED });
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(getByTestId('application-load-template-values'));

    expect(await findByText('boom')).toBeTruthy();
  });

  // ── Resetting the data ────────────────────────────────────────────────────

  it('asks for confirmation and does nothing when the user declines', async () => {
    vi.stubGlobal('confirm', vi.fn(() => false));

    const { getByTestId } = renderApp({ templateSource: INSTALLED });
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(getByTestId('application-reset-data'));
    await act(async () => { await Promise.resolve(); });

    expect(window.confirm).toHaveBeenCalled();
    expect(resetApplicationDataMock).not.toHaveBeenCalled();
  });

  it('resets, reports the counts, and re-renders the interface so the restored rows show', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true));
    resetApplicationDataMock.mockResolvedValue({
      publicationId: 'pub-1', tablesReset: 2, rowsRestored: 40, tablesSkipped: [],
    });

    const { getByTestId, findByTestId } = renderApp({ templateSource: INSTALLED });
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(getByTestId('application-reset-data'));

    await waitFor(() => expect(resetApplicationDataMock).toHaveBeenCalledWith('pub-1'));
    const notice = await findByTestId('application-action-notice');
    expect(notice.textContent).toContain('resetDataSuccess');
    // Without the refetch the user stares at the pre-reset render and concludes
    // the button did nothing.
    expect(refetchMock).toHaveBeenCalled();
  });

  it('reports a PARTIAL reset distinctly, so half-restored never reads as done', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true));
    resetApplicationDataMock.mockResolvedValue({
      publicationId: 'pub-1', tablesReset: 1, rowsRestored: 3, tablesSkipped: ['Orphan'],
    });

    const { getByTestId, findByTestId } = renderApp({ templateSource: INSTALLED });
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(getByTestId('application-reset-data'));

    const notice = await findByTestId('application-action-notice');
    expect(notice.textContent).toContain('resetDataPartial');
    expect(notice.className).toContain('amber');
  });

  it('surfaces a failed reset as an error rather than a success notice', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true));
    resetApplicationDataMock.mockRejectedValue(new Error('reset exploded'));

    const { getByTestId, findByText, queryByTestId } = renderApp({ templateSource: INSTALLED });
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(getByTestId('application-reset-data'));

    expect(await findByText('reset exploded')).toBeTruthy();
    expect(queryByTestId('application-action-notice')).toBeNull();
  });
});
