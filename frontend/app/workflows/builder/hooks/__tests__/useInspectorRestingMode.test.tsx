// @vitest-environment jsdom
/**
 * What a click on a node opens, once the user has said which they want.
 *
 * The rule is small and the way it can go wrong is not: applying the preference on every
 * OPEN looks equivalent and quietly breaks two other behaviours - a double-click, which
 * sets the full view in a handler and would be overwritten by an effect one render later,
 * and clicking straight from one node to the next, which must keep whatever the user
 * switched to in the meantime. Applying it to the RESTING mode instead (the value carried
 * while nothing is selected) is what makes all three agree, so that is what these pin.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, renderHook, screen } from '@testing-library/react';

import { useInspectorRestingMode } from '../useInspectorRestingMode';
import { InspectorOpenModeProvider, useInspectorOpenMode } from '@/contexts/InspectorOpenModeContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

function wrapper({ children }: { children: React.ReactNode }) {
  return <InspectorOpenModeProvider>{children}</InspectorOpenModeProvider>;
}

function setup(initial: { isRunMode?: boolean; hasSelection?: boolean } = {}) {
  const setIsAdvancedMode = vi.fn();
  const view = renderHook(
    (props: { isRunMode: boolean; hasSelection: boolean }) =>
      useInspectorRestingMode({ ...props, setIsAdvancedMode }),
    {
      wrapper,
      initialProps: {
        isRunMode: initial.isRunMode ?? false,
        hasSelection: initial.hasSelection ?? false,
      },
    },
  );
  return { ...view, setIsAdvancedMode };
}

beforeEach(() => {
  window.localStorage.clear();
  act(() => useCurrentOrgStore.getState().clear());
});

afterEach(() => cleanup());

describe('useInspectorRestingMode', () => {
  it('rests on the simple view by default, which is what a click already opened', () => {
    const { setIsAdvancedMode } = setup();

    expect(setIsAdvancedMode).toHaveBeenLastCalledWith(false);
  });

  it('rests on the full view once the preference says so', () => {
    window.localStorage.setItem('lc.workflow.inspectorOpenMode:personal', 'advanced');

    const { setIsAdvancedMode } = setup();

    expect(setIsAdvancedMode).toHaveBeenLastCalledWith(true);
  });

  it('does not touch the mode while a node is open', () => {
    // The half that protects a double-click and a mid-session toggle: with a selection on
    // screen this hook has no opinion at all.
    window.localStorage.setItem('lc.workflow.inspectorOpenMode:personal', 'advanced');
    const { setIsAdvancedMode } = setup({ hasSelection: true });

    expect(setIsAdvancedMode).not.toHaveBeenCalled();
  });

  it('re-states the resting value when the last node is deselected', () => {
    // The three places that clear the selection all reset the mode to something; this is
    // what puts the preference back without teaching it to each of them.
    window.localStorage.setItem('lc.workflow.inspectorOpenMode:personal', 'advanced');
    const { rerender, setIsAdvancedMode } = setup({ hasSelection: true });
    expect(setIsAdvancedMode).not.toHaveBeenCalled();

    rerender({ isRunMode: false, hasSelection: false });

    expect(setIsAdvancedMode).toHaveBeenLastCalledWith(true);
  });

  it('leaves run mode alone: a run opens its results, which is not a preference', () => {
    // Run mode forces the full view elsewhere. If this wrote here too, a stored "simple"
    // would fight that on every deselect inside a run.
    window.localStorage.setItem('lc.workflow.inspectorOpenMode:personal', 'simple');
    const { setIsAdvancedMode } = setup({ isRunMode: true });

    expect(setIsAdvancedMode).not.toHaveBeenCalled();
  });

  it('follows the preference the moment it changes under an idle canvas', () => {
    // The real path: the canvas settings panel calls setOpenMode on this same provider,
    // so the change arrives as React state, not as a storage re-read. Without the effect
    // re-running on it, the choice would only take hold on the next page load.
    const setIsAdvancedMode = vi.fn();
    function Harness() {
      const { setOpenMode } = useInspectorOpenMode();
      useInspectorRestingMode({ isRunMode: false, hasSelection: false, setIsAdvancedMode });
      return (
        <button type="button" onClick={() => setOpenMode('advanced')}>
          prefer-advanced
        </button>
      );
    }

    render(
      <InspectorOpenModeProvider>
        <Harness />
      </InspectorOpenModeProvider>,
    );
    expect(setIsAdvancedMode).toHaveBeenLastCalledWith(false);

    fireEvent.click(screen.getByText('prefer-advanced'));

    expect(setIsAdvancedMode).toHaveBeenLastCalledWith(true);
  });

  it('is inert outside the provider rather than crashing a canvas', () => {
    // Surfaces that reuse builder pieces without the provider (the marketplace preview, a
    // snapshot canvas) must still render; the safe hook gives them the default.
    const setIsAdvancedMode = vi.fn();
    renderHook(() =>
      useInspectorRestingMode({ isRunMode: false, hasSelection: false, setIsAdvancedMode }),
    );

    expect(setIsAdvancedMode).toHaveBeenLastCalledWith(false);
  });
});
