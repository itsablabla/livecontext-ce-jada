'use client';

/**
 * Reading direction of the workflow builder canvas.
 *
 * `horizontal` is the historical layout (trigger on the left, flow runs right).
 * `vertical` reads top-down (trigger on top, flow runs down) like the agent fleet
 * canvas already does, and like most workflow tools.
 *
 * There are TWO layers, both flowing through this one `direction` value:
 *   - The user's per-workspace DEFAULT for NEW workflows: a client preference (like
 *     the theme and the side-panel dock) stored in localStorage, no backend
 *     round-trip, scoped per workspace (mirroring `SidePanelLayoutContext`). Written
 *     by `setDirection` (the account Settings preference).
 *   - The ACTIVE direction of the workflow currently open, which is that workflow's
 *     identity: it is persisted in the workflow PLAN (`plan.layoutDirection`) and seeded
 *     back onto the canvas on load by `setWorkflowDirection`, IN MEMORY ONLY (never
 *     localStorage), so LOADING a workflow never overwrites the account default.
 *     Changing the direction live from the canvas is a different act: it is the user
 *     stating a preference, and it writes both layers.
 *
 * The direction drives THREE things, and they must stay in agreement or the canvas
 * contradicts itself:
 *   1. dagre's `rankdir` (LayoutService) - where auto-layout puts the nodes,
 *   2. the node handles (`getHandleGeometry`) - which edges of the box connect,
 *   3. the node side-attachments (`NodeBottomBar` & co) - which edge is free to
 *      hang buttons off, since the flow edge is taken by handles.
 * Read it from `useWorkflowLayoutDirection()` rather than threading a prop: the
 * node components are mounted by ReactFlow from a type registry, so a prop cannot
 * reach them.
 */

import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useCurrentOrg } from '@/lib/stores/current-org-store';

export type WorkflowLayoutDirection = 'horizontal' | 'vertical';

/**
 * Horizontal, deliberately: every existing workflow was authored and positioned
 * left-to-right, so defaulting to vertical would silently re-read every canvas the
 * user already knows. Vertical is opt-in from the preferences.
 */
export const DEFAULT_WORKFLOW_LAYOUT_DIRECTION: WorkflowLayoutDirection = 'horizontal';

interface WorkflowLayoutDirectionContextValue {
  /** The ACTIVE direction the canvas renders in. */
  direction: WorkflowLayoutDirection;
  /**
   * The stored per-workspace DEFAULT, unaffected by whatever workflow is open.
   *
   * <p>Separate from `direction` because the two answer different questions and had been
   * conflated: the account preference in Settings was reading the ACTIVE value, so once a
   * workflow whose plan stamps a direction had been opened in the session, that page
   * displayed the workflow's direction as if it were the user's default - and, being a
   * controlled select, could not be used to re-pick the value it was already showing.
   * Surfaces that describe the DEFAULT read this; the canvas reads `direction`.
   */
  defaultDirection: WorkflowLayoutDirection;
  /**
   * Set the direction as the user's GLOBAL default (persisted to localStorage). Used
   * by the account Settings preference: it is the default for NEW workflows.
   */
  setDirection: (direction: WorkflowLayoutDirection) => void;
  /**
   * Set the active direction for THIS workflow only, in memory, WITHOUT touching the
   * global preference. Its callers are the loader's two seeding paths: the initial read of
   * a plan's stored direction, and a version restore.
   *
   * <p>The in-canvas toggle used to be a third caller and deliberately is not any more. A
   * memory-only write meant choosing a reading direction from the workflow in front of you
   * left the account default untouched, so every other workflow still opened the other way
   * round: a control that looked general and was not. It calls `setDirection` now.
   */
  setWorkflowDirection: (direction: WorkflowLayoutDirection) => void;
}

const WorkflowLayoutDirectionContext = createContext<WorkflowLayoutDirectionContextValue | null>(null);

const STORAGE_PREFIX = 'lc.workflow.layoutDirection';

export function isWorkflowLayoutDirection(value: string | null | undefined): value is WorkflowLayoutDirection {
  return value === 'horizontal' || value === 'vertical';
}

/** localStorage key for a given workspace (null org = personal workspace). */
function storageKey(orgId: string | null | undefined): string {
  return `${STORAGE_PREFIX}:${orgId ?? 'personal'}`;
}

function readStoredDirection(orgId: string | null | undefined): WorkflowLayoutDirection | null {
  if (typeof window === 'undefined') return null;
  try {
    const saved = window.localStorage.getItem(storageKey(orgId));
    return isWorkflowLayoutDirection(saved) ? saved : null;
  } catch {
    // Storage unavailable (private mode): fall back to the default.
    return null;
  }
}

