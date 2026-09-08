// @vitest-environment jsdom
/**
 * Where BuilderCanvas renders the node inspector.
 *
 * Default ('canvas'): the historical floating window, portalled to document.body.
 * Preference 'panel': portalled into the slot the side panel offers, with
 * `dockMode="panel"` so the inspector fills it instead of sizing itself.
 *
 * The gates matter as much as the happy path. Docking is only honored where a
 * side panel exists AND this canvas owns the page: an embedded canvas (a
 * sub-workflow tab, an application view) and a provider-less surface (the
 * standalone builder, the marketplace preview) keep the floating window, because
 * a preference must never leave the inspector with nowhere to open.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { cleanup, render } from '@testing-library/react';

let mockMode: { isRunMode: boolean; isPreviewOnly: boolean };
let mockPathname: string;
let mockSidePanelValue: { isOpen: boolean; isForward: boolean } | null;
let mockDock: 'canvas' | 'panel';

vi.mock('next/navigation', () => ({ usePathname: () => mockPathname }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => mockMode }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => mockSidePanelValue }));
vi.mock('@/contexts/InspectorDockContext', () => ({
  useInspectorDockSafe: () => ({ dock: mockDock, setDock: vi.fn() }),
}));
vi.mock('@/components/ThemeProvider', () => ({ useTheme: () => ({ theme: 'light' }) }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));
vi.mock('@/components/chat/SimpleToast', () => ({
  SimpleToast: () => null,
  useSimpleToast: () => ({ toast: null, showToast: vi.fn(), hideToast: vi.fn() }),
}));

vi.mock('reactflow', () => ({
  default: ({ children }: { children?: React.ReactNode }) => <div data-testid="react-flow">{children}</div>,
  Background: () => null,
  BackgroundVariant: { Dots: 'dots', Lines: 'lines', Cross: 'cross' },
  Panel: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
  ReactFlowProvider: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  ConnectionMode: { Loose: 'loose', Strict: 'strict' },
  getBezierPath: () => ['', 0, 0, 0, 0],
  getSmoothStepPath: () => ['', 0, 0, 0, 0],
  useUpdateNodeInternals: () => () => {},
}));

vi.mock('../../constants/graphTypes', () => ({ nodeTypes: {}, edgeTypes: {} }));
vi.mock('../../contexts/ValidationContext', () => ({ useValidationOptional: () => null }));
vi.mock('../../utils/workflowPlanGenerator', () => ({ generateWorkflowPlan: vi.fn() }));
vi.mock('../../utils/connectionValidator', () => ({ validateConnection: () => true }));
vi.mock('../../services/LayoutService', () => ({ applyDagreLayout: (n: unknown) => n }));
vi.mock('../../services/nodeMatcher', () => ({ nodeMatchesStep: () => false }));
vi.mock('../../registry/nodeRegistry', () => ({
  nodeRegistry: new Proxy({}, { get: () => () => false }),
}));
vi.mock('../../nodes/nodeClasses', () => ({ findNodeClassById: () => undefined }));
vi.mock('../nodes/shared', () => ({ NodeIcon: () => null, getIconSlug: () => '' }));
vi.mock('../HoverEdgeManager', () => ({ HoverEdgeManager: () => null }));
vi.mock('../CanvasToolbar', () => ({ CanvasToolbar: () => null }));
vi.mock('../CanvasSettingsPanel', () => ({ CanvasSettingsPanel: () => null }));
vi.mock('../EmptyCanvasChat', () => ({ EmptyCanvasChat: () => null }));
vi.mock('../../hooks/useCanvasViewport', () => ({
  useCanvasViewport: () => ({
    isViewReady: true,
    handleInstanceInit: vi.fn(),
    handleZoomIn: vi.fn(),
    handleZoomOut: vi.fn(),
    handleFitView: vi.fn(),
  }),
}));
vi.mock('../../hooks/useInspectorDrag', () => ({
  useInspectorDrag: () => ({ position: { x: 16, y: 16 }, handleDragStart: vi.fn() }),
}));
vi.mock('../../hooks/useBoxSelection', () => ({
  useBoxSelection: () => ({
    isBoxSelectionEnabled: false,
    isSelecting: false,
    selectionStart: null,
    selectionEnd: null,
    cursorMode: 'pan' as const,
    setCursorMode: vi.fn(),
    handleSelectionChange: vi.fn(),
    containerRef: { current: null },
    selectionJustEndedRef: { current: false },
  }),
}));
vi.mock('../../hooks/useTypingSuggestion', () => ({
  useTypingSuggestion: () => ({
    typingSuggestionId: null,
    chatInput: '',
    handleSuggestionClick: vi.fn(),
    handleChatInputChange: vi.fn(),
  }),
}));
vi.mock('../../constants/workflowSuggestions', () => ({ getDisplayedSuggestions: () => [] }));

import { BuilderCanvas } from '../BuilderCanvas';
import {
  OPEN_INSPECTOR_PANEL_EVENT,
  getInspectorDockState,
  publishInspectorDockState,
  setInspectorDockHost,
  type InspectorDockSurface,
} from '@/lib/workflow/inspectorDockBus';

/** Stand-in for the real inspector: records the props the canvas clones onto it. */
const inspectorProps: { last: Record<string, unknown> | null } = { last: null };
function InspectorStub(props: Record<string, unknown>) {
  inspectorProps.last = props;
  return <div data-testid="inspector" />;
}

