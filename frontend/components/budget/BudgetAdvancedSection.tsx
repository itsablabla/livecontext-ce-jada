'use client';

import React from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Input } from '@/components/ui/input';
import { IS_CE } from '@/lib/edition';
import { CREDIT_LIST_USD } from '@/lib/billing/pricing-constants';

/**
 * Credits per dollar, as an INTEGER.
 *
 * <p>Converting with `credits * CREDIT_LIST_USD` (0.001) surfaces IEEE-754
 * artifacts straight into the input: 350 becomes "0.35000000000000003". Dividing
 * by 1000 does not.
 */
const CREDITS_PER_DOLLAR = Math.round(1 / CREDIT_LIST_USD);

/** A stored credit amount, as the edition's unit, ready to put in the field. */
export function creditsToBudgetInput(credits: number | null | undefined): string {
  if (credits == null || credits <= 0) return '';
  return IS_CE ? String(credits / CREDITS_PER_DOLLAR) : String(credits);
}

/**
 * What the user typed, back to CREDITS. Blank or non-positive means "no cap",
 * which is `null` rather than 0: the column is nullable and a 0 cap would stop
 * every run.
 */
export function budgetInputToCredits(raw: string): number | null {
  const trimmed = raw.trim();
  if (trimmed === '') return null;
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || parsed <= 0) return null;
  return IS_CE ? parsed * CREDITS_PER_DOLLAR : parsed;
}

export interface BudgetAdvancedSectionProps {
  /** Is the section unfolded? Owned by the caller: the edit modal opens it when a cap arrives. */
  open: boolean;
  onToggle: () => void;
  /** The amount AS TYPED, in the edition's unit (dollars in CE, credits in cloud). */
  amount: string;
  onAmountChange: (value: string) => void;
  /** monthly | weekly | cumulative. */
  periodMode: string;
  onPeriodModeChange: (value: string) => void;
}

/**
 * The "Advanced" fold on a workflow form: a spending cap and how it resets.
 *
 * <p>ONE implementation, shared by the modal that CREATES a workflow and the one
 * that EDITS it. It lived only in the edit modal, which is why a workflow could
 * not be given a cap until after it existed - and the first run of a brand new
 * automation is exactly when an owner most wants one.
 *
 * <p>THE CONTRACT BOTH CALLERS HONOUR: a CLOSED fold contributes nothing to
 * the save. Not the amount, not the cadence, and not a cleared amount either -
 * closed means "leave the cap alone", never "remove it". Collapsing is
 * therefore non-destructive: the typed value survives and comes back on
 * reopening. This header is a full-width button and easy to hit by accident,
 * so the alternative (clear on collapse) would destroy work with no undo, and
 * the other alternative (submit anyway) would apply a cap nobody can see.
 *
 * <p>Presentational on purpose: every value and every handler comes from the
 * caller. The two callers have genuinely different state problems - the edit
 * modal receives its cap ASYNCHRONOUSLY and has to distinguish "no cap" from
 * "not loaded yet" or a rename silently uncaps the workflow, while on create
 * everything starts empty and anything typed is deliberate. Owning the state
 * here would have forced the edit modal's machinery onto the create form, or
 * quietly dropped it.
 */
export function BudgetAdvancedSection({
  open,
  onToggle,
  amount,
  onAmountChange,
  periodMode,
  onPeriodModeChange,
}: BudgetAdvancedSectionProps) {
  const t = useTranslations('budget');

  return (
    <div className="pt-1 border-t border-theme">
      <button
        type="button"
        data-testid="budget-advanced-toggle"
        aria-expanded={open}
        onClick={onToggle}
        className="flex items-center gap-1 text-sm font-medium text-theme-primary hover:text-[var(--accent-primary)] transition-colors w-full pt-3"
      >
        {open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
        <span>{t('advancedSection')}</span>
      </button>

      {open && (
        <div className="mt-3" data-testid="budget-advanced-fields">
          <label className="block text-sm font-medium text-theme-primary mb-2">{t('capLabel')}</label>
          <div className="relative">
            {IS_CE && (
              <span className="absolute left-3.5 top-1/2 -translate-y-1/2 text-sm text-theme-secondary pointer-events-none">
                $
              </span>
            )}
            <Input
              type="number"
              min="0"
              step="any"
              data-testid="budget-advanced-amount"
              value={amount}
              onChange={(event) => onAmountChange(event.target.value)}
              placeholder={t('capPlaceholder')}
              className={`w-full ${IS_CE ? 'pl-7' : ''}`}
            />
          </div>
          <p className="mt-1.5 text-xs text-theme-secondary">
            {IS_CE ? t('capHelpDollars') : t('capHelpCredits')}
          </p>
          {/* Belongs to the AMOUNT, not the cadence. Nested under the cadence it
              was invisible exactly when the field was empty, which is the one
              moment "Leave empty for no cap" answers a question the reader is
              actually asking. */}
          <p className="mt-1.5 text-xs text-theme-secondary">{t('capHelp')}</p>

          {/* Reset cadence. Only once a cap is typed: with no cap there is
              nothing to reset, and an empty field must stay the zero-friction
              default (no cap at all, like an agent).
              LOAD-BEARING beyond this component: because the select cannot be
              reached with an empty amount, a caller that tracks "the user has
              touched the cap" by watching the AMOUNT alone is also covering the
              cadence. EditMetadataModal's late-arrival guard relies on exactly
              that. Making this reachable with no amount means revisiting it. */}
          {amount.trim() !== '' && (
            <div className="mt-4">
              <label className="block text-sm font-medium text-theme-primary mb-2">{t('periodLabel')}</label>
              <select
                value={periodMode}
                data-testid="budget-advanced-period"
                onChange={(event) => onPeriodModeChange(event.target.value)}
                className="w-full h-9 rounded-md border border-theme bg-theme-primary px-3 text-sm text-theme-primary"
              >
                <option value="monthly">{t('periodMonthly')}</option>
                <option value="weekly">{t('periodWeekly')}</option>
                <option value="cumulative">{t('periodCumulative')}</option>
              </select>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default BudgetAdvancedSection;
