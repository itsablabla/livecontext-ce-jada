'use client';

import { useTranslations } from 'next-intl';
import { Lock } from 'lucide-react';

/** What the row is, shown as the header's right-hand badge. */
export type PaletteItemBadge = 'trigger' | 'integration' | 'category' | 'node';

export interface PaletteItemTooltipProps {
  /** The row's name, repeated in full: the row itself truncates it. */
  label: string;
  /** The row's description, in full: the row clamps it to two lines. */
  description?: string | null;
  /** Free-form extra fact the row shows in place of a description (e.g. "5 tools"). */
  secondaryInfo?: string | null;
  /** What kind of thing the row is. */
  badge: PaletteItemBadge;
  /** The plan the account is missing, when the row is marked as locked. */
  lockedPlan?: string | null;
  /** Does clicking the row open a sub-list instead of adding a node? */
  opensList: boolean;
  /** Can the row be dragged onto the canvas? */
  draggable: boolean;
}

/**
 * Hover card for a palette row in the "Add node" panel.
 *
 * Deliberately the same card the Run tab shows when hovering a step
 * ({@link ../../../../../components/workflow/run-panel/StepTooltipContent}):
 * a bordered header carrying the name and a right-aligned badge, then
 * separated label/value rows, all at `text-xs` inside a 260-320px box. The two
 * panels sit in the same dock and are hovered in the same gesture, so they read
 * as one surface rather than two tooltip styles.
 *
 * What it adds over the row itself: the untruncated name and description, and
 * an explicit statement of what clicking and dragging do, which the palette
 * otherwise only implies through a chevron and a grip that appears on hover.
 */
export function PaletteItemTooltipContent({
  label,
  description,
  secondaryInfo,
  badge,
  lockedPlan,
  opensList,
  draggable,
}: PaletteItemTooltipProps) {
  const t = useTranslations('workflowBuilder.canvas.paletteTooltip');

  const badgeLabel = t(
    badge === 'trigger' ? 'badgeTrigger'
      : badge === 'integration' ? 'badgeIntegration'
        : badge === 'category' ? 'badgeCategory'
          : 'badgeNode',
  );

  return (
    <div className="flex flex-col gap-2 text-xs min-w-[260px] max-w-[320px]">
      {/* Header: name + what the row is */}
      <div className="flex items-start justify-between gap-3 border-b border-gray-100 dark:border-gray-700 pb-1.5">
        <span className="font-semibold text-gray-900 dark:text-gray-100 break-words flex-1 min-w-0">
          {label}
        </span>
        {/* Carries the resolved kind as data, not only as translated text: the
            badge sits next to a name that can contain the same word ("Triggers"
            / "Trigger"), so a test reading the card as a whole cannot tell them
            apart, and the label changes with the locale. */}
        <span data-palette-badge={badge} className="font-medium shrink-0 text-gray-500 dark:text-gray-400">
          {badgeLabel}
        </span>
      </div>

      {/* Description - prose, so it spans the card rather than sitting in a value column */}
      {description && (
        <p className="text-gray-600 dark:text-gray-300 leading-relaxed break-words whitespace-pre-line">
          {description}
        </p>
      )}

      {/* Whatever extra fact the row carries in place of a description */}
      {secondaryInfo && (
        <div className="flex items-center justify-between gap-3">
          <span className="text-gray-500 dark:text-gray-400">{t('details')}</span>
          <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
            {secondaryInfo}
          </span>
        </div>
      )}

      {/* The plan gate. The row only shows a padlock, so the card is where the
          plan is actually named. */}
      {lockedPlan && (
        <div className="flex items-center gap-1.5 border-t border-gray-100 dark:border-gray-700 pt-1.5 font-medium text-amber-600 dark:text-amber-400">
          <Lock className="w-3 h-3 shrink-0" />
          <span className="break-words">{t('requiresPlan', { plan: lockedPlan })}</span>
        </div>
      )}

      {/* What the two gestures do. Composed from the row's real capabilities, not
          assumed: an integration row both opens its tool list AND drags onto the
          canvas as a whole, while a category row only opens. */}
      <div className="flex items-center justify-between gap-3 border-t border-gray-100 dark:border-gray-700 pt-1.5 text-gray-500 dark:text-gray-400">
        <span>{opensList ? t('hintClickBrowse') : t('hintClickAdd')}</span>
        {draggable && <span className="shrink-0">{t('hintDrag')}</span>}
      </div>
    </div>
  );
}

export default PaletteItemTooltipContent;
