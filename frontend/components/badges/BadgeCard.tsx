'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { badgeProgress, type Badge } from '@/lib/api/orchestrator/badges.service';
import { getClientLocale } from '@/lib/utils/locale';
import { BadgeMedal } from './BadgeMedal';

export interface BadgeCardProps {
  badge: Badge;
  /** Opens the trophy's detail card. */
  onSelect?: (badge: Badge) => void;
  className?: string;
}

/**
 * One trophy on the wall: the medal, its name, and one short line.
 *
 * <p><b>No card.</b> Fifty framed tiles read as a spreadsheet, and the frame was
 * doing nothing the medal was not already doing better: the metal, the halo and
 * the desaturation of a locked medal separate earned from unearned on their own.
 * So there is no border, no panel, no background here, and the trophies sit
 * directly on the page the way they would on a shelf.
 *
 * <p>What the frame used to carry moves one click away. The rule ("create 10
 * workflows") lived under every tile because a rule hidden behind a HOVER is a
 * rule nobody can chase; it now lives in the detail card, which the whole medal
 * opens, and which has room to spell it out along with the family's other rungs.
 * What stays on the wall is only what is legible at a glance: the name, and
 * either the rank or how far along you are.
 */
export function BadgeCard({ badge, onSelect, className }: BadgeCardProps) {
  const t = useTranslations('badges');
  const locale = getClientLocale();
  const progress = badgeProgress(badge);

  return (
    <button
      type="button"
      onClick={() => onSelect?.(badge)}
      aria-label={t(`item.${badge.code}.name`)}
      className={cn(
        'group flex flex-col items-center gap-2 rounded-xl p-1 text-center',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]',
        className,
      )}
    >
      <BadgeMedal
        family={badge.family}
        tier={badge.tier}
        unlocked={badge.unlocked}
        progress={progress}
        size={80}
        className="transition-transform duration-200 group-hover:scale-105"
      />

      <div className="min-w-0 w-full">
        <p
          className={cn(
            'truncate text-sm font-medium transition-colors',
            badge.unlocked ? 'text-[var(--text-primary)]' : 'text-theme-secondary',
          )}
          title={t(`item.${badge.code}.name`)}
        >
          {t(`item.${badge.code}.name`)}
        </p>
        <p className="mt-0.5 text-xs tabular-nums text-theme-muted">
          {badge.unlocked
            ? t(`tier.${badge.tier}`)
            : t('progress', {
                // The app locale, never the browser's: a French browser reading
                // the /en app must still see English grouping.
                value: badge.value.toLocaleString(locale),
                threshold: badge.threshold.toLocaleString(locale),
              })}
        </p>
      </div>
    </button>
  );
}