export function WorkflowLayoutDirectionProvider({
  children,
  forcedDirection,
}: {
  children: React.ReactNode;
  /**
   * Pin the direction, ignoring the stored preference, and make the setter a no-op.
   * Used by surfaces that reuse the builder's node components but must NOT follow the
   * workflow preference, e.g. the agent fleet (its own always-TB canvas): without
   * this, flipping the workflow layout would silently move the fleet's node buttons.
   */
  forcedDirection?: WorkflowLayoutDirection;
}) {
  const { currentOrgId } = useCurrentOrg();
  // Seed the DEFAULT so the server render and the first client render agree; the
  // stored value is restored in an effect below (reading localStorage during render
  // would produce a hydration mismatch).
  const [direction, setDirectionState] = useState<WorkflowLayoutDirection>(
    forcedDirection ?? DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
  );
  // The stored default, tracked alongside the active value. It follows storage and the
  // account setter, and is deliberately deaf to `setWorkflowDirection`: opening a workflow
  // must not restate what the user's default is.
  const [defaultDirection, setDefaultDirectionState] = useState<WorkflowLayoutDirection>(
    forcedDirection ?? DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
  );

  // Re-read on mount AND whenever the workspace changes: the preference is per-org.
  // Skipped when the direction is forced (the fleet), which owns its own value.
  useEffect(() => {
    if (forcedDirection) return;
    const stored = readStoredDirection(currentOrgId);
    // Syncing from an external store (localStorage) on mount and on org switch; it cannot
    // run during render without breaking hydration, which is the case the rule allows for.
    // The directives below are ONE line each and sit immediately above their statement: a
    // `disable-next-line` written across three comment lines targets the next COMMENT, so
    // it suppresses nothing and the statement warns anyway.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDirectionState(stored ?? DEFAULT_WORKFLOW_LAYOUT_DIRECTION);
    // No second directive: the rule reports once per effect, so one covers both writes and
    // a second is itself reported as unused.
    setDefaultDirectionState(stored ?? DEFAULT_WORKFLOW_LAYOUT_DIRECTION);
  }, [currentOrgId, forcedDirection]);

  const setDirection = useCallback(
    (next: WorkflowLayoutDirection) => {
      if (forcedDirection) return; // pinned: ignore writes
      // BOTH layers, from either surface, and that is deliberate. From the canvas it is
      // obvious (the user is changing the canvas in front of them). From Settings it is a
      // choice: a workflow may be mounted behind that page with a direction seeded from
      // its plan, and stating a default while the thing you can see keeps contradicting it
      // is the more confusing of the two. The workflow's own direction is not lost - it is
      // in its plan and re-seeds on the next load.
      //
      // With ONE edge, worth knowing before relying on that: the builder writes the ACTIVE
      // direction into the plan it saves (BuilderCanvas keeps a ref in step with it), so a
      // workflow left mounted behind Settings is re-oriented here and then stamps that
      // direction into its own plan on its next save. "Re-seeds on the next load" holds
      // until such a save overwrites what would have been re-seeded.
      setDirectionState(next);
      setDefaultDirectionState(next);
      try {
        window.localStorage.setItem(storageKey(currentOrgId), next);
      } catch {
        // Storage unavailable: keep the in-memory choice for this session.
      }
    },
    [currentOrgId, forcedDirection],
  );

  // Per-workflow: change the active direction in memory only. Does NOT write the
  // global preference (the workflow's choice belongs in its plan, saved on save).
  const setWorkflowDirection = useCallback(
    (next: WorkflowLayoutDirection) => {
      if (forcedDirection) return; // pinned surfaces (the fleet) ignore this too
      setDirectionState(next);
    },
    [forcedDirection],
  );

  const effective = forcedDirection ?? direction;
  // A pin overrides BOTH, mirroring `effective` above: a surface that fixes its own
  // reading direction is not describing anyone's stored default either. No consumer
  // observes this today (the only reader of `defaultDirection` is the account settings
  // page, which is never under a pinned provider), so it is consistency, not behaviour.
  const effectiveDefault = forcedDirection ?? defaultDirection;
  const value = useMemo(
    () => ({
      direction: effective,
      defaultDirection: effectiveDefault,
      setDirection,
      setWorkflowDirection,
    }),
    [effective, effectiveDefault, setDirection, setWorkflowDirection],
  );

  return (
    <WorkflowLayoutDirectionContext.Provider value={value}>{children}</WorkflowLayoutDirectionContext.Provider>
  );
}

/** Throws outside the provider: use in canvas code that is always mounted under it. */
export function useWorkflowLayoutDirection(): WorkflowLayoutDirectionContextValue {
  const ctx = useContext(WorkflowLayoutDirectionContext);
  if (!ctx) {
    throw new Error('useWorkflowLayoutDirection must be used within a WorkflowLayoutDirectionProvider');
  }
  return ctx;
}

/**
 * Defaults + a no-op setter outside the provider, mirroring `useSidePanelLayoutSafe`.
 * Node components use THIS one: they are also mounted by surfaces that do not carry
 * the provider (the marketplace preview, the landing, a snapshot canvas), and a node
 * must never crash a page just because nobody declared a reading direction.
 */
export function useWorkflowLayoutDirectionSafe(): WorkflowLayoutDirectionContextValue {
  const ctx = useContext(WorkflowLayoutDirectionContext);
  return (
    ctx ?? {
      direction: DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
      defaultDirection: DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
      setDirection: () => {},
      setWorkflowDirection: () => {},
    }
  );
}
