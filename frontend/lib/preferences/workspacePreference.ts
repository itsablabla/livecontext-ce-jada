'use client';

/**
 * A purely-visual client preference, remembered per workspace.
 *
 * <p>Several builder preferences are the same object with a different name: a small set
 * of string values, stored in localStorage under a per-workspace key, seeded to a default
 * on the server render and restored after mount. `InspectorDockContext` and
 * `InspectorOpenModeContext` are both exactly that, and writing the third copy of it is
 * what this exists to avoid.
 *
 * <p><b>What it deliberately does NOT cover:</b> `WorkflowLayoutDirectionContext`, which
 * looks similar and is not. Its value has a second, in-memory-only layer (the direction of
 * the workflow currently open, which lives in that workflow's plan) plus a `forcedDirection`
 * pin for surfaces that reuse builder nodes with their own fixed layout. Bending this hook
 * to carry those would make it describe neither case honestly.
 *
 * <p><b>Per workspace, not per user.</b> A person can have one workspace where they want
 * the inspector docked and another where they do not, and switching org must switch the
 * answer - hence the org id in the key and the re-read on org change.
 *
 * <p><b>Never read during render.</b> The builder is a client component the server still
 * renders, so a stored value read in the useState initializer is a hydration mismatch
 * against the server's default. The stored value is restored in an effect instead, which
 * costs one frame at the default for a user who chose otherwise.
 *
 * <p><b>`isValid` and the default must be stable references.</b> They are effect
 * dependencies, so an inline predicate re-runs the read on every render. Nothing breaks
 * (React bails on an equal setState) but it is wasted work, and the contexts here pass
 * module-level constants.
 */

import { useCallback, useEffect, useState } from 'react';
import { useCurrentOrg } from '@/lib/stores/current-org-store';

/** localStorage key for a given workspace (null org = personal workspace). */
export function workspacePreferenceKey(prefix: string, orgId: string | null | undefined): string {
  return `${prefix}:${orgId ?? 'personal'}`;
}

/**
 * The stored value, or null when nothing valid is stored.
 *
 * @param isValid the caller's own guard. Anything else stored under the key (a stale
 *                format, a key collision, devtools) is treated as absent rather than
 *                coerced: this is where a preference helper usually ships the wrong value.
 * @param storage injection point for the failure modes a test cannot otherwise reach: a
 *                store that THROWS on access (Safari private mode, full quota), or an
 *                explicit null for "no storage at all", which is what a server render sees.
 *                Omitting it uses the browser's, and omitting it is not the same as null.
 */
export function readWorkspacePreference<T extends string>(
  prefix: string,
  orgId: string | null | undefined,
  isValid: (value: string | null) => value is T,
  storage?: Storage | null,
): T | null {
  try {
    // Inside the try, and that is the point: in a browser set to block all site data it is
    // the `window.localStorage` GETTER that throws, before any method is called. Resolving
    // the store outside meant the throw escaped the guard and took the provider's mount
    // effect with it - a preference helper crashing the page it exists to decorate.
    const store = storage === undefined
      ? (typeof window !== 'undefined' ? window.localStorage : null)
      : storage;
    if (!store) return null;
    const saved = store.getItem(workspacePreferenceKey(prefix, orgId));
    return isValid(saved) ? saved : null;
  } catch {
    // Storage unavailable (private mode, blocked site data): fall back to the default.
    return null;
  }
}

/**
 * The preference and its setter. The setter keeps the choice in memory even when the write
 * fails, so a user in private mode still gets the behaviour they asked for this session.
 */
export function useWorkspacePreference<T extends string>(
  prefix: string,
  isValid: (value: string | null) => value is T,
  defaultValue: T,
): [T, (next: T) => void] {
  const { currentOrgId } = useCurrentOrg();
  // Seeded to the DEFAULT so the server render and the first client render agree.
  const [value, setValue] = useState<T>(defaultValue);

  // On mount AND on every workspace change: the preference is per-org.
  useEffect(() => {
    const stored = readWorkspacePreference(prefix, currentOrgId, isValid);
    // Syncing from an external store (localStorage) on mount and on org switch. It cannot
    // run during render without breaking hydration, which is exactly the case the rule
    // allows for.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setValue(stored ?? defaultValue);
  }, [prefix, currentOrgId, isValid, defaultValue]);

  const set = useCallback(
    (next: T) => {
      setValue(next);
      try {
        // Same reason as the read: `window.localStorage` can throw on ACCESS, not only on
        // setItem, so the whole expression belongs inside the guard.
        window.localStorage.setItem(workspacePreferenceKey(prefix, currentOrgId), next);
      } catch {
        // Storage unavailable: keep the in-memory choice for this session.
      }
    },
    [prefix, currentOrgId],
  );

  return [value, set];
}
