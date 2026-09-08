'use client';

import * as React from 'react';
import { useInspectorOpenModeSafe } from '@/contexts/InspectorOpenModeContext';

interface UseInspectorRestingModeProps {
  /** A run always opens its results in the full view, whatever the preference says. */
  isRunMode: boolean;
  /** Whether a node is currently selected, i.e. whether an inspector is open. */
  hasSelection: boolean;
  setIsAdvancedMode: (advanced: boolean) => void;
}

/**
 * Keeps the inspector's RESTING mode in step with the user's preference, so a node click
 * opens what they asked for.
 *
 * <p>The preference is applied to the mode the builder carries while NO node is selected,
 * and never on selection itself. That single choice is what lets three behaviours coexist
 * instead of fighting:
 * <ul>
 *   <li>opening a node lands on the preference, because the resting value is what the
 *       inspector starts from;</li>
 *   <li>clicking straight from one node to the next keeps whatever the user switched to in
 *       the meantime, because no reset happens in between;</li>
 *   <li>a double-click still forces the full view. An effect that ran on every open would
 *       have overwritten that one render later, since effects land after the handler.</li>
 * </ul>
 *
 * <p>It re-states the resting value rather than teaching the preference to each of the
 * three places that clear the selection (useSelection's two, the builder's
 * handleDeselectAll), which all reset the mode already.
 *
 * <p>It is also how the preference reaches the canvas at all: the stored value is restored
 * after mount, so it cannot be read in the useState initializer that seeds the mode.
 *
 * <p>Run mode is excluded deliberately. Opening a run is opening its results, so the full
 * view is not a preference there, it is the point.
 *
 * <p>Returns nothing on purpose. It briefly returned the resolved boolean so a caller could
 * pre-empt the effect by a frame, and there turned out to be no such caller: the paths that
 * clear the selection all set a plain false and are corrected here on the next render, and
 * no inspector is even mounted with an empty selection, so there is no frame to save.
 */
export function useInspectorRestingMode({
  isRunMode,
  hasSelection,
  setIsAdvancedMode,
}: UseInspectorRestingModeProps): void {
  const { openMode } = useInspectorOpenModeSafe();
  const opensAdvanced = openMode === 'advanced';

  React.useEffect(() => {
    if (isRunMode || hasSelection) return;
    setIsAdvancedMode(opensAdvanced);
  }, [isRunMode, hasSelection, opensAdvanced, setIsAdvancedMode]);
}
