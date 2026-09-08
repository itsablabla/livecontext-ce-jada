import 'server-only';
import enMessages from '@/messages/en.json';
import {
  PUBLIC_MARKETPLACE_REVALIDATE_SECONDS,
  gatewayBaseUrl,
} from './publicPublications';
import type { Badge, BadgeFamily, BadgeTier } from '@/lib/api/orchestrator/badges.service';

/**
 * Server-side read of the trophies shown on a public author page.
 *
 * The backend answers 404 for a profile whose owner set it to PRIVATE, exactly
 * like the profile endpoint itself, so the two surfaces cannot disagree about
 * whether a page exists.
 */

/** Only the fields the public grid renders; the endpoint sends unlocked rows only. */
export interface PublicBadge {
  code: string;
  family: BadgeFamily;
  tier: BadgeTier;
  unlockedAt: string | null;
}

const FAMILIES = new Set<string>([
  'FOUNDER', 'TENURE', 'BUILDER', 'APP_MAKER', 'SHIPPER', 'OPERATOR',
  'RELIABILITY', 'CONSISTENCY', 'NIGHT_OWL', 'PUBLISHER', 'SHARER', 'POPULARITY',
]);
const TIERS = new Set<string>(['BRONZE', 'SILVER', 'GOLD', 'PLATINUM', 'DIAMOND']);

/**
 * Reject any row whose family or tier the artwork does not know. Those two
 * fields index into the visual maps, so an unrecognised value would crash the
 * medal renderer for the whole page rather than skip one badge.
 */
export function mapPublicBadges(raw: unknown): PublicBadge[] {
  if (!Array.isArray(raw)) return [];
  const out: PublicBadge[] = [];
  for (const entry of raw) {
    if (typeof entry !== 'object' || entry === null) continue;
    const row = entry as Partial<Badge>;
    if (typeof row.code !== 'string' || !row.code) continue;
    if (typeof row.family !== 'string' || !FAMILIES.has(row.family)) continue;
    if (typeof row.tier !== 'string' || !TIERS.has(row.tier)) continue;
    out.push({
      code: row.code,
      family: row.family as BadgeFamily,
      tier: row.tier as BadgeTier,
      unlockedAt: typeof row.unlockedAt === 'string' ? row.unlockedAt : null,
    });
  }
  return out;
}

/**
 * Unlocked trophies for one user. Returns an empty list on any failure so the
 * profile page still renders its listings.
 */
export async function fetchPublicBadges(
  userId: number,
  revalidateSeconds = PUBLIC_MARKETPLACE_REVALIDATE_SECONDS,
): Promise<PublicBadge[]> {
  try {
    const res = await fetch(`${gatewayBaseUrl()}/api/badges/public/${userId}`, {
      headers: { Accept: 'application/json' },
      next: { revalidate: revalidateSeconds },
    });
    if (!res.ok) return [];
    return mapPublicBadges(await res.json());
  } catch {
    return [];
  }
}

/**
 * English trophy name for a code.
 *
 * <p>The public profile page lives outside the {@code [locale]} tree and has no
 * next-intl provider, so it cannot call {@code useTranslations}. Reading the
 * reference locale directly keeps the badge names in the ONE place they are
 * maintained (the message files) instead of forking a second list here, and it
 * matches the rest of this page, whose copy is English too. This module is
 * {@code server-only}, so the message bundle never reaches the browser.
 */
export function publicBadgeName(code: string): string {
  const items = (enMessages as { badges?: { item?: Record<string, { name?: string }> } }).badges?.item;
  return items?.[code]?.name ?? code;
}

/** English family label for a code's family, used as the medal's caption line. */
export function publicBadgeFamilyLabel(family: BadgeFamily): string {
  const families = (enMessages as { badges?: { family?: Record<string, string> } }).badges?.family;
  return families?.[family] ?? family;
}
