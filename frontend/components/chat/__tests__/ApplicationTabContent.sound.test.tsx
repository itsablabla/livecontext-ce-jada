/**
 * @vitest-environment jsdom
 *
 * The speaker in the application controls.
 *
 * Wherever an embedder owns the volume (the application page hands down a
 * `mediaMuted`), the app starts SILENT: an app that talks the moment its page
 * opens is startling, and on a shared link the visitor had not decided to be
 * there yet. This toolbar button is then the only place the sound is turned on,
 * so the things that make it reachable are worth pinning:
 *
 *  1. It appears only once the FRAME reports it has media. The frame is
 *     sandboxed and cross-origin, so nothing else can know; a speaker on a
 *     silent app promises a sound that does not exist.
 *  2. It appears only when someone owns the volume. An undefined `mediaMuted`
 *     means "play as authored" (the workflow side panel), and a switch that
 *     flips a state nobody holds does nothing.
 *  3. It reads its state from that owner, because the icon is the whole label.
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

vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/workflow/wf-1' }));
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
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: false, isPreviewOnly: false }),
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
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));

// Renders extraControls inline so the toolbar's contents are assertable.
vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: (props: { extraControls?: React.ReactNode }) => (
    <div data-testid="toolbar-stub">{props.extraControls}</div>
  ),
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

const baseConfig = { interfaceId: 'iface-1', label: 'tab', actionMapping: {} };

function appElement(props: { mediaMuted?: boolean; onToggleMediaMuted?: () => void }) {
  return (
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={baseConfig as any}
      runId="run_abc"
      workflowId="wf-1"
      onAction={() => undefined}
      // Force the toolbar open so its controls land in the DOM (same rationale
      // as the sibling template-actions suite).
      carouselControls={<span data-testid="carousel-controls-stub" />}
      toolbarOpen
      mediaMuted={props.mediaMuted}
      onToggleMediaMuted={props.onToggleMediaMuted}
    />
  );
}

function renderApp(props: { mediaMuted?: boolean; onToggleMediaMuted?: () => void } = {}) {
  return render(appElement(props));
}

/** Play the part of the frame reporting that it does contain media. */
function reportAudioPresent() {
  act(() => announcePresenceRef.current?.(true));
}

const speaker = (view: ReturnType<typeof render>) =>
  view.queryByTestId('application-sound-toggle');

describe('ApplicationTabContent - the sound switch in the application controls', () => {
  beforeEach(() => {
    // jsdom ships no ResizeObserver; the format branch measures with one.
    vi.stubGlobal('ResizeObserver', class {
      observe() { /* no-op */ }
      unobserve() { /* no-op */ }
      disconnect() { /* no-op */ }
    });
    announcePresenceRef.current = undefined;
    iframeMutedRef.current = undefined;
  });
  afterEach(cleanup);

  it('shows no speaker before the frame has said whether it has anything to play', () => {
    const view = renderApp({ mediaMuted: true, onToggleMediaMuted: () => undefined });

    expect(speaker(view)).toBeNull();
  });

  it('grows the speaker once the frame reports media', () => {
    const view = renderApp({ mediaMuted: true, onToggleMediaMuted: () => undefined });

    reportAudioPresent();

    expect(speaker(view)).not.toBeNull();
  });

  it('shows no speaker when nobody owns the volume, even for an app full of media', () => {
    // mediaMuted undefined = "play as authored" (the workflow side panel). A
    // switch flipping a state nobody holds would do nothing.
    const view = renderApp({ onToggleMediaMuted: () => undefined });

    reportAudioPresent();

    expect(speaker(view)).toBeNull();
  });

  it('shows no speaker when the embedder owns the state but offers no way to flip it', () => {
    const view = renderApp({ mediaMuted: true });

    reportAudioPresent();

    expect(speaker(view)).toBeNull();
  });

  it('asks the embedder to flip the sound when clicked', () => {
    const onToggleMediaMuted = vi.fn();
    const view = renderApp({ mediaMuted: true, onToggleMediaMuted });
    reportAudioPresent();

    fireEvent.click(speaker(view)!);

    expect(onToggleMediaMuted).toHaveBeenCalledTimes(1);
  });

  it('reads its pressed state from the mute state, not from its own clicks', () => {
    // The embedder owns the state, so the button must reflect what it was handed:
    // a locally toggled icon would drift the moment anything else changed it.
    const view = renderApp({ mediaMuted: true, onToggleMediaMuted: () => undefined });
    reportAudioPresent();
    expect(speaker(view)!.getAttribute('aria-pressed')).toBe('false');
    expect(speaker(view)!.getAttribute('aria-label')).toBe('unmuteSound');

    view.rerender(appElement({ mediaMuted: false, onToggleMediaMuted: () => undefined }));

    expect(speaker(view)!.getAttribute('aria-pressed')).toBe('true');
    expect(speaker(view)!.getAttribute('aria-label')).toBe('muteSound');
  });

  it('keeps handing the frame the mute state the embedder holds', () => {
    renderApp({ mediaMuted: true, onToggleMediaMuted: () => undefined });

    expect(iframeMutedRef.current).toBe(true);
  });
});
