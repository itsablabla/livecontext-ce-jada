'use client';

import { useMemo } from 'react';
import { Home, Store, type LucideIcon } from 'lucide-react';
import { usePathname } from '@/i18n/navigation';
import { useCurrentView, type AppView } from '@/hooks/useCurrentView';
import { MARKETPLACE_NAV_ITEM, isNewChatEntryActive } from '@/lib/sidebar/navItems';
import { useSidebarNavPreferences } from '@/lib/stores/sidebar-nav-store';

/**
 * One resolved sidebar navigation entry: what to draw, where it goes, and
 * whether it is the entry the app is currently on.
 *
 * "Resolved" is the point. `SIDEBAR_NAV_ITEMS` already gave the two surfaces one
 * LIST; it did not give them one ANSWER. The rail and the panel each decided
 * for themselves which entry was active, and they had already drifted: on
 * /app/studio the rail lit its home icon and the panel's row stayed dark, so the
 * same sidebar said two different things depending on whether it was open. This
 * type is that answer, computed once.
 */
export interface SidebarNavEntry {
  /** Stable React key. The nav id for a page, or the fixed entry's own name. */
  key: string;
  icon: LucideIcon;
  /** Message key under the `sidebar.nav.*` namespace. */
  titleKey: string;
  /** Whether this entry is the surface the app is on. */
  isActive: boolean;
  /**
   * Where the entry goes, or null for the new-chat entry - an ACTION (open a
   * fresh conversation on the surface you are already on) rather than a
   * destination, which is why it is the one entry the caller handles itself.
   */
  path: string | null;
  view: AppView | null;
}

export interface SidebarNavEntries {
  /**
   * The view the app is on, straight from the URL.
   *
   * Carried alongside the entries because the overflow menu needs the raw
   * answer, not the resolved one: it exists to say "current page" for a page
   * the sidebar is NOT drawing, and a page with no entry has no `isActive` to
   * read.
   */
  currentView: AppView;
  /** The new-chat entry. Always first, never customizable. */
  newChat: SidebarNavEntry;
  /** Marketplace. Always second, never customizable. */
  marketplace: SidebarNavEntry;
  /** The pages the user kept, in display order. */
  pages: SidebarNavEntry[];
}

/**
 * The sidebar's navigation, resolved once for whichever surface draws it.
 *
 * Both the collapsed rail and the expanded panel call this, with the same
 * input, so they cannot list different pages, in a different order, with a
 * different entry lit. That is the whole contract: the surfaces differ in SHAPE
 * (32px icons in a column, or icon+label rows), never in CONTENT.
 *
 * `currentConversationId` is the only input, and both call sites already hold
 * the same value - the shell computes it (context, falling back to the URL) and
 * passes the very same one down to the panel.
 */
export function useSidebarNavEntries(currentConversationId: string | null | undefined): SidebarNavEntries {
  const { view, isDetailPage } = useCurrentView();
  const pathname = usePathname();
  const { visibleItems } = useSidebarNavPreferences();

  const newChatActive = isNewChatEntryActive({ view, isDetailPage, currentConversationId, pathname });

  return useMemo(
    () => ({
      currentView: view,
      newChat: {
        key: 'new-chat',
        // The house icon stays: it is the mark of the surface the whole sidebar
        // hangs off. The LABEL says what pressing it does.
        icon: Home,
        titleKey: 'newChat',
        isActive: newChatActive,
        path: null,
        view: null,
      },
      marketplace: {
        key: 'marketplace',
        icon: Store,
        titleKey: MARKETPLACE_NAV_ITEM.titleKey,
        isActive: view === MARKETPLACE_NAV_ITEM.view,
        path: MARKETPLACE_NAV_ITEM.path,
        view: MARKETPLACE_NAV_ITEM.view,
      },
      pages: visibleItems.map((item) => ({
        key: item.id,
        icon: item.icon,
        titleKey: item.titleKey,
        isActive: view === item.view,
        path: item.path,
        view: item.view,
      })),
    }),
    [newChatActive, view, visibleItems],
  );
}
