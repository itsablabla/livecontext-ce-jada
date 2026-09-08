// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import ComparePlansLink from '../ComparePlansLink';
import { PLAN_COMPARISON_EVENT, type PlanComparisonRequest } from '@/lib/billing/plan-comparison-open';

/** Records what the mounted dialog would receive. */
function listen(): PlanComparisonRequest[] {
  const seen: PlanComparisonRequest[] = [];
  window.addEventListener(PLAN_COMPARISON_EVENT, (event) => {
    seen.push((event as CustomEvent<PlanComparisonRequest>).detail);
  });
  return seen;
}

afterEach(() => cleanup());

describe('ComparePlansLink', () => {
  it('asks for the plain comparison when the caller knows nothing', () => {
    const seen = listen();
    render(<ComparePlansLink />);

    fireEvent.click(screen.getByTestId('compare-plans-link'));

    expect(seen).toEqual([{ highlightPlan: null, highlightRow: null }]);
  });

  it('carries the caller emphasis through to the dialog', () => {
    const seen = listen();
    render(<ComparePlansLink highlightPlan="TEAM" highlightRow="users" />);

    fireEvent.click(screen.getByTestId('compare-plans-link'));

    expect(seen).toEqual([{ highlightPlan: 'TEAM', highlightRow: 'users' }]);
  });

  it('is a button, so it works inside the floating panels that host it', () => {
    // A link would navigate away from a composer menu or a modal; the whole
    // point is that the reader keeps their place.
    render(<ComparePlansLink />);

    const trigger = screen.getByTestId('compare-plans-link');
    expect(trigger.tagName).toBe('BUTTON');
    expect(trigger.getAttribute('type')).toBe('button');
    expect(trigger.getAttribute('href')).toBeNull();
  });

  it('is the app primary button, so the affordance is not missed', () => {
    // It used to be a grey 13px text line sitting under a solid CTA, and readers
    // did not see it. Standalone it now uses the same `default` button as Sign in,
    // Top up and Checkout: accent fill, rounded-xl, the app's one control height.
    render(<ComparePlansLink />);

    const trigger = screen.getByTestId('compare-plans-link');
    expect(trigger.className).toContain('bg-[var(--accent-primary)]');
    expect(trigger.className).toContain('rounded-xl');
    expect(trigger.className).toContain('h-9');
  });

  it('lets a host override the height without losing the accent fill', () => {
    // The storage row seats it beside an h-8 button; the row must not step.
    render(<ComparePlansLink className="h-8 px-3" />);

    const trigger = screen.getByTestId('compare-plans-link');
    expect(trigger.className).toContain('h-8');
    expect(trigger.className).not.toContain('h-9');
    expect(trigger.className).toContain('bg-[var(--accent-primary)]');
  });

  it('stays a text link inline, where a solid box would break the paragraph', () => {
    // UpgradeRequiredBadge renders it inside a <p>, after a link and a separator.
    render(<ComparePlansLink variant="inline" />);

    const trigger = screen.getByTestId('compare-plans-link');
    expect(trigger.className).not.toContain('bg-[var(--accent-primary)]');
    expect(trigger.className).toContain('underline');
  });

  it('drops its icon inline, where it would break a sentence', () => {
    const { container: standalone } = render(<ComparePlansLink />);
    expect(standalone.querySelector('svg')).not.toBeNull();
    cleanup();

    const { container: inline } = render(<ComparePlansLink variant="inline" />);
    expect(inline.querySelector('svg')).toBeNull();
  });
});
