/**
 * Cost display utilities - CE mode shows dollars ($), Cloud mode shows credits.
 *
 * CE mode uses the user's own LLM API keys, so displaying raw credit numbers
 * is meaningless. Instead, we show the dollar equivalent.
 *
 * In Cloud mode, "credits" is the billing unit users understand.
 */

import { IS_CE } from '@/lib/edition';
import { getClientLocale } from './utils/locale';
import { CREDIT_LIST_USD } from '@/lib/billing/pricing-constants';

/**
 * Whether we're in CE mode (useful for conditional rendering).
 */
export const isCeMode = IS_CE;

/**
 * Convert a credit-denominated ledger amount to its USD value at the canonical list
 * scale (1 credit = $0.001, see {@link CREDIT_LIST_USD}). Pure numeric conversion - no
 * formatting or sign. Ledger amounts (local CE ledger and the cloud-linked relay mirror)
 * are stored in credits; CE shows spend in dollars, so callers convert before display.
 */
export function creditsToUsd(credits: number): number {
  return credits * CREDIT_LIST_USD;
}

/**
 * Format the MAGNITUDE of a dollar amount: 2 decimals, but up to 4 for sub-dollar amounts
 * (a single LLM call is often a fraction of a cent). Always unsigned - callers prepend the
 * sign BEFORE the "$" (e.g. "-$0.0086", not "$-0.0086") so every CE cost surface reads
 * consistently. Mirrors the quota page's formatter.
 */
function formatCeDollars(dollars: number): string {
  const abs = Math.abs(dollars);
  const maxFractionDigits = abs > 0 && abs < 1 ? 4 : 2;
  return abs.toLocaleString(getClientLocale(), { minimumFractionDigits: 2, maximumFractionDigits: maxFractionDigits });
}

/**
 * Format a cost value for display. The value is a CREDIT amount; CE renders the dollar
 * equivalent (1 credit = $0.001), Cloud renders the credits at the caller's precision,
 * locale-grouped (per the app locale, e.g. en "72,984.2097" / fr "72 984,2097").
 * CE → "$1.23"   Cloud → "1.2300". Returns '-' only for null/undefined; zero is valid.
 * The {@code decimals} arg applies to the Cloud (credit) path; the CE dollar path uses
 * adaptive cents-or-sub-cent precision instead (credit-tuned decimals over-/under-state $).
 */
export function formatCost(value: number | null | undefined, decimals = 2): string {
  if (value == null) return '-';
  if (!IS_CE) return value.toLocaleString(getClientLocale(), { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
  const usd = creditsToUsd(value);
  // Sign before the "$" - "-$0.0086", never "$-0.0086".
  return `${usd < 0 ? '-' : ''}$${formatCeDollars(usd)}`;
}

/**
 * Format a cost value, returning '-' for null/undefined AND zero.
 * Use when zero means "nothing to show" (e.g., execution records).
 */
export function formatCostOrDash(value: number | null | undefined, decimals = 1): string {
  if (value == null || value === 0) return '-';
  if (!IS_CE) return value.toLocaleString(getClientLocale(), { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
  const usd = creditsToUsd(value);
  // Sign before the "$" - "-$0.0086", never "$-0.0086".
  return `${usd < 0 ? '-' : ''}$${formatCeDollars(usd)}`;
}

/**
 * Compact credit display with K / M abbreviation. Used in the sidebar coin
 * badge, the BalanceBreakdownCard, and the TopUpModal tier cards - i.e.
 * anywhere a single credit number must fit in a tight space without
 * decoration. {@code value=null} returns the em-dash placeholder so a
 * loading state renders consistently.
 *
 * Examples: {@code 1234567 → "1.2M"}, {@code 5000 → "5.0K"},
 * {@code 12.3 → "12.3"}, {@code null → "-"}.
 */
export function formatCreditsCompact(value: number | null | undefined, locale?: string): string {
  if (value === null || value === undefined) return '-';
  // The mantissa is a DECIMAL NUMBER and must be spelled for the app locale.
  // `toFixed` always emits a dot, so a German reader saw "9.8K" - which in
  // German reads as nine thousand eight hundred - beside a panel row spelling
  // the same wallet "9.779". The K/M suffix itself is left alone: it is a unit
  // this app uses in every locale, and swapping it per language would break the
  // fixed-width budget these badges exist for.
  const one = (n: number) =>
    n.toLocaleString(locale ?? getClientLocale(), {
      minimumFractionDigits: 1,
      maximumFractionDigits: 1,
      // No thousands separator INSIDE an abbreviation. The mantissa is under
      // 1000 by construction except in the rounding edge (999,999 rounds to
      // 1000.0K), and grouping it there produced "1,000.0K" - a separator in a
      // form whose entire purpose is to not need one.
      useGrouping: false,
    });
  if (value >= 1_000_000) return `${one(value / 1_000_000)}M`;
  if (value >= 1_000) return `${one(value / 1_000)}K`;
  return one(value);
}

/**
 * Compact cost for WIDTH-CONSTRAINED cells (the agent-metrics credit/cost column).
 * Large magnitudes get K/M/B abbreviation so a big aggregate (e.g. a fleet total of
 * millions of credits) never overflows the narrow column; small amounts keep
 * human-meaningful precision, since a single cheap call is a fraction of a
 * cent/credit. Pair with a precise {@link formatCost}(v, 4) in a hover `title` so the
 * exact figure stays one hover away. Returns '-' for null/undefined; zero is valid.
 *
 * CE    → "$5.0M", "$3.42", "$0.0025"
 * Cloud → "5.0M",  "3.42",  "0.0025"
 *
 * Distinct from {@link formatCreditsCompact} (credit-only badge: always 1 decimal,
 * no CE-dollar branch, no sub-cent precision, no B suffix). Mirrors the K/M
 * thresholds the agent-metrics token columns already use (formatNumber).
 */
export function formatCostCompact(value: number | null | undefined): string {
  if (value == null) return '-';
  const display = IS_CE ? creditsToUsd(value) : value; // dollars in CE, credits in Cloud
  const prefix = IS_CE ? '$' : '';
  const sign = display < 0 ? '-' : '';
  const abs = Math.abs(display);
  if (abs >= 1_000_000_000) return `${sign}${prefix}${(abs / 1_000_000_000).toFixed(1)}B`;
  if (abs >= 1_000_000) return `${sign}${prefix}${(abs / 1_000_000).toFixed(1)}M`;
  if (abs >= 1_000) return `${sign}${prefix}${(abs / 1_000).toFixed(1)}K`;
  // Below 1000 no abbreviation is needed; sub-unit amounts keep up to 4 decimals
  // (same adaptive precision as the CE dollar path).
  const maxFractionDigits = abs > 0 && abs < 1 ? 4 : 2;
  const body = abs.toLocaleString(getClientLocale(), { minimumFractionDigits: 2, maximumFractionDigits: maxFractionDigits });
  return `${sign}${prefix}${body}`;
}
