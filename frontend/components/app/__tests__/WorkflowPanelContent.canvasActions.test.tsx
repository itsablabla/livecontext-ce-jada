/**
 * @vitest-environment jsdom
 *
 * Where Share / Save / Run live for a workflow shown in the side panel.
 *
 * They belong to the CANVAS, so they appear only where a canvas is mounted and
 * only while the Workflow sub-tab is the one showing. On the standalone workflow
 * page the panel has no canvas of its own and the page header already carries
 * them, so putting them here too would be two Save buttons on one screen.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('@/components/app/WorkflowPanelActions', () => ({
  WorkflowPanelActions: ({ workflowId, isRunMode }: { workflowId: string; isRunMode: boolean }) => (
    <div data-testid="panel-actions" data-workflow={workflowId} data-run-mode={String(isRunMode)} />
  ),
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
import { publishRunPanelData, makeEmptyRunPanelData } from '@/components/workflow/run-panel/runPanelBus';

function dispatchAppConfigs() {
  act(() => {
    window.dispatchEvent(new CustomEvent('workflowPanelApplicationConfigsChange', {
      detail: { workflowId: 'wf-1', configs: [{ interfaceId: 'iface-1', label: 'Home', actionMapping: {} }] },
    }));
  });
}

describe('WorkflowPanelContent - canvas actions in the sub-tab bar', () => {
  afterEach(cleanup);

  it('shows them on the Workflow tab, for the workflow the canvas holds', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div />} />);

    const actions = screen.getByTestId('panel-actions');
    expect(actions.getAttribute('data-workflow')).toBe('wf-1');
  });

  it('hides them on the other sub-tabs, where they would have no visible subject', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div />} />);
    dispatchAppConfigs();

    fireEvent.click(screen.getByText('common.application'));
    expect(screen.getByTestId('panel-canvas-actions').style.display).toBe('none');

    fireEvent.click(screen.getByText('common.workflow'));
    expect(screen.getByTestId('panel-canvas-actions').style.display).toBe('');
  });

  /**
   * Hidden, NOT unmounted - and the difference is the whole feature.
   *
   * The Save control's dirty flag, save status and streaming gate all arrive as
   * events that fire on CHANGE. A cluster that unmounts on every sub-tab visit
   * came back believing the canvas was clean, so Save sat greyed out over
   * unsaved work: the exact state this bar exists to remove.
   */
  it('survives a sub-tab round-trip instead of remounting', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div />} />);
    dispatchAppConfigs();

    const before = screen.getByTestId('panel-actions');
    fireEvent.click(screen.getByText('common.application'));
    fireEvent.click(screen.getByText('common.workflow'));

    // Same DOM node: React never tore the subtree down, so nothing it had
    // learned from a one-shot event was lost.
    expect(screen.getByTestId('panel-actions')).toBe(before);
  });

  it('follows the CANVAS into run mode, not the panel provider it is nested under', () => {
    // A canvas embedded in the panel enters run mode IN PLACE, under a provider
    // of its own. Reading the surrounding provider left the bar offering Run for
    // a workflow already running.
    render(<WorkflowPanelContent workflowId="wf-run" runId="run-1" workflowCanvasSlot={<div />} />);
    expect(screen.getByTestId('panel-actions').getAttribute('data-run-mode')).toBe('false');

    act(() => {
      publishRunPanelData({ ...makeEmptyRunPanelData('wf-run'), runId: 'run-1' });
    });

    expect(screen.getByTestId('panel-actions').getAttribute('data-run-mode')).toBe('true');
  });

  it('never shows them on a workflow the caller may not change', () => {
    // The application page passes this for an acquired app: readable workflow,
    // refused Share and version actions.
    render(
      <WorkflowPanelContent
        workflowId="wf-readonly"
        runId="run-1"
        workflowCanvasSlot={<div />}
        canEditWorkflow={false}
      />,
    );

    expect(screen.queryByTestId('panel-actions')).toBeNull();
    expect(screen.queryByTestId('panel-canvas-actions')).toBeNull();
  });

  it('never shows them in a marketplace preview, which can neither save, run nor publish', () => {
    render(<WorkflowPanelContent workflowId="wf-preview" runId="run-1" isPreviewOnly workflowCanvasSlot={<div />} />);

    expect(screen.queryByTestId('panel-actions')).toBeNull();
    // Not even the separator that would frame an empty slot.
    expect(screen.queryByTestId('panel-canvas-actions')).toBeNull();
  });

  it('never shows them without a canvas: that panel is the workflow PAGE, whose header has them', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" />);
    dispatchAppConfigs();

    expect(screen.queryByTestId('panel-actions')).toBeNull();
  });
});
