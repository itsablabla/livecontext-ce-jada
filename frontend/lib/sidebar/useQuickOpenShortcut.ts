'use client';

import { useEffect } from 'react';
import { useSafeNavigate } from '@/contexts/NavigationGuardContext';
import { DEFAULT_QUICK_OPEN_NAV_ID, findNavItem, type SidebarNavItem } from '@/lib/sidebar/navItems';
import { matchesQuickOpenShortcut } from '@/lib/sidebar/quickOpenShortcut';
import { useSidebarNavPreferences } from '@/lib/stores/sidebar-nav-store';

/**
 * Whether something that owns the screen is open.
 *
 * Two probes, because the app's overlays declare themselves in two different
 * ways and NEITHER of the obvious single answers works:
 *
 * - `aria-modal="true"` catches the hand-rolled modals (RunActionButton's
 *   cancel confirmation, ConfirmDeleteModal, ...) but MISSES every Radix one:
 *   Radix deliberately never sets it, aria-hiding the rest of the page instead,
 *   so `components/ui/dialog` - which 34 files use - carries no `aria-modal`.
 * - `role="dialog"` catches those, but far too much: Radix gives Popover
 *   content the SAME `role="dialog"` + `data-state="open"` as a Dialog, and 70
 *   files use `components/ui/popover`. The customize menu that advertises this
 *   very shortcut is one of them, so keying on the role leaves the shortcut
 *   dead exactly where it is announced. (`SidePanel.tsx`'s `OVERLAY_ROLES` uses
 *   that breadth on purpose, for "who owns Escape". This is a different
 *   question.)
 *
 * So the probe is the page lock Radix marks on `document.body`. Read it as what
 * it literally is, a SCROLL lock, not as a synonym for "modal":
 * - A `Select` also sets it (`@radix-ui/react-select` wraps its content in RemoveScroll
 *   unconditionally, with no `modal` prop to turn it off), so an open dropdown
 *   suppresses the shortcut too. That is deliberate: a focus-trapped popup
 *   awaiting a choice is exactly as bad a moment to teleport as a dialog.
 * - It is Radix-only. A component that hides overflow by hand (the full-screen
 *   application tab does) is not covered, and should not be: that is a page,
 *   not a prompt.
 * - A `<Dialog modal={false}>` would set neither probe and would fall through.
 *   Nothing in the app uses one today.
 */
function anOverlayOwnsTheScreen(): boolean {
  if (document.body.hasAttribute('data-scroll-locked')) return true;
  return document.querySelector('[aria-modal="true"]') !== null;
}

/**
 * The page the quick-open shortcut leads to. `findNavItem` can only miss if a
 * stored id outlived the entry it named, so fall back to the default rather
 * than leave the button and the shortcut pointing nowhere.
 */
export function useQuickOpenDestination(): SidebarNavItem {
  const { quickOpenNavId } = useSidebarNavPreferences();
  return findNavItem(quickOpenNavId) ?? findNavItem(DEFAULT_QUICK_OPEN_NAV_ID)!;
}

/**
 * Binds the shortcut. Mounted once by the app shell rather than by the home
 * button that also carries it: the customize menu spells these keys out
 * wherever the sidebar is, so they have to work wherever the sidebar is. That
 * is exactly the shell's reach - `AppSidebar` has no call site outside it - so
 * the keys work everywhere they are advertised, and the standalone builder,
 * which has its own layout and no sidebar, is untouched.
 */
export function useQuickOpenShortcut(): void {
  const navigate = useSafeNavigate();
  const destination = useQuickOpenDestination().path;

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!matchesQuickOpenShortcut(event)) return;
      // Something owns the screen while it is open; navigating out from under
      // it would drop whatever it was asking for with no prompt.
      if (anOverlayOwnsTheScreen()) return;
      event.preventDefault();
      // `useSafeNavigate`, not `router.push`: the same guard the search bar
      // goes through, so a page with unsaved work can still object.
      navigate(destination);
    };
    // The shortcut deliberately fires with a text field focused: it types
    // nothing, and the home composer holds the caret by default.
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [navigate, destination]);
}
