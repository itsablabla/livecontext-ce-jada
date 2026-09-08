'use client';

/**
 * One read for every "credits" surface: the wallet balance, the plan's monthly
 * grant, and the gauge numbers derived from the two.
 *
 * Both halves already existed but were combined ad hoc per surface (the quota
 * page derived the allowance inline, the sidebar showed a bare number with no
 * allowance at all). Centralising it means the header ring, the sidebar block
 * and the wallet card cannot disagree about what "Total" is - all three read
 * this hook, so a FREE account cannot be told "Total 1,000" by the dial and
 * "no monthly allowance" by the page the dial links to.
 *
 * The hard part is not the arithmetic, it is knowing when there IS no
 * denominator. `allowance: null` is a first-class answer here, and callers must
 * render it as "no gauge", never as a 0% dial.
 */

import { useMemo } from 'react';
import { useSubscription, useCreditBalance } from '@/lib/hooks/smart-hooks-complete';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { IS_CE } from '@/lib/edition';
import { resolveMonthlyAllowance, computeCreditGauge, type CreditGauge } from '@/lib/billing/credit-allowance';

export interface CreditWallet {
  /** Credits left to spend (sub + PAYG). null while loading. */
  balance: number | null;
  /** Renewal-grant bucket. null before the V250 payload lands. */
  subBalance: number | null;
  /** Top-up bucket. null before the V250 payload lands. */
  paygBalance: number | null;
  /**
   * Credits granted per billing cycle, incl. the FREE monthly reset.
   * null means "no denominator is knowable" - the billing payload is missing or
   * errored, or the wallet on screen belongs to another account (see below).
   */
  allowance: number | null;
  gauge: CreditGauge;
  /** True while either query is still in flight. */
  isLoading: boolean;
}

export function useCreditWallet(): CreditWallet {
  const {
    subscription,
    isLoading: isSubscriptionLoading,
    error: subscriptionError,
  } = useSubscription();
  const {
    balance,
    subBalance,
    paygBalance,
    isLoading: isBalanceLoading,
  } = useCreditBalance();

  const planCode = (subscription as any)?.subscription?.planCode ?? null;
  const creditTierIndex = (subscription as any)?.subscription?.creditTierIndex ?? 0;
  const activeOrgPlanCode = (subscription as any)?.activeOrgPlanCode ?? null;
  /**
   * Did we actually READ this account's plan?
   *
   * A transport error is not the only way to fail. `GET /api/billing/me`
   * catches its own lookup failure and answers **HTTP 200** with
   * `{subscription: null, status: "error"}`, so react-query reports no error at
   * all and the envelope is truthy. Testing only those two things let a failed
   * read fall through to `resolveMonthlyAllowance(null, 0)` = the FREE grant,
   * and a PRO account holding 40,000 credits was then gauged against 1,000 and
   * told, in gold, "+3,900% over your plan" - precisely the false claim the
   * guard below exists to prevent.
   *
   * So the inner subscription object has to be PRESENT. That also covers
   * `status: "no_subscription"`: an account with no row has no grant to gauge,
   * and no ring is the honest rendering of that.
   */
  const billingPayload = subscription as { status?: string; subscription?: unknown } | null;
  const hasBillingPayload =
    !!billingPayload &&
    !subscriptionError &&
    billingPayload.status !== 'error' &&
    !!billingPayload.subscription;
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  // Both halves come from ONE selector on purpose. `useIsCurrentOrgOwner()`
  // exists and says `currentOrgRole === 'OWNER'`, but this hook needs
  // `currentOrgId` from the same store anyway (personal context has no
  // workspace and the viewer is then necessarily the payer), so reading one
  // field through a named hook and the other through the store would be two
  // entry points to the same state for one trivial comparison.
  const currentOrgRole = useCurrentOrgStore((s) => s.currentOrgRole);

  const allowance = useMemo(() => {
    // No billing payload (still loading, disabled, or the request failed) means
    // no plan is known. Falling through would resolve a null plan code to the
    // FREE grant and gauge a PRO wallet against 1,000 credits, which is a claim
    // about an account we could not read.
    if (!hasBillingPayload) return null;

    // CE bills in dollars against no monthly grant, so there is no cycle
    // allowance to gauge against - whatever plan code the CE billing stub
    // reports (it answers planCode "FREE", which would otherwise resolve to a
    // fabricated 1,000-credit grant).
    //
    // DEFENCE IN DEPTH, not load-bearing: all three consumers are already
    // unreachable in CE - the header dial and the sidebar block self-gate on
    // IS_CE, and the quota page branches to CeQuotaPage before QuotaPageInner
    // (which is what calls this hook) ever mounts. An earlier version of this
    // comment claimed the quota page rendered this hook in both editions; that
    // was simply false, and it was the stated reason for the gate. It stays
    // because a fourth consumer must not have to rediscover the rule, but no
    // user-visible behaviour depends on it today.
    // IS_CE, deliberately, and NOT IS_MANAGED_CLOUD - which edition.ts says to
    // prefer for anything mirroring the backend's isManagedCloud(). The whole
    // billing DISPLAY layer is keyed on IS_CE (formatCostCompact switches
    // credits/dollars on it, BalanceBreakdown reads it), and those components
    // render on the same screens as these. Splitting one surface onto the other
    // constant would make a self-hosted-enterprise install show a credit ring
    // beside dollar amounts. If IS_CE is the wrong axis for billing display, it
    // is wrong uniformly and belongs fixed as a class, not one gate at a time.
    if (IS_CE) return null;

    // Owner-pays (ADR-009): /credits/balance returns the PAYER's wallet, which
    // inside someone else's workspace is the OWNER's, not ours. Our own tier is
    // then the wrong denominator and the payload carries no owner tier, so a
    // FREE guest in a TEAM workspace would be gauged 250,000 credits against a
    // 1,000 grant and told, in gold, "+24,900% over your plan".
    //
    // ROLE is the precise test, not the plan code. Comparing `activeOrgPlanCode`
    // to our own only proves the two plans share a NAME: two colleagues both on
    // PRO with different credit packs (tier 0 = 5,000 vs tier 4 = 100,000) match
    // on code while their grants differ 20x, which reproduces the exact bug on a
    // completely ordinary team. Only the OWNER of the active workspace is
    // guaranteed to be the payer whose tier we hold.
    //
    // Both conditions, deliberately: the role can hydrate late or wrong, and the
    // whole design here prefers showing no gauge to showing a false one.
    const weArePayer = !currentOrgId || currentOrgRole === 'OWNER';
    const workspaceIsOnOurPlan =
      !activeOrgPlanCode || activeOrgPlanCode === (planCode ?? 'FREE');
    if (!weArePayer || !workspaceIsOnOurPlan) return null;

    return resolveMonthlyAllowance(planCode, creditTierIndex);
  }, [hasBillingPayload, planCode, creditTierIndex, activeOrgPlanCode, currentOrgId, currentOrgRole]);

  const gauge = useMemo(() => computeCreditGauge(balance, allowance), [balance, allowance]);

  return {
    balance,
    subBalance,
    paygBalance,
    allowance,
    gauge,
    isLoading: isBalanceLoading || isSubscriptionLoading,
  };
}
