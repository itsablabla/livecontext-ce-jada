import { describe, it, expect } from 'vitest';
import {
  estimateModelCredits,
  formatCreditEstimate,
  roundCreditEstimate,
  type ModelCostBasis,
} from '@/lib/billing/model-cost-estimate';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * The picker tells a user what a model will cost BEFORE they pick it, and it
 * does that arithmetic here rather than asking the server per model. That is
 * only honest while this file lands on the same number the ledger will debit,
 * so the expectations below are the figures auth-service computes through the
 * real billing code (`LlmCostProfileFormulaTest` pins the other side).
 *
 * The quiet failure this guards is a number appearing where none should: an
 * unpriced model, a catalogue sentinel rate, or a self-hosted install with no
 * margin and no credits. Each of those must render nothing, not a zero.
 */

/**
 * What `GET /api/credits/estimate-basis` serves today: one coefficient per rate,
 * the profile's token workload and the billing multiplier already folded in.
 */
const BASIS: ModelCostBasis = {
  enabled: true,
  profiles: {
    agentConversation: { inputCoefficient: 114.33, outputCoefficient: 5.55 },
    chatConversation: { inputCoefficient: 38.85, outputCoefficient: 0.111 },
    guardrailCheck: { inputCoefficient: 17.76, outputCoefficient: 0.111 },
    classifyStep: { inputCoefficient: 1.332, outputCoefficient: 0.0666 },
  },
};

/** Claude Sonnet 5, the model every published figure is quoted against. */
const SONNET_5 = { input: 2, output: 10 };

describe('estimateModelCredits', () => {
  it.each([
    ['agentConversation', 284.16],
    ['chatConversation', 78.81],
    ['guardrailCheck', 36.63],
    ['classifyStep', 3.33],
  ] as const)('prices %s exactly as the billing service does', (profile, expected) => {
    expect(estimateModelCredits(SONNET_5, BASIS, profile)).toBeCloseTo(expected, 2);
  });

  it('scales with the model, so a picker actually compares two models', () => {
    const opus = { input: 5, output: 25 };
    const agentOnOpus = estimateModelCredits(opus, BASIS, 'agentConversation')!;
    const agentOnSonnet = estimateModelCredits(SONNET_5, BASIS, 'agentConversation')!;

    expect(agentOnOpus / agentOnSonnet).toBeCloseTo(2.5, 2);
  });

  it('shows nothing where credits are not metered, which is every CE install', () => {
    const ce: ModelCostBasis = { enabled: false, profiles: {} };

    expect(estimateModelCredits(SONNET_5, ce, 'agentConversation')).toBeNull();
  });

  it('shows nothing before the basis has been answered', () => {
    expect(estimateModelCredits(SONNET_5, null, 'agentConversation')).toBeNull();
    expect(estimateModelCredits(SONNET_5, undefined, 'agentConversation')).toBeNull();
  });

  it('shows nothing for a model the catalogue does not price', () => {
    expect(estimateModelCredits(undefined, BASIS, 'agentConversation')).toBeNull();
    expect(estimateModelCredits({ input: 2 }, BASIS, 'agentConversation')).toBeNull();
  });

  it('shows nothing for a catalogue sentinel rate rather than a negative price', () => {
    // The openrouter/auto router carries "-1" as its list price. Rendering
    // "-1,100 cr" beside it would read as a bug and, worse, as a refund.
    expect(estimateModelCredits({ input: -1, output: -1 }, BASIS, 'agentConversation')).toBeNull();
  });

  it('shows nothing for a profile the server did not publish', () => {
    const partial: ModelCostBasis = { enabled: true, profiles: {} };

    expect(estimateModelCredits(SONNET_5, partial, 'classifyStep')).toBeNull();
  });

  it('prices a genuinely free model at zero rather than hiding it', () => {
    expect(estimateModelCredits({ input: 0, output: 0 }, BASIS, 'agentConversation')).toBe(0);
  });
});

describe('roundCreditEstimate', () => {
  it('keeps a tenth under 10 credits, where the tenth is the whole signal', () => {
    // A classify step at 3.3 and one at 0.4 are a different decision; rounding
    // both to whole credits would erase the cheap end of the catalogue.
    expect(roundCreditEstimate(3.33)).toBe(3.3);
    expect(roundCreditEstimate(0.44)).toBe(0.4);
  });

  it('rounds to whole credits in the middle, and to tens above a thousand', () => {
    expect(roundCreditEstimate(284.16)).toBe(284);
    expect(roundCreditEstimate(78.81)).toBe(79);
    expect(roundCreditEstimate(1234)).toBe(1230);
  });
});

