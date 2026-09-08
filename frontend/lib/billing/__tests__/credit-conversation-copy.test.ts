import { describe, it, expect } from 'vitest';
import {
  creditFactsFor,
  CREDIT_TIERS,
  CREDIT_EXAMPLES,
  CREDIT_EXAMPLES_FAQ_KEY,
  AGENT_CONVERSATIONS_PER_PACK,
  SIMPLE_CONVERSATIONS_PER_PACK,
  FAQ_KEYS,
} from '@/lib/billing/pricing-constants';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * "Your credits cover about N conversations" is the one number on the pricing
 * page a reader can check against their own invoice, so it gets the same
 * treatment as any other cross-layer contract.
 *
 * Three things can break it silently. An EXAMPLE can drift, so that the page
 * promises more work than the pack can pay for. The two ENDS of the headline
 * range can cross, which would read as a typo. And the COPY can lose a locale
 * or a placeholder: a missing key falls back to English and a misspelt
 * placeholder renders as a literal `{agentConversations}`, neither of which
 * throws, so nothing else in the suite would notice.
 */

const LOCALES: Record<string, any> = { en, fr, de, es, pt, zh };

/**
 * Every value any surface passes into these messages.
 *
 * <p>A FLAT list, and honestly so. An earlier version dressed this up as a
 * per-surface intersection to catch "a message using a placeholder only one of
 * its readers supplies" - the failure that shipped when the credits tooltip was
 * rewritten and the comparison dialog still passed three values by name. It
 * could not: every surface passes the same `creditFactsFor` object, so the
 * intersection was the union and the test was exactly as strong as before,
 * while its comment claimed otherwise.
 *
 * <p>What this list is for is narrower and real: a message may not invent a
 * placeholder NOBODY passes. The cross-surface guarantee is held by a test that
 * renders the component with the real catalogues in all six locales and fails
 * on next-intl's own FORMATTING_ERROR, which is the only thing that can know
 * what a caller actually passes:
 * components/pricing/__tests__/PlanComparisonDialog.tooltips.test.tsx
 */
const SUPPLIED_PLACEHOLDERS = [
  ...Object.keys(creditFactsFor('en')),
  // The per-example rows are rendered one at a time with their own two values.
  'count',
];

function read(messages: any, path: string): unknown {
  return path.split('.').reduce<any>((node, part) => (node ? node[part] : undefined), messages);
}

/** Message paths under `pricing` that the FAQ and the plan comparison resolve. */
const REQUIRED_PATHS = [
  'compare.dimensions.creditsTooltip',
  `faq.${CREDIT_EXAMPLES_FAQ_KEY}.examplesCaption`,
  ...CREDIT_EXAMPLES.flatMap((example) => [
    `faq.${CREDIT_EXAMPLES_FAQ_KEY}.examples.${example.id}.count`,
    `faq.${CREDIT_EXAMPLES_FAQ_KEY}.examples.${example.id}.detail`,
  ]),
  ...FAQ_KEYS.flatMap((key) => [`faq.${key}.question`, `faq.${key}.answer`]),
];

describe('credit examples', () => {
  it.each(CREDIT_EXAMPLES)('$id never promises more than the entry pack can pay for', (example) => {
    // The page says the pack buys `perEntryPack` of these and that one costs
    // `creditsEach`. Multiply them back out and the claim has to fit inside the
    // pack the page is talking about.
    expect(example.creditsEach * example.perEntryPack).toBeLessThanOrEqual(CREDIT_TIERS[0]);
  });

  it.each(CREDIT_EXAMPLES)('$id stays close enough to the pack to still be a useful figure', (example) => {
    // The opposite failure: rounding down so hard the page undersells itself and
    // stops matching what a user sees in their own usage.
    expect(example.creditsEach * example.perEntryPack).toBeGreaterThanOrEqual(CREDIT_TIERS[0] * 0.6);
  });

  it('keeps an agent conversation the expensive end and a plain one the cheap end', () => {
    // The whole point of splitting the figure: an agent re-sends the transcript
    // on every tool round-trip, so it must never be quoted as the cheaper case.
    const agent = CREDIT_EXAMPLES.find((example) => example.id === 'agentChat')!;
    const simple = CREDIT_EXAMPLES.find((example) => example.id === 'simpleChat')!;
    const classify = CREDIT_EXAMPLES.find((example) => example.id === 'classifyStep')!;
    expect(agent.creditsEach).toBeGreaterThan(simple.creditsEach);
    expect(simple.creditsEach).toBeGreaterThan(classify.creditsEach);
  });

  it('reads as a range, low end first', () => {
    expect(AGENT_CONVERSATIONS_PER_PACK).toBeLessThan(SIMPLE_CONVERSATIONS_PER_PACK);
  });

  it('quotes the headline range straight from the examples it illustrates', () => {
    // A reader who divides the pack by the per-conversation figure must land on
    // the headline, so the two cannot be edited apart.
    expect(AGENT_CONVERSATIONS_PER_PACK).toBe(
      CREDIT_EXAMPLES.find((example) => example.id === 'agentChat')!.perEntryPack
    );
    expect(SIMPLE_CONVERSATIONS_PER_PACK).toBe(
      CREDIT_EXAMPLES.find((example) => example.id === 'simpleChat')!.perEntryPack
    );
  });

  it('quotes round numbers, so the copy reads as an estimate rather than a guarantee', () => {
    for (const example of CREDIT_EXAMPLES) {
      expect(example.perEntryPack % 5).toBe(0);
    }
  });
});

