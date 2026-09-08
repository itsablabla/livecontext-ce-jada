import { AppWindow, Bot, CalendarClock, Columns3, Folder, Monitor, Store, Table, Workflow, type LucideIcon } from 'lucide-react';
import type { AppView } from '@/hooks/useCurrentView';

/**
 * The navigation entries the user can show or hide in the sidebar.
 *
 * ONE list, read by every surface that draws them: the expanded panel's rows
 * (ConversationSidebar), the collapsed rail's icons (AppSidebar) and the
 * customize menu's checkboxes. They used to be three hand-maintained copies,
 * which is how the rail and the panel drifted apart before.
 *
 * Home and Marketplace are deliberately NOT here: Home is the surface the
 * sidebar hangs off, and Marketplace sits above the block as a fixed row. Both
 * stay visible whatever the user picks.
 */
export type SidebarNavId =
  | 'board'
  | 'agenda'
  | 'agents'
  | 'applications'
  | 'workflows'
  | 'interfaces'
  | 'tables'
  | 'files';

export interface SidebarNavItem {
  id: SidebarNavId;
  icon: LucideIcon;
  path: string;
  /** The `useCurrentView()` view this entry is the active one for. */
  view: AppView;
  /** Message key under the `sidebar.nav.*` namespace. */
  titleKey: string;
}

/** Display order, top to bottom - the same order the rail draws its icons in. */
export const SIDEBAR_NAV_ITEMS: readonly SidebarNavItem[] = [
  { id: 'board', icon: Columns3, path: '/app/board', view: 'board', titleKey: 'board' },
  { id: 'agenda', icon: CalendarClock, path: '/app/agenda', view: 'agenda', titleKey: 'agenda' },
  { id: 'agents', icon: Bot, path: '/app/agent', view: 'agent', titleKey: 'agents' },
  { id: 'applications', icon: AppWindow, path: '/app/applications', view: 'applications', titleKey: 'applications' },
  { id: 'workflows', icon: Workflow, path: '/app/workflow', view: 'workflow', titleKey: 'workflows' },
  { id: 'interfaces', icon: Monitor, path: '/app/interface', view: 'interface', titleKey: 'interfaces' },
  { id: 'tables', icon: Table, path: '/app/tables', view: 'data', titleKey: 'tables' },
  { id: 'files', icon: Folder, path: '/app/files', view: 'files', titleKey: 'files' },
];

export const SIDEBAR_NAV_IDS: readonly SidebarNavId[] = SIDEBAR_NAV_ITEMS.map((item) => item.id);

/**
 * Kept out of the sidebar for everyone until they ask for them. These are the
 * three entries most people never open from the sidebar itself: a board is
 * reached from the home shortcut, an interface from the workflow or
 * application it belongs to, and an application from the marketplace or its
 * own page.
 *
 * "Hidden" overstates it, and the distinction matters for where the menu puts
 * them: they are not gone, they are one click further away, listed in the
 * sidebar's overflow menu. Anyone who wants one back in the sidebar proper
 * ticks it in the customize panel behind that menu.
 *
 * This list is the default for a browser that has never stored a choice. It is
 * NOT a migration: an existing visitor keeps whatever `lc.sidebar.nav.v1`
 * already holds, so a page added to this list later stays in their sidebar
 * until they take it out themselves. That is deliberate - moving someone's
 * pages behind a menu they have never seen is worse than an inconsistent
 * default - but it does mean this list describes new visitors only.
 */
export const DEFAULT_HIDDEN_NAV_IDS: readonly SidebarNavId[] = ['board', 'applications', 'interfaces'];

/**
 * The sidebar always keeps at least this many entries.
 *
 * Note the headroom this leaves: with three pages out of eight hidden by
 * default, a fresh visitor can hide exactly ONE more before the remaining four
 * checkboxes lock. That is deliberate (the floor is about never stranding the
 * reader, not about how much they may hide), but it means the locked state is
 * now two clicks away rather than four. Emptying it entirely
 * would leave no way back to the pages, and the customize menu is reached
 * from that same block - so the floor is what keeps the sidebar navigable.
 */
export const MIN_VISIBLE_NAV_ITEMS = 4;

/** Where the home page's quick-open shortcut points when the user has not chosen. */
export const DEFAULT_QUICK_OPEN_NAV_ID: SidebarNavId = 'agenda';

/**
 * Marketplace: the one rail entry that is not customizable. The expanded panel
 * draws it as a fixed row above the block the customize menu governs, so the
 * rail keeps it too, and first.
 */
export const MARKETPLACE_NAV_ITEM = {
  icon: Store,
  path: '/app/marketplace',
  view: 'marketplace' as AppView,
  titleKey: 'marketplace',
} as const;

/**
 * What the collapsed rail lists: Marketplace, then the pages the user kept, in
 * the same order the expanded panel draws its rows.
 */
export function railNavItems(visibleItems: readonly SidebarNavItem[]) {
  return [MARKETPLACE_NAV_ITEM, ...visibleItems];
}

export function findNavItem(id: SidebarNavId): SidebarNavItem | undefined {
  return SIDEBAR_NAV_ITEMS.find((item) => item.id === id);
}

export function isSidebarNavId(value: unknown): value is SidebarNavId {
  return typeof value === 'string' && SIDEBAR_NAV_IDS.includes(value as SidebarNavId);
}

export interface NewChatActiveInput {
  view: AppView;
  isDetailPage: boolean;
  currentConversationId: string | null | undefined;
  /** Locale-stripped path, as `@/i18n/navigation`'s `usePathname` returns it. */
  pathname: string | null | undefined;
}

/**
 * Whether the new-chat entry is the live surface.
 *
 * A pure function, and exported, because this predicate is exactly what drifted
 * between the rail and the panel. It reads as four clauses:
 *
 *  - a DETAIL page is never it (an open conversation, an open studio thread);
 *  - the studio home IS it - the studio is a MODE of the home surface, not a
 *    destination of its own, so nothing else in the sidebar can light up there;
 *  - the chat home is it, but only with no conversation open;
 *  - a DM thread lives on the home surface too and must un-light it, since the
 *    Messages list is a view of this same sidebar rather than a page.
 */
export function isNewChatEntryActive({
  view,
  isDetailPage,
  currentConversationId,
  pathname,
}: NewChatActiveInput): boolean {
  if (isDetailPage) return false;
  if (view === 'studio') return true;
  return view === 'chat' && !currentConversationId && !pathname?.includes('/app/messages');
}
