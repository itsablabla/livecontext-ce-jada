// @vitest-environment jsdom
/**
 * The "Advanced" fold on a workflow form: a spending cap and how it resets.
 *
 * It existed only on the EDIT form, so a workflow could not be capped until
 * after it had been created - and the first run of a new automation is exactly
 * when an owner wants a ceiling on it. It is now one component, rendered by the
 * create modal and the edit modal alike, which is what these tests pin: the
 * behaviour, and the fact that both forms get it from here.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import fs from 'node:fs';
import path from 'node:path';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => `budget.${key}`,
}));

import {
  BudgetAdvancedSection,
  budgetInputToCredits,
  creditsToBudgetInput,
} from '../BudgetAdvancedSection';

afterEach(() => cleanup());

function renderSection(props: Partial<React.ComponentProps<typeof BudgetAdvancedSection>> = {}) {
  const onAmountChange = vi.fn();
  const onPeriodModeChange = vi.fn();
  const onToggle = vi.fn();
  render(
    <BudgetAdvancedSection
      open
      onToggle={onToggle}
      amount=""
      onAmountChange={onAmountChange}
      periodMode="monthly"
      onPeriodModeChange={onPeriodModeChange}
      {...props}
    />
  );
  return { onAmountChange, onPeriodModeChange, onToggle };
}

describe('the advanced fold', () => {
  it('shows nothing but its own header until it is opened', () => {
    // A new workflow needs a name and nothing else; the cap is opt-in.
    renderSection({ open: false });

    expect(screen.getByTestId('budget-advanced-toggle')).toBeInTheDocument();
    expect(screen.queryByTestId('budget-advanced-fields')).toBeNull();
  });

  it('tells assistive tech whether it is open, not only the chevron', () => {
    const { rerender } = render(
      <BudgetAdvancedSection
        open={false}
        onToggle={() => {}}
        amount=""
        onAmountChange={() => {}}
        periodMode="monthly"
        onPeriodModeChange={() => {}}
      />
    );
    expect(screen.getByTestId('budget-advanced-toggle')).toHaveAttribute('aria-expanded', 'false');

    rerender(
      <BudgetAdvancedSection
        open
        onToggle={() => {}}
        amount=""
        onAmountChange={() => {}}
        periodMode="monthly"
        onPeriodModeChange={() => {}}
      />
    );
    expect(screen.getByTestId('budget-advanced-toggle')).toHaveAttribute('aria-expanded', 'true');
  });

  it('hides the reset cadence until there is a cap to reset', () => {
    // With no cap there is nothing to reset, and an empty field has to stay the
    // zero-friction default: no cap at all, like an agent.
    const { rerender } = render(
      <BudgetAdvancedSection
        open
        onToggle={() => {}}
        amount=""
        onAmountChange={() => {}}
        periodMode="monthly"
        onPeriodModeChange={() => {}}
      />
    );
    expect(screen.queryByTestId('budget-advanced-period')).toBeNull();

    rerender(
      <BudgetAdvancedSection
        open
        onToggle={() => {}}
        amount="500"
        onAmountChange={() => {}}
        periodMode="monthly"
        onPeriodModeChange={() => {}}
      />
    );
    expect(screen.getByTestId('budget-advanced-period')).toBeInTheDocument();
  });

  it('treats a field of spaces as no cap, not as a cap', () => {
    render(
      <BudgetAdvancedSection
        open
        onToggle={() => {}}
        amount="   "
        onAmountChange={() => {}}
        periodMode="monthly"
        onPeriodModeChange={() => {}}
      />
    );
    expect(screen.queryByTestId('budget-advanced-period')).toBeNull();
  });

  it('reports every keystroke to its owner rather than holding the value', () => {
    // The two forms have different state problems - the edit form has to tell
    // "no cap" from "not loaded yet" - so the value belongs to the caller.
    const { onAmountChange } = renderSection();

    fireEvent.change(screen.getByTestId('budget-advanced-amount'), { target: { value: '250' } });

    expect(onAmountChange).toHaveBeenCalledWith('250');
  });

  it('offers the three cadences the backend implements, and no others', () => {
    renderSection({ amount: '500' });

    const options = Array.from(
      screen.getByTestId('budget-advanced-period').querySelectorAll('option')
    ).map((option) => option.getAttribute('value'));
    expect(options).toEqual(['monthly', 'weekly', 'cumulative']);
  });
});

describe('the unit conversion, in CLOUD', () => {
  // CLOUD only: `IS_CE` is a build-time constant and this file does not mock it,
  // so the CE arm of both helpers never runs here and the round-trip below is
  // the identity. The CE path (x1000 both ways, the "$" prefix, the dollars
  // help text) is exercised in EditMetadataModal.budget.test.tsx, which mocks
  // the edition; saying otherwise here would claim coverage this file has not
  // got.
  it('reads an empty field as no cap, never as zero', () => {
    // A 0 cap would stop every run; the column is nullable and null means "none".
    for (const empty of ['', '   ']) {
      expect(budgetInputToCredits(empty)).toBeNull();
    }
  });

  it('refuses a negative or nonsense amount instead of storing it', () => {
    for (const bad of ['-5', '0', 'abc', 'NaN']) {
      expect(budgetInputToCredits(bad), bad).toBeNull();
    }
  });

  it('leaves no cap as an empty field, so the form does not invent a 0', () => {
    expect(creditsToBudgetInput(null)).toBe('');
    expect(creditsToBudgetInput(undefined)).toBe('');
    expect(creditsToBudgetInput(0)).toBe('');
  });

  it('round-trips a stored cap without drifting', () => {
    // The reason the conversion uses an integer credits-per-dollar factor:
    // `credits * 0.001` renders 350 as "0.35000000000000003" in the field.
    for (const credits of [1, 350, 5000, 123456]) {
      expect(budgetInputToCredits(creditsToBudgetInput(credits)), String(credits)).toBe(credits);
    }
  });
});

describe('both workflow forms get the fold from here', () => {
  const FORMS = [
    'components/chat/CreateWorkflowModal.tsx',
    'components/app/EditMetadataModal.tsx',
  ];

  it.each(FORMS)('%s renders the shared section', (file) => {
    // The duplication this prevents is the reason the create form had no cap at
    // all: the fold was 76 lines inside the edit modal, so giving it to a second
    // form meant copying them, and nobody did.
    const source = fs.readFileSync(path.join(process.cwd(), file), 'utf8');
    expect(source).toContain('<BudgetAdvancedSection');
    // And neither re-implements the pieces it owns.
    expect(source, `${file} spells the cadence options itself`).not.toContain("value=\"cumulative\"");
    expect(source, `${file} converts credits itself`).not.toContain('CREDIT_LIST_USD');
  });
});