describe('credit-to-conversation copy', () => {
  it.each(Object.keys(LOCALES))('%s translates every credits and FAQ string', (locale) => {
    const pricing = (LOCALES[locale] as any)?.pricing ?? {};
    const missing = REQUIRED_PATHS.filter((path) => {
      const value = read(pricing, path);
      return typeof value !== 'string' || value.trim() === '';
    });
    expect(missing).toEqual([]);
  });

  it.each(Object.keys(LOCALES))('%s only uses placeholders the page actually supplies', (locale) => {
    const pricing = (LOCALES[locale] as any)?.pricing ?? {};
    const unknown: string[] = [];
    for (const path of REQUIRED_PATHS) {
      const value = read(pricing, path);
      if (typeof value !== 'string') continue;
      for (const match of value.matchAll(/\{(\w+)\}/g)) {
        if (!SUPPLIED_PLACEHOLDERS.includes(match[1])) {
          unknown.push(`${path}: {${match[1]}}`);
        }
      }
    }
    expect(unknown).toEqual([]);
  });

  it.each(Object.keys(LOCALES))('%s names the pack the figures are counted against', (locale) => {
    // The three counts below the answer are meaningless without the pack they
    // divide. A translation that drops the placeholder leaves them floating.
    const caption = read(
      (LOCALES[locale] as any)?.pricing ?? {},
      `faq.${CREDIT_EXAMPLES_FAQ_KEY}.examplesCaption`
    );
    expect(caption).toContain('{credits}');
  });

  it.each(Object.keys(LOCALES))('%s prices each example rather than only counting them', (locale) => {
    // `count` answers "how many", `detail` answers "at what each". A detail
    // that lost its placeholder would state the shape of the work and forget
    // to price it, which is the half a reader came for.
    const faq = (LOCALES[locale] as any)?.pricing?.faq?.[CREDIT_EXAMPLES_FAQ_KEY] ?? {};
    for (const example of CREDIT_EXAMPLES) {
      expect(faq.examples?.[example.id]?.count, `${locale}.${example.id}`).toContain('{count}');
      expect(faq.examples?.[example.id]?.detail, `${locale}.${example.id}`).toContain('{credits}');
    }
  });

  it.each(Object.keys(LOCALES))('%s no longer ships the band the FAQ replaced', (locale) => {
    // The page asked and answered the same question twice; the band is gone and
    // its messages with it, or the next editor updates copy nobody reads.
    expect((LOCALES[locale] as any)?.pricing?.credits?.reference).toBeUndefined();
  });

  it.each(Object.keys(LOCALES))('%s tells the FAQ answer apart for agent and tool-free conversations', (locale) => {
    const answer = read((LOCALES[locale] as any)?.pricing ?? {}, 'faq.conversationCost.answer');
    expect(answer).toContain('{agentCredits}');
    expect(answer).toContain('{simpleCredits}');
    expect(answer).toContain('{classifyCredits}');
  });

  it('lists the conversation-cost answer first, where a reader looks for it', () => {
    expect(FAQ_KEYS[0]).toBe('conversationCost');
  });

  it.each(Object.keys(LOCALES))('%s writes no em-dash or en-dash in the new copy', (locale) => {
    const pricing = (LOCALES[locale] as any)?.pricing ?? {};
    const offenders = REQUIRED_PATHS.filter((path) => {
      const value = read(pricing, path);
      return typeof value === 'string' && /[--]/.test(value);
    });
    expect(offenders).toEqual([]);
  });
});
