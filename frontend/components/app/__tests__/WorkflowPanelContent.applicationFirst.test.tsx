/**
 * @vitest-environment jsdom
 *
 * `applicationFirst`: the panel was opened ON an application.
 *
 * The same component serves a workflow tab and an application tab. For a
 * workflow the canvas is the point and the Application sub-tab is a bonus that
 * appears once a run exposes an interface. For an application it is the reverse,
 * and two of the defaults were actively wrong there: the panel opened on the
 * canvas, and in a marketplace preview the Application sub-tab was hidden
 * outright.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

vi.mock('@/components/app/WorkflowPanelActions', () => ({
  WorkflowPanelActions: () => null,
}));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: false, isLoading: false }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/chat' }));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useWorkflowMode: () => ({ isRunMode: true, isPreviewOnly: false, workflowId: 'wf-1', runId: 'run-1' }),
}));
vi.mock('@/hooks/useWorkflowChat', () => ({
  useWorkflowChat: () => ({
    conversationId: 'c-1', messages: [], isLoading: false,
    sendMessage: vi.fn(), loadConversation: vi.fn(), stopStream: vi.fn(),
  }),
}));
vi.mock('@/hooks/useModels', () => ({
  useModels: () => ({ models: [], defaultModel: undefined }),
  useVisibleModels: () => ({ models: [], defaultModel: undefined }),
  EMPTY_SELECTED_MODEL: {},
  modelMatches: () => false,
  selectedModelFromAIModel: () => ({}),
  selectedModelEquals: () => true,
  getEffectiveDefaultSelectedModel: () => ({}),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({ useUnifiedAppSafe: () => null }));
vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => ({ isStreamingConversation: () => false }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  // Not a VIEWER: these suites are about the panel, not about role gating.
  useCanMutateInCurrentOrg: () => true,
  useCurrentOrgStore: Object.assign(
    (sel: (s: any) => any) => sel({ currentOrgId: 'org-1' }),
    { subscribe: () => () => {} },
  ),
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: Object.assign(
    () => ({}),
    { getState: () => ({ setCarouselIndex: vi.fn() }) },
  ),
  carouselKeyFor: (workflowId?: string | null, runId?: string | null) => `${workflowId ?? ''}:${runId ?? ''}`,
}));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({ normalizeLabel: (s: string) => s }));
// Stubbed to keep the real store (a stateful class with a module-level
// registry) out of a composer test, and EMPTY so that no status reads as
// terminal here. It has to name every constant this tree REACHES, not just
// the ones this file reads: run-panel/runFormatting pulls UNREVIVABLE_STATUSES
// in through WorkflowPanelContent, and a stub missing one does not fail an
// assertion, it fails the whole FILE at import.
vi.mock('@/contexts/workflow-run/RunStateStore', () => ({
  TERMINAL_STATUSES: new Set<string>(),
  UNREVIVABLE_STATUSES: new Set<string>(),
}));

// Child components → simple identifiable stubs.
vi.mock('@/components/chat/ChatCore', () => ({ ChatCore: () => <div data-testid="chat-core" /> }));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({
  ModelSelectorDropdown: () => <div data-testid="model-selector" />,
  PROVIDER_ICON_MAP: {},
}));
vi.mock('@/components/chat/TriggerTabContent', () => ({ TriggerTabContent: () => <div data-testid="trigger-content" /> }));
vi.mock('@/components/chat/ApplicationCarousel', () => ({
  ApplicationCarousel: () => <div data-testid="app-carousel" />,
}));

import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';

const CONFIGS = [{ interfaceId: 'iface-1', label: 'Home', actionMapping: {} }];

function isShowing(testId: string) {
  return screen.queryByTestId(testId) !== null;
}

describe('WorkflowPanelContent - applicationFirst', () => {
  afterEach(cleanup);

  it('opens on the Application when the host seeds the interfaces', () => {
    render(
      <WorkflowPanelContent
        workflowId="wf-1"
        runId="run-1"
        workflowCanvasSlot={<div />}
        applicationFirst
        initialApplicationConfigs={CONFIGS}
      />,
    );

    // Without the seed the tab does not exist yet and the canvas wins the first
    // frame - the flash this prop exists to remove.
    expect(isShowing('app-carousel')).toBe(true);
  });

  it('opens on the canvas without it, which is what a workflow tab wants', () => {
    render(
      <WorkflowPanelContent
        workflowId="wf-1"
        runId="run-1"
        workflowCanvasSlot={<div />}
        initialApplicationConfigs={CONFIGS}
      />,
    );

    expect(isShowing('app-carousel')).toBe(false);
    expect(screen.queryByText('common.workflow')).not.toBeNull();
  });

  it('waits on the Application with no seed at all, rather than flashing the canvas', () => {
    // A workflow id of its own: the panel keeps a module-level per-workflow cache
    // of the configs it has seen, which another test in this file fills.
    render(<WorkflowPanelContent workflowId="wf-unseeded" runId="run-1" workflowCanvasSlot={<div />} applicationFirst />);

    // Empty carousel ("no interfaces"), NOT the canvas.
    expect(isShowing('app-carousel')).toBe(true);

    act(() => {
      window.dispatchEvent(new CustomEvent('workflowPanelApplicationConfigsChange', {
        detail: { workflowId: 'wf-unseeded', configs: CONFIGS },
      }));
    });

    // ...and it is still the Application once they arrive, now with its pages.
    expect(isShowing('app-carousel')).toBe(true);
    expect(screen.queryByText('common.application')).not.toBeNull();
  });

  it('offers the Application sub-tab in a marketplace preview, where it is the whole point', () => {
    render(
      <WorkflowPanelContent
        workflowId="wf-1"
        runId="run-1"
        isPreviewOnly
        workflowCanvasSlot={<div />}
        applicationFirst
        initialApplicationConfigs={CONFIGS}
      />,
    );

    expect(screen.queryByText('common.application')).not.toBeNull();
  });

  it('keeps hiding it by default in a preview that is NOT about the application', () => {
    render(
      <WorkflowPanelContent
        workflowId="wf-1"
        runId="run-1"
        isPreviewOnly
        workflowCanvasSlot={<div />}
        initialApplicationConfigs={CONFIGS}
      />,
    );

    expect(screen.queryByText('common.application')).toBeNull();
  });
});
