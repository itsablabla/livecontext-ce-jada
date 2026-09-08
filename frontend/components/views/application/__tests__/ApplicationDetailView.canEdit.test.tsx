/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

/**
 * Pins the editable-application gating: `canEdit` reaches the embedded canvas as
 * `hideToggle`, so it decides whether the application offers the run/edit toggle
 * at all. It is true for one caller only - the publisher of a publication they
 * have NOT installed, who is bound to the editable SOURCE workflow. An installed
 * application is a frozen clone the backend refuses to write, and a preview is
 * read-only; both stay run-locked.
 *
 * `isInstalledClone` is the separate question of WHICH workflow is bound, and the
 * two are true at opposite times: it is what tells the toolbar whether the reset,
 * which always targets the caller's install, has a target on this screen.
 *
 * Regression: dropping either flag anywhere in the layout -> ApplicationDetailView
 * chain silently flips an app to read-only, to editable, or offers a reset that
 * writes to tables the screen does not show.
 */

const canvasProps = vi.hoisted(() => [] as Array<{ hideToggle?: boolean }>);
const carouselProps = vi.hoisted(() => [] as Array<{ templateSource?: { canReset?: boolean } }>);
const panelProps = vi.hoisted(() => [] as Array<{ canEditWorkflow?: boolean }>);
const addTabMock = vi.hoisted(() => vi.fn());
const updatePublicationMock = vi.hoisted(() => vi.fn());

// The view mounts ApplicationSettingsMenu, whose "open the copy" link is a locale-aware
// next-intl Link. That module resolves next/navigation from inside node_modules, which
// vitest cannot do - stub it; the cog's own behaviour has its own suite.
vi.mock('@/i18n/navigation', () => ({
  Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: { updatePublication: updatePublicationMock },
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (k: string) => k,
}));

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useWorkflowMode: () => ({ setRunId: vi.fn(), isPreviewOnly: false, setViewingEpoch: vi.fn() }),
}));

vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({
    addTab: addTabMock,
    setActiveTab: vi.fn(),
    open: vi.fn(),
    isOpen: true,
  }),
}));

// Render the canvas slot so the (mocked) WorkflowRunCanvas runs and records its props.
vi.mock('@/components/app/WorkflowPanelContent', () => ({
  WorkflowPanelContent: ({ workflowCanvasSlot, canEditWorkflow }: { workflowCanvasSlot?: React.ReactNode; canEditWorkflow?: boolean }) => {
    panelProps.push({ canEditWorkflow });
    return <div data-testid="workflow-panel">{workflowCanvasSlot}</div>;
  },
  setPendingActivateTab: vi.fn(),
}));

vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({
  WorkflowRunCanvas: (props: {
    hideToggle?: boolean;
    onApplicationConfigsChange?: (configs: Array<Record<string, unknown>>) => void;
  }) => {
    canvasProps.push({ hideToggle: props.hideToggle });
    // The real canvas emits the run's interfaces, and that emission is what makes
    // the page render its carousel at all.
    React.useEffect(() => {
      props.onApplicationConfigsChange?.([{ interfaceId: 'iface-1', label: 'Home', actionMapping: {} }]);
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    return <div data-testid="workflow-canvas" />;
  },
}));

vi.mock('@/components/chat/ApplicationCarousel', () => ({
  ApplicationCarousel: (props: { templateSource?: { canReset?: boolean } }) => {
    carouselProps.push({ templateSource: props.templateSource });
    return null;
  },
}));
vi.mock('@/components/marketplace/PublicationInfoPanel', () => ({ PublicationInfoPanel: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: { getState: () => ({ setCarouselIndex: vi.fn() }) },
  carouselKeyFor: (workflowId?: string | null, runId?: string | null) => `${workflowId ?? ''}:${runId ?? ''}`,
}));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({ normalizeLabel: (s: string) => s }));
vi.mock('../workflow/WorkflowLoadingState', () => ({ WorkflowLoadingState: () => null }));
vi.mock('../workflow/WorkflowUnauthorizedState', () => ({ WorkflowUnauthorizedState: () => null }));
vi.mock('../workflow/hooks', () => ({ useAutoCollapseSidebar: () => undefined }));

import { ApplicationDetailView } from '@/components/views/application/ApplicationDetailView';

