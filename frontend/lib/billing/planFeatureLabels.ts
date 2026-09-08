import { PLAN_FEATURE_KEYS } from '@/lib/billing/pricing-constants';

/**
 * One plan card's feature lines, as plain labels or as `"label||tooltip"`
 * strings for {@link FeatureLabel} to split into a label and an info "i".
 *
 * <p>ONE mapping, read by both surfaces that draw plan cards: the settings
 * pricing page and the public landing section. They each carried their own copy
 * of it, identical down to the comments, differing only in where the credit
 * figure came from. Nothing had diverged yet; what the duplication cost was
 * every edit twice, and this change is one of them: the credits line carried an
 * "i" on the free plan and on no other, so a paid plan quoted a figure with
 * nothing anywhere saying what it buys.
 *
 * <p>The `||` convention is the shared one across the landing pricing section,
 * this page and the insufficient-credits modal; see {@link FeatureLabel}.
 */
export interface PlanFeatureLabelDeps {
  /** Translator scoped to `pricing.planCards`. */
  tCards: (key: string, values?: Record<string, unknown>) => string;
  /**
   * Translator scoped to `pricing`, for the credits tooltip. That message lives
   * under `compare.dimensions` because the plan-comparison table owns it, and
   * the point of reading it from there is that the card and the comparison say
   * the SAME thing about what a month of credits buys.
   */
  tPricing: (key: string, values?: Record<string, unknown>) => string;
  /** The credit pack this card is currently showing, already locale-formatted. */
  credits: string;
  /**
   * What the credits tooltip interpolates: see `creditFactsFor`. Passed in
   * rather than computed here so both surfaces quote one set of figures.
   */
  creditFacts: Record<string, string | number>;
}

/** `label||tooltip`, the shape FeatureLabel splits on. */
function withTooltip(label: string, tooltip: string): string {
  return `${label}||${tooltip}`;
}

export function planFeatureLabels(planId: string, deps: PlanFeatureLabelDeps): string[] {
  const { tCards, tPricing, credits, creditFacts } = deps;
  const creditsTooltip = tPricing('compare.dimensions.creditsTooltip', creditFacts);

  return (PLAN_FEATURE_KEYS[planId] || []).map((key) => {
    switch (key) {
      // The paid plans' monthly pack. It is the line every visitor compares
      // plans on and the one nobody can price from its own words: "50,000
      // credits per month" says nothing about what 50,000 buys. The tooltip is
      // the comparison table's, verbatim, so the two surfaces cannot drift into
      // two different accounts of the same number.
      case 'creditsDynamic':
        return withTooltip(tCards('features.creditsPerMonth', { credits }), creditsTooltip);

      // Enterprise: no figure to show, same question to answer.
      case 'creditsCustom':
        return withTooltip(tCards('features.creditsCustom'), creditsTooltip);

      // Free monthly credits: same "i", different answer. They run workflows
      // only; chat and agents need a paid plan or a top-up.
      case 'creditsFree':
        return withTooltip(tCards('features.creditsFree'), tCards('features.creditsFreeTooltip'));

      // Managed integration credentials for cloud-linked self-hosted installs
      // (relay + per-call credit markup).
      case 'cePlatformCreds':
        return withTooltip(tCards('features.cePlatformCreds'), tCards('features.cePlatformCredsTooltip'));

      // Which integrations a plan unlocks. The line used to carry the brand
      // list inline ("(YouTube, Instagram, TikTok, X, LinkedIn...)"), which is
      // the longest thing on the card, dates the moment one is added, and reads
      // as an exhaustive promise it was never meant to be. The names moved into
      // the "i", where a list belongs and where it can say "and more".
      case 'nodesPublishing':
        return withTooltip(tCards('features.nodesPublishing'), tCards('features.nodesPublishingTooltip'));

      default:
        return tCards(`features.${key}`);
    }
  });
}

export default planFeatureLabels;
