'use client';

import { usePlanFeatureGate, type PlanLock } from '@/hooks/usePlanFeatureGate';

/**
 * The one feature key for vector columns and similarity search.
 *
 * <p>Must stay identical to `VectorFeatureGate.FEATURE_KEY` in datasource-service and to
 * `CeExclusiveAcquisitionGuard.VECTOR_SEARCH_FEATURE_KEY` in publication-service. Nothing but the
 * string holds the three sides together, so it lives in exactly one place per side and each side
 * has a test that spells it out.
 */
export const VECTOR_FEATURE_KEY = 'feature:vector_search';

/**
 * Whether this account's plan includes vector columns and similarity search, and which plan it
 * would take.
 *
 * <p><b>Why a hook rather than the build-time edition constant it replaces.</b> Until 2026-09-03
 * the answer was a property of the DEPLOYMENT: `IS_CE` decided it, frozen at build time, the same
 * for every visitor. It is now a property of the ACCOUNT'S PLAN, which a compile-time constant
 * cannot express at all. Three surfaces used to read `IS_CE` for this and now read here instead:
 * the two "add a column" pickers and the workflow builder's column-type and operator lists.
 *
 * <p><b>It is an upsell, never a barrier.</b> Like every other plan marker in the product, this
 * resolves to UNLOCKED while the answer is loading, on error, and on self-hosted, because a marker
 * drawn from a missing answer would hide a capability the account owns. The backend refuses for
 * real; the job here is to say so early and name the plan.
 */
export function useVectorFeatureLock(): PlanLock {
  const { lockFor } = usePlanFeatureGate();
  return lockFor([VECTOR_FEATURE_KEY]);
}
