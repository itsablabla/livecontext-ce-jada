'use client';

import React from 'react';
import { X } from 'lucide-react';
import { useTranslations } from 'next-intl';

/**
 * Floating bulk-selection action bar - a centered pill pinned to the bottom of
 * the content area, shown while one or more cards/rows are selected. Mirrors the
 * task board's BulkActionBar (TaskBoardPage) so selection actions float at the
 * bottom instead of pushing the page down with an inline toolbar.
 *
 * Positioning: `absolute bottom-6 left-1/2 -translate-x-1/2`. The bar anchors to
 * the nearest positioned ancestor. On a standalone full page the /app layout's
 * <main> is `relative`, so it centers in the content area (between the sidebar and
 * any open side panel). Embedded (side panel / modal / builder inspector) the host
 * container is made `relative` (see DataTable's embedded root), so the bar stays
 * INSIDE that panel instead of floating over the whole app. Either way it stays put
 * while the page scrolls - no `fixed` viewport offset.
 */

const BAR_BUTTON_BASE =
  'inline-flex items-center gap-1.5 h-7 px-2.5 rounded-md text-xs transition-colors disabled:opacity-50';
// The bar is a high-contrast neutral surface (black in light theme, white in dark theme), matching the
// app's neutral badges, so its contents are white in light theme / black in dark theme to stay readable.
const BAR_BUTTON_NEUTRAL =
  'text-white/80 dark:text-black/80 hover:text-white dark:hover:text-black hover:bg-white/10 dark:hover:bg-black/10';
// Danger red inverts vs. the bar bg: lighter red on the black (light-theme) bar, darker red on the white (dark-theme) bar.
const BAR_BUTTON_DANGER = 'text-red-400 dark:text-red-600 hover:bg-red-500/15';

export type BulkBarButtonVariant = 'neutral' | 'danger';

/** Class string for a bar button - exposed so callers can style non-button elements consistently. */
export function bulkBarButtonClass(variant: BulkBarButtonVariant = 'neutral'): string {
  return `${BAR_BUTTON_BASE} ${variant === 'danger' ? BAR_BUTTON_DANGER : BAR_BUTTON_NEUTRAL}`;
}

interface BulkBarButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: BulkBarButtonVariant;
}

/** A small pill button styled to sit inside the SelectionActionBar. */
export function BulkBarButton({ variant = 'neutral', className = '', children, ...rest }: BulkBarButtonProps) {
  return (
    <button type="button" className={`${bulkBarButtonClass(variant)} ${className}`.trim()} {...rest}>
      {children}
    </button>
  );
}

interface SelectionActionBarProps {
  /** Number of selected items - rendered as "{count} selected". */
  count: number;
  /** Clears the selection (the trailing × button). */
  onClear: () => void;
  /** Action buttons - typically <BulkBarButton> elements. */
  children: React.ReactNode;
  /** data-testid for the bar wrapper (defaults to "selection-action-bar"). */
  testId?: string;
}

export function SelectionActionBar({ count, onClear, children, testId = 'selection-action-bar' }: SelectionActionBarProps) {
  const t = useTranslations('common');
  return (
    <div
      data-testid={testId}
      // rounded-2xl, not rounded-full: a floating card takes one radius step above the
      // controls it holds (its buttons are rounded-md), the same ladder the canvas
      // chrome uses. A pill container around square buttons is what made this read as
      // a leftover from the round era.
      // `max-w` + `flex-wrap`: the bar is centred on its container and holds five to
      // seven labelled buttons, so on a phone it used to hang off BOTH edges with
      // no way to reach the ones at the ends. It now folds onto a second line
      // inside its container instead. The cap is `100%`, not `100vw`: the bar is
      // absolutely positioned inside the content area, which is narrower than the
      // window whenever the sidebar is open.
      className="absolute bottom-6 left-1/2 -translate-x-1/2 z-40 flex max-w-[calc(100%-1rem)] flex-wrap items-center justify-center gap-1.5 rounded-2xl border border-black/10 dark:border-white/15 bg-black dark:bg-white text-white dark:text-black shadow-lg px-3 py-2"
    >
      <span className="text-sm font-medium text-white dark:text-black tabular-nums px-1">
        {t('selectedCount', { count })}
      </span>
      <div className="hidden h-4 w-px bg-white/20 dark:bg-black/20 sm:block" />
      {children}
      <div className="hidden h-4 w-px bg-white/20 dark:bg-black/20 sm:block" />
      <button
        type="button"
        data-testid="selection-action-bar-clear"
        onClick={onClear}
        title={t('clearSelection')}
        aria-label={t('clearSelection')}
        className="inline-flex items-center justify-center h-7 w-7 rounded-md text-white/60 dark:text-black/60 hover:text-white dark:hover:text-black hover:bg-white/10 dark:hover:bg-black/10 transition-colors"
      >
        <X className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}