let host: HTMLDivElement | null = null;

function offerHost(surface: InspectorDockSurface = 'page') {
  host = document.createElement('div');
  document.body.appendChild(host);
  setInspectorDockHost('wf-1', surface, host);
  return host;
}

beforeEach(() => {
  (globalThis as any).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  mockMode = { isRunMode: false, isPreviewOnly: false };
  mockPathname = '/app/workflow/wf-1';
  mockSidePanelValue = { isOpen: true, isForward: true };
  mockDock = 'canvas';
  inspectorProps.last = null;
  resetDock();
});

afterEach(() => {
  cleanup();
  resetDock();
  host?.remove();
  host = null;
});

function resetDock() {
  for (const surface of ['page', 'embedded'] as const) {
    setInspectorDockHost('wf-1', surface, null);
    publishInspectorDockState({ workflowId: 'wf-1', surface, docked: false });
  }
}

const baseProps = () => ({
  nodes: [{ id: 'n1', position: { x: 0, y: 0 }, data: { label: 'Send Email' }, selected: true }] as any[],
  edges: [] as any[],
  onNodesChange: vi.fn(),
  onEdgesChange: vi.fn(),
  onConnect: vi.fn(),
  onCreateNode: vi.fn(),
  onSelectionChange: vi.fn(),
  hoveredEdgeId: null,
  onHoverEdge: vi.fn(),
  onDeleteEdge: vi.fn(),
  workflowId: 'wf-1',
  hasSelectedNodes: true,
  inspectorPanel: <InspectorStub />,
});

