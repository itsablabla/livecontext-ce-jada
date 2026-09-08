/**
 * @vitest-environment jsdom
 *
 * A page-switch link is a page switch, never a trigger fire.
 *
 * `interface:<label>:navigate` reaching the action handler must NOT leave the
 * frontend. The parser that handles real triggers strips the last segment and
 * fires the remainder, so an unguarded navigate became
 * `executeStep(runId, 'interface:<label>', ...)` - a backend call from what the
 * author wrote as a link between two pages of their app.
 *
 * The guard used to live in the application side panel, which rendered a single
 * interface and could only ignore the switch. That panel now composes this one,
 * which owns a carousel of every page, so the invariant is pinned HERE - and the
 * switch is honoured rather than dropped.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

/** The last page the carousel was asked to show, by interface id. */
const lastTargetRef = vi.hoisted(() => ({ current: null as string | null }));

/** The onAction the panel hands to the carousel. */
const onActionRef = vi.hoisted(() => ({
  current: undefined as ((ref: string, data: Record<string, unknown>) => void) | undefined,
}));
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
  ApplicationCarousel: (props: {
    onAction: (ref: string, data: Record<string, unknown>) => void;
    targetInterfaceId?: string | null;
    onTargetConsumed?: () => void;
  }) => {
    onActionRef.current = props.onAction;
    // Records the page it was asked for, then consumes the request the way the
    // real carousel does - so each assertion below sees only its OWN request.
    if (props.targetInterfaceId) lastTargetRef.current = props.targetInterfaceId;
    React.useEffect(() => {
      if (props.targetInterfaceId) props.onTargetConsumed?.();
    });
    return <div data-testid="app-carousel" />;
  },
}));

import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';

const CONFIGS = [
  { interfaceId: 'iface-1', label: 'Home', actionMapping: {} },
  { interfaceId: 'iface-2', label: 'details', actionMapping: {} },
];

function renderPanelOnApplicationTab() {
  render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div />} />);
  act(() => {
    window.dispatchEvent(new CustomEvent('workflowPanelApplicationConfigsChange', {
      detail: { workflowId: 'wf-1', configs: CONFIGS },
    }));
  });
  // The carousel only mounts once its tab is the one showing.
  act(() => {
    window.dispatchEvent(new CustomEvent('workflowOpenApplicationTab', {
      detail: { interfaceId: 'iface-1' },
    }));
  });
  // That focus request has been consumed; the assertions below are about what
  // the ACTION asks for.
  lastTargetRef.current = null;
}

describe('WorkflowPanelContent - a navigate ref is a page switch, not a trigger', () => {
  beforeEach(() => {
    onActionRef.current = undefined;
    lastTargetRef.current = null;
  });
  afterEach(cleanup);

  it('switches the carousel page and sends nothing to the backend', () => {
    renderPanelOnApplicationTab();
    const requests: Event[] = [];
    window.addEventListener('workflowApplicationActionRequest', (e) => requests.push(e));

    act(() => { onActionRef.current?.('interface:details:navigate', {}); });

    // Named by INTERFACE, not by position: the carousel resolves it, so the
    // request survives the run binding that would have moved a stored index.
    expect(lastTargetRef.current).toBe('iface-2');
    expect(requests).toHaveLength(0);
  });

  it('sends nothing for the legacy trigger-prefixed page switch either', () => {
    renderPanelOnApplicationTab();
    const requests: Event[] = [];
    window.addEventListener('workflowApplicationActionRequest', (e) => requests.push(e));

    act(() => { onActionRef.current?.('trigger:details:navigate', {}); });

    expect(requests).toHaveLength(0);
  });

  it('sends nothing when the navigate names a page the app does not have', () => {
    renderPanelOnApplicationTab();
    const requests: Event[] = [];
    window.addEventListener('workflowApplicationActionRequest', (e) => requests.push(e));

    act(() => { onActionRef.current?.('interface:ghost_page:navigate', {}); });

    expect(lastTargetRef.current).toBeNull();
    expect(requests).toHaveLength(0);
  });

  it('still forwards a real trigger to the canvas', () => {
    renderPanelOnApplicationTab();
    const requests: CustomEvent[] = [];
    window.addEventListener('workflowApplicationActionRequest', (e) => requests.push(e as CustomEvent));

    act(() => { onActionRef.current?.('trigger:my_form:submit', { city: 'Lyon' }); });

    expect(requests).toHaveLength(1);
    // Named, so only the canvas of THIS workflow answers: every mounted canvas
    // subscribes to this bus.
    expect(requests[0].detail).toEqual({
      triggerRef: 'trigger:my_form:submit',
      data: { city: 'Lyon' },
      workflowId: 'wf-1',
    });
  });
});
