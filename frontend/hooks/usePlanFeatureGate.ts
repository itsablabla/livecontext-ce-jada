'use client';

import * as React from 'react';
import { useQuery } from '@tanstack/react-query';
import { planFeaturesService, type PlanFeatureMap } from '@/lib/api/services/plan-features.service';
import { normalizeRequirement, planMeets } from '@/lib/billing/planTier';
import { IS_CE } from '@/lib/edition';
import { useOptionalAuth } from '@/lib/providers/smart-providers';

export interface PlanLock {
  /** The account's plan is below the requirement: the node must not be usable. */
  locked: boolean;
  /** The plan that WOULD include it, or null when nothing is required. */
  requiredPlan: string | null;
}

const UNLOCKED: PlanLock = { locked: false, requiredPlan: null };

export interface PlanFeatureGate {
  /** The caller's effective plan, or null while it is unknown. */
  planCode: string | null;
  /** True until the map is known. Nothing is drawn as locked before then. */
  isLoading: boolean;
  /**
   * featureKey -> minimum plan, or undefined until the answer is in (and on CE
   * and on error). Exposed for callers that walk many features at once, such as
   * the workflow validator, which wants the map rather than a call per node.
   */
  requirements: Record<string, string> | undefined;
  /**
   * The verdict for a feature, given its keys most-specific-first (a `tool:`
   * key ahead of the `api:` key it belongs to). The FIRST key carrying a
   * requirement decides, matching the backend's precedence.
   */
  lockFor: (keys: Array<string | null | undefined>) => PlanLock;
}

/**
 * Which nodes and integrations this account's plan does not include.
 *
 * <p>One query for the whole map, shared by every consumer through react-query,
 * because a palette asks this question once per row and must not ask the network
 * once per row with it.
 *
 * <p><b>Nothing is locked until the answer is in.</b> A lock that appears a
 * moment after the palette opens is noise; worse, a lock drawn from a missing
 * answer would hide nodes the account owns. Loading, error and CE all resolve to
 * "unlocked" - the backend still refuses at run time, so the cost of being
 * permissive here is a clear failure instead of a silent one.
 */
export function usePlanFeatureGate(options?: { enabled?: boolean }): PlanFeatureGate {
  // The NON-throwing accessor on purpose: this hook is called by the node palette,
  // which is rendered by surfaces (and tests) that do not always sit inside
  // AppDataProvider. A gate that is only an upsell must never be the thing that
  // crashes the builder.
  const auth = useOptionalAuth();
  // Self-hosted has no plans to move between, so the question does not apply and
  // the request is never made.
  // `options.enabled` lets a caller keep the request off a surface that has
  // nothing to ask about. ValidationProvider uses it: an EMPTY canvas must fire
  // no tenant query at all (marketplace snapshot previews mount it with
  // nodes=[] and their contract is zero live calls).
  const enabled =
    (options?.enabled ?? true) && !IS_CE && !!auth && !auth.isLoading && auth.isAuthenticated;

  const { data, isPending } = useQuery<PlanFeatureMap>({
    queryKey: ['plan-features'],
    queryFn: () => planFeaturesService.getForCaller(),
    enabled,
    staleTime: 5 * 60 * 1000,
    gcTime: 10 * 60 * 1000,
    retry: false,
  });

  const isLoading = enabled && isPending;
  const planCode = data?.planCode ?? null;
  const requirements = data?.requirements;

  const lockFor = React.useCallback(
    (keys: Array<string | null | undefined>): PlanLock => {
      if (!enabled || !requirements) return UNLOCKED;
      for (const key of keys) {
        if (!key) continue;
        const required = normalizeRequirement(requirements[key]);
        if (!required) continue;
        return { locked: !planMeets(planCode, required), requiredPlan: required };
      }
      return UNLOCKED;
    },
    [enabled, requirements, planCode],
  );

  return { planCode, isLoading, requirements, lockFor };
}
