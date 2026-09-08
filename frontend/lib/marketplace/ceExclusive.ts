import { IS_MANAGED_CLOUD } from '@/lib/edition';

/**
 * CE-exclusive publications: the client side of the backend rule.
 *
 * A publication is flagged `ceExclusive` by the backend when its snapshot uses something
 * managed cloud cannot run AT ANY PLAN, today a local-CLI agent, so the acquire endpoints refuse
 * it there with HTTP 403 `code: 'CE_EXCLUSIVE'`.
 *
 * A vector/embedding column is NOT one of these since 2026-09-03: it appears in
 * `ceExclusiveFeatures` without setting the boolean, and the acquire endpoints gate it on the
 * workspace's plan instead (403 `code: 'PLAN_UPGRADE_REQUIRED'`). Nothing in this file decides
 * that, which is why it only ever reads the boolean.
 *
 * This module is the SINGLE place the UI decides "is this install blocked for
 * me", so the card, the preview panel and the acquire modal can never disagree.
 * It is a UX affordance, not the enforcement: the backend guard is.
 */

/** Minimal shape needed to decide - keeps the helper usable from any publication-like object. */
export interface CeExclusiveLike {
  ceExclusive?: boolean;
  ceExclusiveFeatures?: string[];
}

/** True when this publication needs a self-hosted install, whatever the viewer's edition. */
export function isCeExclusive(publication: CeExclusiveLike | null | undefined): boolean {
  return Boolean(publication?.ceExclusive);
}

/**
 * True when THIS deployment cannot install the publication: CE-exclusive AND we
 * are running a MANAGED-cloud build. On any self-hosted build (Community
 * Edition or Self-Hosted Enterprise) this is false, mirroring the backend guard
 * which keys on `AppEditionProvider.isManagedCloud()`.
 *
 * Deliberately NOT `IS_CLOUD`: that constant is binary and lumps self-hosted
 * enterprise in with cloud, which would hide the Install button from a customer
 * whose own backend allows the install.
 */
export function isCeExclusiveBlocked(publication: CeExclusiveLike | null | undefined): boolean {
  return IS_MANAGED_CLOUD && isCeExclusive(publication);
}

/**
 * i18n key suffix for each backend feature code, so the badge tooltip can name
 * what forces the requirement. Unknown codes are dropped rather than rendered
 * raw - a newer backend must never leak "SOME_NEW_CODE" into the UI.
 */
const FEATURE_LABEL_KEYS: Record<string, string> = {
  CLI_AGENT: 'ceExclusiveFeatureCliAgent',
  VECTOR_SEARCH: 'ceExclusiveFeatureVectorSearch',
};

/** Known feature codes of a publication, mapped to their `marketplace.*` i18n keys. */
export function ceExclusiveFeatureKeys(publication: CeExclusiveLike | null | undefined): string[] {
  const features = publication?.ceExclusiveFeatures ?? [];
  return features.map((code) => FEATURE_LABEL_KEYS[code]).filter((key): key is string => Boolean(key));
}
