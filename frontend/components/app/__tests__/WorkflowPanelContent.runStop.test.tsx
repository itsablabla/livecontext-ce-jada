/**
 * @vitest-environment jsdom
 *
 * The stop button must be reachable from EVERY sub-tab, not only the Run tab.
 *
 * The bug: launching a workflow lands the user on the tab that needs their
 * input - the form or chat tab of a payload trigger, or the Application tab
 * once an interface opens. The only stop controls lived on the Run tab and on
 * the canvas pill, so from those tabs a live run could not be stopped at all.
 * The tab bar is the one piece of chrome every sub-tab shares, so the control
 * belongs there - and only while something is actually executing.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

const runPanelProps = vi.hoisted(() => ({ current: null as any }));
const nodeCreatorProps = vi.hoisted(() => ({ current: null as any }));
const api = vi.hoisted(() => ({ stopWorkflow: vi.fn(async () => ({}) as never) }));
vi.mock('@/lib/api', () => ({ orchestratorApi: api }));
/** Workspace role the panel is rendered under. VIEWER may watch, not steer. */
const orgRole = vi.hoisted(() => ({ canMutate: true }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => orgRole.canMutate,
  useCurrentOrgStore: Object.assign(
    (sel: (s: Record<string, unknown>) => unknown) => sel({ currentOrgId: 'org-1', currentOrgRole: 'OWNER' }),
    { subscribe: () => () => undefined, getState: () => ({ currentOrgId: 'org-1' }) },
  ),
}));

// The canvas action cluster (Share / Save / Run) has its own suite; here it is a
// marker so these tests keep exercising the tab bar rather than the publish
// wizard and the version-history fetch it pulls in.
vi.mock('@/components/app/WorkflowPanelActions', () => ({
  WorkflowPanelActions: () => null,
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
/** Route the panel is mounted on - a public share link is not the same thing. */
const pathname = vi.hoisted(() => ({ current: '/app/workflow/wf-1' }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => pathname.current }));
// Captures what the panel hands ChatCore, so the model menu it BUILDS can be
// read without the stand-in having to render it.
const chatCoreProps = vi.hoisted(() => ({ last: null as Record<string, unknown> | null }));
const credits = vi.hoisted(() => ({ blocked: false }));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: credits.blocked }),
}));
vi.mock('@/components/chat/ChatCore', () => ({
  ChatCore: (props: Record<string, unknown>) => {
    chatCoreProps.last = props;
    return <div data-testid="chat" />;
  },
}));
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
  useWorkflowMode: () => ({ isPreviewOnly: false, workflowId: 'wf-1' }),
}));
vi.mock('@/components/workflow/run-panel/RunPanelContent', () => ({
  RunPanelContent: (props: any) => { runPanelProps.current = props; return <div data-testid="run-panel" />; },
}));
vi.mock('@/components/app/NodeCreatorPanelContent', () => ({
  NodeCreatorPanelContent: (props: any) => { nodeCreatorProps.current = props; return <div data-testid="node-creator" />; },
}));

import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';
import {
  clearRunPanelCache,
  makeEmptyRunPanelData,
  publishRunPanelData,
} from '@/components/workflow/run-panel/runPanelBus';

function publishRun(overrides: Record<string, unknown> = {}) {
  act(() => {
    publishRunPanelData({
      ...makeEmptyRunPanelData('wf-1'),
      runId: 'run-1',
      runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 },
      currentEpoch: 1,
      ...overrides,
    } as any);
  });
}

beforeEach(() => clearRunPanelCache());
afterEach(() => {
  clearRunPanelCache();
  runPanelProps.current = null;
  nodeCreatorProps.current = null;
  cleanup();
});

/** Label of the sub-tab currently selected, so "it stays on tab X" means it. */
function activeTabLabel(container: HTMLElement): string {
  return container.querySelector('[data-testid="panel-sub-tab"][data-active="true"]')?.textContent ?? '';
}

/** Click a sub-tab AND prove it took: without this every "stays on X" test
 *  degenerates into "the stop is somewhere", which test #1 already says. */
function selectTab(container: HTMLElement, label: string): void {
  act(() => { screen.getByText(label).closest('button')!.click(); });
  expect(activeTabLabel(container)).toContain(label);
}

/** The stop control the tab bar owns (never the one inside the Run tab body). */
function tabBarStop(container: HTMLElement): HTMLElement | null {
  return container.querySelector<HTMLElement>('[data-testid="panel-run-stop"] [data-run-action]');
}

