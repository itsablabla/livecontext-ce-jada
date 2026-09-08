/**
 * How a surface opens the plan comparison: one browser event, one listener.
 *
 * <p><b>Why an event and not a context.</b> The dialog is mounted once in the
 * app layout, and the landing mounts its own outside the app tree; a provider
 * would have to wrap both trees to serve two buttons. The codebase already
 * answers this question the same way for the credits modal
 * (`INSUFFICIENT_CREDITS_EVENT`), so this is the established shape rather than a
 * second mechanism.
 *
 * <p><b>Cheap to call is exactly why it spread.</b> The comparison was once
 * opened from a dozen surfaces, because each one cost a single line. It is now
 * the pricing page's and the landing's, and
 * `plan-comparison-entry-points.test.ts` fails on any caller outside that list -
 * including a raw dispatch of {@link PLAN_COMPARISON_EVENT}, which is the same
 * entry point wearing a different name.
 */

import { track } from '@/lib/analytics/analytics';

/** Dispatch on `window` to open the comparison. Detail: {@link PlanComparisonRequest}. */
export const PLAN_COMPARISON_EVENT = 'openPlanComparison';

export interface PlanComparisonRequest {
  /**
   * A backend plan code (`STARTER`, `PRO`, `TEAM`, `ENTERPRISE_*`) to emphasise,
   * for a comparison opened because something needs that plan. The column is
   * marked, never preselected: the reader still chooses.
   */
  highlightPlan?: string | null;
  /**
   * A row to bring the reader's eye to: a dimension id (`storage`, `users`,
   * `workspaces`, `credits`...) or a feature key (`sso`, `apiAccess`...). An id
   * matching no row simply highlights nothing.
   */
  highlightRow?: string | null;
}

/**
 * Open the comparison from anywhere.
 *
 * A no-op on the server, where there is no window to dispatch on and no dialog
 * to open: callers may invoke it from an event handler without guarding first.
 */
export function openPlanComparison(request: PlanComparisonRequest = {}): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<PlanComparisonRequest>(PLAN_COMPARISON_EVENT, { detail: request }));
  // Tracked HERE, the single opener, so every entry point is counted without
  // adding a call site (which the entry-points guard would refuse).
  track('plan_comparison_opened', {
    highlight_plan: request.highlightPlan ?? null,
    highlight_row: request.highlightRow ?? null,
  });
}
