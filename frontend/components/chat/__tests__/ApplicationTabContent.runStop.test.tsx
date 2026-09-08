/**
 * @vitest-environment jsdom
 *
 * Stop, beside Launch, in the application controls.
 *
 * An application IS the run for the person using it: they fire a trigger from
 * this toolbar and then watch the interface. Until this, the only way to stop
 * what they had just started lived on another surface entirely (the canvas pill
 * or the Run tab of the side panel) - and on the application page and the
 * /s/<token> share that surface is not even on screen. So the run could be
 * started here and stopped nowhere.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, act, cleanup } from '@testing-library/react';
import * as React from 'react';

/** The frame's presence callback: lets a test play an app that turns out to have media. */
const announcePresenceRef = vi.hoisted(() => ({
  current: undefined as ((hasAudio: boolean) => void) | undefined,
}));
/** Last mute state the frame was handed. */
const iframeMutedRef = vi.hoisted(() => ({ current: undefined as boolean | undefined }));

/** Route the application is being watched on - a share link is not the same thing. */
const pathname = vi.hoisted(() => ({ current: '/app/workflow/wf-1' }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => pathname.current }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getShowcaseRender: vi.fn(), resetApplicationData: vi.fn() },
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflow: vi.fn() },
}));
vi.mock('@/lib/api/orchestrator/execution.service', () => ({
  executionService: { scheduleExecuteNow: vi.fn(), triggerSpecific: vi.fn(), triggerManual: vi.fn() },
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [{ executionTotal: 0 }, { executeStep: vi.fn() }],
}));
/** The run's OWN frozen-preview flag, independent of the embedder's prop. */
const mode = vi.hoisted(() => ({ isPreviewOnly: false }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: false, isPreviewOnly: mode.isPreviewOnly }),
}));
vi.mock('@/app/workflows/builder/hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined }),
  useInterfaceRender: () => ({
    data: { htmlTemplate: '<audio src="x"></audio>', items: [] },
    isLoading: false,
    isFetching: false,
    isPlaceholderData: false,
    refetch: vi.fn(),
  }),
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useSharedInterfacePage: () => [0, () => undefined],
}));
vi.mock('@/components/app/WorkflowPanelContent', () => ({ setPendingActivateTab: () => undefined }));
vi.mock('@/lib/api/api-client', () => ({
  apiClient: {
    get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(),
    getTokenProvider: () => null, getAuthToken: async () => null,
  },
}));
const api = vi.hoisted(() => ({ stopWorkflow: vi.fn(async () => ({}) as never) }));
vi.mock('@/lib/api', () => ({ orchestratorApi: api }));
/** Workspace role the application is watched under. VIEWER may watch, not steer. */
const orgRole = vi.hoisted(() => ({ canMutate: true }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => orgRole.canMutate,
}));

// Renders extraControls inline so the toolbar's contents are assertable.
/** What the component decided to put in the toolbar: `undefined` = nothing at all. */
const toolbarControls = vi.hoisted(() => ({ current: 'unset' as unknown }));
vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: (props: { extraControls?: React.ReactNode }) => {
    toolbarControls.current = props.extraControls;
    return <div data-testid="toolbar-stub">{props.extraControls}</div>;
  },
}));

// Stand-in for the sandboxed frame: records the mute state it is handed and
// exposes the presence callback.
vi.mock('@/app/workflows/builder/components/interface/InterfaceIframe', () => ({
  InterfaceIframe: (props: {
    mediaMuted?: boolean;
    onMediaAudioPresence?: (hasAudio: boolean) => void;
  }) => {
    iframeMutedRef.current = props.mediaMuted;
    announcePresenceRef.current = props.onMediaAudioPresence;
    return <div data-testid="iframe-stub" />;
  },
}));

vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="loading-spinner" /> }));
vi.mock('@/app/workflows/builder/components/TriggerPanel', () => ({ TriggerPanel: () => null }));
vi.mock('@/app/workflows/builder/utils/interfaceHtmlUtils', () => ({
  mergeTriggerDataIntoResolved: () => ({}),
}));
vi.mock('@/app/workflows/builder/utils/safeCenteringCss', () => ({
  SAFE_CENTERING_CSS: '', centeringCssFor: () => '',
}));
vi.mock('@/lib/utils/dateFormatters', () => ({
  parseUtcAware: (s: string) => new Date(s), formatUtcTime: (s: string) => s,
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { ApplicationTabContent } from '../ApplicationTabContent';
import {
  clearRunPanelCache,
  makeEmptyRunPanelData,
  publishRunPanelData,
} from '@/components/workflow/run-panel/runPanelBus';

const baseConfig = { interfaceId: 'iface-1', label: 'tab', actionMapping: {} };

function renderApp(props: { previewMode?: boolean } = {}) {
  return render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={baseConfig as any}
      runId="run_abc"
      workflowId="wf-app"
      onAction={() => undefined}
      // Force the toolbar open so its controls land in the DOM (same rationale
      // as the sibling sound / template-actions suites).
      carouselControls={<span data-testid="carousel-controls-stub" />}
      toolbarOpen
      previewMode={props.previewMode}
    />,
  );
}

/** Publish what a mounted canvas would publish for this workflow. */
function publishStatus(status: string | null) {
  act(() => {
    publishRunPanelData({
      ...makeEmptyRunPanelData('wf-app'),
      runId: 'run_abc',
      runInfo: status ? { runId: 'run_abc', status } : null,
    } as never);
  });
}

/**
 * The stop, wherever the toolbar rendered.
 *
 * `document`, not the render container: in preview the toolbar is portalled to
 * `document.body`, so a container query silently found nothing and the
 * preview-mode test passed no matter what the guard did.
 */
const stopButton = (_view: ReturnType<typeof render>) =>
  document.querySelector('[data-run-action="stop"]');

describe('ApplicationTabContent - stopping the run from the application controls', () => {
  beforeEach(() => {
    // jsdom ships no ResizeObserver; the format branch measures with one.
    vi.stubGlobal('ResizeObserver', class {
      observe() { /* no-op */ }
      unobserve() { /* no-op */ }
      disconnect() { /* no-op */ }
    });
    api.stopWorkflow.mockClear();
    orgRole.canMutate = true;
    mode.isPreviewOnly = false;
    pathname.current = '/app/workflow/wf-1';
    toolbarControls.current = 'unset';
    clearRunPanelCache();
  });
  afterEach(() => { clearRunPanelCache(); cleanup(); });

  it('grows a stop in the controls while the run is executing', () => {
    publishStatus('RUNNING');
    const view = renderApp();
    expect(stopButton(view)).not.toBeNull();
  });

  it('stops the run when pressed, even with no canvas on this surface', () => {
    publishStatus('RUNNING');
    const view = renderApp();

    act(() => { (stopButton(view) as HTMLElement).click(); });

    expect(api.stopWorkflow).toHaveBeenCalledWith('run_abc');
  });

  it('lets the mounted canvas take the stop, without a REST call behind its back', async () => {
    // The production path: the application page mounts a canvas inside its panel,
    // and that canvas owns the run manager.
    const claimed: string[] = [];
    const canvas = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      detail.handled = true;
      detail.result = Promise.resolve();
      claimed.push(detail.action);
    };
    window.addEventListener('workflowRunAction', canvas);
    try {
      publishStatus('RUNNING');
      const view = renderApp();
      await act(async () => { (stopButton(view) as HTMLElement).click(); });
    } finally {
      window.removeEventListener('workflowRunAction', canvas);
    }

    expect(claimed).toEqual(['stop']);
    expect(api.stopWorkflow).not.toHaveBeenCalled();
  });

  it('acts on the run THIS application is showing, not the one the bus holds', () => {
    // The page names its own run; the bus can still name another (a showcase run,
    // or the previous one while a rebind is in flight). Dropping the prop from
    // the hook call would stop the wrong run, silently.
    act(() => {
      publishRunPanelData({
        ...makeEmptyRunPanelData('wf-app'),
        runId: 'run_SOMEONE_ELSE',
        runInfo: { runId: 'run_SOMEONE_ELSE', status: 'RUNNING' },
      } as never);
    });
    const view = renderApp();

    act(() => { (stopButton(view) as HTMLElement).click(); });

    expect(api.stopWorkflow).toHaveBeenCalledWith('run_abc');
  });

  it('is behind the grip while the controls are collapsed - their default state', () => {
    // Pinning the CONSEQUENCE, not an accident: because this stop is not on
    // screen by default, the panel tab bar must keep its own rather than
    // standing down on the Application tab.
    publishStatus('RUNNING');
    const view = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_abc"
        workflowId="wf-app"
        onAction={() => undefined}
        carouselControls={<span data-testid="carousel-controls-stub" />}
      />,
    );
    expect(stopButton(view)).toBeNull();
  });

  it('is refused on a public share link, where stopping is not allow-listed', () => {
    // The gateway's share allow-list covers firing triggers and interface
    // actions, never stopping - so a visitor's click would 403 every time. The
    // role check does NOT cover this: an anonymous visitor has no organisation.
    pathname.current = '/s/some-share-token';
    publishStatus('RUNNING');
    const view = renderApp();
    expect(stopButton(view)).toBeNull();
  });

  it('is refused to a VIEWER: stopping a run is a mutation', () => {
    orgRole.canMutate = false;
    publishStatus('RUNNING');
    const view = renderApp();
    expect(stopButton(view)).toBeNull();
  });

  it('marks the control when the attempt failed, so the click is never silent', async () => {
    api.stopWorkflow.mockRejectedValueOnce(new Error('backend refused'));
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    publishStatus('RUNNING');
    const view = renderApp();

    await act(async () => { (stopButton(view) as HTMLElement).click(); });
    errors.mockRestore();

    expect((stopButton(view) as HTMLElement).getAttribute('data-run-action-failed')).toBe('true');
  });

  it('offers nothing while the run waits on its triggers - that is the resting state', () => {
    publishStatus('WAITING_TRIGGER');
    const view = renderApp();
    expect(stopButton(view)).toBeNull();
  });

  it('contributes NO control at all for a run it has no action for', () => {
    // The gate is `resolveRunAction(...) === 'stop'`, not "there is a status".
    // Relaxed, the control renders null but still COUNTS as a control, which
    // defeats the toolbar's "nothing to show" early return - so an idle run gets
    // a toolbar it should not have. Asserting the rendered text cannot see that
    // (a null child prints nothing either); the decision itself has to be read.
    publishStatus('WAITING_TRIGGER');
    renderApp();
    expect(toolbarControls.current).toBeUndefined();
  });

  it('does contribute one while the run is executing', () => {
    publishStatus('RUNNING');
    renderApp();
    expect(toolbarControls.current).toBeDefined();
  });

  it('offers nothing once the run is over', () => {
    publishStatus('COMPLETED');
    const view = renderApp();
    expect(stopButton(view)).toBeNull();
  });

  it('offers nothing before any run status is known', () => {
    publishStatus(null);
    const view = renderApp();
    expect(stopButton(view)).toBeNull();
  });

  it('never offers it when the EMBEDDER declares a preview', () => {
    publishStatus('RUNNING');
    const view = renderApp({ previewMode: true });
    expect(stopButton(view)).toBeNull();
  });

  it('never offers it when the RUN ITSELF is a frozen preview', () => {
    // Two independent signals, asserted apart, like the template actions above:
    // the canvas' refusal is SILENT, so a guard that only half-holds produces the
    // dead click this control exists to abolish.
    mode.isPreviewOnly = true;
    publishStatus('RUNNING');
    const view = renderApp();
    expect(stopButton(view)).toBeNull();
  });

  it('never offers it when the run SNAPSHOT is a frozen preview', () => {
    act(() => {
      publishRunPanelData({
        ...makeEmptyRunPanelData('wf-app'),
        runId: 'run_abc',
        runInfo: { runId: 'run_abc', status: 'RUNNING' },
        isPreviewOnly: true,
      } as never);
    });
    const view = renderApp();
    expect(stopButton(view)).toBeNull();
  });

  it('follows the run: appears when it starts, goes when it ends', () => {
    publishStatus('WAITING_TRIGGER');
    const view = renderApp();
    expect(stopButton(view)).toBeNull();

    publishStatus('RUNNING');
    expect(stopButton(view)).not.toBeNull();

    publishStatus('FAILED');
    expect(stopButton(view)).toBeNull();
  });
});