/** Pull the hideToggle the captured side-panel tab content forwards to WorkflowRunCanvas. */
function renderAndReadHideToggle(canEdit: boolean | undefined): boolean | undefined {
  canvasProps.length = 0;
  panelProps.length = 0;
  addTabMock.mockClear();
  render(
    <ApplicationDetailView
      workflowId="wf-1"
      runId="run-1"
      title="My App"
      canEdit={canEdit}
    />
  );
  // The workflow canvas lives inside the side-panel tab content (registered via addTab),
  // not the main render tree. Render that captured content to run WorkflowRunCanvas.
  expect(addTabMock).toHaveBeenCalledTimes(1);
  const tabContent = addTabMock.mock.calls[0][0].content as React.ReactElement;
  render(tabContent);
  expect(canvasProps).toHaveLength(1);
  return canvasProps[0].hideToggle;
}

/** Pull the canReset the page hands its carousel, once the canvas has emitted its interfaces. */
function renderAndReadCanReset(publication: NonNullable<React.ComponentProps<typeof ApplicationDetailView>['publication']>, isInstalledClone?: boolean): boolean | undefined {
  carouselProps.length = 0;
  addTabMock.mockClear();
  render(
    <ApplicationDetailView
      workflowId="wf-1"
      runId="run-1"
      publication={publication}
      isInstalledClone={isInstalledClone}
    />
  );
  // Rendering the captured tab content runs the canvas, whose emission is what
  // brings the carousel on screen.
  const tabContent = addTabMock.mock.calls[0][0].content as React.ReactElement;
  render(tabContent);
  return carouselProps.at(-1)?.templateSource?.canReset;
}

describe('ApplicationDetailView - editable gating (canEdit -> hideToggle)', () => {
  afterEach(() => {
    canvasProps.length = 0;
    addTabMock.mockReset();
    updatePublicationMock.mockReset();
    vi.restoreAllMocks();
    cleanup();
  });

  it('shows the run/edit toggle for the publisher of their own source workflow (canEdit=true -> hideToggle=false)', () => {
    expect(renderAndReadHideToggle(true)).toBe(false);
    expect(panelProps.at(-1)?.canEditWorkflow).toBe(true);
  });

  it('keeps the canvas run-locked for non-owners (canEdit=false -> hideToggle=true)', () => {
    expect(renderAndReadHideToggle(false)).toBe(true);
    // The same answer reaches the panel, which is what withholds the Share / Save
    // bar and the node palette beside that canvas.
    expect(panelProps.at(-1)?.canEditWorkflow).toBe(false);
  });

  it('defaults to read-only when canEdit is omitted (hideToggle=true)', () => {
    expect(renderAndReadHideToggle(undefined)).toBe(true);
  });

  it('offers the data reset only when this page is bound to the caller install', () => {
    // The endpoint always resolves the caller's own installed clone. Reading
    // ownership instead of what is bound was wrong twice over: it withheld the
    // reset from a publisher who had installed their own app - their clone being
    // the one on screen - and offered it to a visitor who installed nothing, where
    // it has nothing to resolve.
    const pub = { id: 'p1', title: 'X', visibility: 'PRIVATE', creditsPerUse: 0, ownedByMe: true } as never;

    expect(renderAndReadCanReset(pub, true), 'bound to the install').toBe(true);
    cleanup();
    expect(renderAndReadCanReset(pub, false), 'bound to a source workflow').toBe(false);
    cleanup();
    // The default is what the shared-link page gets, since it passes no flag at
    // all: a visitor there has no install, so the reset must be off by default.
    expect(renderAndReadCanReset(pub, undefined), 'flag omitted').toBe(false);
  });

  it('does not render the Publish-update button (UI removed) even for the publication owner', () => {
    // The publish logic (handlePublishUpdate + updatePublication) is intentionally
    // kept in the component, but its button is no longer rendered, so there is no
    // UI affordance to trigger a publish-update for anyone - owner, acquirer, or
    // anonymous preview alike.
    const pub = { id: 'p1', title: 'X', visibility: 'PRIVATE', creditsPerUse: 0 } as never;
    const { queryByText } = render(
      <ApplicationDetailView workflowId="wf-1" runId="run-1" canEdit canPublish publication={pub} />
    );
    expect(queryByText('publishUpdate')).toBeNull();
    expect(updatePublicationMock).not.toHaveBeenCalled();
  });
});
