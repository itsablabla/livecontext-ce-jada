// @vitest-environment jsdom
/**
 * The popover in CE (self-hosted), where the unit is DOLLARS.
 *
 * The whole folder mocked `isCeMode: false` implicitly by never touching it, so
 * the CE arm of the amount formatter never executed in any test. That arm exists
 * because `formatCost` already renders "$1.23" in CE: appending the unit word
 * there would print "$1.23 dollars", and inverting the ternary would print
 * "$1.23 credits" to every self-hosted install. Both would have shipped green.
 *
 * <p>A FORWARD guard, not a regression test: the pre-change popover rendered
 * "$0.80 of $1.00" and would satisfy most of what is asserted here. What it
 * catches is the arm being inverted or removed from here on, which is what the
 * third test contrasts.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import en from '../../../messages/en.json';

const mocks = vi.hoisted(() => ({ ce: { value: true } }));

// EVERYTHING here is mocked, `isCeMode` included - it has to be, since the real
// one is a build-time constant. What differs is why: `isCeMode` is the switch
// this file drives, and the two formatters are STUBBED to the CE shape. They are stand-ins, deliberately: what
// this file asserts is which BRANCH runs and what the copy does around the
// figure, not `formatCeDollars`' rounding, which lib/format-cost owns and tests.
vi.mock('@/lib/format-cost', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/format-cost')>();
  return {
    ...actual,
    get isCeMode() {
      return mocks.ce.value;
    },
    formatCost: (value: number | null | undefined) => (value == null ? '-' : `$${(value / 1000).toFixed(2)}`),
    formatCostCompact: (value: number) => `$${(value / 1000).toFixed(2)}`,
  };
});

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) => {
    const value = key.split('.').reduce<any>((node, part) => node?.[part], en);
    if (typeof value !== 'string') return key;
    return params
      ? value.replace(/\{(\w+)\}/g, (_m, name) => String(params[name] ?? `{${name}}`))
      : value;
  },
}));

import { BudgetChip } from '../BudgetChip';

const popover = () => document.querySelector('[data-testid="budget-popover"]');
const text = () => popover()?.textContent ?? '';

async function open() {
  fireEvent.focus(screen.getByTestId('budget-chip'));
  await waitFor(() => expect(popover()).not.toBeNull());
}

beforeEach(() => {
  mocks.ce.value = true;
});
afterEach(() => cleanup());

describe('the spending popover on a self-hosted install', () => {
  it('shows dollars and never names the unit twice', async () => {
    render(<BudgetChip spent={800} cap={1000} periodMode="monthly" />);
    await open();

    expect(text()).toContain('$0.80');
    expect(text()).toContain('$1.00');
    // "$0.80 dollars" is what appending the unit unconditionally would print.
    expect(text()).not.toContain('$0.80 dollars');
    // And "$0.80 credits" is what inverting the branch would print. This is the
    // assertion that actually fails if the CE arm is removed.
    expect(text()).not.toContain('$0.80 credits');
  });

  it('still says what the figure counts, in the unit this install bills in', async () => {
    render(<BudgetChip spent={800} cap={1000} periodMode="monthly" />);
    await open();

    // The scope line is the one place the word belongs in CE, and it must say
    // dollars there rather than the cloud's credits.
    expect(text()).toContain('dollars');
    expect(text()).not.toContain('in credits');
  });

  it('names credits, not dollars, once the same install is cloud', async () => {
    // The contrast that gives the two above their meaning: the branch is gated
    // on the edition, not quietly removed for everyone.
    mocks.ce.value = false;
    render(<BudgetChip spent={800} cap={1000} periodMode="monthly" />);
    await open();

    expect(text()).toContain('credits');
    expect(text()).not.toContain('dollars');
  });
});
