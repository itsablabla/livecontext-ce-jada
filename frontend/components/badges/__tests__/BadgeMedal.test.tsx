/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { BadgeMedal } from '../BadgeMedal';
import { badgeProgress, type Badge } from '@/lib/api/orchestrator/badges.service';

function makeBadge(overrides: Partial<Badge> = {}): Badge {
  return {
    code: 'builder_10',
    family: 'BUILDER',
    tier: 'SILVER',
    metric: 'WORKFLOWS_CREATED',
    threshold: 10,
    value: 4,
    unlocked: false,
    unlockedAt: null,
    ...overrides,
  };
}

describe('badgeProgress', () => {
  it('is the fraction of the threshold reached', () => {
    expect(badgeProgress(makeBadge({ value: 4, threshold: 10 }))).toBeCloseTo(0.4);
  });

  it('is 1 for an unlocked badge whatever the metric now reads', () => {
    // The frozen value can legitimately exceed - or, after a deletion, fall
    // short of - the threshold. Neither may draw a partial ring on a trophy the
    // user already owns.
    expect(badgeProgress(makeBadge({ unlocked: true, value: 0, threshold: 10 }))).toBe(1);
    expect(badgeProgress(makeBadge({ unlocked: true, value: 99, threshold: 10 }))).toBe(1);
  });

  it('clamps a value above the threshold to 1 rather than overdrawing the ring', () => {
    expect(badgeProgress(makeBadge({ value: 40, threshold: 10 }))).toBe(1);
  });

  it('reads 0 for a zero threshold instead of dividing by zero', () => {
    expect(badgeProgress(makeBadge({ threshold: 0, value: 5 }))).toBe(0);
  });
});

describe('BadgeMedal', () => {
  it('draws a lock and a progress ring while locked', () => {
    const { container } = render(
      <BadgeMedal family="BUILDER" tier="SILVER" unlocked={false} progress={0.4} />,
    );
    // Two <svg> layers: the medal (desaturated) and the ring (kept in colour on
    // its own layer so the filter cannot eat it).
    expect(container.querySelectorAll('svg').length).toBeGreaterThanOrEqual(2);
    expect(container.querySelector('.grayscale')).not.toBeNull();
    const ring = container.querySelector('circle[stroke-dasharray]');
    expect(ring).not.toBeNull();
  });

  it('drops the lock, the desaturation and the ring once unlocked', () => {
    const { container } = render(<BadgeMedal family="BUILDER" tier="SILVER" unlocked />);
    expect(container.querySelector('.grayscale')).toBeNull();
    expect(container.querySelector('circle[stroke-dasharray]')).toBeNull();
  });

  it('draws no ring for a locked badge with zero progress, so an untouched grid stays calm', () => {
    const { container } = render(
      <BadgeMedal family="BUILDER" tier="SILVER" unlocked={false} progress={0} />,
    );
    expect(container.querySelector('circle[stroke-dasharray]')).toBeNull();
  });

  it('carries no rank dots under the medal, at any tier', () => {
    // The medal is a clean object: rank comes from the metal and the halo, and
    // the tier is written out under it on the card. The dots were removed on
    // purpose, so this pins their absence rather than leaving it to drift back.
    for (const tier of ['BRONZE', 'SILVER', 'GOLD', 'PLATINUM', 'DIAMOND'] as const) {
      const { container } = render(<BadgeMedal family="BUILDER" tier={tier} unlocked />);
      expect(container.querySelectorAll('circle[r="1.9"]')).toHaveLength(0);
    }
  });

  it('still separates the tiers by metal, so rank is not lost with the dots', () => {
    const bronze = render(<BadgeMedal family="BUILDER" tier="BRONZE" unlocked />);
    const diamond = render(<BadgeMedal family="BUILDER" tier="DIAMOND" unlocked />);
    // Attribute selector, not `linearGradient stop`: a CSS type selector is
    // case-sensitive for SVG elements, so the camelCase tag name silently
    // matches nothing and the assertion would compare two empty lists.
    const stops = (c: HTMLElement) =>
      Array.from(c.querySelectorAll('[stop-color]')).map((s) => s.getAttribute('stop-color'));

    expect(stops(bronze.container)).not.toEqual(stops(diamond.container));
  });

  it('scopes its gradient ids per family and tier so two medals cannot swap looks', () => {
    const { container } = render(<BadgeMedal family="OPERATOR" tier="GOLD" unlocked />);
    const ids = Array.from(container.querySelectorAll('[id]')).map((el) => el.id);
    expect(ids.every((id) => id.startsWith('badge-operator-gold'))).toBe(true);
  });

  it('honours an idPrefix so the same medal can appear twice on one page', () => {
    const { container } = render(
      <BadgeMedal family="OPERATOR" tier="GOLD" unlocked idPrefix="profile" />,
    );
    const ids = Array.from(container.querySelectorAll('[id]')).map((el) => el.id);
    expect(ids.every((id) => id.startsWith('profile-operator-gold'))).toBe(true);
  });
});
