'use client';

/**
 * Where the workflow builder's node inspector opens.
 *
 * `canvas` is the historical placement: a floating, draggable window over the
 * ReactFlow canvas, next to the node it configures. `panel` docks it instead as
 * a sub-tab of the unified side panel, so configuring a node no longer covers
 * the graph.
 *
 * ONE value, TWO surfaces. The account preference (Settings > Preferences) and
 * the in-canvas settings panel of a workflow write the SAME per-workspace
 * preference, deliberately: the user asked for the workflow-level control to be
 * linked to the general one, so flipping it in either place moves the inspector
 * everywhere. That is why this context has a single `setDock` and no
 * per-workflow setter (unlike {@link WorkflowLayoutDirectionContext}, whose
 * direction is part of a workflow's identity and lives in its plan).
 *
 * It is a purely-visual client preference, like the theme and the side-panel
 * dock, so it lives in localStorage with no backend round-trip, scoped per
 * workspace (mirroring `SidePanelLayoutContext`). The storage discipline itself
 * (per-workspace key, default on the server render, re-read on org switch) is
 * `useWorkspacePreference`, shared with {@link InspectorOpenModeContext} rather
 * than written out a third time.
 *
 * Choosing `panel` is a REQUEST, not a guarantee: the canvas only honors it
 * where a side panel actually exists to dock into (see `inspectorDockBus`), and
 * falls back to the floating window otherwise - a preference must never make the
 * inspector unreachable.
 */

import React, { createContext, useContext, useMemo } from 'react';
import { useWorkspacePreference } from '@/lib/preferences/workspacePreference';

export type InspectorDock = 'canvas' | 'panel';

/**
 * Canvas, deliberately: the floating inspector is what every existing user knows
 * and it is the only placement that works on surfaces with no side panel. The
 * docked variant is opt-in from the preferences.
 */
export const DEFAULT_INSPECTOR_DOCK: InspectorDock = 'canvas';

interface InspectorDockContextValue {
  /** Where the inspector is asked to open. */
  dock: InspectorDock;
  /** Persist the choice for this workspace (Settings, and the canvas settings panel). */
  setDock: (dock: InspectorDock) => void;
}

const InspectorDockContext = createContext<InspectorDockContextValue | null>(null);

const STORAGE_PREFIX = 'lc.workflow.inspectorDock';

export function isInspectorDock(value: string | null | undefined): value is InspectorDock {
  return value === 'canvas' || value === 'panel';
}

export function InspectorDockProvider({ children }: { children: React.ReactNode }) {
  const [dock, setDock] = useWorkspacePreference<InspectorDock>(
    STORAGE_PREFIX,
    isInspectorDock,
    DEFAULT_INSPECTOR_DOCK,
  );
  const value = useMemo(() => ({ dock, setDock }), [dock, setDock]);

  return <InspectorDockContext.Provider value={value}>{children}</InspectorDockContext.Provider>;
}

/** Throws outside the provider: use on pages always mounted under it (Settings). */
export function useInspectorDock(): InspectorDockContextValue {
  const ctx = useContext(InspectorDockContext);
  if (!ctx) {
    throw new Error('useInspectorDock must be used within an InspectorDockProvider');
  }
  return ctx;
}

/**
 * Defaults + a no-op setter outside the provider, mirroring
 * `useWorkflowLayoutDirectionSafe`. The canvas uses THIS one: it is also mounted
 * by surfaces that carry no provider (the standalone builder route, the
 * marketplace preview), and none of them may crash because nobody declared where
 * the inspector docks.
 */
export function useInspectorDockSafe(): InspectorDockContextValue {
  const ctx = useContext(InspectorDockContext);
  return ctx ?? { dock: DEFAULT_INSPECTOR_DOCK, setDock: () => {} };
}
