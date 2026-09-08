// @vitest-environment jsdom
/**
 * The right-click menu is a DYNAMIC superset of a node's bottom-bar buttons:
 * run actions appear only when the live run state allows them, contextual
 * side-panel buttons are reused as-is, and graph-edit operations show only in
 * edit mode. These tests pin that mode-aware item set.
 */
import React from 'react';
import { afterEach, describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';

let mockExec: {
  canExecute: boolean;
  canRerun: boolean;
  /** Folds terminality in: false on a FINISHED stepped run. */
  isStepByStepMode: boolean;
  /** The run's persisted mode, which is what the rerun label must read. */
  isSteppedRun: boolean;
  isRunning: boolean;
  pendingSignalCount: number;
  executeStep: ReturnType<typeof vi.fn>;
  rerunStep: ReturnType<typeof vi.fn>;
  resolveApproval: ReturnType<typeof vi.fn>;
};
let mockContextualButtons: Array<{ key: string; icon: React.ReactNode; title: string; onClick: (e: React.MouseEvent) => void }>;

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('../../contexts/StepByStepContext', () => ({ useNodeExecutionStatus: () => mockExec }));
// Settable, not a frozen {}: the run-action gates read flags.isTriggerNode /
// flags.isInterfaceNode, and a constant empty object makes those branches untestable - both
// could be deleted with the suite still green.
let mockFlags: Record<string, boolean>;
vi.mock('../../hooks/useNodeContextualButtons', () => ({
  deriveNodeContextFlags: () => mockFlags,
  useNodeContextualButtons: () => mockContextualButtons,
}));
vi.mock('../../nodes/nodeClasses', () => ({ findNodeClassById: () => null }));

import CanvasContextMenuDefault, { NodeContextMenu, PaneContextMenu, type NodeContextMenuActions, type PaneContextMenuActions } from '../CanvasContextMenu';

const testNode = { id: 'n1', type: 'flowNode', position: { x: 0, y: 0 }, data: { id: 'n1', label: 'My Node', kind: 'action' } } as Node<BuilderNodeData>;

const nodeActions = (): NodeContextMenuActions => ({
  openSettings: vi.fn(),
  duplicate: vi.fn(),
  copy: vi.fn(),
  selectDownstream: vi.fn(),
  disconnect: vi.fn(),
  addNote: vi.fn(),
  deleteNode: vi.fn(),
});

const paneActions = (): PaneContextMenuActions => ({
  addNode: vi.fn(),
  paste: vi.fn(),
  selectAll: vi.fn(),
  autoLayout: vi.fn(),
  fitView: vi.fn(),
});

beforeEach(() => {
  mockExec = {
    canExecute: false,
    canRerun: false,
    isStepByStepMode: true,
    isSteppedRun: true,
    isRunning: false,
    pendingSignalCount: 0,
    executeStep: vi.fn(),
    rerunStep: vi.fn(),
    resolveApproval: vi.fn(),
  };
  mockContextualButtons = [];
  mockFlags = {};
});

describe('NodeContextMenu - edit mode', () => {
  const renderEdit = (overrides: Partial<React.ComponentProps<typeof NodeContextMenu>> = {}) => {
    const actions = nodeActions();
    const onClose = vi.fn();
    render(
      <NodeContextMenu
        node={testNode}
        x={10}
        y={10}
        isRunMode={false}
        isPreviewOnly={false}
        hasDownstream
        hasConnections
        actions={actions}
        onClose={onClose}
        {...overrides}
      />,
    );
    return { actions, onClose };
  };

  it('shows the full edit operation set', () => {
    renderEdit();
    expect(screen.getByText('openSettings')).toBeTruthy();
    expect(screen.getByText('duplicate')).toBeTruthy();
    expect(screen.getByText('copy')).toBeTruthy();
    expect(screen.getByText('selectDownstream')).toBeTruthy();
    expect(screen.getByText('disconnectAll')).toBeTruthy();
    expect(screen.getByText('addNote')).toBeTruthy();
    expect(screen.getByText('delete')).toBeTruthy();
    // No run actions when the run state does not permit them.
    expect(screen.queryByText('executeStep')).toBeNull();
    expect(screen.queryByText('viewDetails')).toBeNull();
  });

  it('hides selectDownstream when there is no downstream and disconnect when unconnected', () => {
    renderEdit({ hasDownstream: false, hasConnections: false });
    expect(screen.queryByText('selectDownstream')).toBeNull();
    expect(screen.queryByText('disconnectAll')).toBeNull();
  });

  it('invokes the bound action and closes when an item is clicked', () => {
    const { actions, onClose } = renderEdit();
    fireEvent.click(screen.getByText('duplicate'));
    expect(actions.duplicate).toHaveBeenCalledWith('n1');
    expect(onClose).toHaveBeenCalled();
  });
});

describe('NodeContextMenu - run mode', () => {
  const renderRun = (overrides: Partial<React.ComponentProps<typeof NodeContextMenu>> = {}) => {
    const actions = nodeActions();
    const onClose = vi.fn();
    render(
      <NodeContextMenu
        node={testNode}
        x={10}
        y={10}
        isRunMode
        isPreviewOnly={false}
        hasDownstream
        hasConnections
        actions={actions}
        onClose={onClose}
        {...overrides}
      />,
    );
    return { actions, onClose };
  };

  it('hides edit operations and shows "view details" instead of "open settings"', () => {
    renderRun();
    expect(screen.getByText('viewDetails')).toBeTruthy();
    expect(screen.queryByText('openSettings')).toBeNull();
    expect(screen.queryByText('duplicate')).toBeNull();
    expect(screen.queryByText('delete')).toBeNull();
  });

  it('surfaces Run step when the node is executable, and wires it', () => {
    mockExec.canExecute = true;
    renderRun();
    const item = screen.getByText('executeStep');
    expect(item).toBeTruthy();
    fireEvent.click(item);
    expect(mockExec.executeStep).toHaveBeenCalled();
  });

  it('surfaces Re-run when the node can be re-run, stepping the run itself', () => {
    mockExec.canRerun = true;
    mockExec.isSteppedRun = true;
    renderRun();
    expect(screen.getByText('rerunStep')).toBeTruthy();
  });

  it('warns that the rest re-runs on its own when the run is automatic', () => {
    // Automatic mode does not stop at the reran node: the whole downstream chain replays
    // unattended, which can mean real (paid) calls. The label must not read the same as the
    // step-by-step one, where the user advances node by node.
    mockExec.canRerun = true;
    mockExec.isSteppedRun = false;
    renderRun();
    expect(screen.getByText('rerunStepAuto')).toBeTruthy();
    expect(screen.queryByText('rerunStep')).toBeNull();
  });

  it('keeps the step-by-step label on a FINISHED stepped run', () => {
    // isStepByStepMode folds terminality in and is false here, so keying the label off it
    // promised unattended re-execution on the one run where a rerun executes nothing.
    mockExec.canRerun = true;
    mockExec.isSteppedRun = true;
    mockExec.isStepByStepMode = false;
    renderRun();
    expect(screen.getByText('rerunStep')).toBeTruthy();
  });

  it('hides Re-run on a read-only preview surface', () => {
    // A rerun resets this node AND everything downstream; canRerun alone stopped being a
    // sufficient gate once it was no longer restricted to step-by-step mode.
    mockExec.canRerun = true;
    renderRun({ isPreviewOnly: true });
    expect(screen.queryByText('rerunStep')).toBeNull();
    expect(screen.queryByText('rerunStepAuto')).toBeNull();
  });

  it('hides Re-run outside run mode', () => {
    mockExec.canRerun = true;
    renderRun({ isRunMode: false });
    expect(screen.queryByText('rerunStep')).toBeNull();
    expect(screen.queryByText('rerunStepAuto')).toBeNull();
  });

  it('hides Re-run on a trigger: "restart from here" there is the whole DAG', () => {
    mockExec.canRerun = true;
    mockFlags = { isTriggerNode: true };
    renderRun();
    expect(screen.queryByText('rerunStep')).toBeNull();
    expect(screen.queryByText('rerunStepAuto')).toBeNull();
  });

  it('hides Re-run on an interface node', () => {
    mockExec.canRerun = true;
    mockFlags = { isInterfaceNode: true };
    renderRun();
    expect(screen.queryByText('rerunStep')).toBeNull();
    expect(screen.queryByText('rerunStepAuto')).toBeNull();
  });

  it('surfaces Approve/Reject when a signal is pending', () => {
    mockExec.pendingSignalCount = 1;
    renderRun();
    fireEvent.click(screen.getByText('approve'));
    expect(mockExec.resolveApproval).toHaveBeenCalledWith('APPROVED');
    fireEvent.click(screen.getByText('reject'));
    expect(mockExec.resolveApproval).toHaveBeenCalledWith('REJECTED');
  });
});

describe('NodeContextMenu - contextual side-panel buttons', () => {
  it('renders the reused contextual buttons and wires their handlers', () => {
    const onClick = vi.fn();
    mockContextualButtons = [{ key: 'agent-config', icon: <span />, title: 'Configuration', onClick }];
    const onClose = vi.fn();
    render(
      <NodeContextMenu
        node={testNode}
        x={10}
        y={10}
        isRunMode={false}
        isPreviewOnly={false}
        hasDownstream={false}
        hasConnections={false}
        actions={nodeActions()}
        onClose={onClose}
      />,
    );
    fireEvent.click(screen.getByText('Configuration'));
    expect(onClick).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });
});

describe('PaneContextMenu', () => {
  const renderPane = (props: Partial<React.ComponentProps<typeof PaneContextMenu>>) => {
    const actions = paneActions();
    const onClose = vi.fn();
    render(
      <PaneContextMenu
        x={10}
        y={10}
        editable
        canPaste
        hasNodes
        actions={actions}
        onClose={onClose}
        {...props}
      />,
    );
    return { actions, onClose };
  };

  it('shows every item when editable, with a non-empty clipboard and existing nodes', () => {
    renderPane({});
    ['addNode', 'paste', 'selectAll', 'autoLayout', 'fitView'].forEach((key) => {
      expect(screen.getByText(key)).toBeTruthy();
    });
  });

  it('hides mutating items in run/preview mode but keeps select all and fit view', () => {
    renderPane({ editable: false });
    expect(screen.queryByText('addNode')).toBeNull();
    expect(screen.queryByText('paste')).toBeNull();
    expect(screen.queryByText('autoLayout')).toBeNull();
    expect(screen.getByText('selectAll')).toBeTruthy();
    expect(screen.getByText('fitView')).toBeTruthy();
  });

  it('hides paste when the clipboard is empty', () => {
    renderPane({ canPaste: false });
    expect(screen.queryByText('paste')).toBeNull();
  });

  it('invokes the action and closes on click', () => {
    const { actions, onClose } = renderPane({});
    fireEvent.click(screen.getByText('addNode'));
    expect(actions.addNode).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });
});

describe('ContextMenuShell lifecycle (via PaneContextMenu)', () => {
  const renderShell = () => {
    const onClose = vi.fn();
    render(<PaneContextMenu x={10} y={10} editable canPaste hasNodes actions={paneActions()} onClose={onClose} />);
    return { onClose };
  };

  it('closes on an outside mousedown', () => {
    const { onClose } = renderShell();
    fireEvent.mouseDown(document.body);
    expect(onClose).toHaveBeenCalled();
  });

  it('does not close on a mousedown inside the menu', () => {
    const { onClose } = renderShell();
    fireEvent.mouseDown(screen.getByTestId('canvas-context-menu'));
    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes on Escape, wheel, and resize', () => {
    const escape = renderShell();
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(escape.onClose).toHaveBeenCalled();

    const wheel = renderShell();
    fireEvent.wheel(document.body);
    expect(wheel.onClose).toHaveBeenCalled();

    const resize = renderShell();
    fireEvent(window, new Event('resize'));
    expect(resize.onClose).toHaveBeenCalled();
  });
});

describe('default CanvasContextMenu wrapper (lazy dispatcher)', () => {
  it('renders the node menu for variant="node" without leaking the variant prop to the DOM', () => {
    render(
      <CanvasContextMenuDefault
        variant="node"
        node={testNode}
        x={10}
        y={10}
        isRunMode={false}
        isPreviewOnly={false}
        hasDownstream={false}
        hasConnections={false}
        actions={nodeActions()}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByText('openSettings')).toBeTruthy();
    expect(screen.getByTestId('canvas-context-menu').hasAttribute('variant')).toBe(false);
  });

  it('renders the pane menu for variant="pane"', () => {
    render(
      <CanvasContextMenuDefault
        variant="pane"
        x={10}
        y={10}
        editable
        canPaste
        hasNodes
        actions={paneActions()}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByText('addNode')).toBeTruthy();
    expect(screen.getByTestId('canvas-context-menu').hasAttribute('variant')).toBe(false);
  });
});

describe('the modifier key in the menu shortcuts', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('names the key through the messages rather than hardcoding Ctrl', () => {
    // `Ctrl` is a word printed on the key: a German keyboard prints `Strg`.
    // Under the key-echo translator mock a hardcoded literal would read "CtrlV".
    render(
      <PaneContextMenu x={0} y={0} editable canPaste hasNodes actions={paneActions()} onClose={() => {}} />,
    );

    expect(screen.getByText('ctrlKeyNameV')).toBeTruthy();
  });

  it('names it the same way in the node menu, which carries its own copy of the line', () => {
    render(
      <NodeContextMenu
        node={testNode}
        x={0}
        y={0}
        isRunMode={false}
        isPreviewOnly={false}
        hasDownstream
        hasConnections
        actions={nodeActions()}
        onClose={() => {}}
      />,
    );

    expect(screen.getByText('ctrlKeyNameD')).toBeTruthy();
  });

  it('uses the command glyph on a Mac', () => {
    // Only that the platform is followed. That it is READ safely (after
    // hydration, not during render) is what `lib/utils/__tests__/platform.test.ts`
    // pins with a real server render - this assertion cannot tell the two apart.
    vi.stubGlobal('navigator', { platform: 'MacIntel' });

    render(
      <PaneContextMenu x={0} y={0} editable canPaste hasNodes actions={paneActions()} onClose={() => {}} />,
    );

    expect(screen.getByText('⌘V')).toBeTruthy();
  });
});
