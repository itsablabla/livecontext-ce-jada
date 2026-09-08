// @vitest-environment jsdom
/**
 * The canvas settings panel holds PREFERENCES, not workflow-local copies of them.
 *
 * Two things are covered here, and the first is a fix rather than a new feature.
 *
 * LAYOUT DIRECTION was the odd one out: its select called `setWorkflowDirection`, which is
 * memory-only by design (it exists for the loader, seeding a workflow's own stored
 * direction from its plan). So changing the reading direction from the workflow in front of
 * you left the account default untouched, and every other workflow still opened the other
 * way round. The control looked general and was not. It now writes the same value the
 * account preference does, exactly as the inspector placement beside it already did.
 *
 * INSPECTOR OPEN MODE is the new one: how much of a node a click opens, its settings alone
 * or the full view with input and output. Same contract, so it is proven the same way.
 *
 * Both are rendered next to a probe consumer under ONE real provider, so the linkage is
 * end to end: the panel writes, the probe (standing in for Settings > Preferences, which
 * reads the same context) sees it, and the per-workspace localStorage key is the one the
 * account preference reads back.
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
import {
  WorkflowLayoutDirectionProvider,
  useWorkflowLayoutDirection,
} from '@/contexts/WorkflowLayoutDirectionContext';
import {
  InspectorOpenModeProvider,
  useInspectorOpenMode,
} from '@/contexts/InspectorOpenModeContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

/**
 * Stands in for the Settings > Preferences selects: same contexts, other surface.
 *
 * Reads `defaultDirection`, exactly as that page does - the STORED default rather than the
 * direction the open workflow happens to render in. That distinction is load-bearing here:
 * a probe on `direction` cannot tell a persisted write from a memory-only one, because the
 * memory-only setter moves it too.
 */
function AccountPreferenceProbe() {
  const { defaultDirection } = useWorkflowLayoutDirection();
  const { openMode } = useInspectorOpenMode();
  return (
    <>
      <span data-testid="account-direction">{defaultDirection}</span>
      <span data-testid="account-open-mode">{openMode}</span>
    </>
  );
}

function renderPanel(props: Partial<React.ComponentProps<typeof CanvasSettingsPanel>> = {}) {
  return render(
    <WorkflowLayoutDirectionProvider>
      <InspectorOpenModeProvider>
        <AccountPreferenceProbe />
        <CanvasSettingsPanel
          isOpen
          onClose={vi.fn()}
          isRunMode={false}
          reactFlowConnectionType="bezier"
          nodes={[]}
          edges={[]}
          {...props}
        />
      </InspectorOpenModeProvider>
    </WorkflowLayoutDirectionProvider>,
  );
}

/** Each select is found by an option only it offers. */
function selectOffering(optionLabel: string): HTMLSelectElement {
  const select = screen.getByText(optionLabel).closest('select');
  if (!select) throw new Error(`no select offering ${optionLabel}`);
  return select as HTMLSelectElement;
}

const directionSelect = () => selectOffering('layoutVertical');
const openModeSelect = () => selectOffering('inspectorOpenModeAdvanced');

beforeEach(() => {
  window.localStorage.clear();
  act(() => useCurrentOrgStore.getState().clear());
  mockPreviewOnly.value = false;
  mockSidePanel.value = {};
});

afterEach(() => cleanup());

