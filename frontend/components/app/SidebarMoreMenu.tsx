'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { ChevronLeft, MoreHorizontal, RotateCcw, SlidersHorizontal } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Kbd } from '@/components/ui/kbd';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { useSafeNavigate } from '@/contexts/NavigationGuardContext';
import type { AppView } from '@/hooks/useCurrentView';
import {
  MIN_VISIBLE_NAV_ITEMS,
  SIDEBAR_NAV_ITEMS,
  type SidebarNavId,
  type SidebarNavItem,
} from '@/lib/sidebar/navItems';
import {
  SIDEBAR_NAV_ROW_ICON_CLASS,
  SIDEBAR_NAV_ROW_LABEL_CLASS,
  sidebarNavRowClass,
} from '@/lib/sidebar/navRowStyles';
import { useQuickOpenShortcutLabel } from '@/lib/sidebar/quickOpenShortcut';
import { useSidebarNavPreferences } from '@/lib/stores/sidebar-nav-store';
import { useMobileDetection } from '@/hooks/useMobileDetection';

interface SidebarMoreMenuProps {
  /**
   * Draw the trigger as one icon for the collapsed rail instead of a full row.
   *
   * The sidebar spends a lot of its life collapsed - it is the state a page
   * like the agenda opens in - and pages you can only reach by first expanding
   * the panel are pages most people never find.
   */
  collapsed?: boolean;
  /**
   * How this surface navigates. Routing (and, on the rail, analytics) stays
   * with the surface, so a page opened from this menu behaves exactly like the
   * same page opened from a sidebar row. Falls back to the app's guarded
   * navigation when no surface supplies one.
   */
  onNavigate?: (item: SidebarNavItem) => void;
  /**
   * The view the app is on, so a page listed here reads as active exactly as it
   * would in a sidebar row.
   *
   * It matters more here than it looks: Applications is out of the sidebar by
   * default now, so for a mainstream page the overflow list is the ONLY place
   * that can say "you are here". Without it, someone sitting on Applications
   * sees nothing anywhere in the sidebar telling them so.
   */
  currentView?: AppView;
}

/** Which half of the menu is showing. */
type MenuView = 'overflow' | 'customize';

/**
 * The sidebar's overflow menu: the "..." that ends the navigation block (or one
 * more icon on the collapsed rail).
 *
 * It answers two different questions with one control, in the order people ask
 * them. First: "where is the page I do not see?" - so the menu opens on the
 * pages the sidebar is not currently showing, as ordinary destinations one
 * click away. Only then: "why is it not there, and can I change that?" - which
 * is the Customize button at the bottom, leading to the panel that picks which
 * pages the sidebar draws and where the home quick-open shortcut points.
 *
 * The two live behind one trigger rather than two because they are the same
 * subject seen twice: the overflow list IS the set of pages the customize panel
 * has unticked. Splitting them would put "go there once" and "put it back" in
 * different places.
 *
 * The quick-open destination is set in that same panel for the same reason:
 * the shortcut is another way into the very pages listed here, so splitting it
 * off would mean setting one thing in two places.
 */
