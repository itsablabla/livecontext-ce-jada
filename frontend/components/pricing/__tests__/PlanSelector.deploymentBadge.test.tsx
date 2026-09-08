// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => {
    const map: Record<string, string> = {
      'price.free': 'Free',
      period: '/mo',
      'actions.startFree': 'Start Free',
      'badges.recommended': 'Recommended',
      'badges.current': 'Current',
      cloud: 'Cloud',
      selfHosted: 'Self-hosted',
      availableOn: 'Available on Cloud and self-hosted',
    };
    return map[key] ?? key;
  },
  // PlanSelector renders FoundingPriceNote, which formats the announced price and the
  // deadline in the APP locale. Without this the whole card fails to render.
  useLocale: () => 'en',
}));

// `useOptionalTheme` is mocked alongside `useTheme`: the icons in this tree render
// through `useThemeSafely`, which reads the context OPTIONALLY so the same icons can
// render on the public marketplace outside any ThemeProvider. A mock missing it throws.
vi.mock('@/components/ThemeProvider', () => ({ useTheme: () => ({ theme: 'light' }),
  useOptionalTheme: () => ({ theme: 'light' }),
}));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({ usePlans: () => ({ isLoading: false }) }));

import PlanSelector from '../PlanSelector';

const basePlan = {
  id: 'pro',
  name: 'Pro',
  description: '',
  price: '24',
  period: '/mo',
  credits: '5,000',
  storage: '10 GB',
  features: ['Feature A', 'Feature B'],
  cta: 'Get Pro',
  popular: false,
  creditPrice: '',
};

const noop = async () => ({ success: true });

afterEach(cleanup);

describe('PlanSelector - static deployment badge', () => {
  it('renders the static Cloud · Self-hosted availability chip', () => {
    render(<PlanSelector plan={basePlan} billingCycle="monthly" onPlanSelect={noop} />);
    expect(screen.getByText('Cloud')).toBeTruthy();
    expect(screen.getByText('Self-hosted')).toBeTruthy();
    // it is a note, not an interactive selector
    expect(screen.getByRole('note').getAttribute('aria-label')).toBe('Available on Cloud and self-hosted');
  });

  it('keeps the normal Cloud pricing/CTA (no per-card price swap or GitHub link)', () => {
    render(<PlanSelector plan={basePlan} billingCycle="monthly" onPlanSelect={noop} />);
    expect(screen.getByText('$24')).toBeTruthy();
    expect(screen.getByRole('button', { name: /Get Pro/i })).toBeTruthy();
    expect(screen.queryByRole('link', { name: /github/i })).toBeNull();
  });

  it('groups the thousands separator in a large price (locale-aware) instead of a bare number', () => {
    // Class C regression: a >= 1000 price (top credit tiers) rendered as "$4000".
    // The default app locale (en, no URL prefix / cookie in jsdom) groups it.
    render(<PlanSelector plan={{ ...basePlan, price: '4000' }} billingCycle="monthly" onPlanSelect={noop} />);
    expect(screen.getByText('$4,000')).toBeTruthy();
    expect(screen.queryByText('$4000')).toBeNull();
  });
});

describe('PlanSelector - founding price while plans are loading', () => {
  it('hides the founding caption until the price itself has rendered', async () => {
    // Regression: the caption rendered outside the isLoadingPlans branch, so a card
    // showed "Founding price until ..." under an empty skeleton box.
    vi.resetModules();
    vi.doMock('@/lib/hooks/smart-hooks-complete', () => ({ usePlans: () => ({ isLoading: true }) }));
    const { default: LoadingPlanSelector } = await import('../PlanSelector');

    render(
      <LoadingPlanSelector
        plan={{
          id: 'pro',
          name: 'Pro',
          description: 'desc',
          price: '24',
          period: '/mo',
          credits: '5,000',
          storage: '10 GB',
          features: [],
          cta: 'Start Pro',
          creditPrice: '',
        }}
        billingCycle="monthly"
        onPlanSelect={async () => ({ success: true })}
        pricingEvent={{
          id: 'test-window',
          startsAt: '2026-03-01T00:00:00.000Z',
          endsAt: '2026-03-31T23:59:59.999Z',
          windowEndsAt: '2026-03-08T00:00:00.000Z',
          announcedBasePrices: { pro: 45 },
          locksPrice: true,
        }}
        creditTierIndex={0}
      />
    );

    expect(screen.queryByText(/Founding price until/i)).toBeNull();
    expect(screen.queryByText('$45')).toBeNull();
  });
});
