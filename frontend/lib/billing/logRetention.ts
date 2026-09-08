/**
 * GENERATED FILE - DO NOT EDIT BY HAND.
 * Source: shared/contracts/log-retention.json
 * Regenerate: node shared/contracts/scripts/generate-log-retention.js
 *
 * Execution-log retention per plan. The pricing page reads featureKey from here
 * so the advertised window and the window the backend enforces come from one
 * file. days === null means the journal is retained indefinitely.
 */

export interface LogRetentionTier {
  rank: number;
  days: number | null;
  featureKey: string;
}

export const LOG_RETENTION_BY_PLAN: Record<string, LogRetentionTier> = {
  FREE: { rank: 0, days: 7, featureKey: 'logs7' },
  STARTER: { rank: 1, days: 30, featureKey: 'logs30' },
  PRO: { rank: 2, days: 30, featureKey: 'logs30' },
  TEAM: { rank: 3, days: 90, featureKey: 'logs90' },
  ENTERPRISE: { rank: 4, days: null, featureKey: 'logsCustom' },
};

/** Feature key for a plan id as used by PLAN_FEATURE_KEYS (lowercase). */
export function logRetentionFeatureKey(planId: string): string {
  return LOG_RETENTION_BY_PLAN[planId.toUpperCase()]?.featureKey ?? 'logsCustom';
}

/** Retention window in days, or null when the journal is kept indefinitely. */
export function logRetentionDays(planId: string): number | null {
  return LOG_RETENTION_BY_PLAN[planId.toUpperCase()]?.days ?? null;
}
