// @vitest-environment jsdom
/**
 * Launching a NEW epoch while a single epoch is focused.
 *
 * Reading one epoch is read-only by design: useNodeExecutionStatus gates every
 * control to the all-epochs view (and to the newest epoch), so the normal play
 * button under a trigger disappears the moment the user focuses an epoch. Without
 * something to put back, focusing an epoch would strand the run - there would be
 * no way to fire it again from the canvas.
 *
 * FlowNode puts back ONE button, `focus-trigger-play`, and this suite pins what it
 * owes:
 *   - it exists for EVERY fireable trigger, including the three payload triggers
 *     (chat / form / webhook) that cannot be fired blind - they used to be left
 *     out, so a focused epoch offered no play under those nodes at all (the
 *     run-mode toolbar's trigger menu stayed reachable, so this was a missing
 *     affordance rather than a dead end);
 *   - it stands down on the run's NEWEST epoch, which stays interactive and keeps
 *     the normal play - two Play icons on one node is the other way to get this
 *     wrong;
 *   - it always returns to the all-epochs view FIRST, so the epoch it launches is
 *     visible when it arrives (a payload trigger's run starts later, from the side
 *     panel, where nothing else would move the canvas back);
 *   - it then does exactly what that trigger's own play does: fire immediately, or
 *     open that trigger's side-panel tab - never both.
 *
 * It is gated on the same predicate as the normal play (the eight fireable trigger
 * types), not on `isTriggerNode`. That flag is kind-dependent - a workflows or
 * error trigger only satisfies it at kind 'entry' - so gating on it dropped the
 * button on nodes that ARE fireable, which is why two of the blind-fireable cases
 * below also fail on the pre-fix code.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

let mockExec: any;
let mockMode: any;

const selectAllEpochs = vi.fn();
const dispatchOpenTriggerTab = vi.fn();

const execStatus = (over: Record<string, any> = {}) => ({
  isStepByStepMode: false,
  // Gated by the focus view: false on a PAST epoch, true on the newest one.
  isReady: false,
  // Survives the focus gate, but not a terminal run - what the focus play reads.
  canExecuteRaw: true,
  canExecute: false,
  isExecuting: false,
  isRerunning: false,
  isRunning: false,
  isFailed: false,
  isSkipped: false,
  isCompleted: false,
  canRerun: false,
  stepId: 'trigger:my_trigger',
  executeStep: vi.fn(),
  rerunStep: vi.fn(),
  fireFromAnyEpoch: vi.fn(),
  ...over,
});

const bottomBarProps: any[] = [];
vi.mock('../NodeBottomBar', () => ({
  ShimmerOverlay: () => null,
  NodeBottomBar: (props: any) => {
    bottomBarProps.push(props);
    return <div data-testid="bottom-bar" />;
  },
}));

vi.mock('@/components/workflow/run-panel/useDefaultEpochSelection', () => ({
  selectAllEpochs: (...args: any[]) => selectAllEpochs(...args),
}));
vi.mock('@/components/workflow/run-panel/runPanelBus', () => ({
  boundRunId: (workflowId: string, runId: string) => `${workflowId}:${runId}`,
}));
vi.mock('@/lib/workflow/triggerTabEvent', () => ({
  dispatchOpenTriggerTab: (...args: any[]) => dispatchOpenTriggerTab(...args),
}));

vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => mockMode }));
vi.mock('../../../contexts/StepByStepContext', () => ({ useNodeExecutionStatus: () => mockExec }));
vi.mock('../../../contexts/ValidationContext', () => ({ useValidation: () => ({ hasNodeErrors: () => false }) }));
vi.mock('../../../nodes/nodeClasses', () => ({ findNodeClassById: () => undefined }));
vi.mock('../../NodeStatusBadge', () => ({ NodeStatusBadge: () => null }));
vi.mock('../shared', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../shared')>();
  return { ...actual, NodeHeader: () => null, NodeActionButtons: () => null };
});
vi.mock('reactflow', () => ({
  Handle: () => null,
  Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
  useNodes: () => [],
  useEdges: () => [],
  useReactFlow: () => ({ getNodes: () => [], getEdges: () => [] }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({ openTab: vi.fn(), updateTab: vi.fn(), setActiveTab: vi.fn(), open: vi.fn(), tabs: [] }),
}));
vi.mock('@/components/app/AgentPanelContent', () => ({
  AgentPanelContent: () => null,
  AGENT_CONVERSATION_TAB: 'conversation',
  AGENT_CONFIGURATION_TAB: 'configuration',
}));
vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));
vi.mock('@/lib/sidePanel/openFilesPanel', () => ({ openFilesPanel: vi.fn() }));
vi.mock('../../../hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined, isLoading: false }),
  useInterfaceRender: () => ({ data: undefined, isLoading: false }),
}));
vi.mock('../../../hooks/useRunOutputData', () => ({
  useRunOutputData: () => ({ totalItems: 0, currentIndex: 0, currentItem: undefined, goToIndex: vi.fn(), getObjectAtPath: vi.fn() }),
}));
vi.mock('@/components/agent-fleet/hooks/useAgentActivityStream', () => ({ useAgentActivity: () => null }));
vi.mock('@/contexts/WorkflowRunContext', () => ({ useRun: () => [undefined] }));
vi.mock('@tanstack/react-query', () => ({ useQueryClient: () => ({ invalidateQueries: vi.fn() }) }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { getTokenProvider: () => null, getAuthToken: async () => null } }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileRefToUrl: () => '',
  normalizeFileRef: (x: any) => x,
  findFileRefs: () => [],
  isFileRef: () => false,
  fileService: { formatFileSize: () => '' },
}));
vi.mock('../../interface/InterfaceThumbnail', () => ({ InterfaceThumbnail: () => null }));
vi.mock('../FleetTriggerButtons', () => ({ FleetTriggerButtons: () => null }));
vi.mock('../TriggerNodePinButton', () => ({ TriggerNodePinButton: () => null }));
vi.mock('../TriggerEditLaunchButton', () => ({ TriggerEditLaunchButton: () => null }));
vi.mock('../ResizableNodeWrapper', () => ({ ResizableNodeWrapper: () => null }));
vi.mock('../shared/BrowserLiveCdpPanel', () => ({ AgentBrowsePanelContent: () => null }));

import { FlowNode } from '../FlowNode';

beforeEach(() => {
  bottomBarProps.length = 0;
  selectAllEpochs.mockClear();
  dispatchOpenTriggerTab.mockClear();
  mockMode = {
    isRunMode: true,
    isPreviewOnly: false,
    isApplicationMode: false,
    runId: 'run-1',
    // A focused epoch: the state where the normal controls are gone.
    viewingEpoch: 2,
    setViewingEpoch: vi.fn(),
    workflowId: 'wf-1',
  };
  mockExec = execStatus();
});

/**
 * `data.id` is the node class id the trigger flags are derived from. The table
 * trigger additionally requires kind 'entry' (deriveNodeContextFlags), so the
 * kind is part of the fixture rather than a constant.
 */
