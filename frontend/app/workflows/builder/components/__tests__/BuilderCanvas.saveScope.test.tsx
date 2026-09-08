// @vitest-environment jsdom
/**
 * A Save addressed to one workflow must not save another one.
 *
 * `workflowViewSave` is a window event and this handler ignored who it was
 * addressed to. That was unambiguous while one canvas existed per page; the
 * right side panel now mounts its own (a sub-workflow tab, an application tab),
 * so pressing Save in the page header persisted the panel's workflow too - and
 * adding a Save button to that panel would have made it mutual.
 *
 * The rule is permissive on purpose: an event that names NO workflow still
 * reaches every canvas, which is what keeps the in-canvas call sites and the e2e
 * fixtures working.
 *
 * Mock scaffolding mirrors BuilderCanvas.saveLayoutDirection.test.tsx.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act } from '@testing-library/react';

let mockMode: { isRunMode: boolean; isPreviewOnly: boolean };
let mockPathname: string;
let mockDirection: 'horizontal' | 'vertical';

vi.mock('next/navigation', () => ({ usePathname: () => mockPathname }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => mockMode }));
vi.mock('@/contexts/WorkflowLayoutDirectionContext', () => ({
  useWorkflowLayoutDirectionSafe: () => ({
    direction: mockDirection,
    setDirection: vi.fn(),
    setWorkflowDirection: vi.fn(),
  }),
}));
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
const generateWorkflowPlan = vi.fn((..._args: unknown[]) => ({ mocked: 'plan' }));
vi.mock('../../utils/workflowPlanGenerator', () => ({
  generateWorkflowPlan: (...args: unknown[]) => generateWorkflowPlan(...args),
}));
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
vi.mock('../EmptyCanvasChat', () => ({ EmptyCanvasChat: () => <div data-testid="empty-canvas-chat" /> }));

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

beforeEach(() => {
  (globalThis as any).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  mockMode = { isRunMode: false, isPreviewOnly: false };
  mockPathname = '/app/workflow/wf-1';
  mockDirection = 'horizontal';
  generateWorkflowPlan.mockClear();
});

const baseProps = (onSaveWorkflow: any) => ({
  nodes: [{ id: 'n1', position: { x: 0, y: 0 }, data: {} }] as any[],
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
  onSaveWorkflow,
});

async function fireSaveFor(detail: { workflowId: string } | undefined) {
  await act(async () => {
    window.dispatchEvent(new CustomEvent('workflowViewSave', { detail }));
    // let the async handler settle
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('BuilderCanvas - the Save event is scoped to its workflow', () => {
  it('saves when the event names THIS workflow', async () => {
    const onSaveWorkflow = vi.fn().mockResolvedValue(undefined);
    render(<BuilderCanvas {...baseProps(onSaveWorkflow)} />);

    await fireSaveFor({ workflowId: 'wf-1' });

    expect(onSaveWorkflow).toHaveBeenCalledWith('wf-1', { mocked: 'plan' });
  });

  it('ignores a Save addressed to another workflow', async () => {
    const onSaveWorkflow = vi.fn().mockResolvedValue(undefined);
    render(<BuilderCanvas {...baseProps(onSaveWorkflow)} />);

    // The page header saving ITS workflow, while this canvas sits in the panel.
    await fireSaveFor({ workflowId: 'wf-other' });

    expect(onSaveWorkflow).not.toHaveBeenCalled();
    expect(generateWorkflowPlan).not.toHaveBeenCalled();
  });

  it('still saves on an event that names no workflow at all', async () => {
    const onSaveWorkflow = vi.fn().mockResolvedValue(undefined);
    render(<BuilderCanvas {...baseProps(onSaveWorkflow)} />);

    await fireSaveFor(undefined);

    expect(onSaveWorkflow).toHaveBeenCalledWith('wf-1', { mocked: 'plan' });
  });

  // Not a regression case: this canvas already named the workflow on completion
  // before the change. It is here because a second Save control now listens, and
  // silently dropping the id would make that one answer to the wrong canvas.
  it('reports completion under the workflow it saved, so only its own controls react', async () => {
    const onSaveWorkflow = vi.fn().mockResolvedValue(undefined);
    render(<BuilderCanvas {...baseProps(onSaveWorkflow)} />);
    const completions: CustomEvent[] = [];
    window.addEventListener('workflowViewSaveComplete', (e) => completions.push(e as CustomEvent));

    await fireSaveFor({ workflowId: 'wf-1' });

    expect(completions).toHaveLength(1);
    expect(completions[0].detail).toMatchObject({ success: true, workflowId: 'wf-1' });
  });
});
