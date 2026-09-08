import type { useTranslations } from 'next-intl';
import type { PlatformCredentialPublicInfo } from '@/lib/api/orchestrator';
import { priceUnitLabel } from '@/lib/credentials/priceUnits';
import { getClientLocale } from '@/lib/utils/locale';

/**
 * A price unit in the reader's language.
 *
 * <p>`priceUnit` and `billed_unit` are wire tokens from the platform's own
 * enum, always English. Dropping one into a translated sentence produced
 * "60 credits per second" inside a French page and an English word inside a
 * Chinese one. The same placeholder is already rendered correctly by the
 * workflow inspector through `priceUnitLabel`, so this reuses that helper and
 * its dictionary rather than adding a second one.
 */
export function localizedUnit(unit: string | undefined, tUnits: ReturnType<typeof useTranslations>): string {
  return unit ? priceUnitLabel(unit, tUnits) : '';
}

/**
 * A credit amount as the reader's app locale groups it.
 *
 * <p>These are numbers on screen, so they follow the repo rule for numbers:
 * the APP locale (the URL segment, else the NEXT_LOCALE cookie), never the
 * browser's and never a hardcoded one. A bare String() printed 600000 to a
 * French reader whose every other figure on the page reads 600 000, and it
 * did so on two surfaces at once once this arithmetic was shared.
 *
 * <p>Trailing zeros are dropped rather than padded: a rate is quoted as it
 * was published, and 60.00 credits per second states a precision the
 * catalogue does not have.
 */
function formatCredits(value: number): string {
  // A non-finite amount is not a number to show: markupCredits is only
  // null-checked upstream, so a malformed one would otherwise render the
  // literal "NaN" into a price sentence. Empty, and the caller falls back to
  // the unpriced note.
  if (!Number.isFinite(value)) return '';
  return value.toLocaleString(getClientLocale(), { maximumFractionDigits: 6 });
}

/**
 * What this model costs, read from the PUBLISHED price rather than the seed.
 *
 * <p>The model listing carries a `price` too, but it is the list rate shipped
 * with the catalog seed, and the amount actually charged comes from the pricing
 * version an administrator published, which they can and do change. Quoting the
 * seed here would have one screen state one number while the invoice states
 * another. So the components come from the same quote endpoint every surface
 * uses: one price, reached by one arithmetic, wherever it is shown.
 *
 * <p>Shared by the studio composer and the workflow inspector's Generate node.
 * Both state a price before the spend is committed, so they have to phrase the
 * same quote the same way; two copies of this arithmetic is how the two
 * surfaces end up quoting one model differently. (It was written for the
 * generation dialog, which the studio replaced.)
 *
 * <p>The floor and ceiling are included because a rate on its own understates a
 * model that carries a minimum: "4 credits per second" for a model whose floor
 * is 8 describes a price no short call can actually cost.
 *
 * <p>Returns an empty string when nothing is published. That is not "free": a
 * generation with no published price is REFUSED on the platform key, so the
 * caller shows the unpriced note instead of an amount.
 *
 * @param t the `generation` namespace, which owns the `price.*` wording.
 * @param tUnits the `credentials` namespace, which owns the unit names.
 */
export function describeQuotedPrice(
  quote: PlatformCredentialPublicInfo | undefined,
  t: ReturnType<typeof useTranslations>,
  tUnits: ReturnType<typeof useTranslations>,
): string {
  // Covers the version-default case too, and is the only check that can: the
  // server never emits a price alongside `versionDefaultOnly`, on either leg
  // (the local quote computes `hasPricing` as "positive AND not the
  // credential-wide default", and the CE cloud relay resolves no markup at all
  // for one). A second client-side copy of that rule looked like a belt to the
  // server's braces and was simply unreachable, so it certified nothing while
  // reading as though it protected the spend button.
  if (!quote?.hasPricing) return '';
  const rate = Number(quote.unitCredits);
  const base = Number(quote.baseCredits);
  const parts: string[] = [];

  if (Number.isFinite(rate) && rate > 0 && quote.priceUnit && quote.priceUnit !== 'call') {
    parts.push(t('price.perUnit', { rate: formatCredits(rate), unit: localizedUnit(quote.priceUnit, tUnits) }));
    if (Number.isFinite(base) && base > 0) {
      parts.push(t('price.plusBase', { base: formatCredits(base) }));
    }
    // The total for THIS request, when the quote knew its size. It is the
    // number that will be charged, so it leads rather than being implied.
    // Finite, not merely present. formatCredits answers '' for a value it cannot
    // render, and here `parts` already holds the per-unit rate, so the sentence
    // would come back TRUTHY with an empty amount in it ("Total:  credits") and
    // the caller's unpriced fallback would never fire.
    if (quote.quantity != null && Number.isFinite(Number(quote.markupCredits))) {
      parts.unshift(t('price.total', { credits: formatCredits(Number(quote.markupCredits)) }));
    }
  } else {
    const flat = Number.isFinite(Number(quote.markupCredits)) && Number(quote.markupCredits) > 0
      ? Number(quote.markupCredits)
      : (Number.isFinite(base) && base > 0 ? base : rate);
    if (!Number.isFinite(flat) || flat <= 0) return '';
    parts.push(t('price.flat', { credits: formatCredits(flat) }));
  }

  const min = quote.minCredits == null ? null : Number(quote.minCredits);
  const max = quote.maxCredits == null ? null : Number(quote.maxCredits);
  if (min != null && Number.isFinite(min) && min > 0) {
    parts.push(t('price.min', { credits: formatCredits(min) }));
  }
  if (max != null && Number.isFinite(max) && max > 0) {
    parts.push(t('price.max', { credits: formatCredits(max) }));
  }
  return parts.join(', ');
}
