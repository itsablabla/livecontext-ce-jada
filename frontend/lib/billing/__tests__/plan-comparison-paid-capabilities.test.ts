import { describe, expect, it } from 'vitest';
import { PLAN_FEATURE_KEYS } from '../pricing-constants';
import { buildPlanComparison } from '../plan-comparison';

/**
 * Two capabilities are sold from Pro upward, and the pricing page has to say so or the first time
 * a customer learns it is when a node fails mid-run.
 *
 * Each is enforced by a row in `auth.plan_feature_requirement`, seeded by a migration:
 *   feature:vector_search -> PRO   (V467)  embedding columns, similarity search, and installing a
 *                                          marketplace app that uses them
 *   node:browser_agent    -> PRO   (V469)  the Browser Agent node
 *
 * This file pins the PRICING side of that pair. Nothing in the build can compare it against the
 * SQL, so the two are kept together by the migration comments and by this note; if an admin moves
 * a bar in the settings screen, the seed and this test describe the DEFAULT, not the live value.
 */
describe('paid capabilities are priced where they are gated', () => {
  const PAID_FROM_PRO = ['vectorSearch', 'browserAgent'];

  it.each(PAID_FROM_PRO)('%s is listed on Pro, Team and Enterprise', (key) => {
    expect(PLAN_FEATURE_KEYS.pro).toContain(key);
    expect(PLAN_FEATURE_KEYS.team).toContain(key);
    expect(PLAN_FEATURE_KEYS.enterprise).toContain(key);
  });

  it.each(PAID_FROM_PRO)('%s is NOT listed on Free or Starter, which is what makes the row informative', (key) => {
    // A capability every tier carries renders as a row of ticks and tells the reader nothing.
    expect(PLAN_FEATURE_KEYS.free).not.toContain(key);
    expect(PLAN_FEATURE_KEYS.starter).not.toContain(key);
  });

  it.each(PAID_FROM_PRO)('%s appears as a row in the comparison matrix', (key) => {
    const rows = buildPlanComparison().flatMap((section) => section.rows);

    expect(rows.map((row) => row.id)).toContain(key);
  });

  it('places both in the building section rather than letting them fall through to the last one', () => {
    // The matrix appends an unplaced key to whatever section happens to be last, so a key added to
    // PLAN_FEATURE_KEYS alone is visible but filed under Support. Being explicit keeps them beside
    // the other building capabilities.
    const building = buildPlanComparison().find((section) => section.id === 'building');

    expect(building?.rows.map((row) => row.id)).toEqual(
      expect.arrayContaining(PAID_FROM_PRO),
    );
  });
});
