/**
 * @vitest-environment jsdom
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import { BadgeDetailDialog } from '../BadgeDetailDialog';
import type { Badge } from '@/lib/api/orchestrator/badges.service';

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

const LADDER: Badge[] = [
  makeBadge({ code: 'builder_1', tier: 'BRONZE', threshold: 1, value: 4, unlocked: true, unlockedAt: '2026-01-02T00:00:00Z' }),
  makeBadge({ code: 'builder_10', tier: 'SILVER', threshold: 10 }),
  makeBadge({ code: 'builder_50', tier: 'GOLD', threshold: 50 }),
];

function renderDialog(props: Partial<React.ComponentProps<typeof BadgeDetailDialog>> = {}) {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <BadgeDetailDialog badge={makeBadge()} onOpenChange={() => {}} {...props} />
    </NextIntlClientProvider>,
  );
}

describe('BadgeDetailDialog', () => {
  afterEach(() => cleanup());

  it('renders nothing when no trophy is selected', () => {
    renderDialog({ badge: null });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('names the trophy, its rank and its family', () => {
    renderDialog();

    expect(screen.getByText(enMessages.badges.item.builder_10.name)).toBeInTheDocument();
    expect(screen.getByText(enMessages.badges.tier.SILVER)).toBeInTheDocument();
    expect(screen.getByText(enMessages.badges.family.BUILDER)).toBeInTheDocument();
  });

  it('spells out the rule and how far there is left to go while locked', () => {
    renderDialog({ badge: makeBadge({ value: 4, threshold: 10 }) });

    expect(screen.getByText('Create 10 workflows')).toBeInTheDocument();
    expect(screen.getByText('4 / 10')).toBeInTheDocument();
    expect(screen.getByText('6 to go')).toBeInTheDocument();
  });

  it('never reports a negative remainder when the metric overshot the threshold', () => {
    // A locked badge can legitimately read above its threshold for one render,
    // between crossing it and the evaluation that awards it.
    renderDialog({ badge: makeBadge({ value: 42, threshold: 10 }) });
    expect(screen.getByText('0 to go')).toBeInTheDocument();
  });

  it('replaces the progress bar with the unlock date once earned', () => {
    renderDialog({ badge: makeBadge({ unlocked: true, unlockedAt: '2026-05-04T00:00:00Z' }) });

    expect(screen.queryByText('4 / 10')).not.toBeInTheDocument();
    expect(screen.getByText(/Unlocked/)).toBeInTheDocument();
  });

  it('draws the family ladder and lets a rung be opened in place', () => {
    const onSelect = vi.fn();
    renderDialog({ siblings: LADDER, onSelect });

    expect(screen.getByText(enMessages.badges.detail.ladder)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: enMessages.badges.item.builder_50.name }));

    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect.mock.calls[0][0].code).toBe('builder_50');
  });

  it('hides the ladder where the caller holds only part of the family', () => {
    // A public profile carries unlocked badges only; a ladder built from that
    // would present a family of six as a family of one.
    renderDialog({ siblings: undefined });
    expect(screen.queryByText(enMessages.badges.detail.ladder)).not.toBeInTheDocument();
  });

  it('hides the ladder for a family with a single trophy, where there is no progression to show', () => {
    renderDialog({
      badge: makeBadge({ code: 'founder_2026', family: 'FOUNDER', tier: 'DIAMOND' }),
      siblings: [makeBadge({ code: 'founder_2026', family: 'FOUNDER', tier: 'DIAMOND' })],
    });
    expect(screen.queryByText(enMessages.badges.detail.ladder)).not.toBeInTheDocument();
  });

  it('leaves the ladder inert when the caller cannot navigate', () => {
    renderDialog({ siblings: LADDER });

    const rung = screen.getByRole('button', { name: enMessages.badges.item.builder_50.name });
    expect(rung).toBeDisabled();
  });

  it('prefers a badge-specific rule sentence over the shared metric one', () => {
    // The cohort trophy measures a date, so a "{count} of X" sentence would be
    // nonsense for it.
    renderDialog({
      badge: makeBadge({
        code: 'founder_2026',
        family: 'FOUNDER',
        tier: 'DIAMOND',
        metric: 'DAYS_UNTIL_JOIN_CUTOFF',
        threshold: 1,
        unlocked: true,
        unlockedAt: '2026-05-04T00:00:00Z',
      }),
    });

    expect(screen.getByText(enMessages.badges.item.founder_2026.requirement)).toBeInTheDocument();
  });
});
