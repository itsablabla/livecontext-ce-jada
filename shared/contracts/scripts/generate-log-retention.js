#!/usr/bin/env node

/**
 * Generates language-specific bindings for the execution-log retention contract.
 *
 * Reads:  shared/contracts/log-retention.json   (source of truth)
 * Writes:
 *   - backend/common-lib/src/main/java/com/apimarketplace/common/retention/LogRetentionPolicy.java
 *   - frontend/lib/billing/logRetention.ts
 *
 * The purge jobs (orchestrator, agent) and the storage row-class allow-list are
 * hand-written and NOT generated: they compose this policy but hold no duration
 * data of their own.
 *
 * Usage:  node shared/contracts/scripts/generate-log-retention.js
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const CONTRACT_PATH = path.join(ROOT, 'shared', 'contracts', 'log-retention.json');

const JAVA_OUT = path.join(ROOT, 'backend', 'common-lib', 'src', 'main', 'java',
  'com', 'apimarketplace', 'common', 'retention', 'LogRetentionPolicy.java');
const TS_OUT = path.join(ROOT, 'frontend', 'lib', 'billing', 'logRetention.ts');

const BANNER = 'GENERATED FILE - DO NOT EDIT BY HAND.\n'
  + ' * Source: shared/contracts/log-retention.json\n'
  + ' * Regenerate: node shared/contracts/scripts/generate-log-retention.js';

function load() {
  return JSON.parse(fs.readFileSync(CONTRACT_PATH, 'utf-8'));
}

function ensureDir(filePath) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
}

function javaValue(days) {
  return days === null || days === undefined ? 'null' : `Integer.valueOf(${days})`;
}

function emitJava(schema) {
  const cases = schema.tiers
    .map((t) => `            case ${t.rank} -> ${javaValue(t.days)}; // ${t.plan}`)
    .join('\n');

  const keyCases = schema.tiers
    .map((t) => `            case ${t.rank} -> "${t.featureKey}"; // ${t.plan}`)
    .join('\n');

  return `package com.apimarketplace.common.retention;

import com.apimarketplace.common.plan.PlanTier;

/**
 * ${BANNER}
 *
 * <p>How long a workspace's EXECUTION JOURNAL is kept, per subscription plan.
 *
 * <p><b>Keyed by plan RANK, not by plan code.</b> {@link PlanTier} ranks
 * {@code PAYG} with {@code STARTER}, {@code CREDIT_PACK} with {@code FREE}, and
 * all four {@code ENTERPRISE_*} SKUs together. Keying this table by literal code
 * would leave those codes unmapped, and an unmapped code must never be guessed
 * into a shorter window.
 *
 * <p><b>Unknown plan CODES retain FOREVER</b>, and so do self-hosted installs and
 * the no-subscription sentinel: {@link #retentionDays(String)} answers {@code null}
 * for all of them, and {@code null} means "keep".
 *
 * <p><b>Blank and null are the exception, and callers must guard them.</b>
 * {@link PlanTier} ranks an empty code as FREE, so this method answers the SEVEN
 * day window for {@code null} and {@code ""}. That is a deliberate reflection of
 * PlanTier rather than a second opinion about it, but it means a blank code is the
 * one input that shortens rather than retains. {@code LogRetentionService} refuses
 * blanks before ever calling here; any new caller must do the same. This is the
 * deliberate mirror image of {@code PlanFeatureGate}, which fails OPEN: that gate
 * protects revenue, so a lookup failure costs a sale; this policy DELETES, so a
 * lookup failure must cost nothing. A purge job that defaulted an unresolvable
 * tenant to the shortest window would erase a paying customer's journal on the
 * strength of a failed HTTP call.
 *
 * <p>Retention covers the execution journal only. It never covers files (any
 * storage row carrying an {@code s3_key}), chat history, or the credit ledger;
 * those exclusions live in the purge jobs, which own the row-class allow-list.
 */
public final class LogRetentionPolicy {

    private LogRetentionPolicy() {
    }

    /**
     * Days of execution-log retention for {@code planCode}, or {@code null} to
     * retain indefinitely.
     *
     * @param planCode the account's effective plan code, as returned by
     *                 auth-service. The no-subscription sentinel RETAINS (it also
     *                 means "no account matched"); {@code null} and blank rank as
     *                 FREE and therefore SHORTEN, so guard them before calling
     */
    public static Integer retentionDays(String planCode) {
        if (isNoSubscriptionSentinel(planCode)) {
            // NOT free. PlanLimitService returns this sentinel both for "account
            // found, no active subscription" and for "account not found", so
            // reading it as FREE would hand the SHORTEST window to a tenant the
            // platform simply failed to identify. The FREE window requires the
            // literal code FREE, i.e. a positive identification.
            return null;
        }
        int rank = PlanTier.userRank(planCode);
        return switch (rank) {
${cases}
            default -> null; // CE and any code PlanTier does not know: retain
        };
    }

    /**
     * The pricing-page feature key describing {@code planCode}'s window, so the
     * advertised copy and the enforced window cannot drift apart.
     */
    public static String featureKey(String planCode) {
        if (isNoSubscriptionSentinel(planCode)) {
            return "logsCustom";
        }
        int rank = PlanTier.userRank(planCode);
        return switch (rank) {
${keyCases}
            default -> "logsCustom";
        };
    }

    /**
     * Whether a row created at {@code ageDays} old is past {@code planCode}'s
     * window. Retention-forever plans always answer {@code false}.
     */
    public static boolean isExpired(String planCode, long ageDays) {
        Integer days = retentionDays(planCode);
        return days != null && ageDays > days;
    }

    private static boolean isNoSubscriptionSentinel(String planCode) {
        return planCode != null
                && PlanTier.NO_SUBSCRIPTION.equalsIgnoreCase(planCode.trim());
    }
}
`;
}

function tsValue(days) {
  return days === null || days === undefined ? 'null' : String(days);
}

function emitTs(schema) {
  const entries = schema.tiers
    .map((t) => `  ${t.plan}: { rank: ${t.rank}, days: ${tsValue(t.days)}, featureKey: '${t.featureKey}' },`)
    .join('\n');

  return `/**
 * ${BANNER}
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
${entries}
};

/** Feature key for a plan id as used by PLAN_FEATURE_KEYS (lowercase). */
export function logRetentionFeatureKey(planId: string): string {
  return LOG_RETENTION_BY_PLAN[planId.toUpperCase()]?.featureKey ?? 'logsCustom';
}

/** Retention window in days, or null when the journal is kept indefinitely. */
export function logRetentionDays(planId: string): number | null {
  return LOG_RETENTION_BY_PLAN[planId.toUpperCase()]?.days ?? null;
}
`;
}

function main() {
  const schema = load();

  ensureDir(JAVA_OUT);
  fs.writeFileSync(JAVA_OUT, emitJava(schema), 'utf-8');
  console.log('wrote', path.relative(ROOT, JAVA_OUT));

  ensureDir(TS_OUT);
  fs.writeFileSync(TS_OUT, emitTs(schema), 'utf-8');
  console.log('wrote', path.relative(ROOT, TS_OUT));
}

main();
