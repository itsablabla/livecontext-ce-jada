/**
 * @vitest-environment jsdom
 *
 * The workflow panel's bottom sub-tab bar must use the SHARED panel tab style
 * (`panelTabClass`) at EVERY tab, and each tab must derive its active state from
 * its OWN id - a copy-paste slip between the four near-identical buttons would
 * otherwise light up two tabs at once, or none.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

// The composer fetches the verdict once for its model menu. Stubbed:
// these suites are about layout, not billing.
// The canvas action cluster (Share / Save / Run) has its own suite; here it is a
// marker so these tests keep exercising the tab bar rather than the publish
// wizard and the version-history fetch it pulls in.
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

vi.mock('@/components/chat/ChatCore', () => ({ ChatCore: () => <div data-testid="chat-core" /> }));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({
  ModelSelectorDropdown: () => <div data-testid="model-selector" />,
  PROVIDER_ICON_MAP: {},
}));
vi.mock('@/components/chat/TriggerTabContent', () => ({ TriggerTabContent: () => <div data-testid="trigger-content" /> }));
vi.mock('@/components/chat/ApplicationCarousel', () => ({
  ApplicationCarousel: () => <div data-testid="app-carousel" />,
}));

import { panelTabClass } from '@/components/ui/panel-tab';
import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';

afterEach(cleanup);

function renderPanel() {
  const utils = render(
    <WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div data-testid="canvas-slot" />} />,
  );
  act(() => {
    window.dispatchEvent(new CustomEvent('workflowPanelApplicationConfigsChange', {
      detail: { workflowId: 'wf-1', configs: [{ interfaceId: 'iface-1', label: 'Search Page', actionMapping: {} }] },
    }));
  });
  return utils;
}

function subTabs(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>('[data-testid="panel-sub-tab"]'));
}
/**
 * Whole-token membership, NOT substring: `toContain('bg-[var(--bg-hover)]')`
 * is also satisfied by `hover:bg-[var(--bg-hover)]`, so a tab that lost its
 * resting fill would pass with no visible active state.
 */
function tokens(el: HTMLElement): string[] {
  return el.className.split(/\s+/).filter(Boolean);
}

describe('WorkflowPanelContent bottom sub-tabs', () => {
  it('styles every sub-tab with the shared compact panel tab class', () => {
    const { container } = renderPanel();
    const tabs = subTabs(container);
    // Pin the SET, not just a count: a tab that loses its data-testid drops out
    // of this loop silently and stops being style-checked at all.
    expect(tabs.map((t) => t.textContent)).toEqual([
      'sidePanel.aiChat',
      'common.workflow',
      'workflowBuilder.canvas.addNode',
      'common.application',
    ]);
    for (const tab of tabs) {
      const isActive = tab.getAttribute('aria-pressed') === 'true';
      expect(tab.className).toContain(panelTabClass(isActive, 'sm'));
    }
  });

  it('the active sub-tab has a resting fill, not one that only appears on hover', () => {
    const { container } = renderPanel();
    const active = subTabs(container).filter((t) => t.getAttribute('aria-pressed') === 'true');
    expect(active).toHaveLength(1);
    expect(tokens(active[0])).toContain('bg-[var(--bg-hover)]');
    expect(tokens(active[0])).not.toContain('bg-transparent');
    for (const inactive of subTabs(container).filter((t) => t.getAttribute('aria-pressed') !== 'true')) {
      expect(tokens(inactive)).toContain('bg-transparent');
      expect(tokens(inactive)).not.toContain('bg-[var(--bg-hover)]');
    }
  });

  it('marks exactly one sub-tab active, and it follows the click', () => {
    const { container } = renderPanel();
    const activeLabels = () =>
      subTabs(container).filter((t) => t.getAttribute('aria-pressed') === 'true').map((t) => t.textContent);

    expect(activeLabels()).toHaveLength(1);

    fireEvent.click(screen.getByText('common.application').closest('button')!);
    expect(activeLabels()).toEqual(['common.application']);

    fireEvent.click(screen.getByText('sidePanel.aiChat').closest('button')!);
    expect(activeLabels()).toEqual(['sidePanel.aiChat']);
  });

  it('puts the bar on the primary surface, which the hover + focus-ring offset assume', () => {
    const { container } = renderPanel();
    const bar = subTabs(container)[0].closest('div')!.parentElement!;
    expect(bar.className).toContain('bg-theme-primary');
    expect(bar.className).not.toContain('bg-theme-secondary');
  });
});
