import { describe, expect, it } from 'vitest';
import {
  SELECTABLE_PLANS,
  normalizeRequirement,
  planMeets,
  requiredRank,
  userRank,
} from '../planTier';

/**
 * This file is half of a PAIR: `common-lib` `PlanTierTest.java` asserts the same
 * table on the backend. The backend decides whether a node RUNS and this copy
 * decides whether the builder draws a lock on it, so a divergence shows the user
 * an available node that then fails, or a locked node they could have used.
 * Every case here has a counterpart there.
 */
describe('planTier', () => {
  describe('planMeets', () => {
    it('orders the tiers: above passes, below does not', () => {
      expect(planMeets('TEAM', 'PRO')).toBe(true);
      expect(planMeets('PRO', 'PRO')).toBe(true);
      expect(planMeets('STARTER', 'PRO')).toBe(false);
      expect(planMeets('FREE', 'PRO')).toBe(false);
    });

    it('lets everyone through when nothing is required', () => {
      expect(planMeets('FREE', null)).toBe(true);
      expect(planMeets('FREE', '')).toBe(true);
      expect(planMeets('FREE', 'FREE')).toBe(true);
    });

    it('treats no subscription as FREE, so a paid requirement blocks it', () => {
      expect(planMeets('__NONE__', 'STARTER')).toBe(false);
      expect(planMeets(null, 'STARTER')).toBe(false);
      expect(planMeets(undefined, 'STARTER')).toBe(false);
    });

    it('never gates CE - a self-hosted install has no plan to upgrade to', () => {
      expect(planMeets('CE', 'TEAM')).toBe(true);
      expect(planMeets('CE', 'ENTERPRISE')).toBe(true);
    });

    it('fails OPEN on an unknown user plan rather than locking a paying customer out', () => {
      expect(planMeets('SCALE_2027', 'TEAM')).toBe(true);
    });

    it('treats an unknown requirement as no requirement, so a typo opens rather than seals', () => {
      expect(planMeets('FREE', 'PROO')).toBe(true);
      expect(planMeets('FREE', 'gold')).toBe(true);
    });

    it('ignores case and surrounding whitespace', () => {
      expect(planMeets(' pro ', 'pro')).toBe(true);
      expect(planMeets(' free ', ' PRO ')).toBe(false);
    });

    it('ranks PAYG with STARTER: paying per call is not a tier upgrade', () => {
      expect(planMeets('PAYG', 'STARTER')).toBe(true);
      expect(planMeets('PAYG', 'PRO')).toBe(false);
    });

    it('ranks a credit pack as FREE: it is a top-up on a plan, not a plan', () => {
      expect(planMeets('CREDIT_PACK', 'STARTER')).toBe(false);
    });

    it('clears every tier for any ENTERPRISE SKU, taught or not', () => {
      expect(planMeets('ENTERPRISE_BASIC', 'TEAM')).toBe(true);
      expect(planMeets('ENTERPRISE_GALACTIC', 'ENTERPRISE')).toBe(true);
    });
  });

  describe('normalizeRequirement', () => {
    it('returns null for everything that is not a real requirement', () => {
      expect(normalizeRequirement(null)).toBeNull();
      expect(normalizeRequirement('')).toBeNull();
      expect(normalizeRequirement('FREE')).toBeNull();
      expect(normalizeRequirement('not-a-plan')).toBeNull();
    });

    it('upper-cases a real requirement', () => {
      expect(normalizeRequirement(' pro ')).toBe('PRO');
      expect(normalizeRequirement('enterprise')).toBe('ENTERPRISE');
    });
  });

  describe('ranks', () => {
    it('ranks no subscription at the bottom and CE at the top', () => {
      expect(userRank('__NONE__')).toBe(0);
      expect(userRank('CE')).toBeGreaterThan(userRank('ENTERPRISE_ULTIMATE'));
    });

    it('gives a requirement of FREE rank 0, which is what "no gate" means', () => {
      expect(requiredRank('FREE')).toBe(0);
      expect(requiredRank(null)).toBe(0);
      expect(requiredRank('PRO')).toBeGreaterThan(0);
    });
  });

  it('offers one spelling per tier, cheapest first (same list the backend validates against)', () => {
    expect([...SELECTABLE_PLANS]).toEqual(['FREE', 'STARTER', 'PRO', 'TEAM', 'ENTERPRISE']);
  });
});
