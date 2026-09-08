import { describe, it, expect } from 'vitest';
import { PLAN_FEATURE_KEYS } from '@/lib/billing/pricing-constants';
import { buildPlanComparison } from '@/lib/billing/plan-comparison';
import { LOG_RETENTION_BY_PLAN, logRetentionFeatureKey, logRetentionDays } from '@/lib/billing/logRetention';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * The advertised retention window must equal the one the backend enforces.
 *
 * Before shared/contracts/log-retention.json these durations lived ONLY here and
 * in six locale files, so a purge job would have carried a second copy. The two
 * would then drift at the first pricing change, silently and in the worst
 * direction: the page keeps advertising a window nobody applies any more, and
 * nothing fails. These assertions are the only place that divergence is visible.
 */

const LOCALES: Record<string, any> = { en, fr, de, es, pt, zh };

/** Plans that carry a retention row on the pricing page, by their PLAN_FEATURE_KEYS id. */
const PRICED_PLANS = ['free', 'starter', 'pro', 'team', 'enterprise'];

const retentionKeyOf = (planId: string): string | undefined =>
  PLAN_FEATURE_KEYS[planId].find((k) => k.startsWith('logs'));

/** First run of digits in a localized string, e.g. "90 天" and "90-day ..." both yield 90. */
const digitsIn = (s: string): number | null => {
  const m = /(\d+)/.exec(s);
  return m ? Number(m[1]) : null;
};

describe('execution-log retention: pricing page vs shared contract', () => {
  it.each(PRICED_PLANS)('plan %s advertises the feature key the contract derives', (planId) => {
    expect(retentionKeyOf(planId)).toBe(logRetentionFeatureKey(planId));
  });

  it('every plan on the pricing page carries exactly one retention key', () => {
    for (const planId of PRICED_PLANS) {
      const keys = PLAN_FEATURE_KEYS[planId].filter((k) => k.startsWith('logs'));
      expect(keys, `plan ${planId}`).toHaveLength(1);
    }
  });

  it('the contract covers every plan the pricing page prices', () => {
    for (const planId of PRICED_PLANS) {
      expect(LOG_RETENTION_BY_PLAN[planId.toUpperCase()], `plan ${planId}`).toBeDefined();
    }
  });

  it('a null window is advertised as custom, never as a number of days', () => {
    for (const planId of PRICED_PLANS) {
      if (logRetentionDays(planId) === null) {
        expect(logRetentionFeatureKey(planId), `plan ${planId}`).toBe('logsCustom');
      }
    }
  });
});

describe('execution-log retention: copy states the enforced number', () => {
  const numericPlans = PRICED_PLANS.filter((p) => logRetentionDays(p) !== null);

  it.each(numericPlans)('%s: both localized strings state the contract day count', (planId) => {
    const days = logRetentionDays(planId)!;
    const key = logRetentionFeatureKey(planId);

    for (const [locale, messages] of Object.entries(LOCALES)) {
      const sentence = messages?.pricing?.planCards?.features?.[key];
      const shortValue = messages?.pricing?.compare?.values?.[key];

      expect(sentence, `${locale}: pricing.planCards.features.${key}`).toBeTruthy();
      expect(shortValue, `${locale}: pricing.compare.values.${key}`).toBeTruthy();

      expect(digitsIn(sentence), `${locale}: "${sentence}" must state ${days}`).toBe(days);
      expect(digitsIn(shortValue), `${locale}: "${shortValue}" must state ${days}`).toBe(days);
    }
  });

  /**
   * Renaming logs60 to logs90 in PLAN_FEATURE_KEYS left plan-comparison.ts's own
   * logs dimension keyed on the dead name, so TEAM's retention cell rendered
   * blank and the new key reappeared at the bottom of the page as a stray flag
   * row. Every assertion above still passed: none of them looked at the built
   * matrix. This one does, because that is what a reader actually sees.
   */
  it.each(PRICED_PLANS)('%s shows a retention value in the comparison matrix, not a blank cell', (planId) => {
    const sections = buildPlanComparison();
    const rows = sections.flatMap((section) => section.rows);

    const logsRow = rows.find((row) => row.id === 'logs');
    expect(logsRow, 'the comparison matrix must still carry a logs dimension').toBeDefined();
    expect(logsRow!.kind).toBe('scale');

    const cell = (logsRow as { cells: Record<string, string | null> }).cells[planId];
    expect(cell, `plan ${planId} renders no retention value`).toBe(logRetentionFeatureKey(planId));
  });

  it('the retention keys never leak out as stray flag rows', () => {
    const liveKeys = new Set(PRICED_PLANS.map(logRetentionFeatureKey));
    const flagRowIds = buildPlanComparison()
      .flatMap((section) => section.rows)
      .filter((row) => row.kind === 'flag')
      .map((row) => row.id);

    for (const key of liveKeys) {
      expect(flagRowIds, `${key} escaped its dimension and became a flag row`).not.toContain(key);
    }
  });

  it('no locale still carries a key the contract abandoned', () => {
    const live = new Set(PRICED_PLANS.map(logRetentionFeatureKey));
    for (const [locale, messages] of Object.entries(LOCALES)) {
      const advertised = Object.keys(messages?.pricing?.compare?.values ?? {}).filter((k) =>
        /^logs\d+$/.test(k)
      );
      for (const key of advertised) {
        expect(live.has(key), `${locale}: ${key} is advertised but no plan uses it`).toBe(true);
      }
    }
  });
});
