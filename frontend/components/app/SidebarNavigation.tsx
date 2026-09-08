'use client';

import React, { memo, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { NavIconButton } from '@/components/app/NavIconButton';
import { SidebarMoreMenu } from '@/components/app/SidebarMoreMenu';
import type { AppView } from '@/hooks/useCurrentView';
import type { SidebarNavItem } from '@/lib/sidebar/navItems';
import {
  SIDEBAR_NAV_ROW_ICON_CLASS,
  SIDEBAR_NAV_ROW_LABEL_CLASS,
  sidebarNavRowClass,
} from '@/lib/sidebar/navRowStyles';
import { useSidebarNavEntries, type SidebarNavEntry } from '@/lib/sidebar/useSidebarNavEntries';
import { track } from '@/lib/analytics/analytics';

/**
 * Which shape the navigation takes. NOT which entries it holds - that is the
 * same list either way, and keeping the two words apart is the point of this
 * component.
 *
 *  - `rail`  : the 64px collapsed column, one 32px icon per entry.
 *  - `panel` : the 256px expanded column, one icon+label row per entry.
 */
export type SidebarNavVariant = 'rail' | 'panel';

interface SidebarNavigationProps {
  variant: SidebarNavVariant;
  /**
   * Whether THIS shape is the one the user is looking at.
   *
   * Both shapes stay mounted: the rail is hidden by opacity so it can fade, and
   * the panel keeps its layout wrapper so the block below does not jump. So
   * "mounted" is not "live", and the overflow menu - a single control, not a
   * per-shape decoration - is mounted by the live one only. Passing the real
   * state and letting the component decide is what stops a caller from putting
   * two identical "..." triggers on the page.
   */
  active: boolean;
  /** The conversation the main surface is on, so the new-chat entry knows it is not it. */
  currentConversationId?: string | null;
  /** Open a fresh conversation. The one entry that is an action, not a destination. */
  onNewChat: () => void;
  /** Go to a page. Routing (and the navigation guard) stays with the shell. */
  onNavigate: (path: string) => void;
  /**
   * `panel` only: the Projects section, which lives INSIDE the height-capped
   * block, between Marketplace and the page rows.
   *
   * A slot rather than a child component of its own because that block is a
   * single scroll region: projects and pages share one 40% cap and one
   * scrollbar. Handing the section in keeps this component the only thing that
   * knows the block's shape, while its content stays where it belongs (the
   * conversation sidebar owns projects).
   */
  projectsSlot?: React.ReactNode;
}

/**
 * THE sidebar navigation. One component, both states of the sidebar.
 *
 * It used to be two: an icon rail written in the shell and eight row blocks
 * written in the conversation sidebar. They were given one list to read
 * (`SIDEBAR_NAV_ITEMS`) which stopped them listing different PAGES, but they
 * still each decided what was active and each reported clicks their own way -
 * so /app/studio lit the rail's home icon and not the panel's row, and a click
 * on "Agents" was counted from the rail and not from the panel. Those are the
 * same two entries behaving differently depending on whether the sidebar was
 * open, which is the thing this component exists to make impossible.
 *
 * It is MOUNTED twice, once per shape, because the layout genuinely has two
 * slots: the rail sits in the sidebar header, the rows sit in the scroll region
 * below it. That is a placement detail. Everything a reader could notice -
 * which entries, in which order, which one is lit, what a click does and what it
 * reports - is computed once, here, and shared.
 */
export const SidebarNavigation = memo(function SidebarNavigation({
  variant,
  active,
  currentConversationId,
  onNewChat,
  onNavigate,
  projectsSlot,
}: SidebarNavigationProps) {
  const t = useTranslations('sidebar');
  const { currentView, newChat, marketplace, pages } = useSidebarNavEntries(currentConversationId);

  // One navigation path for every entry, whatever shape drew it, INCLUDING the
  // ones reached through the overflow menu. The rail used to report these and
  // the panel did not, which quietly halved the only signal saying which pages
  // people actually use - and that signal is what the sidebar's own defaults
  // (which pages start hidden) are meant to be based on.
  const goTo = useCallback(
    (path: string, view: AppView) => {
      onNavigate(path);
      track('nav_item_clicked', { to_view: view });
    },
    [onNavigate],
  );

  const handleMoreNavigate = useCallback(
    (item: SidebarNavItem) => goTo(item.path, item.view),
    [goTo],
  );

  /**
   * What pressing an entry does, whichever shape drew it.
   *
   * ONE stable callback for every entry rather than an arrow per entry: the
   * icon and row components below are memo()'d, and a fresh closure per render
   * would give each of them a new prop every time this shell re-renders, so not
   * one of them would ever skip.
   */
  const handleSelect = useCallback(
    (entry: SidebarNavEntry) => {
      if (entry.path && entry.view) {
        goTo(entry.path, entry.view);
        return;
      }
      onNewChat();
    },
    [goTo, onNewChat],
  );

  if (variant === 'rail') {
    return (
      <>
        {[newChat, marketplace, ...pages].map((entry) => (
          <SidebarNavIcon
            key={entry.key}
            entry={entry}
            label={t(`nav.${entry.titleKey}`)}
            onSelect={handleSelect}
          />
        ))}
        {/* The overflow menu, mounted by the live shape only - see `active`. */}
        {active && <SidebarMoreMenu collapsed currentView={currentView} onNavigate={handleMoreNavigate} />}
      </>
    );
  }

  return (
    <>
      {/* New chat and Marketplace: fixed above the block, so they never scroll
          away. Not customizable either - see SIDEBAR_NAV_ITEMS. */}
      {active && [newChat, marketplace].map((entry) => (
        <SidebarNavRow
          key={entry.key}
          entry={entry}
          label={t(`nav.${entry.titleKey}`)}
          onSelect={handleSelect}
        />
      ))}

      {/* Navigation block (Projects ... More): height-capped at 40% of the
          sidebar with its own scrollbar, so the Chats list below always stays
          visible. The wrapper is a pure layout container with no `active`
          guard: when the sidebar is collapsed it is simply empty (0 height),
          which keeps the column's box model identical in both states. */}
      <div className="flex-shrink-0 max-h-[40%] overflow-y-auto sidebar-scroll">
        {active && projectsSlot}
        {active && pages.map((entry) => (
          <SidebarNavRow
            key={entry.key}
            entry={entry}
            label={t(`nav.${entry.titleKey}`)}
            onSelect={handleSelect}
          />
        ))}
        {/* The pages the rows above are NOT showing, and behind them the panel
            that picks which pages those are. Last row of the block so it reads
            as the block's own control rather than another destination. */}
        {active && <SidebarMoreMenu currentView={currentView} onNavigate={handleMoreNavigate} />}
      </div>
    </>
  );
});

interface SidebarNavEntryProps {
  entry: SidebarNavEntry;
  label: string;
  onSelect: (entry: SidebarNavEntry) => void;
}

/** One collapsed-rail entry: a 32px icon button, no label. */
const SidebarNavIcon = memo(function SidebarNavIcon({ entry, label, onSelect }: SidebarNavEntryProps) {
  return (
    <NavIconButton
      icon={entry.icon}
      title={label}
      isActive={entry.isActive}
      onClick={(e) => {
        // The rail sits inside the sidebar header, which has click handlers of
        // its own (the logo, the collapse toggle).
        e.stopPropagation();
        onSelect(entry);
      }}
    />
  );
});

/**
 * One expanded row: the icon, the label, and the row's own hover/active
 * surface. Every visual decision lives in `navRowStyles`, shared with the
 * overflow row so a row cannot be styled ALMOST like a row.
 */
const SidebarNavRow = memo(function SidebarNavRow({ entry, label, onSelect }: SidebarNavEntryProps) {
  return (
    <div className="flex-shrink-0">
      <div className="">
        <div className="flex items-center px-4">
          <button
            // Announced, not only tinted. The rail's icons and the overflow
            // trigger both say "current page"; a row that only changed colour
            // would leave a screen-reader user told where they are on some
            // surfaces and not others.
            aria-current={entry.isActive ? 'page' : undefined}
            onClick={() => onSelect(entry)}
            className={sidebarNavRowClass(entry.isActive)}
          >
            <entry.icon className={SIDEBAR_NAV_ROW_ICON_CLASS} />
            <h2 className={SIDEBAR_NAV_ROW_LABEL_CLASS}>{label}</h2>
          </button>
        </div>
      </div>
    </div>
  );
});
