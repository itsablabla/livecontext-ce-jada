import { describe, it, expect, vi } from 'vitest';

/**
 * The fallback that makes the matrix total.
 *
 * A feature key added to a plan but routed to no dimension and named in no
 * section must still reach the table. Without this the failure is invisible:
 * the plan card shows the feature, the comparison silently omits it, and
 * nothing errors. The key is added HERE, in a mocked constants module, because
 * the real one has no unrouted key by construction - which is the point, and
 * also why the guard cannot be exercised against it.
 */

vi.mock('@/lib/billing/pricing-constants', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/billing/pricing-constants')>();
  return {
    ...actual,
    PLAN_FEATURE_KEYS: {
      ...actual.PLAN_FEATURE_KEYS,
      team: [...actual.PLAN_FEATURE_KEYS.team, 'featureNobodyRouted'],
      enterprise: [...actual.PLAN_FEATURE_KEYS.enterprise, 'featureNobodyRouted'],
    },
  };
});

const { buildPlanComparison } = await import('@/lib/billing/plan-comparison');

describe('a feature key routed nowhere', () => {
  const sections = buildPlanComparison();
  const rows = sections.flatMap((s) => s.rows);
  const orphan = rows.find((row) => row.id === 'featureNobodyRouted');

  it('still reaches the table, as a capability row', () => {
    expect(orphan, 'an unrouted key must not disappear from the comparison').toBeDefined();
    expect(orphan!.kind).toBe('flag');
  });

  it('says which plans have it and which do not', () => {
    expect(orphan!.cells).toEqual({
      free: false,
      starter: false,
      pro: false,
      team: true,
      enterprise: true,
    });
  });

  it('lands in the last section, where a reader will still find it', () => {
    const last = sections[sections.length - 1];
    expect(last.rows.some((row) => row.id === 'featureNobodyRouted')).toBe(true);
  });
});
