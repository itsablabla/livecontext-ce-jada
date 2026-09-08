/**
 * Plan ordering, mirroring `common-lib` `PlanTier.java`.
 *
 * The two copies MUST agree: the backend decides whether a node RUNS, this one
 * decides whether the builder draws a lock on it. A disagreement is worse than
 * either behaviour alone, because it shows an available node that then fails at
 * run time, or a locked node the account could actually have used.
 *
 * Both unknowns fail OPEN here too: an unrecognised user plan clears every bar,
 * an unrecognised requirement is no requirement. `CE` is unrestricted - a
 * self-hosted install has no plan to upgrade to.
 */

export const NO_SUBSCRIPTION = '__NONE__';
export const CE_PLAN = 'CE';
export const FREE_PLAN = 'FREE';

const UNRESTRICTED = Number.MAX_SAFE_INTEGER;

/** Only the ORDER matters. Keep in step with PlanTier.RANKS. */
const RANKS: Record<string, number> = {
  FREE: 0,
  CREDIT_PACK: 0,
  STARTER: 1,
  PAYG: 1,
  PRO: 2,
  TEAM: 3,
  ENTERPRISE_BASIC: 4,
  ENTERPRISE_STANDARD: 4,
  ENTERPRISE_PREMIUM: 4,
  ENTERPRISE_ULTIMATE: 4,
};

/** The codes an admin may require, cheapest first. Keep in step with PlanTier.SELECTABLE. */
export const SELECTABLE_PLANS = ['FREE', 'STARTER', 'PRO', 'TEAM', 'ENTERPRISE'] as const;

function normalize(code: string | null | undefined): string {
  return (code ?? '').trim().toUpperCase();
}

/** Rank of the plan a USER is on. Unknown codes and CE clear every bar. */
export function userRank(planCode: string | null | undefined): number {
  const code = normalize(planCode);
  if (!code || code === NO_SUBSCRIPTION) return 0;
  if (code === CE_PLAN) return UNRESTRICTED;
  const rank = RANKS[code];
  if (rank === undefined) {
    // An ENTERPRISE SKU this file has not been taught yet is still enterprise:
    // the prefix is the product name, and new SKUs ship far more often than
    // new tiers.
    return code.startsWith('ENTERPRISE') ? 4 : UNRESTRICTED;
  }
  return rank;
}

/** Rank of a REQUIREMENT. Anything unrecognised is no requirement at all. */
export function requiredRank(planCode: string | null | undefined): number {
  const code = normalize(planCode);
  if (!code || code === FREE_PLAN) return 0;
  const rank = RANKS[code];
  if (rank === undefined) {
    return code.startsWith('ENTERPRISE') ? 4 : 0;
  }
  return rank;
}

/** Whether a user on `userPlanCode` may use something requiring `requiredPlanCode`. */
export function planMeets(
  userPlanCode: string | null | undefined,
  requiredPlanCode: string | null | undefined,
): boolean {
  const required = requiredRank(requiredPlanCode);
  if (required <= 0) return true;
  return userRank(userPlanCode) >= required;
}

/**
 * The requirement, normalised, or null when there is nothing to require. Use it
 * to decide whether to render an upgrade affordance at all.
 */
export function normalizeRequirement(requiredPlanCode: string | null | undefined): string | null {
  const code = normalize(requiredPlanCode);
  if (!code || code === FREE_PLAN || requiredRank(code) <= 0) return null;
  return code;
}