describe('BuilderCanvas - inspector dock', () => {
  it('keeps the inspector floating on the canvas by default', () => {
    const slot = offerHost();

    render(<BuilderCanvas {...baseProps()} />);

    expect(document.querySelectorAll('[data-testid="inspector"]')).toHaveLength(1);
    // Not in the panel's slot, and not asked to fill anything.
    expect(slot.querySelector('[data-testid="inspector"]')).toBeNull();
    expect(inspectorProps.last?.dockMode).toBeUndefined();
    // A floating inspector is draggable; a docked one is not.
    expect(typeof inspectorProps.last?.onDragHandleMouseDown).toBe('function');
  });

  it('renders the inspector INSIDE the panel slot when the preference says panel', () => {
    mockDock = 'panel';
    const slot = offerHost();

    render(<BuilderCanvas {...baseProps()} />);

    expect(slot.querySelector('[data-testid="inspector"]')).not.toBeNull();
    expect(document.querySelectorAll('[data-testid="inspector"]')).toHaveLength(1);
    expect(inspectorProps.last?.dockMode).toBe('panel');
    // No drag handle: the panel owns the geometry.
    expect(inspectorProps.last?.onDragHandleMouseDown).toBeUndefined();
  });

  it('renders NOTHING while the dock is wanted but no slot exists yet', () => {
    // The panel is still opening. Falling back to the floating window here would
    // flash it on screen for a frame before it jumps into the panel.
    mockDock = 'panel';

    render(<BuilderCanvas {...baseProps()} />);

    expect(document.querySelectorAll('[data-testid="inspector"]')).toHaveLength(0);
  });

  it('publishes the dock request and asks the page to open the panel', () => {
    mockDock = 'panel';
    const opened: string[] = [];
    const handler = (e: Event) => opened.push((e as CustomEvent).detail?.workflowId);
    window.addEventListener(OPEN_INSPECTOR_PANEL_EVENT, handler);

    render(<BuilderCanvas {...baseProps()} />);

    const state = getInspectorDockState('wf-1', 'page');
    expect(state.docked).toBe(true);
    // Captioned with the selected node so the panel tab says which node it is.
    expect(state.label).toBe('Send Email');
    expect(opened).toContain('wf-1');
    window.removeEventListener(OPEN_INSPECTOR_PANEL_EVENT, handler);
  });

  it('withdraws the dock request when the canvas unmounts', () => {
    // Otherwise the panel keeps an Inspector tab alive for a canvas that is gone.
    mockDock = 'panel';
    offerHost();
    const { unmount } = render(<BuilderCanvas {...baseProps()} />);
    expect(getInspectorDockState('wf-1', 'page').docked).toBe(true);

    unmount();

    expect(getInspectorDockState('wf-1', 'page').docked).toBe(false);
  });

  it('asks for no dock at all while nothing is selected', () => {
    mockDock = 'panel';
    offerHost();

    render(<BuilderCanvas {...baseProps()} hasSelectedNodes={false} />);

    expect(getInspectorDockState('wf-1', 'page').docked).toBe(false);
  });

  it('docks an EMBEDDED canvas into its OWN panel slot', () => {
    // The Application panel: the canvas is a sub-tab of the panel, and the
    // inspector becomes its sibling in the same panel.
    mockDock = 'panel';
    mockPathname = '/app/applications/pub-1';
    const embeddedSlot = offerHost('embedded');

    render(<BuilderCanvas {...baseProps()} />);

    expect(embeddedSlot.querySelector('[data-testid="inspector"]')).not.toBeNull();
    expect(inspectorProps.last?.dockMode).toBe('panel');
  });

  it('does not let an embedded canvas take the PAGE slot, or overwrite its state', () => {
    // The same workflow can be open on the page AND inside a panel tab at once (a
    // self-referencing sub-workflow node). Sharing one key would put this canvas
    // into the other one's slot and clear the tab it just asked for.
    mockDock = 'panel';
    mockPathname = '/app/c/conv-1';
    const pageSlot = offerHost('page');
    publishInspectorDockState({ workflowId: 'wf-1', surface: 'page', docked: true, label: 'Other Node' });

    render(<BuilderCanvas {...baseProps()} />);

    expect(pageSlot.querySelector('[data-testid="inspector"]')).toBeNull();
    const state = getInspectorDockState('wf-1', 'page');
    expect(state.docked).toBe(true);
    expect(state.label).toBe('Other Node');
    // It published under its own surface instead.
    expect(getInspectorDockState('wf-1', 'embedded').docked).toBe(true);
  });

  it('keeps the inspector floating with no SidePanelProvider around', () => {
    // The standalone builder route: honoring the preference here would leave the
    // inspector nowhere to open at all.
    mockDock = 'panel';
    mockSidePanelValue = null;
    const slot = offerHost();

    render(<BuilderCanvas {...baseProps()} />);

    expect(slot.querySelector('[data-testid="inspector"]')).toBeNull();
    expect(document.querySelectorAll('[data-testid="inspector"]')).toHaveLength(1);
    expect(inspectorProps.last?.dockMode).toBeUndefined();
  });

  it('keeps the read-only preview floating', () => {
    mockDock = 'panel';
    mockMode = { isRunMode: false, isPreviewOnly: true };
    const slot = offerHost();

    render(<BuilderCanvas {...baseProps()} />);

    expect(slot.querySelector('[data-testid="inspector"]')).toBeNull();
    expect(document.querySelectorAll('[data-testid="inspector"]')).toHaveLength(1);
  });
});