export function SidebarMoreMenu({ collapsed = false, onNavigate, currentView }: SidebarMoreMenuProps = {}) {
  const t = useTranslations('sidebar');
  // On a phone the sidebar is a 256px drawer pinned to the left edge, so a menu
  // opening to its RIGHT has 150-odd pixels to live in and nowhere to flip to.
  // Below the row it gets the whole width of the screen instead.
  const isMobile = useMobileDetection();
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<MenuView>('overflow');
  const shortcutLabel = useQuickOpenShortcutLabel();
  const safeNavigate = useSafeNavigate();
  const viewRef = useRef<HTMLDivElement>(null);
  const { hiddenNavIds, quickOpenNavId, toggleNavItem, setQuickOpenNavId, resetNavPreferences, atMinimum } =
    useSidebarNavPreferences();

  // The pages the sidebar is not showing, in the panel's own order so the menu
  // never re-sorts them under the reader.
  const overflowItems = useMemo(
    () => SIDEBAR_NAV_ITEMS.filter((item) => hiddenNavIds.includes(item.id)),
    [hiddenNavIds],
  );

  // The live page can be one the sidebar is not drawing (Applications is, by
  // default), and then no row and no rail icon can carry its active state. The
  // trigger stands in for it, so the resting sidebar still says where you are.
  const holdsActivePage = overflowItems.some((item) => item.view === currentView);

  const handleOpenChange = useCallback(
    (next: boolean) => {
      setOpen(next);
      // Reopening always starts at the destinations, which is what this control
      // is mostly for - unless there are none, in which case the first view
      // would be a menu holding a single button.
      //
      // This has to STICK rather than be derived from the list: someone who
      // opened onto the panel and unticks a page has just put something in the
      // overflow list, and deriving the view would yank them out of the panel
      // they are still using.
      if (next) setView(overflowItems.length > 0 ? 'overflow' : 'customize');
    },
    [overflowItems.length],
  );

  /**
   * The view actually drawn.
   *
   * The list can empty under the reader while it is the view being shown, and
   * an empty list plus a lone Customize button is exactly what opening onto the
   * panel avoids. Derived rather than corrected in an effect: no frame where
   * the empty list is on screen, and no setState cascade to reason about.
   */
  const shownView: MenuView = view === 'overflow' && overflowItems.length === 0 ? 'customize' : view;

  // Switching view swaps the whole content of the popover, which unmounts the
  // button that was just pressed. Without this, focus falls back to <body> and
  // a keyboard user is dropped out of the menu mid-task; Radix returns focus to
  // the trigger only when the popover CLOSES, which is not what happened here.
  //
  // Deliberately NOT on the first open: Radix already focuses its own content
  // then, and stealing that focus would skip the announcement the dialog makes
  // on opening. Only a switch between the two views needs correcting.
  const focusedView = useRef<MenuView | null>(null);
  useEffect(() => {
    if (!open) {
      focusedView.current = null;
      return;
    }
    const previous = focusedView.current;
    focusedView.current = shownView;
    if (previous !== null && previous !== shownView) viewRef.current?.focus();
  }, [open, shownView]);

  // Ticking a box is an act performed IN the panel, so it settles the view there.
  // Without this the reader could be shown the panel by derivation alone (their
  // stored view still being the list), and refilling the list by unticking would
  // snap them back out of the panel they were using.
  const togglePage = useCallback(
    (id: SidebarNavId) => {
      setView('customize');
      toggleNavItem(id);
    },
    [toggleNavItem],
  );

  const goTo = useCallback(
    (item: SidebarNavItem) => {
      // Through the same handler the trigger uses, so anything opening and
      // closing has to do stays true of this path too.
      handleOpenChange(false);
      if (onNavigate) {
        onNavigate(item);
        return;
      }
      safeNavigate(item.path);
    },
    [handleOpenChange, onNavigate, safeNavigate],
  );

  const trigger = collapsed ? (
    <Button
      // Same treatment as NavIconButton, the rail's own entry: `ghost` (not
      // `ghostGray`, which pins nested svgs to the button colour), a 32px box
      // and a 16px icon that warms on hover.
      variant="ghost"
      size="icon"
      data-testid="sidebar-more-trigger"
      // Only while the menu is shut: with it open the row inside announces the
      // same page, and both would say "current page" within one navigation.
      aria-current={holdsActivePage && !open ? 'page' : undefined}
      className={`group w-8 h-8 hover:bg-surface-hover hover:text-theme-primary ${
        holdsActivePage ? 'bg-surface-hover' : ''
      }`}
      title={t('more.title')}
      // Both, unlike NavIconButton which leans on `title` alone: `title` gives
      // the hover tooltip an icon-only control needs, and `aria-label` gives it
      // a name that does not depend on a tooltip being exposed to the AT.
      aria-label={t('more.title')}
    >
      {/* `group-[.bg-surface-hover]:` is how every other entry warms its icon when
          active - NavIconButton and the panel rows both carry it. Without it the
          trigger paints the active background and keeps a secondary icon, i.e.
          wears the state differently from its own neighbours. */}
      <MoreHorizontal className="w-4 h-4 shrink-0 text-theme-secondary transition-colors group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary" />
    </Button>
  ) : (
    // A navigation ROW, drawn from the same strings as the rows above it rather
    // than approximated with a <Button>. The Button base carries `gap-2` on top
    // of the icon's own `mr-2`, which put this label 8px right of "Files" and
    // "Tables"; a plain button on the shared class list cannot drift that way.
    <button
      type="button"
      data-testid="sidebar-more-trigger"
      aria-current={holdsActivePage && !open ? 'page' : undefined}
      className={sidebarNavRowClass(holdsActivePage)}
      // No `aria-label` and no `title`: the visible text below already names
      // this row, and repeating it would only make a named row announce itself
      // twice and show a tooltip of its own label.
    >
      <MoreHorizontal className={SIDEBAR_NAV_ROW_ICON_CLASS} />
      <span className={SIDEBAR_NAV_ROW_LABEL_CLASS}>{t('more.title')}</span>
    </button>
  );

  const menu = (
    <Popover open={open} onOpenChange={handleOpenChange}>
          <PopoverTrigger asChild>{trigger}</PopoverTrigger>

          <PopoverContent
            align="start"
            side={isMobile ? 'bottom' : 'right'}
            sideOffset={8}
            // The name goes HERE, on Radix's `role="dialog"`, and follows the
            // view. An `aria-label` on the plain divs below would sit on
            // `role=generic`, where naming from the author is not allowed and
            // whether anything reads it is browser-dependent.
            aria-label={shownView === 'overflow' ? t('more.title') : t('customize.title')}
            className="w-72 max-h-[70vh] overflow-y-auto p-3 rounded-2xl border border-gray-300/70 dark:border-gray-600/70"
          >
            {shownView === 'overflow' ? (
              <div
                ref={viewRef}
                tabIndex={-1}
                role="group"
                // Named as well as the dialog above it, which does repeat the
                // word on entry. The focus target of a view switch has to carry
                // a name of its own, or the switch lands somewhere anonymous.
                aria-label={t('more.title')}
                className="space-y-1 outline-none"
                data-testid="sidebar-more-overflow"
              >
                <div className="space-y-0.5">
                  {overflowItems.map((item) => {
                    const Icon = item.icon;
                    const label = t(`nav.${item.titleKey}`);
                    const isActive = currentView === item.view;
                    return (
                      <button
                        key={item.id}
                        type="button"
                        data-testid={`sidebar-more-page-${item.id}`}
                        aria-current={isActive ? 'page' : undefined}
                        onClick={() => goTo(item)}
                        className={`flex w-full cursor-pointer items-center gap-3 rounded-xl px-2 py-2 text-left transition-colors ${
                          isActive ? 'bg-surface-hover' : 'hover:bg-surface-hover'
                        }`}
                      >
                        <Icon
                          className={`h-4 w-4 shrink-0 ${isActive ? 'text-theme-primary' : 'text-theme-secondary'}`}
                        />
                        <span className={`text-sm text-theme-primary ${isActive ? 'font-medium' : ''}`}>
                          {label}
                        </span>
                      </button>
                    );
                  })}
                </div>

                <div className="mt-2 border-t border-theme pt-2">
                  {/* The menu's three actions (this one, Back and Reset) are
                      ordinary `outline` buttons, the shape used across the app,
                      and keep their variant's own `--text-primary`. They were
                      `ghostGray` overridden to `text-theme-secondary`: grey
                      label, no border, nothing to aim at, so the one control
                      that opens the panel read as a caption rather than as the
                      button it is. */}
                  <Button
                    variant="outline"
                    size="sm"
                    data-testid="sidebar-more-customize"
                    className="w-full justify-start"
                    onClick={() => setView('customize')}
                  >
                    <SlidersHorizontal className="h-3.5 w-3.5" />
                    <span className="text-sm">{t('customize.title')}</span>
                  </Button>
                </div>
              </div>
            ) : (
              <div
                ref={viewRef}
                tabIndex={-1}
                role="group"
                aria-label={t('customize.title')}
                className="space-y-1 outline-none"
                data-testid="sidebar-customize-panel"
              >
                {overflowItems.length > 0 && (
                  // Only when there is somewhere to go back TO. Having opened
                  // straight onto this panel because overflow was empty, a
                  // "back" to that empty list would be a dead end.
                  <Button
                    variant="outline"
                    size="sm"
                    data-testid="sidebar-more-back"
                    className="mb-1 w-full justify-start"
                    onClick={() => setView('overflow')}
                  >
                    <ChevronLeft className="h-3.5 w-3.5" />
                    <span className="text-sm">{t('more.back')}</span>
                  </Button>
                )}

                <p className="px-1 text-sm font-medium text-theme-primary">{t('customize.pagesTitle')}</p>
                <p className="px-1 text-sm text-theme-muted">
                  {t('customize.pagesHint', { count: MIN_VISIBLE_NAV_ITEMS })}
                </p>

                <div className="mt-2 space-y-0.5">
                  {SIDEBAR_NAV_ITEMS.map((item) => {
                    const checked = !hiddenNavIds.includes(item.id);
                    // The last entries standing cannot be unticked: hiding them
                    // would take the sidebar under its floor. They stay ticked and
                    // readable rather than disappearing from the menu.
                    const locked = checked && atMinimum;
                    const Icon = item.icon;
                    const label = t(`nav.${item.titleKey}`);
                    return (
                      <div
                        key={item.id}
                        // The whole row toggles, not just the 16px box: the page name is
                        // what people aim at. A <label htmlFor> would not do it - the
                        // control underneath is a button (Radix), and forwarding a label
                        // click to a button is browser-dependent, so it did nothing in
                        // some browsers and toggled TWICE in the ones that do forward.
                        onClick={(event) => {
                          // A click that landed on the box is already handled by
                          // onCheckedChange; toggling here too would cancel it out.
                          if ((event.target as HTMLElement).closest('[role="checkbox"]')) return;
                          if (locked) return;
                          togglePage(item.id);
                        }}
                        className={`flex items-center gap-3 rounded-xl px-2 py-2 transition-colors ${
                          locked ? 'cursor-not-allowed opacity-60' : 'cursor-pointer hover:bg-surface-hover'
                        }`}
                        title={locked ? t('customize.minimumReached', { count: MIN_VISIBLE_NAV_ITEMS }) : undefined}
                      >
                        <Checkbox
                          id={`sidebar-nav-${item.id}`}
                          aria-label={label}
                          checked={checked}
                          disabled={locked}
                          onCheckedChange={() => togglePage(item.id)}
                        />
                        <Icon className="h-4 w-4 shrink-0 text-theme-secondary" />
                        <span className="text-sm text-theme-primary">{label}</span>
                      </div>
                    );
                  })}
                </div>

                <div className="my-3 border-t border-theme" />

                <div className="flex items-center justify-between gap-2 px-1">
                  <p className="text-sm font-medium text-theme-primary">{t('customize.quickOpenTitle')}</p>
                  {/* The same jump without the mouse. Spelled out here because the
                      button that carries it only shows on the home page, while
                      these keys work from anywhere. */}
                  <Kbd data-testid="sidebar-customize-shortcut">
                    <span className="sr-only">{t('customize.shortcutAria')}: </span>
                    {shortcutLabel}
                  </Kbd>
                </div>
                <p className="px-1 text-sm text-theme-muted">{t('customize.quickOpenHint')}</p>

                <RadioGroup
                  value={quickOpenNavId}
                  onValueChange={(value) => setQuickOpenNavId(value as typeof quickOpenNavId)}
                  className="mt-2 gap-0.5"
                >
                  {SIDEBAR_NAV_ITEMS.map((item) => {
                    const Icon = item.icon;
                    const label = t(`nav.${item.titleKey}`);
                    return (
                      <div
                        key={item.id}
                        // Whole row selects - same reason as the checkboxes above.
                        onClick={(event) => {
                          if ((event.target as HTMLElement).closest('[role="radio"]')) return;
                          setQuickOpenNavId(item.id);
                        }}
                        className="flex cursor-pointer items-center gap-3 rounded-xl px-2 py-2 transition-colors hover:bg-surface-hover"
                      >
                        <RadioGroupItem id={`sidebar-quick-open-${item.id}`} aria-label={label} value={item.id} />
                        <Icon className="h-4 w-4 shrink-0 text-theme-secondary" />
                        <span className="text-sm text-theme-primary">{label}</span>
                      </div>
                    );
                  })}
                </RadioGroup>

                <div className="mt-3 border-t border-theme pt-2">
                  <Button
                    variant="outline"
                    size="sm"
                    data-testid="sidebar-more-reset"
                    className="w-full justify-start"
                    onClick={() => {
                      // Same reason as `togglePage`: this refills the overflow
                      // list, and without settling the view the derivation
                      // would snap the reader out of the panel they are in.
                      setView('customize');
                      resetNavPreferences();
                    }}
                  >
                    <RotateCcw className="h-3.5 w-3.5" />
                    <span className="text-sm">{t('customize.reset')}</span>
                  </Button>
                </div>
              </div>
            )}
          </PopoverContent>
    </Popover>
  );

  // The rail places its own icons; only the expanded panel needs the row
  // wrapper that lines this control up with the navigation rows above it.
  if (collapsed) return menu;
  return (
    <div className="flex-shrink-0">
      <div className="flex items-center px-4">{menu}</div>
    </div>
  );
}
