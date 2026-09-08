/**
 * @vitest-environment jsdom
 *
 * Which element the trigger panel is anchored to.
 *
 * The panel centres on, and caps its width against, whatever it is handed as
 * `anchorElement`. Handing it the tab CONTAINER looks right and is wrong the
 * moment the interface declares a format: a phone-format application renders as
 * a scaled, letterboxed box INSIDE a container that still spans the full width,
 * so the anchor measured ~= the window and the panel kept its full 32rem,
 * spilling past both edges of the application it is supposed to control.
 *
 * The anchor must therefore be the letterboxed box when there is one, and the
 * container only when the application really does fill it.
 */
import { describe, it, expect, vi, afterEach, afterAll } from 'vitest';
import { render, screen, cleanup, act } from '@testing-library/react';
import * as React from 'react';

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverStub }).ResizeObserver = ResizeObserverStub;

// jsdom computes no layout: give every element a real box so the letterbox
// measurement yields a non-zero scale and the scaled viewport mounts.
// 400x600 against the 1080x1920 "vertical" preset gives scale = 0.3125.
const rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(
  () => ({
    width: 400, height: 600, top: 0, left: 0, right: 400, bottom: 600, x: 0, y: 0,
    toJSON: () => ({}),
  }) as DOMRect,
);
afterAll(() => rectSpy.mockRestore());

async function flushEffects() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/workflow/wf-1' }));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [{ runStatus: 'completed', executionTotal: 0 }, { executeStep: vi.fn() }],
}));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: true, isPreviewOnly: false }),
}));

const FORMAT: { value: string | undefined } = { value: undefined };

vi.mock('@/app/workflows/builder/hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined }),
  useInterfaceRender: () => ({
    data: {
      htmlTemplate: '<div>app</div>',
      format: FORMAT.value,
      items: [{ data: { foo: 'bar' }, itemIndex: 0 }],
      pagination: { totalPages: 1 },
    },
    isLoading: false,
    isFetching: false,
    isPlaceholderData: false,
    refetch: vi.fn(),
  }),
}));

vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useSharedInterfacePage: () => [0, () => undefined],
}));

vi.mock('@/lib/api/api-client', () => ({ apiClient: { getTokenProvider: () => null } }));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/api/orchestrator/execution.service', () => ({ executionService: {} }));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflow: vi.fn().mockResolvedValue({ plan: { triggers: [] } }) },
}));

vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: () => <div data-testid="toolbar-stub" />,
}));

vi.mock('@/app/workflows/builder/components/interface/InterfaceIframe', () => ({
  InterfaceIframe: () => <div data-testid="iframe-stub" />,
}));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <span data-testid="loading-spinner" />,
}));

/**
 * Records the anchor the panel was handed, so the test can compare it against
 * the elements actually on screen. `data-testid` is not enough on its own: the
 * container and the letterbox box are both plain divs, and only identity tells
 * them apart.
 */
const receivedAnchor: { el: HTMLElement | null | undefined } = { el: undefined };
vi.mock('@/app/workflows/builder/components/TriggerPanel', () => ({
  TriggerPanel: ({ anchorElement }: { anchorElement?: HTMLElement | null }) => {
    receivedAnchor.el = anchorElement;
    return <div data-testid="trigger-panel-stub" />;
  },
}));

vi.mock('@/app/workflows/builder/utils/interfaceHtmlUtils', () => ({
  mergeTriggerDataIntoResolved: () => ({ foo: 'bar' }),
}));

vi.mock('@/app/workflows/builder/utils/safeCenteringCss', () => ({
  SAFE_CENTERING_CSS: '', centeringCssFor: () => '',
}));

vi.mock('@/lib/utils/dateFormatters', () => ({
  parseUtcAware: (s: string) => new Date(s),
  formatUtcTime: (s: string) => s,
}));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { ApplicationTabContent } from '../ApplicationTabContent';

function renderApp(config: Record<string, unknown>, extraProps: Record<string, unknown> = {}) {
  const { format, ...appConfig } = config;
  FORMAT.value = format as string | undefined;
  return render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={appConfig as any}
      runId="run_1"
      workflowId="wf-1"
      onAction={() => undefined}
      {...extraProps}
    />,
  );
}

describe('ApplicationTabContent - the trigger panel anchors on what the user sees', () => {
  afterEach(() => {
    cleanup();
    FORMAT.value = undefined;
    receivedAnchor.el = undefined;
  });

  it('anchors on the letterboxed box when the interface declares a format', async () => {
    renderApp({ interfaceId: 'iface-1', label: 'tab', actionMapping: {}, format: 'vertical' });
    await flushEffects();

    const letterbox = screen.getByTestId('application-format-scaled-box');
    expect(receivedAnchor.el).toBe(letterbox);
    // ...and that box is the SCALED application area, not the full container:
    // 1080 * 0.3125 = 337.5 wide, which is what the panel must fit.
    expect(letterbox.style.width).toBe('337.5px');
  });

  it('is not anchored on the full-width tab container when a format is declared', async () => {
    // The regression this guards: the container is what `setAppContainerEl`
    // holds, it spans the whole tab, and anchoring there makes the format
    // invisible to the panel.
    renderApp({ interfaceId: 'iface-1', label: 'tab', actionMapping: {}, format: 'vertical' });
    await flushEffects();

    const letterbox = screen.getByTestId('application-format-scaled-box');
    const container = document.querySelector('.flex-1.flex.flex-col.min-h-0.relative');
    expect(container).not.toBeNull();
    expect(container!.contains(letterbox)).toBe(true);  // the box really is inside it
    expect(receivedAnchor.el).not.toBe(container);
    // The anchor carries the SCALED width; the container carries none, which is
    // exactly why anchoring there hid the format from the panel.
    expect(receivedAnchor.el?.style.width).toBe('337.5px');
  });

  it('anchors on the letterboxed box in fullscreen too', async () => {
    // Fullscreen is a separate early-return tree with its own TriggerPanel call
    // site; a format is just as letterboxed there, so the anchor rule is the
    // same and reverting only this site must not go unnoticed.
    renderApp(
      { interfaceId: 'iface-1', label: 'tab', actionMapping: {}, format: 'vertical' },
      { isExpanded: true },
    );
    await flushEffects();

    expect(receivedAnchor.el).toBe(screen.getByTestId('application-format-scaled-box'));
  });

  it('falls back to the fullscreen overlay when the application fills it', async () => {
    renderApp({ interfaceId: 'iface-1', label: 'tab', actionMapping: {} }, { isExpanded: true });
    await flushEffects();

    expect(screen.queryByTestId('application-format-scaled-box')).toBeNull();
    expect(receivedAnchor.el?.className).toContain('fixed');
    expect(receivedAnchor.el?.className).toContain('inset-0');
  });

  it('falls back to the tab container when the application fills it', async () => {
    // No format: the iframe really is the whole container, so that IS the
    // application area and the panel should centre on it.
    renderApp({ interfaceId: 'iface-1', label: 'tab', actionMapping: {} });
    await flushEffects();

    expect(screen.queryByTestId('application-format-scaled-box')).toBeNull();
    expect(receivedAnchor.el).toBeTruthy();
    expect(receivedAnchor.el).toBe(document.querySelector('.flex-1.flex.flex-col.min-h-0.relative'));
  });
});
