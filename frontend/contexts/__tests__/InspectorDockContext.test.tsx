/**
 * @vitest-environment jsdom
 *
 * InspectorDockContext holds where the workflow node inspector opens: floating
 * over the canvas (the default) or docked as a side-panel sub-tab. These tests
 * pin the 'canvas' default, localStorage persistence, PER-ORG isolation (a
 * choice in Org A never bleeds into Org B), re-hydration on workspace switch,
 * the rejection of a corrupt stored value, and the safe hook's fallback outside
 * the provider - the case that keeps the marketplace preview, any snapshot canvas, and the
 * standalone `/workflows` builder rendering instead of crashing. That last one mounts no
 * side panel, so it deliberately mounts no provider either and hides the control rather
 * than offering a placement it could never honour.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';

import {
  DEFAULT_INSPECTOR_DOCK,
  InspectorDockProvider,
  isInspectorDock,
  useInspectorDock,
  useInspectorDockSafe,
} from '../InspectorDockContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

function Consumer() {
  const { dock, setDock } = useInspectorDock();
  return (
    <div>
      <span data-testid="dock">{dock}</span>
      <button onClick={() => setDock('panel')}>to-panel</button>
      <button onClick={() => setDock('canvas')}>to-canvas</button>
    </div>
  );
}

function SafeConsumer() {
  const { dock, setDock } = useInspectorDockSafe();
  return (
    <div>
      <span data-testid="dock">{dock}</span>
      <button onClick={() => setDock('panel')}>to-panel</button>
    </div>
  );
}

const KEY = (org: string) => `lc.workflow.inspectorDock:${org}`;

beforeEach(() => {
  window.localStorage.clear();
  act(() => useCurrentOrgStore.getState().clear());
});

afterEach(() => cleanup());

describe('InspectorDockContext', () => {
  it('defaults to the floating canvas inspector when nothing is stored', () => {
    render(<InspectorDockProvider><Consumer /></InspectorDockProvider>);
    expect(screen.getByTestId('dock')).toHaveTextContent('canvas');
    expect(DEFAULT_INSPECTOR_DOCK).toBe('canvas');
  });

  it('persists the choice for the personal workspace', () => {
    render(<InspectorDockProvider><Consumer /></InspectorDockProvider>);
    act(() => { screen.getByText('to-panel').click(); });

    expect(screen.getByTestId('dock')).toHaveTextContent('panel');
    expect(window.localStorage.getItem(KEY('personal'))).toBe('panel');
  });

  it('restores the stored choice on mount', () => {
    window.localStorage.setItem(KEY('personal'), 'panel');
    render(<InspectorDockProvider><Consumer /></InspectorDockProvider>);
    expect(screen.getByTestId('dock')).toHaveTextContent('panel');
  });

  it('keeps each workspace on its own choice and re-hydrates on switch', () => {
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    render(<InspectorDockProvider><Consumer /></InspectorDockProvider>);
    act(() => { screen.getByText('to-panel').click(); });
    expect(window.localStorage.getItem(KEY('org-a'))).toBe('panel');

    // Org B never chose: it falls back to the default rather than inheriting A's.
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-b', 'MEMBER'));
    expect(screen.getByTestId('dock')).toHaveTextContent('canvas');
    expect(window.localStorage.getItem(KEY('org-b'))).toBeNull();

    // Coming back to A restores what A had chosen.
    act(() => useCurrentOrgStore.getState().setCurrentOrg('org-a', 'OWNER'));
    expect(screen.getByTestId('dock')).toHaveTextContent('panel');
  });

  it('ignores a corrupt stored value instead of rendering it', () => {
    window.localStorage.setItem(KEY('personal'), 'sidebar');
    render(<InspectorDockProvider><Consumer /></InspectorDockProvider>);
    expect(screen.getByTestId('dock')).toHaveTextContent('canvas');
    expect(isInspectorDock('sidebar')).toBe(false);
    expect(isInspectorDock('panel')).toBe(true);
    expect(isInspectorDock(null)).toBe(false);
  });

  it('falls back to the default outside the provider, and its setter is inert', () => {
    // The surfaces with no provider (the marketplace preview, a snapshot canvas)
    // must render, not throw - and must not be able to write a preference from
    // a place that could never honor it.
    render(<SafeConsumer />);
    expect(screen.getByTestId('dock')).toHaveTextContent('canvas');

    act(() => { screen.getByText('to-panel').click(); });
    expect(screen.getByTestId('dock')).toHaveTextContent('canvas');
    expect(window.localStorage.getItem(KEY('personal'))).toBeNull();
  });
});
