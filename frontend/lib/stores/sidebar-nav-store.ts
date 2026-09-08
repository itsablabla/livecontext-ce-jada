/**
 * Which navigation entries the sidebar shows, and where the home page's
 * quick-open shortcut points.
 *
 * A store rather than a hook-local state because THREE surfaces read the same
 * answer and must agree instantly: the expanded panel's rows, the collapsed
 * rail's icons, and the customize menu that edits them. Ticking a box in the
 * menu has to move the rail behind it in the same frame.
 *
 * Persistence is per browser (localStorage), like the sidebar's own
 * collapsed state and the agenda's view preferences: it is a view preference,
 * not workspace data, and two people sharing a workspace legitimately want
 * different pages in their sidebar.
 *
 * Hydration is deferred (`skipHydration`) and driven by `useSidebarNavPreferences`
 * from an effect. Reading localStorage while the store is created would make the
 * first client render disagree with the server HTML, which React reports as a
 * hydration mismatch.
 */

import { useEffect, useMemo } from 'react';
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import {
  DEFAULT_HIDDEN_NAV_IDS,
  DEFAULT_QUICK_OPEN_NAV_ID,
  MIN_VISIBLE_NAV_ITEMS,
  SIDEBAR_NAV_ITEMS,
  isSidebarNavId,
  type SidebarNavId,
  type SidebarNavItem,
} from '@/lib/sidebar/navItems';

interface SidebarNavState {
  /**
   * Stored as the HIDDEN set, never the visible one: an entry added to the app
   * later is then visible by default for everyone, instead of being invisible
   * to every user who ever opened the menu.
   */
  hiddenNavIds: SidebarNavId[];
  /** Destination of the home page's quick-open shortcut. */
  quickOpenNavId: SidebarNavId;
  /** Show/hide one entry. Hiding is refused below the floor - see MIN_VISIBLE_NAV_ITEMS. */
  toggleNavItem: (id: SidebarNavId) => void;
  setQuickOpenNavId: (id: SidebarNavId) => void;
  resetNavPreferences: () => void;
}

const STORAGE_KEY = 'lc.sidebar.nav.v1';

function sanitizeHidden(value: unknown): SidebarNavId[] {
  if (!Array.isArray(value)) return [...DEFAULT_HIDDEN_NAV_IDS];
  // Drop ids this build no longer knows, and never let a stored payload hide
  // so much that the sidebar falls under the floor.
  const known = Array.from(new Set(value.filter(isSidebarNavId)));
  const maxHidden = Math.max(0, SIDEBAR_NAV_ITEMS.length - MIN_VISIBLE_NAV_ITEMS);
  return known.slice(0, maxHidden);
}

export const useSidebarNavStore = create<SidebarNavState>()(
  persist(
    (set, get) => ({
      hiddenNavIds: [...DEFAULT_HIDDEN_NAV_IDS],
      quickOpenNavId: DEFAULT_QUICK_OPEN_NAV_ID,

      toggleNavItem: (id) => {
        const hidden = get().hiddenNavIds;
        if (hidden.includes(id)) {
          set({ hiddenNavIds: hidden.filter((hiddenId) => hiddenId !== id) });
          return;
        }
        const visibleCount = SIDEBAR_NAV_ITEMS.length - hidden.length;
        if (visibleCount <= MIN_VISIBLE_NAV_ITEMS) return;
        set({ hiddenNavIds: [...hidden, id] });
      },

      setQuickOpenNavId: (id) => set({ quickOpenNavId: id }),

      resetNavPreferences: () =>
        set({ hiddenNavIds: [...DEFAULT_HIDDEN_NAV_IDS], quickOpenNavId: DEFAULT_QUICK_OPEN_NAV_ID }),
    }),
    {
      name: STORAGE_KEY,
      storage: typeof window !== 'undefined' ? createJSONStorage(() => localStorage) : undefined,
      skipHydration: true,
      partialize: (state) => ({ hiddenNavIds: state.hiddenNavIds, quickOpenNavId: state.quickOpenNavId }),
      // Merge field by field. A payload written by an older build, or edited by
      // hand, can carry an unknown id or a wrong type; spreading it wholesale
      // would put that straight into the render.
      merge: (persisted, current) => {
        const stored = (persisted ?? {}) as Partial<SidebarNavState>;
        return {
          ...current,
          hiddenNavIds: sanitizeHidden(stored.hiddenNavIds),
          quickOpenNavId: isSidebarNavId(stored.quickOpenNavId)
            ? stored.quickOpenNavId
            : DEFAULT_QUICK_OPEN_NAV_ID,
        };
      },
    },
  ),
);

export interface SidebarNavPreferences {
  /** Entries to draw, in display order. Never fewer than MIN_VISIBLE_NAV_ITEMS. */
  visibleItems: SidebarNavItem[];
  hiddenNavIds: SidebarNavId[];
  quickOpenNavId: SidebarNavId;
  toggleNavItem: (id: SidebarNavId) => void;
  setQuickOpenNavId: (id: SidebarNavId) => void;
  resetNavPreferences: () => void;
  /** True when hiding one more entry would break the floor, so the UI can say why. */
  atMinimum: boolean;
}

/**
 * Read (and edit) the sidebar navigation preferences.
 *
 * Triggers the deferred rehydration on mount. Calling it from several
 * components is safe: rehydrating re-reads the storage that every `set` has
 * already written to, so it can only ever resolve to the current value.
 */
export function useSidebarNavPreferences(): SidebarNavPreferences {
  const hiddenNavIds = useSidebarNavStore((state) => state.hiddenNavIds);
  const quickOpenNavId = useSidebarNavStore((state) => state.quickOpenNavId);
  const toggleNavItem = useSidebarNavStore((state) => state.toggleNavItem);
  const setQuickOpenNavId = useSidebarNavStore((state) => state.setQuickOpenNavId);
  const resetNavPreferences = useSidebarNavStore((state) => state.resetNavPreferences);

  useEffect(() => {
    if (!useSidebarNavStore.persist.hasHydrated()) {
      void useSidebarNavStore.persist.rehydrate();
    }
  }, []);

  // Memoized on the stored set, which zustand keeps referentially stable between
  // changes: a fresh array every render would defeat the `useMemo` the sidebar
  // wraps its rail around, and re-render it on every keystroke elsewhere.
  const visibleItems = useMemo(
    () => SIDEBAR_NAV_ITEMS.filter((item) => !hiddenNavIds.includes(item.id)),
    [hiddenNavIds],
  );

  return {
    visibleItems,
    hiddenNavIds,
    quickOpenNavId,
    toggleNavItem,
    setQuickOpenNavId,
    resetNavPreferences,
    atMinimum: visibleItems.length <= MIN_VISIBLE_NAV_ITEMS,
  };
}