describe('formatCreditEstimate', () => {
  it('formats in the app locale, never the browser one', () => {
    expect(formatCreditEstimate({ input: 20, output: 100 }, BASIS, 'agentConversation', 'en'))
      .toBe('2,840');
    expect(formatCreditEstimate({ input: 20, output: 100 }, BASIS, 'agentConversation', 'fr'))
      .toMatch(/2\s?840/);
  });

  it('says a model is too cheap to price rather than calling it free', () => {
    // A tenth of a credit is the floor of what the badge can express. "0" would
    // read as free, which is a promise the ledger does not keep.
    expect(formatCreditEstimate({ input: 0.0001, output: 0.0001 }, BASIS, 'classifyStep', 'en'))
      .toBe('<0.1');
  });

  it('returns nothing to render when there is no estimate', () => {
    expect(formatCreditEstimate(SONNET_5, { enabled: false, profiles: {} }, 'classifyStep', 'en'))
      .toBeNull();
  });
});

describe('estimate copy', () => {
  const LOCALES: Record<string, any> = { en, fr, de, es, pt, zh };
  const PROFILES = ['agentConversation', 'chatConversation', 'guardrailCheck', 'classifyStep'];

  it.each(Object.keys(LOCALES))('%s labels the badge and every profile tooltip', (locale) => {
    const modelInfo = (LOCALES[locale] as any)?.modelInfo ?? {};
    expect(typeof modelInfo.creditEstimateShort).toBe('string');
    for (const profile of PROFILES) {
      const tooltip = modelInfo.creditEstimateTooltip?.[profile];
      expect(typeof tooltip, `${locale}.${profile}`).toBe('string');
      // Every tooltip states the figure; one that dropped the placeholder would
      // describe the shape of work and forget to price it.
      expect(tooltip, `${locale}.${profile}`).toContain('{credits}');
    }
  });

  it.each(Object.keys(LOCALES))('%s names the unit in full on the badge, never an abbreviation', (locale) => {
    // The badge read "~2,840 cr", which is not a word in any of these
    // languages: a reader who has never met the currency has to guess it, and
    // "cr" beside a price reads as easily as a currency code. So the unit the
    // badge prints must be the unit the sentence explaining it prints - the
    // text right after the figure in the tooltip.
    //
    // Compared at a WORD BOUNDARY, which is the whole difficulty. A plain
    // `startsWith` passes on the abbreviation ("credits for a typical..."
    // does start with "cr"), and splitting the tooltip on punctuation only
    // worked for zh by the accident of the ideographic comma that happens to
    // follow it today. The rule below holds for a space-separated language and
    // for a CJK one alike: what the badge prints must open the tooltip's own
    // wording, and must not stop mid-word.
    const modelInfo = (LOCALES[locale] as any)?.modelInfo ?? {};
    const badgeUnit = modelInfo.creditEstimateShort.split('{credits}')[1]?.trim() ?? '';
    const tooltipRest = modelInfo.creditEstimateTooltip.agentConversation
      .split('{credits}')[1]
      ?.trimStart() ?? '';

    expect(badgeUnit.length, `${locale}.creditEstimateShort states no unit`).toBeGreaterThan(0);
    expect(tooltipRest, `${locale} tooltip states nothing after the figure`).not.toBe('');
    expect(tooltipRest.startsWith(badgeUnit), `${locale}: badge "${badgeUnit}" is not how the tooltip opens`).toBe(true);
    // The character that follows must end the word. "cr" fails here: the
    // tooltip continues "credits", so the next character is a letter.
    const next = tooltipRest.slice(badgeUnit.length, badgeUnit.length + 1);
    expect(/^\p{L}$/u.test(next), `${locale}: badge "${badgeUnit}" truncates the tooltip's word`).toBe(false);
  });

  it.each(Object.keys(LOCALES))('%s writes no em-dash or en-dash in the estimate copy', (locale) => {
    const modelInfo = (LOCALES[locale] as any)?.modelInfo ?? {};
    const strings = [modelInfo.creditEstimateShort, ...PROFILES.map((p) => modelInfo.creditEstimateTooltip?.[p])];
    expect(strings.filter((value) => typeof value === 'string' && /[--]/.test(value))).toEqual([]);
  });
});
