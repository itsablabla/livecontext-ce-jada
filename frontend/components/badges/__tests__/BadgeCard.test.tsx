/**
 * @vitest-environment jsdom
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import { BadgeCard } from '../BadgeCard';
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

function renderCard(badge: Badge, onSelect?: (badge: Badge) => void) {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <BadgeCard badge={badge} onSelect={onSelect} />
    </NextIntlClientProvider>,
  );
}

/** Anything that would draw a frame or a panel around the medal. */
const CARD_CHROME = /\bborder\b|border-\[|border-dashed|\bbg-\[|bg-theme-|shadow/;

describe('BadgeCard', () => {
  afterEach(() => cleanup());

  it('is a button, so the whole medal opens the trophy card', () => {
    const onSelect = vi.fn();
    renderCard(makeBadge(), onSelect);

    fireEvent.click(screen.getByRole('button', { name: enMessages.badges.item.builder_10.name }));

    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect.mock.calls[0][0].code).toBe('builder_10');
  });

  it('does not blow up when no handler is given', () => {
    renderCard(makeBadge());
    fireEvent.click(screen.getByRole('button'));
    expect(screen.getByRole('button')).toBeInTheDocument();
  });

  it('wears no card: no border, no panel, no background, earned or not', () => {
    // Three rounds of feedback landed here - full border, then hairline, then
    // none at all. The medal separates earned from unearned by itself, so any
    // frame creeping back turns the wall into a spreadsheet again.
    for (const badge of [
      makeBadge(),
      makeBadge({ unlocked: true, unlockedAt: '2026-05-04T00:00:00Z' }),
    ]) {
      cleanup();
      const { container } = renderCard(badge);
      const tile = container.querySelector('button')!;

      expect(tile.className).not.toMatch(CARD_CHROME);
      expect(tile.style.backgroundImage).toBe('');
      expect(tile.style.background).toBe('');
    }
  });

  it('carries no progress bar either - the medal already draws the ring', () => {
    const { container } = renderCard(makeBadge({ value: 4, threshold: 10 }));
    // A bar under a bare medal reads the same number twice, and it is the one
    // horizontal element that would make the cell look boxed again.
    expect(container.querySelector('[style*="width: 40%"]')).toBeNull();
    expect(container.querySelector('circle[stroke-dasharray]')).not.toBeNull();
  });

  it('shows how far along a locked trophy is', () => {
    renderCard(makeBadge({ value: 4, threshold: 10 }));
    expect(screen.getByText('4 / 10')).toBeInTheDocument();
  });

  it('shows the rank instead of a count once earned', () => {
    renderCard(makeBadge({ unlocked: true, unlockedAt: '2026-05-04T00:00:00Z' }));

    expect(screen.getByText(enMessages.badges.tier.SILVER)).toBeInTheDocument();
    expect(screen.queryByText('4 / 10')).not.toBeInTheDocument();
  });

  it('leaves the rule to the detail card rather than printing it under every medal', () => {
    renderCard(makeBadge());
    expect(screen.queryByText('Create 10 workflows')).not.toBeInTheDocument();
  });

  it('names the trophy for a screen reader as well as on screen', () => {
    renderCard(makeBadge());
    const name = enMessages.badges.item.builder_10.name;

    expect(screen.getByRole('button', { name })).toBeInTheDocument();
    expect(screen.getByText(name)).toBeInTheDocument();
  });
});
