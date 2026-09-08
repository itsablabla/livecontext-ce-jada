// @vitest-environment jsdom
/**
 * The inspector placement is reachable from a workflow's own canvas settings, and
 * it is LINKED to the account preference rather than being a second, parallel
 * setting: both write the single value held by InspectorDockContext.
 *
 * These tests render the panel next to a probe consumer under ONE real provider,
 * so the linkage is proven end to end - the panel writes, the probe (standing in
 * for Settings > Preferences, which reads the same context) sees it, and the
 * per-workspace localStorage key is the one the account preference reads back.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('reactflow', () => ({
  Panel: ({ children }: any) => <div>{children}</div>,
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isPreviewOnly: mockPreviewOnly.value }),
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ value, onValueChange, children }: any) => (
    <select value={value ?? ''} onChange={(e) => onValueChange?.(e.target.value)}>
      {children}
    </select>
  ),
  SelectTrigger: ({ children, ...rest }: any) => <span {...rest}>{children}</span>,
  SelectValue: () => null,
  SelectContent: ({ children }: any) => <>{children}</>,
  SelectItem: ({ value, children }: any) => <option value={value}>{children}</option>,
}));
vi.mock('@/contexts/SidePanelContext', () => ({
  // The dock control is only offered where there is a panel to dock into. A truthy value
  // is all the panel asks for.
  useSidePanelSafe: () => mockSidePanel.value,
}));
vi.mock('../ConnectionTypeSelector', () => ({ ConnectionTypeSelector: () => null }));
vi.mock('../WorkflowPlanGenerator', () => ({ WorkflowPlanGenerator: () => null }));
vi.mock('../../services/LayoutService', () => ({
  applyDagreLayout: (n: unknown) => n,
  layoutConfigForDirection: () => ({}),
}));

const mockPreviewOnly = { value: false };
const mockSidePanel = { value: {} as unknown };

import { CanvasSettingsPanel } from '../CanvasSettingsPanel';
import { InspectorDockProvider, useInspectorDock } from '@/contexts/InspectorDockContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

/** Stands in for the Settings > Preferences select: same context, other surface. */
function AccountPreferenceProbe() {
  const { dock } = useInspectorDock();
  return <span data-testid="account-preference">{dock}</span>;
}

function renderPanel() {
  return render(
    <InspectorDockProvider>
      <AccountPreferenceProbe />
      <CanvasSettingsPanel
        isOpen
        onClose={vi.fn()}
        isRunMode={false}
        reactFlowConnectionType="bezier"
        nodes={[]}
        edges={[]}
      />
    </InspectorDockProvider>,
  );
}

/** The inspector select is the one offering the canvas / panel options. */
function inspectorSelect(): HTMLSelectElement {
  const select = screen.getByText('inspectorDockCanvas').closest('select');
  if (!select) throw new Error('inspector dock select not found');
  return select as HTMLSelectElement;
}

beforeEach(() => {
  window.localStorage.clear();
  act(() => useCurrentOrgStore.getState().clear());
  mockPreviewOnly.value = false;
  mockSidePanel.value = {};
});

afterEach(() => cleanup());

describe('CanvasSettingsPanel - inspector placement', () => {
  it('opens on the floating default', () => {
    renderPanel();

    expect(inspectorSelect().value).toBe('canvas');
    expect(screen.getByTestId('account-preference')).toHaveProperty('textContent', 'canvas');
  });

  it('writes the SAME preference the account settings read', () => {
    renderPanel();

    fireEvent.change(inspectorSelect(), { target: { value: 'panel' } });

    // Not a workflow-local copy: the one shared value moved.
    expect(screen.getByTestId('account-preference')).toHaveProperty('textContent', 'panel');
    expect(window.localStorage.getItem('lc.workflow.inspectorDock:personal')).toBe('panel');
  });

  it('shows the choice already made in the account settings', () => {
    window.localStorage.setItem('lc.workflow.inspectorDock:personal', 'panel');

    renderPanel();

    expect(inspectorSelect().value).toBe('panel');
  });

  it('scopes the choice to the active workspace', () => {
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    renderPanel();

    fireEvent.change(inspectorSelect(), { target: { value: 'panel' } });

    expect(window.localStorage.getItem('lc.workflow.inspectorDock:org-a')).toBe('panel');
    expect(window.localStorage.getItem('lc.workflow.inspectorDock:personal')).toBeNull();
  });

  it('hides the control in the read-only preview, which has no panel to dock into', () => {
    mockPreviewOnly.value = true;

    renderPanel();

    expect(screen.queryByText('inspectorDockCanvas')).toBeNull();
  });

  it('hides it on a surface with no side panel at all', () => {
    // The standalone /workflows builder. The canvas there falls back to the floating
    // inspector whatever the preference says, so offering the choice would state one the
    // surface cannot honour - which is not the same as the documented "request, not a
    // guarantee", where nobody is shown a control claiming otherwise.
    mockSidePanel.value = null;

    renderPanel();

    expect(screen.queryByText('inspectorDockCanvas')).toBeNull();
  });
});
