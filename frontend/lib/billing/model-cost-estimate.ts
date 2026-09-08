/**
 * Pre-flight "what will this model cost me?" for the model pickers.
 *
 * <p><b>Why the numbers are not computed here.</b> The coefficients come from
 * {@code GET /api/credits/estimate-basis}. The margin lever has exactly one home
 * (auth-service `billing.llm.cloud-multiplier`), so a picker that restated it
 * would quote a price the ledger does not charge the day it moves. The server
 * folds the multiplier and the profile's token workload into ONE coefficient per
 * rate, which also keeps a field named "multiplier" off the wire. This module
 * only does the multiply-add the backend authorises.
 *
 * <p><b>Why the arithmetic is safe to do client-side.</b> The profiles behind
 * those coefficients carry no cache tokens, and with no cache tokens every
 * provider family prices the same workload identically, so the billing formula
 * collapses to `inputRate x inputCoefficient + outputRate x outputCoefficient`.
 * The backend pins that equivalence against the real billing code
 * (`LlmCostProfileFormulaTest`, `LlmCostEstimateServiceTest`); it is the contract
 * this file relies on.
 */

/**
 * One profile as the backend publishes it: the factor each of the model's rates
 * (USD per 1M tokens) is multiplied by to reach credits.
 */
export interface LlmCostProfile {
  inputCoefficient: number;
  outputCoefficient: number;
}

/** Response of `GET /api/credits/estimate-basis`. */
export interface ModelCostBasis {
  /** False where the install does not meter credits (CE): show nothing. */
  enabled: boolean;
  profiles: Record<string, LlmCostProfile>;
}

/**
 * Which shape of work a picker is pricing. Mirrors `LlmCostProfile` in
 * auth-service; the string values ARE the keys of the published `profiles` map.
 */
export type CostProfileId = 'agentConversation' | 'chatConversation' | 'guardrailCheck' | 'classifyStep';

/** A model's list rates, as the catalogue serves them (USD per 1M tokens). */
export interface ModelRates {
  input?: number;
  output?: number;
}

/**
 * Credits one unit of `profileId` would cost on a model billed at `rates`.
 * Returns null when anything needed is missing (unpriced model, CE, a profile
 * the backend did not publish) so the caller can simply render nothing.
 */
export function estimateModelCredits(
  rates: ModelRates | undefined | null,
  basis: ModelCostBasis | undefined | null,
  profileId: CostProfileId
): number | null {
  if (!basis?.enabled) return null;
  const profile = basis.profiles?.[profileId];
  if (!profile
      || typeof profile.inputCoefficient !== 'number'
      || typeof profile.outputCoefficient !== 'number') {
    return null;
  }
  const inputRate = rates?.input;
  const outputRate = rates?.output;
  if (typeof inputRate !== 'number' || typeof outputRate !== 'number') return null;
  // A zero-rate row is a real answer (a free model), a negative one is catalogue
  // garbage (the openrouter/auto router's "-1" sentinel) and must not be shown.
  if (inputRate < 0 || outputRate < 0) return null;

  return inputRate * profile.inputCoefficient + outputRate * profile.outputCoefficient;
}

/**
 * Round an estimate to something a reader can hold in their head. It is an
 * estimate of a median, so precision past the leading digits would be false
 * confidence: under 10 credits keeps one decimal (a classify step at 3.4 is
 * meaningfully different from 0.4), 10 and above rounds to a whole credit and
 * 1,000 and above to the nearest ten.
 */
export function roundCreditEstimate(credits: number): number {
  if (credits < 10) return Math.round(credits * 10) / 10;
  if (credits < 1000) return Math.round(credits);
  return Math.round(credits / 10) * 10;
}

/**
 * Formatted estimate for display, in the app locale, or null when there is
 * nothing to show. Never returns "0": a model too cheap to round to a tenth of
 * a credit is shown as "<0.1" by the caller's message, not as free.
 */
export function formatCreditEstimate(
  rates: ModelRates | undefined | null,
  basis: ModelCostBasis | undefined | null,
  profileId: CostProfileId,
  locale: string
): string | null {
  const credits = estimateModelCredits(rates, basis, profileId);
  if (credits === null) return null;
  const rounded = roundCreditEstimate(credits);
  if (rounded <= 0) return credits > 0 ? '<0.1' : '0';
  return rounded.toLocaleString(locale);
}