const triggerNode = (id: string, kind = 'trigger') => ({ id, label: 'My trigger', kind, status: 'completed' });

const renderNode = (data: Record<string, unknown>) =>
  render(<FlowNode data={data as any} selected={false} id="rf-1" {...({} as any)} />);

const lastBar = () => bottomBarProps[bottomBarProps.length - 1];
const focusPlay = () => (lastBar()?.buttons ?? []).find((b: any) => b.key === 'focus-trigger-play');

/** Triggers that fire straight away - no payload to collect first. */
const BLIND_FIREABLE: Array<[string, string]> = [
  ['manual-trigger', 'trigger'],
  ['schedule-trigger', 'trigger'],
  ['workflows-trigger', 'trigger'],
  ['tables-trigger', 'entry'],
  ['error-trigger', 'trigger'],
];
/** Triggers that need input, so their play opens a side-panel tab instead. */
const PAYLOAD_TRIGGERS: Array<[string, string]> = [
  ['chat-trigger', 'chat'],
  ['form-trigger', 'form'],
  ['webhook-trigger', 'webhook'],
];

describe('FlowNode: launching a new epoch from a focused epoch', () => {
  it.each(BLIND_FIREABLE)('%s: fires this trigger against all epochs, after returning to the all-epochs view', (nodeId, kind) => {
    renderNode(triggerNode(nodeId, kind));

    const btn = focusPlay();
    expect(btn, `${nodeId} owes a focus-epoch play`).toBeTruthy();
    btn.onClick();

    // The canvas leaves the focused epoch FIRST, keyed on the run the canvas is
    // bound to - not the provider's, which a panel-mounted canvas does not have.
    expect(selectAllEpochs).toHaveBeenCalledWith('wf-1:run-1', mockMode.setViewingEpoch);
    // epoch=undefined, so the clicked trigger is the one that runs.
    expect(mockExec.fireFromAnyEpoch).toHaveBeenCalledTimes(1);
    // Nothing to collect: no tab is opened.
    expect(dispatchOpenTriggerTab).not.toHaveBeenCalled();
  });

  it.each(PAYLOAD_TRIGGERS)('%s: opens its own side-panel tab instead of firing blind', (nodeId, tab) => {
    renderNode(triggerNode(nodeId));

    const btn = focusPlay();
    expect(btn, `${nodeId} owes a focus-epoch play - it can still START a run`).toBeTruthy();
    btn.onClick();

    // Still returns to all epochs, and does so BEFORE the panel takes over: the
    // run starts later, from the panel, which cannot move the canvas back.
    expect(selectAllEpochs).toHaveBeenCalledWith('wf-1:run-1', mockMode.setViewingEpoch);
    // Named by step id: several triggers can share a type, and matching on type
    // alone opens the first one's tab.
    expect(dispatchOpenTriggerTab).toHaveBeenCalledWith({
      nodeId: 'rf-1',
      triggerId: 'trigger:my_trigger',
      triggerType: tab,
    });
    // A payload trigger must never be fired blind.
    expect(mockExec.fireFromAnyEpoch).not.toHaveBeenCalled();
  });

  it('is gone in the all-epochs view - the normal play owns that view', () => {
    mockMode.viewingEpoch = null;
    renderNode(triggerNode('manual-trigger'));
    expect(focusPlay()).toBeUndefined();
  });

  it('is gone on a trigger that cannot execute: it would promise a run the backend refuses', () => {
    // canExecuteRaw is false on a TERMINAL run too, where the steps stay in
    // readySteps - which is why the button reads executability, not readiness.
    mockExec = execStatus({ canExecuteRaw: false });
    renderNode(triggerNode('chat-trigger'));
    expect(focusPlay()).toBeUndefined();
  });

  it('never doubles the normal play on the run\'s newest epoch', () => {
    // The focus gate keeps the NEWEST epoch interactive, so the normal play is
    // still rendered there. Two Play icons side by side, with different tooltips
    // and different semantics, is the failure this guards.
    mockExec = execStatus({ isReady: true, canExecute: true });
    renderNode(triggerNode('manual-trigger'));

    expect(lastBar().playButton, 'the normal play owns the newest epoch').toBeTruthy();
    expect(focusPlay(), 'and the focus play must stand down').toBeUndefined();
  });

  it('shows without hover, like the play it stands in for - and lifts the rest of the bar with it', () => {
    // The bar reveals as one unit, so the flag is also what puts pin/unpin back on a focused
    // epoch. Without it the whole row waits for hover here while the all-epochs view shows its
    // play (and its pin) unprompted - the same node, two different rules.
    renderNode(triggerNode('manual-trigger'));
    expect(focusPlay().revealsBar).toBe(true);
  });

  it('leaves the contextual buttons unflagged: the reveal means "you can run this now"', () => {
    // Flagging anything else would make the bar permanent on every node carrying an agent or
    // files button, which is the cue spent on nothing.
    renderNode(triggerNode('manual-trigger'));
    for (const b of (lastBar().buttons ?? [])) {
      if (b.key !== 'focus-trigger-play') expect(b.revealsBar).toBeFalsy();
    }
  });

  it('names itself with the shared canvas string, not a hardcoded label', () => {
    // next-intl is stubbed to echo the key, so this pins the key rather than the
    // English text - the six locales already translate it.
    renderNode(triggerNode('manual-trigger'));
    expect(focusPlay().title).toBe('runWorkflow');
  });

  it('is gone on a read-only surface (published app, marketplace preview)', () => {
    mockMode.isPreviewOnly = true;
    renderNode(triggerNode('manual-trigger'));
    // The whole bottom bar is gated out there, so there is no button to find.
    expect(focusPlay()).toBeUndefined();
  });

  it('is gone in edit mode: there is no epoch to be focused on', () => {
    mockMode.isRunMode = false;
    renderNode(triggerNode('manual-trigger'));
    expect(focusPlay()).toBeUndefined();
  });

  it('is gone on a node that is not a fireable trigger', () => {
    // A generic entry node carries no fireable trigger type: offering a play
    // here would promise a run it cannot start.
    renderNode({ id: 'ai-agent', label: 'Agent', kind: 'agent', status: 'completed' });
    expect(focusPlay()).toBeUndefined();
  });
});
