import React from 'react';
import { BadgeMedal } from './BadgeMedal';
import type { PublicBadge } from '@/lib/marketplace/publicBadges';

export interface PublicBadgeShowcaseProps {
  badges: PublicBadge[];
  /** Resolved trophy name per code - the page passes the reference-locale label. */
  nameFor: (code: string) => string;
  heading: string;
}

/**
 * The trophy wall on a public author page.
 *
 * <p>A server component with no hooks, so the medals are in the HTML a crawler
 * (and a JS-less visitor) receives. That is also why it stays a flat wall with
 * no dialog: this page renders outside the app's next-intl provider, and a
 * click-to-open card here would need a client bundle for something a visitor
 * reads once.
 *
 * <p>Renders nothing at all when the author has no trophies yet: an empty "no
 * trophies" box on a stranger's profile is noise, unlike the signed-in grid
 * where locked badges are the point.
 */
export function PublicBadgeShowcase({ badges, nameFor, heading }: PublicBadgeShowcaseProps) {
  if (badges.length === 0) return null;

  return (
    <section className="mb-10">
      <div className="mb-3 flex items-baseline gap-2">
        <h2 className="text-lg font-semibold text-[var(--text-primary)]">{heading}</h2>
        <span className="text-sm tabular-nums text-[var(--text-muted)]">{badges.length}</span>
      </div>
      {/* No panel. The medals carry themselves, and a framed box here would be
          the one card left on a wall that no longer has any. */}
      <div className="flex flex-wrap gap-x-6 gap-y-5">
        {badges.map((badge) => (
          <div
            key={badge.code}
            className="flex w-20 flex-col items-center gap-1.5 text-center"
            title={nameFor(badge.code)}
          >
            <BadgeMedal
              family={badge.family}
              tier={badge.tier}
              unlocked
              size={64}
              idPrefix="profile"
            />
            <span className="line-clamp-2 text-xs leading-tight text-[var(--text-secondary)]">
              {nameFor(badge.code)}
            </span>
          </div>
        ))}
      </div>
    </section>
  );
}
