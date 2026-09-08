import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';
import { CREDIT_EXAMPLES, CREDIT_EXAMPLES_FAQ_KEY, FAQ_KEYS } from '@/lib/billing/pricing-constants';

/**
 * The worked credit examples are TRANSLATED figures rendered large. Pinned on
 * one line they overflow a phone and push the whole page into a horizontal
 * scroll, which is invisible in every desktop check and in every unit test that
 * only asserts text.
 *
 * The regression this guards is real: the first version of this block was a
 * band of its own whose headline carried `whitespace-nowrap` at `text-3xl`, and
 * five of the six locales overflowed a 320px phone. The band is gone (the page
 * used to ask and answer the same question twice, once above the FAQ and once
 * inside it) but the figures it carried are now rendered inside the
 * conversation-cost answer, at the same sizes and with the same hazard.
 *
 * Class names rather than a rendered layout, because jsdom computes no widths:
 * this is the same way the pickers pin their stacking order.
 */

const SOURCE = fs.readFileSync(
  path.join(process.cwd(), 'components/pricing/PricingPageContent.tsx'),
  'utf8'
);

/**
 * The examples block: from the `key === CREDIT_EXAMPLES_FAQ_KEY` guard that
 * introduces it to the end of the FAQ section.
 *
 * Anchored on CODE, not on the prose comment above it. A comment-anchored slice
 * silently becomes `slice(-1)` the day someone rewords the comment, and every
 * `not.toContain` below would then pass on an empty string.
 */
const GUARD = '{key === CREDIT_EXAMPLES_FAQ_KEY && (';
const BLOCK_START = SOURCE.indexOf(GUARD);
const BLOCK_END = SOURCE.indexOf('{/* CTA Section */}');
const BLOCK = SOURCE.slice(BLOCK_START, BLOCK_END);

describe('the block this file is about', () => {
  it('was actually found, so nothing below can pass vacuously', () => {
    expect(BLOCK_START, `"${GUARD}" not found in PricingPageContent.tsx`).toBeGreaterThan(-1);
    expect(BLOCK_END).toBeGreaterThan(BLOCK_START);
    expect(BLOCK).toContain('examplesCaption');
    expect(BLOCK).toContain('CREDIT_EXAMPLES.map');
  });

  it('renders under exactly one FAQ entry, and it is the one that asks the question', () => {
    // The guard is what stops the other five answers asking for an
    // `examplesCaption` they do not have. Nothing renders this page in a test,
    // so this is the only place that failure can be caught.
    expect(SOURCE.split(GUARD)).toHaveLength(2);
    expect(SOURCE.match(/examplesCaption/g) ?? []).toHaveLength(1);
    expect(SOURCE.indexOf('examplesCaption')).toBeGreaterThan(BLOCK_START);
  });
});

describe('the credit examples, on a narrow screen', () => {
  it('never pins a translated figure to one line', () => {
    expect(BLOCK).not.toContain('whitespace-nowrap');
  });

  it('steps each figure down below the sm breakpoint', () => {
    // Large on a desktop, one size smaller on a phone: the label is long in
    // every locale and the FAQ card has ~240px of content width at 320px.
    expect(BLOCK).toContain('text-lg sm:text-xl');
  });

  it('puts the three examples in one column before sm', () => {
    // `grid sm:grid-cols-3` is one column below the breakpoint. A bare
    // `grid-cols-3` would squeeze three figures into a phone's width.
    expect(BLOCK).toMatch(/<dl className="mt-3 grid sm:grid-cols-3/);
    expect(BLOCK).not.toMatch(/<dl className="[^"]*grid grid-cols-3/);
  });

  it('lets each example cell shrink instead of forcing the grid wider', () => {
    expect(BLOCK).toContain('min-w-0');
  });
});

describe('the credits band it replaced', () => {
  it('is gone from the page, so the estimate is stated exactly once', () => {
    // The removed band was the ONLY reader of `credits.reference.*`. A stray
    // reference left behind would render a raw message key, silently. (That the
    // MESSAGES are gone too is pinned in credit-conversation-copy.test.ts.)
    expect(SOURCE).not.toContain('credits.reference');
  });
});

describe('the examples, per locale', () => {
  const LOCALES: Record<string, any> = { en, fr, de, es, pt, zh };
  /** 320px phone, less the container's px-4 and the FAQ card's p-6. */
  const NARROW_CONTENT_WIDTH = 320 - 2 * 16 - 2 * 24;

  /** Rough advance width at text-lg (18px): CJK is full-width, latin about half. */
  function approximateWidth(text: string, fontSize: number): number {
    return [...text].reduce((total, char) => {
      if (char === ' ') return total + fontSize * 0.27;
      return total + (char.codePointAt(0)! > 0x2e80 ? fontSize : fontSize * 0.5);
    }, 0);
  }

  function renderedFigures(locale: string): string[] {
    const faq = (LOCALES[locale] as any).pricing.faq[CREDIT_EXAMPLES_FAQ_KEY];
    return CREDIT_EXAMPLES.map((example) =>
      faq.examples[example.id].count.replace('{count}', String(example.perEntryPack))
    );
  }

  it('states a figure too long for a phone line, which is WHY nothing may pin one', () => {
    // Unconditional on purpose. The earlier version of this test only asserted
    // anything INSIDE an `if (tooWide)`, so a future set of shorter labels
    // would have made it run zero assertions and still report green - the
    // failure mode it was written to prevent, wearing a test's clothes.
    const widest = Math.max(
      ...Object.keys(LOCALES).flatMap((locale) =>
        renderedFigures(locale).map((figure) => approximateWidth(figure, 18))
      )
    );
    expect(widest).toBeGreaterThan(NARROW_CONTENT_WIDTH);
    expect(BLOCK).not.toContain('whitespace-nowrap');
  });

  it.each(Object.keys(LOCALES))('%s states all three figures with something to show', (locale) => {
    const figures = renderedFigures(locale);
    expect(figures).toHaveLength(CREDIT_EXAMPLES.length);
    for (const figure of figures) {
      expect(figure.trim().length).toBeGreaterThan(0);
      // A count left un-interpolated would print "{count} conversations".
      expect(figure).not.toContain('{count}');
    }
  });

  it.each(Object.keys(LOCALES))('%s keeps the examples off every other FAQ entry', (locale) => {
    // The mirror image of the guard test above: the other answers do not carry
    // these keys, which is exactly why rendering the block under them would
    // print raw message keys.
    const faq = (LOCALES[locale] as any).pricing.faq;
    for (const key of FAQ_KEYS.filter((k) => k !== CREDIT_EXAMPLES_FAQ_KEY)) {
      expect(faq[key]?.examplesCaption, `${locale}.${key}`).toBeUndefined();
      expect(faq[key]?.examples, `${locale}.${key}`).toBeUndefined();
    }
  });
});
