'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { getClientLocale } from '@/lib/utils/locale';
import { badgeProgress, type Badge } from '@/lib/api/orchestrator/badges.service';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { FAMILY_VISUALS } from './badgeVisuals';
import { BadgeMedal } from './BadgeMedal';

export interface BadgeDetailDialogProps {
  /** The badge to show; null closes the dialog. */
  badge: Badge | null;
  /**
   * Every badge of the same family, in catalog order. Draws the ladder strip so
   * a trophy is seen as one rung of a progression rather than a lone sticker.
   * Omit on surfaces that only hold part of the catalog (a public profile shows
   * unlocked badges only, where a partial ladder would misread as "the rest
   * does not exist").
   */
  siblings?: Badge[];
  onOpenChange: (open: boolean) => void;
  /** Jump to another rung from the ladder. Omit to make the ladder decorative. */
  onSelect?: (badge: Badge) => void;
}

/**
 * The trophy card, full size.
 *
 * <p>Everything the grid tile has to truncate gets room here: the medal at a
 * size where the metal and the silhouette actually read, the full requirement
 * sentence, and the exact standing (earned on a date, or how much is left).
 *
 * <p>The family ladder at the bottom is what makes the dialog worth opening
 * twice: it answers "and then what?", which no single tile can.
 */
export function BadgeDetailDialog({
  badge,
  siblings,
  onOpenChange,
  onSelect,
}: BadgeDetailDialogProps) {
  const t = useTranslations('badges');
  const locale = getClientLocale();

  if (!badge) return null;

  const progress = badgeProgress(badge);
  const remaining = Math.max(0, badge.threshold - badge.value);
  const requirement = t.has(`item.${badge.code}.requirement`)
    ? t(`item.${badge.code}.requirement`)
    : t(`requirement.${badge.metric}`, { count: badge.threshold });
  const accent = FAMILY_VISUALS[badge.family].accent;

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent
        className="max-w-md gap-0 overflow-hidden p-0"
        // The card IS the description; without this Radix warns about a missing
        // one on every open.
        aria-describedby={undefined}
      >
        {/* A wash of the family colour behind the medal. Two stops of the same
            accent the medal itself uses, at low alpha, so the card is tinted by
            the trophy rather than by a palette invented here. */}
        <div
          className="flex flex-col items-center px-6 pb-5 pt-8 text-center"
          style={{
            backgroundImage: `radial-gradient(120% 90% at 50% 0%, ${accent[0]}2e 0%, ${accent[1]}14 45%, transparent 78%)`,
          }}
        >
          <BadgeMedal
            family={badge.family}
            tier={badge.tier}
            unlocked={badge.unlocked}
            progress={progress}
            size={132}
            idPrefix="detail"
          />

          <DialogTitle className="mt-4 text-lg font-semibold text-[var(--text-primary)]">
            {t(`item.${badge.code}.name`)}
          </DialogTitle>

          <div className="mt-2 flex flex-wrap items-center justify-center gap-1.5">
            <Chip>{t(`family.${badge.family}`)}</Chip>
            <Chip>{t(`tier.${badge.tier}`)}</Chip>
          </div>
        </div>

        <div className="space-y-4 border-t border-theme px-6 py-5">
          <div>
            <p className="text-xs font-medium uppercase tracking-wide text-theme-muted">
              {t('detail.requirementLabel')}
            </p>
            <p className="mt-1 text-sm text-[var(--text-primary)]">{requirement}</p>
          </div>

          {badge.unlocked ? (
            <p className="text-sm text-theme-secondary">
              {t('unlockedOn', { date: formatUtcDate(badge.unlockedAt, { locale }) })}
            </p>
          ) : (
            <div>
              <div className="mb-1.5 flex items-baseline justify-between gap-3">
                <span className="text-sm tabular-nums text-theme-secondary">
                  {t('progress', {
                    value: badge.value.toLocaleString(locale),
                    threshold: badge.threshold.toLocaleString(locale),
                  })}
                </span>
                <span className="text-xs text-theme-muted">
                  {t('detail.remaining', { count: remaining.toLocaleString(locale) })}
                </span>
              </div>
              <div className="h-1.5 w-full overflow-hidden rounded-full bg-[var(--bg-tertiary)]">
                <div
                  className="h-full rounded-full bg-[var(--accent-primary)] transition-[width] duration-500"
                  style={{ width: `${Math.round(progress * 100)}%` }}
                />
              </div>
            </div>
          )}

          {siblings && siblings.length > 1 && (
            <div>
              <p className="mb-2 text-xs font-medium uppercase tracking-wide text-theme-muted">
                {t('detail.ladder')}
              </p>
              <div className="flex flex-wrap gap-3">
                {siblings.map((rung) => {
                  const current = rung.code === badge.code;
                  return (
                    <button
                      key={rung.code}
                      type="button"
                      // Without onSelect the ladder is a picture, so it must not
                      // advertise itself as a control to a screen reader either.
                      disabled={!onSelect}
                      onClick={() => onSelect?.(rung)}
                      title={t(`item.${rung.code}.name`)}
                      className={cn(
                        'rounded-xl p-1 transition-colors',
                        current && 'bg-theme-secondary',
                        onSelect && !current && 'hover:bg-theme-secondary',
                        !onSelect && 'cursor-default',
                      )}
                      aria-current={current ? 'true' : undefined}
                    >
                      <BadgeMedal
                        family={rung.family}
                        tier={rung.tier}
                        unlocked={rung.unlocked}
                        progress={badgeProgress(rung)}
                        size={36}
                        idPrefix="ladder"
                      />
                    </button>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

function Chip({ children }: { children: React.ReactNode }) {
  return (
    <span className="rounded-full border-[0.5px] border-theme bg-[var(--bg-primary)] px-2 py-0.5 text-xs text-theme-secondary">
      {children}
    </span>
  );
}