describe('WorkflowPanelContent - stopping a run from any sub-tab', () => {
  beforeEach(() => {
    orgRole.canMutate = true;
    pathname.current = '/app/workflow/wf-1';
  });

  it('offers the stop while the run is executing, on the tab the launch landed on', () => {
    publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);

    // The panel opens on AI Chat, not on the Run tab - exactly the situation
    // that used to leave a live run unstoppable.
    expect(screen.getByText('sidePanel.aiChat').closest('button')!.getAttribute('aria-pressed')).toBe('true');
    expect(tabBarStop(container)).not.toBeNull();
    expect(tabBarStop(container)!.getAttribute('data-run-action')).toBe('stop');
  });

  it('STAYS on the Run tab too: its history level carries no action control', () => {
    // The run identity bar (and its stop) belongs to the Run tab's RUN level.
    // Walk up to the run history during a live run and the panel has nothing,
    // which is why this control does not try to guess who owns one.
    publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);

    selectTab(container, 'sidePanel.runTab');

    expect(tabBarStop(container)).not.toBeNull();
  });

  it('STAYS on the Workflow tab: the canvas pill needs run mode and no settings drawer', () => {
    // Standing down here cost the user the only control whenever the pill was not
    // showing. A duplicate button is cosmetic; an absent one is the bug.
    publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 } });
    const { container } = render(
      <WorkflowPanelContent workflowId="wf-1" workflowCanvasSlot={<div data-testid="canvas-slot" />} />,
    );

    selectTab(container, 'common.workflow');

    expect(tabBarStop(container)).not.toBeNull();
  });

  it('STAYS on the Application tab, whose own controls are collapsed by default', () => {
    // The interface toolbar starts behind a grip, so its stop is not on screen
    // until the user opens it - and the Application tab is exactly where a
    // launched run tends to land.
    publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);
    act(() => {
      window.dispatchEvent(new CustomEvent('workflowPanelApplicationConfigsChange', {
        detail: { workflowId: 'wf-1', configs: [{ interfaceId: 'iface-1', label: 'Page', actionMapping: {} }] },
      }));
    });

    selectTab(container, 'common.application');

    expect(tabBarStop(container)).not.toBeNull();
  });

  it('survives every sub-tab the user walks through', () => {
    publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);

    for (const label of ['sidePanel.runTab', 'sidePanel.aiChat', 'sidePanel.runTab']) {
      selectTab(container, label);
      expect(tabBarStop(container), `stop missing on ${label}`).not.toBeNull();
    }
  });

  it('sits OUTSIDE the scrolling tab track, so overflowing tabs cannot hide it', () => {
    // The one control that stops a live run must stay on screen when the tab
    // strip overflows; that is the whole reason for its place in the markup.
    publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);

    const stopHost = container.querySelector('[data-testid="panel-run-stop"]')!;
    const track = container.querySelector('.overflow-x-auto');
    expect(track).not.toBeNull();
    expect(track!.contains(stopHost)).toBe(false);
  });

  it('marks the control when the attempt failed, so the click is never silent', async () => {
    api.stopWorkflow.mockRejectedValueOnce(new Error('backend refused'));
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);

    await act(async () => { tabBarStop(container)!.click(); });
    errors.mockRestore();

    expect(tabBarStop(container)!.getAttribute('data-run-action-failed')).toBe('true');
  });

  it('stops the run when pressed', async () => {
    api.stopWorkflow.mockClear();
    publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);

    await act(async () => { tabBarStop(container)!.click(); });

    // No canvas is mounted in this fixture, which is the whole point: the click
    // has to reach the backend anyway instead of dying on an unheard event.
    expect(api.stopWorkflow).toHaveBeenCalledWith('run-1');
  });

  it('lets the mounted canvas take the stop, without a REST call behind its back', async () => {
    // The production path: a canvas IS mounted on every host of this panel, and
    // it owns the run manager. It must be the one that acts.
    api.stopWorkflow.mockClear();
    const claimed: string[] = [];
    const canvas = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      detail.handled = true;
      detail.result = Promise.resolve();
      claimed.push(detail.action);
    };
    window.addEventListener('workflowRunAction', canvas);
    try {
      publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 } });
      const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);

      await act(async () => { tabBarStop(container)!.click(); });
    } finally {
      window.removeEventListener('workflowRunAction', canvas);
    }

    expect(claimed).toEqual(['stop']);
    expect(api.stopWorkflow).not.toHaveBeenCalled();
  });

  it('is refused on a public share link, where stopping is not allow-listed', () => {
    // The panel is only kept off a share page by a `display:none` wrapper, which
    // is a layout detail and not a guard - the control would still be in the
    // visitor's DOM, and their click would 403.
    pathname.current = '/s/some-share-token';
    publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);
    expect(tabBarStop(container)).toBeNull();
  });

  it('paints no chrome at all when there is no action to offer', () => {
    // The wrapper carries padding: rendering it around nothing puts an empty
    // padded box in the tab bar for every idle and finished run.
    publishRun({ runInfo: { runId: 'run-1', status: 'WAITING_TRIGGER', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);
    expect(container.querySelector('[data-testid="panel-run-stop"]')).toBeNull();
  });

  it('is refused to a VIEWER, who may watch the workspace but not steer it', () => {
    // The Run button in this same tab bar is gated the same way; the stop was
    // widening what a read-only member could do to a colleague's run.
    orgRole.canMutate = false;
    publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);
    expect(tabBarStop(container)).toBeNull();
  });

  it('shows no stop for a run parked on its triggers - that is the idle state', () => {
    // A pinned production run sits in WAITING_TRIGGER indefinitely. A permanent
    // red square beside the tabs would be noise, and cancelling it is
    // destructive: that control stays on the run surfaces.
    publishRun({ runInfo: { runId: 'run-1', status: 'WAITING_TRIGGER', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);
    expect(tabBarStop(container)).toBeNull();
  });

  it('shows no stop once the run has finished', () => {
    publishRun({ runInfo: { runId: 'run-1', status: 'COMPLETED', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);
    expect(tabBarStop(container)).toBeNull();
  });

  it('shows no stop with no run bound at all', () => {
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);
    expect(tabBarStop(container)).toBeNull();
  });

  it('never offers it when the HOST declares a preview', () => {
    // Two independent signals, asserted apart: setting both would keep this green
    // even if one of the guards were deleted.
    publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" isPreviewOnly />);
    expect(tabBarStop(container)).toBeNull();
  });

  it('never offers it when the RUN SNAPSHOT says the run is a frozen preview', () => {
    publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 }, isPreviewOnly: true });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);
    expect(tabBarStop(container)).toBeNull();
  });

  it('disappears the moment the run leaves the running state', () => {
    publishRun({ runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 2 } });
    const { container } = render(<WorkflowPanelContent workflowId="wf-1" />);
    expect(tabBarStop(container)).not.toBeNull();

    publishRun({ runInfo: { runId: 'run-1', status: 'COMPLETED', planVersion: 2 } });

    expect(tabBarStop(container)).toBeNull();
  });
});
