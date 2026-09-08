import React from 'react';
import { Lock } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { BadgeFamily, BadgeTier } from '@/lib/api/orchestrator/badges.service';
import { FAMILY_VISUALS, TIER_VISUALS, shapeInnerPath, shapePath } from './badgeVisuals';

export interface BadgeMedalProps {
  family: BadgeFamily;
  tier: BadgeTier;
  unlocked: boolean;
  /** 0 to 1. Draws the ring on a locked medal; ignored once unlocked. */
  progress?: number;
  /** Rendered edge length in pixels. */
  size?: number;
  /**
   * Disambiguates the SVG gradient ids when the same medal is rendered twice on
   * one page. Ids only have to be unique per document, and two medals of the
   * same family+tier define identical gradients, so the default is safe - this
   * exists for the rare case where one page mixes sizes of the same medal.
   */
  idPrefix?: string;
  className?: string;
}

/**
 * One trophy medallion, drawn as inline SVG.
 *
 * <p>No hooks and no client-only APIs, so the same component renders in the
 * signed-in grid and in the server-rendered public profile. That matters:
 * the public page is crawled, and a client-only medal would leave a hole in
 * the HTML a crawler sees.
 *
 * <p>A locked medal is drawn exactly like an unlocked one and then drained of
 * colour, rather than replaced by a placeholder. The silhouette stays
 * recognisable, so the grid reads as "here is what you can earn" instead of a
 * wall of grey squares - and the progress ring, drawn OUTSIDE the desaturating
 * filter, keeps its accent colour so it is still legible.
 */
export function BadgeMedal({
  family,
  tier,
  unlocked,
  progress = 0,
  size = 88,
  idPrefix = 'badge',
  className,
}: BadgeMedalProps) {
  const familyVisual = FAMILY_VISUALS[family];
  const tierVisual = TIER_VISUALS[tier];
  const Icon = familyVisual.icon;

  const uid = `${idPrefix}-${family}-${tier}`.toLowerCase();
  const metalId = `${uid}-metal`;
  const accentId = `${uid}-accent`;
  const sheenId = `${uid}-sheen`;
  const glowId = `${uid}-glow`;

  const path = shapePath(familyVisual.shape);
  // A silhouette may carry its own inner contour when a uniform scale would not
  // trace its outline (see shapeInnerPath). Everything else insets by scaling
  // the outer path about the centre.
  const innerPath = shapeInnerPath(familyVisual.shape);
  const insetTransform = innerPath ? undefined : 'translate(50 50) scale(0.74) translate(-50 -50)';
  const clamped = Math.max(0, Math.min(1, progress));

  // Progress ring geometry: r=47 sits just outside every silhouette's
  // circumradius, so the ring never crosses the medal itself.
  const ringRadius = 47;
  const ringCircumference = 2 * Math.PI * ringRadius;

  return (
    <div
      className={cn('relative shrink-0 select-none', className)}
      style={{ width: size, height: size }}
    >
      <svg
        viewBox="0 0 100 100"
        className={cn(
          'absolute inset-0 h-full w-full transition-[filter,opacity] duration-300',
          unlocked ? 'drop-shadow-sm' : 'opacity-45 grayscale',
        )}
        aria-hidden="true"
      >
        <defs>
          <linearGradient id={metalId} x1="20%" y1="0%" x2="80%" y2="100%">
            <stop offset="0%" stopColor={tierVisual.metal[0]} />
            <stop offset="52%" stopColor={tierVisual.metal[1]} />
            <stop offset="100%" stopColor={tierVisual.metal[2]} />
          </linearGradient>
          <radialGradient id={accentId} cx="38%" cy="30%" r="78%">
            <stop offset="0%" stopColor={familyVisual.accent[0]} />
            <stop offset="100%" stopColor={familyVisual.accent[1]} />
          </radialGradient>
          {/* Diagonal sheen: opaque white on the upper-left half, transparent
              past the midpoint. This is what reads as "metal" rather than
              "flat coloured shape". */}
          <linearGradient id={sheenId} x1="0%" y1="0%" x2="70%" y2="100%">
            <stop offset="0%" stopColor="#ffffff" stopOpacity="0.55" />
            <stop offset="45%" stopColor="#ffffff" stopOpacity="0.08" />
            <stop offset="100%" stopColor="#ffffff" stopOpacity="0" />
          </linearGradient>
          <radialGradient id={glowId} cx="50%" cy="50%" r="50%">
            <stop offset="55%" stopColor={tierVisual.glow} stopOpacity={tierVisual.glowOpacity} />
            <stop offset="100%" stopColor={tierVisual.glow} stopOpacity="0" />
          </radialGradient>
        </defs>

        {/* Halo. Only unlocked medals glow - it is the cheapest way to make an
            earned trophy pop out of a mostly-locked grid. */}
        {unlocked && <circle cx="50" cy="50" r="50" fill={`url(#${glowId})`} />}

        {/* Medal body + rim. */}
        <path d={path} fill={`url(#${metalId})`} stroke={tierVisual.rim} strokeWidth="1.6" />
        {/* Inner disc carrying the family colour, inset so the metal reads as a frame. */}
        <path
          d={innerPath ?? path}
          fill={`url(#${accentId})`}
          transform={insetTransform}
          opacity="0.96"
        />
        {/* Thin bright line where the two surfaces meet - a bevel, not a border. */}
        <path
          d={innerPath ?? path}
          fill="none"
          stroke="#ffffff"
          strokeOpacity="0.35"
          strokeWidth="1"
          transform={insetTransform}
        />
        {/* Sheen over everything, clipped to the medal silhouette. */}
        <path d={path} fill={`url(#${sheenId})`} />

      </svg>

      {/* Progress ring for locked medals, drawn on its own layer so the
          desaturation above does not eat it. */}
      {!unlocked && clamped > 0 && (
        <svg viewBox="0 0 100 100" className="absolute inset-0 h-full w-full" aria-hidden="true">
          <circle
            cx="50"
            cy="50"
            r={ringRadius}
            fill="none"
            stroke="currentColor"
            strokeWidth="3"
            className="text-[var(--border-color)]"
            opacity="0.45"
          />
          <circle
            cx="50"
            cy="50"
            r={ringRadius}
            fill="none"
            stroke={familyVisual.accent[0]}
            strokeWidth="3"
            strokeLinecap="round"
            strokeDasharray={`${ringCircumference * clamped} ${ringCircumference}`}
            transform="rotate(-90 50 50)"
          />
        </svg>
      )}

      {/* The family icon sits above the SVG rather than inside it: a nested
          <svg> would inherit the medal's viewBox scaling and force every icon
          to be re-tuned per shape. */}
      <Icon
        className={cn(
          'absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 text-white',
          unlocked ? 'drop-shadow-[0_1px_2px_rgba(0,0,0,0.45)]' : 'opacity-60',
        )}
        style={{ width: size * 0.32, height: size * 0.32, marginTop: -size * 0.03 }}
        strokeWidth={2.1}
        aria-hidden="true"
      />

      {!unlocked && (
        <span
          className="absolute bottom-0 right-0 flex items-center justify-center rounded-full
                     bg-[var(--bg-primary)] text-[var(--text-muted)] shadow-sm ring-1 ring-[var(--border-color)]"
          style={{ width: size * 0.28, height: size * 0.28 }}
        >
          <Lock style={{ width: size * 0.15, height: size * 0.15 }} aria-hidden="true" />
        </span>
      )}
    </div>
  );
}
