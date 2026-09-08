'use client';

/**
 * How much of the node inspector a click on a node opens.
 *
 * `simple` is the historical behaviour: clicking a node opens its parameters alone, and the
 * input and output columns are one more gesture away (double-click, or the inspector's own
 * expand control). `advanced` opens straight into the three-column view, so what feeds a
 * node and what it produced are on screen with its settings.
 *
 * <p><b>Who this is for.</b> Simple is the right first contact with a node and stays the
 * default. But once someone is wiring a real workflow, the question they open a node to
 * answer is almost always "what am I actually getting here", and paying a second gesture
 * for it on every node is the kind of friction that is invisible to whoever built it and
 * constant for whoever uses it.
 *
 * <p>ONE value, TWO surfaces, exactly like {@link InspectorDockContext}: the account
 * preference (Settings > Preferences) and a workflow's own canvas settings write the SAME
 * per-workspace preference, so choosing it in either place changes it everywhere. That is
 * the point of putting it in the canvas at all - a control that only changed the workflow
 * in front of you would have to be set again in every other one.
 *
 * <p><b>Run mode ignores it and must.</b> Opening a run is opening the results, so the
 * inspector is forced into the three-column view there whatever this says. The preference
 * decides what a BUILD-mode click does.
 *
 * <p>A request, not a guarantee: a node with no three-column view to show falls back to the
 * compact one, per node and without touching this preference. That covers API steps with no
 * tool chosen, generic MCP and AI nodes, and anything still on a navigation step (see
 * `shouldForceSmallMode` in InspectorPanel). A preference must never leave a node
 * unconfigurable.
 */

import React, { createContext, useContext, useMemo } from 'react';
import { useWorkspacePreference } from '@/lib/preferences/workspacePreference';

export type InspectorOpenMode = 'simple' | 'advanced';

/**
 * Simple, deliberately: it is what every existing user's click already does, and the
 * three-column view on a small screen is a worse first impression than a second gesture.
 */
export const DEFAULT_INSPECTOR_OPEN_MODE: InspectorOpenMode = 'simple';

const STORAGE_PREFIX = 'lc.workflow.inspectorOpenMode';

interface InspectorOpenModeContextValue {
  /** What a build-mode node click opens. */
  openMode: InspectorOpenMode;
  /** Persist the choice for this workspace (Settings, and the canvas settings panel). */
  setOpenMode: (mode: InspectorOpenMode) => void;
}

const InspectorOpenModeContext = createContext<InspectorOpenModeContextValue | null>(null);

export function isInspectorOpenMode(value: string | null | undefined): value is InspectorOpenMode {
  return value === 'simple' || value === 'advanced';
}

export function InspectorOpenModeProvider({ children }: { children: React.ReactNode }) {
  const [openMode, setOpenMode] = useWorkspacePreference<InspectorOpenMode>(
    STORAGE_PREFIX,
    isInspectorOpenMode,
    DEFAULT_INSPECTOR_OPEN_MODE,
  );
  const value = useMemo(() => ({ openMode, setOpenMode }), [openMode, setOpenMode]);
  return <InspectorOpenModeContext.Provider value={value}>{children}</InspectorOpenModeContext.Provider>;
}

/** Throws outside the provider: use on pages always mounted under it (Settings). */
export function useInspectorOpenMode(): InspectorOpenModeContextValue {
  const ctx = useContext(InspectorOpenModeContext);
  if (!ctx) {
    throw new Error('useInspectorOpenMode must be used within an InspectorOpenModeProvider');
  }
  return ctx;
}

/**
 * Defaults + a no-op setter outside the provider, mirroring `useInspectorDockSafe`. The
 * canvas uses THIS one: it is mounted by surfaces that carry no provider (the marketplace
 * preview, a snapshot canvas), and none of them may crash because nobody declared how much
 * of an inspector to open.
 */
export function useInspectorOpenModeSafe(): InspectorOpenModeContextValue {
  const ctx = useContext(InspectorOpenModeContext);
  return ctx ?? { openMode: DEFAULT_INSPECTOR_OPEN_MODE, setOpenMode: () => {} };
}
