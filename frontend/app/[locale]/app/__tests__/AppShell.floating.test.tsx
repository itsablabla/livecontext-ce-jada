/**
 * @vitest-environment jsdom
 *
 * Detaching the side panel must be a pure MODE FLIP, from every dock.
 *
 * The panel positions itself `fixed` when detached, so it looks the same wherever
 * AppShell mounts it - but WHERE it is mounted decides whether React keeps the
 * subtree or tears it down. The dock branches put different components at the same
 * child positions, so changing branch on the flip remounts the panel and everything
 * it holds: a running workflow canvas, an open SSE run stream, an interface iframe.
 *
 * These tests count mounts rather than inspect classes, because the class-level
 * arrangement is identical either way - only the identity of the subtree differs,
 * and that is exactly what the user loses.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

const mounts = vi.hoisted(() => ({ count: 0 }));

vi.mock('@/components/app/AppSidebar', () => ({ AppSidebar: () => <div data-testid="sidebar" /> }));
vi.mock('@/components/app/AppHeader', () => ({ AppHeader: () => <div data-testid="header" /> }));
vi.mock('@/components/app/SidePanel', () => ({
  SidePanel: () => {
    // A mount counter stands in for everything a keepMounted tab holds.
    React.useEffect(() => { mounts.count += 1; }, []);
    return <div data-testid="side-panel" />;
  },
}));
vi.mock('@/contexts/ConversationActivityContext', () => ({
  ConversationActivityProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
// The shell binds the app-wide quick-open shortcut. Stubbed to nothing because
// these are layout tests: the hook's behaviour is covered in
// lib/sidebar/__tests__/useQuickOpenShortcut.test.tsx, and that every
// arrangement binds it is asserted in AppShell.test.tsx.
vi.mock('@/lib/sidebar/useQuickOpenShortcut', () => ({
  useQuickOpenShortcut: () => {},
}));

import { AppShell } from '../AppShell';
import { SidePanelLayoutProvider, useSidePanelLayout, type SidePanelPosition } from '@/contexts/SidePanelLayoutContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

const POSITION_KEY = 'lc.sidePanel.position:personal';

beforeEach(() => {
  window.localStorage.clear();
  mounts.count = 0;
  act(() => useCurrentOrgStore.getState().clear());
});
afterEach(cleanup);

/** Drives the dock and the layout preferences from inside the provider. */
function Dock() {
  const { setPosition, setBottomMode } = useSidePanelLayout();
  return (
    <>
      <button type="button" onClick={() => setPosition('floating')}>detach</button>
      <button type="button" onClick={() => setPosition('bottom-full')}>attach-bottom-full</button>
      {/* What Settings > Preferences does to the bottom-panel style. */}
      <button type="button" onClick={() => setBottomMode('bottom')}>prefer-bottom</button>
    </>
  );
}

function renderShell(startAt: SidePanelPosition) {
  window.localStorage.setItem(POSITION_KEY, startAt);
  render(
    <SidePanelLayoutProvider>
      <Dock />
      <AppShell><div>page</div></AppShell>
    </SidePanelLayoutProvider>,
  );
}

const detach = () => act(() => { screen.getByText('detach').click(); });

describe('AppShell - detaching keeps the panel mounted', () => {
  // 'right' and 'bottom' already shared one branch before this change, so they are
  // regression guards rather than proof of the fix - only 'bottom-full' discriminates.
  it.each<SidePanelPosition>(['right', 'bottom', 'bottom-full'])(
    'detaching from the %s dock does not remount the panel',
    (dock) => {
      renderShell(dock);
      // Baselined rather than asserted at 1: the stored dock is restored in an
      // effect (localStorage cannot be read during render without a hydration
      // mismatch), so mounting on 'bottom-full' costs one pre-existing remount
      // that has nothing to do with detaching. The DETACH is what must be free.
      const baseline = mounts.count;

      detach();

      expect(screen.getByTestId('side-panel')).toBeTruthy();
      expect(mounts.count, `detaching from ${dock} remounted the panel`).toBe(baseline);
    },
  );

  it('keeps the full-width bottom arrangement while detached, so re-attaching does not remount either', () => {
    // 'bottom-full' is the DEFAULT bottom variant, so this is the common case, not
    // an exotic one: pinning the detached panel to the 'right' arrangement sent it
    // through a branch change on the way out AND on the way back.
    renderShell('bottom-full');
    const sidebar = screen.getByTestId('sidebar');
    const baseline = mounts.count;

    detach();

    // Still the column root: the panel is a direct child of it, not of the row
    // that holds the sidebar.
    expect(sidebar.closest('.flex.flex-1')!.contains(screen.getByTestId('side-panel'))).toBe(false);
    expect(mounts.count).toBe(baseline);

    // ...and the round trip, which is the half the title names.
    act(() => { screen.getByText('attach-bottom-full').click(); });

    expect(sidebar.closest('.flex.flex-1')!.contains(screen.getByTestId('side-panel'))).toBe(false);
    expect(mounts.count, 're-attaching remounted the panel').toBe(baseline);
  });

  it('survives an unrelated Settings preference change while it is detached', () => {
    // Regression: the bottom-variant preference used to rewrite the value this
    // shell branches on while the panel floats, and the two bottom variants mount
    // the panel on OPPOSITE sides of that branch - so ticking a radio in Settings
    // tore down a panel the user had merely detached, taking its running canvas,
    // its SSE stream and its interface iframes with it.
    renderShell('bottom-full');
    detach();
    const baseline = mounts.count;

    act(() => { screen.getByText('prefer-bottom').click(); });

    expect(mounts.count, 'a Settings preference change remounted the detached panel').toBe(baseline);
  });
});
