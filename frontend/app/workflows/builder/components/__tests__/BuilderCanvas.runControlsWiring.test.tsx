// @vitest-environment jsdom
/**
 * The canvas decides WHICH mode gets which toolbar affordance, and the toolbar /
 * box-selection hook only obey. That seam is easy to break silently, so these
 * tests pin the three values BuilderCanvas feeds them:
 *  - `allowBoxSelection` = !locked, so a persisted "selection" cursor mode can
 *    never reach run mode or the read-only preview (no control there to undo it),
 *  - `showRunControls` = run mode AND not preview-only,
 *  - the workflow id the pin button needs.
 *
 * Mock scaffolding mirrors BuilderCanvas.toolboxOverlay.test.tsx.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

let mockMode: { isRunMode: boolean; isPreviewOnly: boolean };
let mockPathname: string;
const toolbarProps: Array<Record<string, unknown>> = [];
const boxSelectionArgs: Array<Record<string, unknown>> = [];

vi.mock('next/navigation', () => ({ usePathname: () => mockPathname }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => mockMode }));
// `useOptionalTheme` is mocked alongside `useTheme`: the icons in this tree render
// through `useThemeSafely`, which reads the context OPTIONALLY so the same icons can
// render on the public marketplace outside any ThemeProvider. A mock missing it throws.
vi.mock('@/components/ThemeProvider', () => ({ useTheme: () => ({ theme: 'light' }),
  useOptionalTheme: () => ({ theme: 'light' }),
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div data-testid="loading-spinner" /> }));
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
vi.mock('../../registry/nodeRegistry', () => ({ nodeRegistry: new Proxy({}, { get: () => () => false }) }));
vi.mock('../../nodes/nodeClasses', () => ({ findNodeClassById: () => undefined }));
vi.mock('../nodes/shared', () => ({ NodeIcon: () => null, getIconSlug: () => '' }));
vi.mock('../HoverEdgeManager', () => ({ HoverEdgeManager: () => null }));
vi.mock('../CanvasSettingsPanel', () => ({ CanvasSettingsPanel: () => null }));
vi.mock('../EmptyCanvasChat', () => ({ EmptyCanvasChat: () => null }));
vi.mock('../CanvasToolbar', () => ({
  CanvasToolbar: (props: Record<string, unknown>) => {
    toolbarProps.push(props);
    return <div data-testid="canvas-toolbar" />;
  },
}));

vi.mock('../../hooks/useCanvasViewport', () => ({
  useCanvasViewport: () => ({
    isViewReady: true, handleInstanceInit: vi.fn(), handleZoomIn: vi.fn(), handleZoomOut: vi.fn(), handleFitView: vi.fn(),
  }),
}));
vi.mock('../../hooks/useInspectorDrag', () => ({
  useInspectorDrag: () => ({ position: { x: 16, y: 16 }, handleDragStart: vi.fn() }),
}));
vi.mock('../../hooks/useBoxSelection', () => ({
  useBoxSelection: (args: Record<string, unknown>) => {
    boxSelectionArgs.push(args);
    return {
      isBoxSelectionEnabled: false, isSelecting: false, selectionStart: null, selectionEnd: null,
      cursorMode: 'pan', setCursorMode: vi.fn(), handleSelectionChange: vi.fn(),
      containerRef: { current: null }, selectionJustEndedRef: { current: false },
    };
  },
}));
vi.mock('../../hooks/useTypingSuggestion', () => ({
  useTypingSuggestion: () => ({ typingSuggestionId: null, chatInput: '', handleSuggestionClick: vi.fn(), handleChatInputChange: vi.fn() }),
}));
vi.mock('../../constants/workflowSuggestions', () => ({ getDisplayedSuggestions: () => [] }));

import { BuilderCanvas } from '../BuilderCanvas';

beforeEach(() => {
  (globalThis as any).ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
  mockMode = { isRunMode: false, isPreviewOnly: false };
  mockPathname = '/app/workflow/wf-1';
  toolbarProps.length = 0;
  boxSelectionArgs.length = 0;
});

// The toolbar only mounts with at least one node on the canvas.
const baseProps = () => ({
  nodes: [{ id: 'trigger-1', position: { x: 0, y: 0 }, data: { id: 'manual-trigger', kind: 'entry', label: 'Start' } }] as any[],
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
});

const lastToolbar = () => toolbarProps[toolbarProps.length - 1];
const lastBoxSelection = () => boxSelectionArgs[boxSelectionArgs.length - 1];

describe('BuilderCanvas - run controls + cursor mode wiring', () => {
  it('lets the editor act on the persisted cursor mode', () => {
    render(<BuilderCanvas {...baseProps()} />);
    expect(lastBoxSelection().allowBoxSelection).toBe(true);
  });

  it('ignores the persisted cursor mode in run mode', () => {
    mockMode = { isRunMode: true, isPreviewOnly: false };
    render(<BuilderCanvas {...baseProps()} />);
    expect(lastBoxSelection().allowBoxSelection).toBe(false);
  });

  it('ignores the persisted cursor mode in the read-only preview', () => {
    // The preview shows no cursor select either, so a stored selection mode
    // would leave a visitor unable to pan.
    mockMode = { isRunMode: false, isPreviewOnly: true };
    render(<BuilderCanvas {...baseProps()} />);
    expect(lastBoxSelection().allowBoxSelection).toBe(false);
  });

  it('offers the run controls in run mode, with the workflow id the pin needs', () => {
    mockMode = { isRunMode: true, isPreviewOnly: false };
    render(<BuilderCanvas {...baseProps()} />);
    expect(lastToolbar().showRunControls).toBe(true);
    expect(lastToolbar().workflowId).toBe('wf-1');
  });

  it('withholds the run controls in edit mode', () => {
    render(<BuilderCanvas {...baseProps()} />);
    expect(lastToolbar().showRunControls).toBe(false);
  });

  it('withholds the run controls in the read-only preview even in run mode', () => {
    // A marketplace visitor can neither fire a trigger nor pin a production version.
    mockMode = { isRunMode: true, isPreviewOnly: true };
    render(<BuilderCanvas {...baseProps()} />);
    expect(lastToolbar().showRunControls).toBe(false);
  });

  it('hands the canvas nodes to the toolbar so it can list the triggers', () => {
    mockMode = { isRunMode: true, isPreviewOnly: false };
    const props = baseProps();
    render(<BuilderCanvas {...props} />);
    expect(lastToolbar().nodes).toBe(props.nodes);
  });
});