describe('CanvasSettingsPanel - layout direction is a preference', () => {
  it('writes the account default, not just this canvas', () => {
    // The bug, stated: before this the value moved on screen and nowhere else, so the
    // next workflow opened in the old direction and the setting had to be made again.
    renderPanel();

    fireEvent.change(directionSelect(), { target: { value: 'vertical' } });

    expect(window.localStorage.getItem('lc.workflow.layoutDirection:personal')).toBe('vertical');
    expect(screen.getByTestId('account-direction')).toHaveProperty('textContent', 'vertical');
  });

  it('moves the ACTIVE direction too, which is what the canvas renders', () => {
    // The shared probe reads `defaultDirection`, so on its own it would let a change that
    // persisted without re-orienting the canvas pass. Both layers move from this control:
    // that is the whole claim.
    function ActiveProbe() {
      const { direction } = useWorkflowLayoutDirection();
      return <span data-testid="active-direction">{direction}</span>;
    }

    render(
      <WorkflowLayoutDirectionProvider>
        <InspectorOpenModeProvider>
          <ActiveProbe />
          <CanvasSettingsPanel
            isOpen
            onClose={vi.fn()}
            isRunMode={false}
            reactFlowConnectionType="bezier"
            nodes={[]}
            edges={[]}
          />
        </InspectorOpenModeProvider>
      </WorkflowLayoutDirectionProvider>,
    );

    fireEvent.change(directionSelect(), { target: { value: 'vertical' } });

    expect(screen.getByTestId('active-direction')).toHaveProperty('textContent', 'vertical');
  });

  it('still re-flows the graph, which is the one place a direction change moves nodes', () => {
    // The persistence must not come at the cost of the thing the control is FOR: the
    // loader deliberately does not re-flow (it would trash saved positions), so if this
    // call site stopped doing it, nothing would.
    const onForceNodesUpdate = vi.fn();
    renderPanel({ nodes: [{ id: 'a', position: { x: 0, y: 0 }, data: {} }] as any, onForceNodesUpdate });

    fireEvent.change(directionSelect(), { target: { value: 'vertical' } });

    expect(onForceNodesUpdate).toHaveBeenCalledTimes(1);
  });

  it('does nothing when the direction does not change', () => {
    // Re-selecting the value already shown must not re-flow a graph the user has
    // hand-placed. It also does not persist, and that is a KNOWN LIMIT rather than a
    // choice: the real control is a controlled Radix Select, which fires `onValueChange`
    // only on an actual change, so this handler is never even reached on a re-pick. This
    // suite's `<select>` double DOES fire, which is exactly why a "persist on every pick"
    // workaround tests green here and ships dead - so the assertion is written to the
    // behaviour the product has.
    const onForceNodesUpdate = vi.fn();
    renderPanel({ nodes: [{ id: 'a', position: { x: 0, y: 0 }, data: {} }] as any, onForceNodesUpdate });

    fireEvent.change(directionSelect(), { target: { value: 'horizontal' } });

    expect(onForceNodesUpdate, 'a graph moved for a direction that did not change')
      .not.toHaveBeenCalled();
    expect(window.localStorage.getItem('lc.workflow.layoutDirection:personal')).toBeNull();
  });

  it('leaves the account DEFAULT alone when a workflow seeds its own direction', () => {
    // The other half of "one value, two surfaces", and the half that was wrong: the
    // Settings select used to read the ACTIVE direction, so opening a workflow whose plan
    // stamps vertical made that page report vertical as the user's default - a claim
    // nobody made, on a control that (being a controlled select) could not then be used to
    // re-pick the value it was showing.
    // A STORED default, not the fallback constant: otherwise this proves only that the
    // default survives, which it would even if `defaultDirection` were hardcoded.
    window.localStorage.setItem('lc.workflow.layoutDirection:personal', 'vertical');

    // Seeded from a handler, not a mount effect. The real loader seeds after FETCHING the
    // workflow, so the provider's own mount effect (which reads storage) has long since
    // run; a child mount effect would fire BEFORE it and be overwritten, testing an
    // ordering production never has.
    function PlanSeeder() {
      const { setWorkflowDirection } = useWorkflowLayoutDirection();
      return (
        <button type="button" onClick={() => setWorkflowDirection('horizontal')}>
          seed-from-plan
        </button>
      );
    }

    function DirectionProbe() {
      const { direction, defaultDirection } = useWorkflowLayoutDirection();
      return (
        <>
          <span data-testid="active">{direction}</span>
          <span data-testid="stored-default">{defaultDirection}</span>
        </>
      );
    }

    render(
      <WorkflowLayoutDirectionProvider>
        <PlanSeeder />
        <DirectionProbe />
      </WorkflowLayoutDirectionProvider>,
    );

    fireEvent.click(screen.getByText('seed-from-plan'));

    // The canvas follows the workflow...
    expect(screen.getByTestId('active')).toHaveProperty('textContent', 'horizontal');
    // ...and Settings keeps describing the user's own stored default.
    expect(screen.getByTestId('stored-default'), 'a workflow restated the account default')
      .toHaveProperty('textContent', 'vertical');
    expect(window.localStorage.getItem('lc.workflow.layoutDirection:personal')).toBe('vertical');
  });

  it('shows the direction already chosen in the account settings', () => {
    window.localStorage.setItem('lc.workflow.layoutDirection:personal', 'vertical');

    renderPanel();

    expect(directionSelect().value).toBe('vertical');
  });

  it('scopes the choice to the active workspace', () => {
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    renderPanel();

    fireEvent.change(directionSelect(), { target: { value: 'vertical' } });

    expect(window.localStorage.getItem('lc.workflow.layoutDirection:org-a')).toBe('vertical');
    expect(window.localStorage.getItem('lc.workflow.layoutDirection:personal')).toBeNull();
  });
});

describe('CanvasSettingsPanel - what a node click opens', () => {
  it('opens on the simple default', () => {
    renderPanel();

    expect(openModeSelect().value).toBe('simple');
    expect(screen.getByTestId('account-open-mode')).toHaveProperty('textContent', 'simple');
  });

  it('writes the SAME preference the account settings read', () => {
    renderPanel();

    fireEvent.change(openModeSelect(), { target: { value: 'advanced' } });

    expect(screen.getByTestId('account-open-mode')).toHaveProperty('textContent', 'advanced');
    expect(window.localStorage.getItem('lc.workflow.inspectorOpenMode:personal')).toBe('advanced');
  });

  it('shows the choice already made in the account settings', () => {
    window.localStorage.setItem('lc.workflow.inspectorOpenMode:personal', 'advanced');

    renderPanel();

    expect(openModeSelect().value).toBe('advanced');
  });

  it('scopes the choice to the active workspace', () => {
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    renderPanel();

    fireEvent.change(openModeSelect(), { target: { value: 'advanced' } });

    expect(window.localStorage.getItem('lc.workflow.inspectorOpenMode:org-a')).toBe('advanced');
    expect(window.localStorage.getItem('lc.workflow.inspectorOpenMode:personal')).toBeNull();
  });

  it('is hidden in the read-only preview, whose nodes are not configurable', () => {
    mockPreviewOnly.value = true;

    renderPanel();

    expect(screen.queryByText('inspectorOpenModeAdvanced')).toBeNull();
  });
});
