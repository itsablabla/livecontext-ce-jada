/**
 * @vitest-environment jsdom
 *
 * The node inspector can be DOCKED into the side panel as a sub-tab. The panel
 * never builds the inspector itself (it lives in the builder tree and needs its
 * contexts): it offers a HOST element, and the canvas portals the real thing in.
 * This pins that contract:
 *   - the tab appears only while the canvas asks for the dock;
 *   - the host is registered while the tab exists and withdrawn when it goes;
 *   - the host stays MOUNTED across sub-tab switches (hidden, not torn down);
 *   - the open event focuses the tab, and only for the addressed workflow;
 *   - a panel that already HOSTS a canvas, or a preview, never offers the slot.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

const previewMode = vi.hoisted(() => ({ isPreviewOnly: false }));

vi.mock('@/components/app/WorkflowPanelActions', () => ({ WorkflowPanelActions: () => null }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/workflow/wf-1' }));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: false }),
}));
vi.mock('@/components/chat/ChatCore', () => ({ ChatCore: () => <div data-testid="chat" /> }));
vi.mock('@/app/shared/components', () => ({ WelcomeTitle: ({ children }: any) => <>{children}</> }));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({
  ModelSelectorDropdown: () => null,
  PROVIDER_ICON_MAP: {},
}));
vi.mock('@/components/ai/NoProviderCta', () => ({ NoProviderCta: () => null }));
vi.mock('@/components/chat/TriggerTabContent', () => ({ TriggerTabContent: () => null }));
vi.mock('@/components/chat/ApplicationCarousel', () => ({ ApplicationCarousel: () => null }));
vi.mock('@/hooks/useWorkflowChat', () => ({
  useWorkflowChat: () => ({
    conversationId: null, messages: [], isLoading: false,
    sendMessage: vi.fn(), loadConversation: vi.fn(), stopStream: vi.fn(),
  }),
}));
vi.mock('@/hooks/useModels', () => ({
  useVisibleModels: () => ({ models: [], defaultModel: null, isLoading: false, error: null }),
  EMPTY_SELECTED_MODEL: { id: '' },
  modelMatches: () => false,
  selectedModelFromAIModel: () => ({ id: '' }),
  selectedModelEquals: () => true,
  getEffectiveDefaultSelectedModel: () => ({ id: '' }),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({ useUnifiedAppSafe: () => null }));
vi.mock('@/contexts/StreamingContext', () => ({ useStreaming: () => ({ isStreamingConversation: () => false }) }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: any) => <>{children}</>,
  useWorkflowMode: () => ({ isPreviewOnly: previewMode.isPreviewOnly, workflowId: 'wf-1' }),
}));
vi.mock('@/components/workflow/run-panel/RunPanelContent', () => ({
  RunPanelContent: () => <div data-testid="run-panel" />,
}));
vi.mock('@/components/app/NodeCreatorPanelContent', () => ({
  NodeCreatorPanelContent: () => <div data-testid="node-creator" />,
}));

import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';
import { clearRunPanelCache } from '@/components/workflow/run-panel/runPanelBus';
import {
  getInspectorDockHost,
  openInspectorPanel,
  publishInspectorDockState,
  type InspectorDockSurface,
} from '@/lib/workflow/inspectorDockBus';

function dock(
  workflowId: string,
  docked: boolean,
  label?: string,
  surface: InspectorDockSurface = 'page',
) {
  act(() => publishInspectorDockState({ workflowId, surface, docked, label }));
}

function clickTab(text: string) {
  act(() => { screen.getByText(text).closest('button')!.click(); });
}

function resetDock() {
  publishInspectorDockState({ workflowId: 'wf-1', surface: 'page', docked: false });
  publishInspectorDockState({ workflowId: 'wf-1', surface: 'embedded', docked: false });
}

beforeEach(() => {
  clearRunPanelCache();
  previewMode.isPreviewOnly = false;
  resetDock();
});

afterEach(() => {
  cleanup();
  resetDock();
});

describe('WorkflowPanelContent - docked inspector sub-tab', () => {
  it('shows no Inspector tab and offers no slot while the canvas is not asking', () => {
    render(<WorkflowPanelContent workflowId="wf-1" />);

    expect(screen.queryByTestId('inspector-dock-slot')).toBeNull();
    expect(getInspectorDockHost('wf-1', 'page')).toBeNull();
  });

  it('shows the tab and registers the slot once the canvas asks for the dock', () => {
    render(<WorkflowPanelContent workflowId="wf-1" />);

    dock('wf-1', true, 'Send Email');

    // Captioned with the node's own label, so a panel of several tabs still says
    // WHICH node is being configured.
    expect(screen.getByText('Send Email')).toBeTruthy();
    const slot = screen.getByTestId('inspector-dock-slot');
    expect(getInspectorDockHost('wf-1', 'page')).toBe(slot);
  });

  it('falls back to the generic caption when the node has no label yet', () => {
    render(<WorkflowPanelContent workflowId="wf-1" />);

    dock('wf-1', true);

    expect(screen.getByText('sidePanel.inspectorTab')).toBeTruthy();
  });

  it('adopts a selection published BEFORE it mounted', () => {
    // The panel body is unmounted while the side panel is closed, so the publish
    // that came with the node selection has no listener. Reopening the panel must
    // still find the tab, which is what the bus cache is for.
    dock('wf-1', true, 'Fetch Rows');

    render(<WorkflowPanelContent workflowId="wf-1" />);

    expect(screen.getByText('Fetch Rows')).toBeTruthy();
    expect(getInspectorDockHost('wf-1', 'page')).toBe(screen.getByTestId('inspector-dock-slot'));
  });

  it('keeps the slot MOUNTED but hidden when another sub-tab is active', () => {
    // Unmounting it on every sub-tab switch would tear the portal down and lose
    // whatever local state the inspector holds (open sections, scroll, drafts).
    render(<WorkflowPanelContent workflowId="wf-1" />);
    // What the canvas actually does on a selection: publish the state, then ask
    // for the tab. The publish alone deliberately does not steal focus.
    dock('wf-1', true, 'Send Email');
    act(() => openInspectorPanel({ workflowId: 'wf-1', surface: 'page' }));

    const slot = screen.getByTestId('inspector-dock-slot');
    expect(slot.style.display).toBe('');

    clickTab('sidePanel.aiChat');

    expect(screen.getByTestId('inspector-dock-slot')).toBe(slot);
    expect(slot.style.display).toBe('none');
    expect(getInspectorDockHost('wf-1', 'page')).toBe(slot);
  });

  it('withdraws the slot and drops the tab when the node is deselected', () => {
    render(<WorkflowPanelContent workflowId="wf-1" />);
    dock('wf-1', true, 'Send Email');

    dock('wf-1', false);

    expect(screen.queryByTestId('inspector-dock-slot')).toBeNull();
    expect(screen.queryByText('Send Email')).toBeNull();
    // A detached element left registered would silently swallow the portal.
    expect(getInspectorDockHost('wf-1', 'page')).toBeNull();
    // The panel does not sit on a dead tab: it falls back to the chat.
    expect(screen.getByTestId('chat')).toBeTruthy();
  });

  it('focuses the Inspector tab on an open request, and ignores another workflow request', () => {
    render(<WorkflowPanelContent workflowId="wf-1" />);
    dock('wf-1', true, 'Send Email');
    clickTab('sidePanel.aiChat');
    expect(screen.getByTestId('inspector-dock-slot').style.display).toBe('none');

    act(() => openInspectorPanel({ workflowId: 'wf-other', surface: 'page' }));
    expect(screen.getByTestId('inspector-dock-slot').style.display).toBe('none');

    act(() => openInspectorPanel({ workflowId: 'wf-1', surface: 'page' }));
    expect(screen.getByTestId('inspector-dock-slot').style.display).toBe('');
  });

  it('applies an open request that arrived BEFORE the tab existed', () => {
    // The canvas publishes the state and asks for the focus together, and the two
    // do not always land in the same commit. Applying the focus while the tab is
    // still unavailable would set an active tab the fallback undoes at once, so
    // the request waits for the tab instead.
    render(<WorkflowPanelContent workflowId="wf-1" />);

    act(() => openInspectorPanel({ workflowId: 'wf-1', surface: 'page' }));
    expect(screen.queryByTestId('inspector-dock-slot')).toBeNull();

    dock('wf-1', true, 'Send Email');

    expect(screen.getByTestId('inspector-dock-slot').style.display).toBe('');
  });

  it('does NOT steal the tab back when only the selected node is renamed', () => {
    // The dock state also changes when the node's LABEL is edited. Focus is taken
    // on the OPEN request alone, or renaming a node from another tab would yank
    // the user back mid-keystroke.
    render(<WorkflowPanelContent workflowId="wf-1" />);
    dock('wf-1', true, 'Send Email');
    clickTab('sidePanel.aiChat');

    dock('wf-1', true, 'Send Email v2');

    expect(screen.getByText('Send Email v2')).toBeTruthy();
    expect(screen.getByTestId('inspector-dock-slot').style.display).toBe('none');
  });

  it('offers the slot on the EMBEDDED surface when it hosts a canvas', () => {
    // The Application panel and the sub-workflow tabs: the canvas is a sub-tab of
    // this very panel, so the inspector becomes its sibling and the two are shown
    // one at a time.
    render(<WorkflowPanelContent workflowId="wf-1" workflowCanvasSlot={<div data-testid="canvas" />} />);

    dock('wf-1', true, 'Send Email', 'embedded');

    expect(screen.getByText('Send Email')).toBeTruthy();
    expect(getInspectorDockHost('wf-1', 'embedded')).toBe(screen.getByTestId('inspector-dock-slot'));
    // And it does not answer for the page canvas of the workflow behind it.
    expect(getInspectorDockHost('wf-1', 'page')).toBeNull();
  });

  it('a canvas-hosting panel ignores the PAGE canvas state', () => {
    // Both can be live at once for one workflow (a self-referencing sub-workflow).
    // Listening on both would make this panel show a tab for someone else's node.
    render(<WorkflowPanelContent workflowId="wf-1" workflowCanvasSlot={<div data-testid="canvas" />} />);

    dock('wf-1', true, 'Send Email', 'page');

    expect(screen.queryByTestId('inspector-dock-slot')).toBeNull();
  });

  it('a page panel ignores an EMBEDDED open request', () => {
    render(<WorkflowPanelContent workflowId="wf-1" />);
    dock('wf-1', true, 'Send Email');
    clickTab('sidePanel.aiChat');

    act(() => openInspectorPanel({ workflowId: 'wf-1', surface: 'embedded' }));

    expect(screen.getByTestId('inspector-dock-slot').style.display).toBe('none');
  });

  it('never offers the slot in the read-only marketplace preview', () => {
    previewMode.isPreviewOnly = true;
    render(<WorkflowPanelContent workflowId="wf-1" isPreviewOnly />);

    dock('wf-1', true, 'Send Email');

    expect(screen.queryByTestId('inspector-dock-slot')).toBeNull();
    expect(getInspectorDockHost('wf-1', 'page')).toBeNull();
  });
});
