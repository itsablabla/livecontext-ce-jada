// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest';
import { describeQuotedPrice, localizedUnit } from '../price';

/**
 * The price sentence two surfaces now share.
 *
 * <p>This arithmetic was lifted out of the generation dialog so the workflow
 * inspector could state a price on each model row without pulling a
 * 1500-line component into the builder bundle. Extracting it made it the single
 * place both surfaces get their number from, and a shared helper that neither
 * surface tests directly is the one whose regressions show up as two screens
 * quoting the same model differently.
 *
 * <p>The two properties that actually decide money: an unpublished price must
 * read as UNPRICED rather than as free (a generation with no published rate is
 * REFUSED on the platform key, so a blank there would invite the one choice
 * that cannot work), and the total for the request has to LEAD the sentence,
 * because that is the amount that will be charged.
 */

/** The i18n stand-ins: the key IS the text, so an assertion names what it depends on. */
const t = ((key: string, values?: Record<string, unknown>) =>
  values ? `${key}(${Object.values(values).join('|')})` : key) as never;
const tUnits = ((key: string) => key) as never;

describe('describeQuotedPrice', () => {
  it('says nothing at all when no price is published, so the caller can say "not sold here"', () => {
    // Empty is NOT "free": the run is refused on the platform key, and the
    // caller turns this into the unpriced note rather than an amount.
    expect(describeQuotedPrice({ hasPricing: false } as never, t, tUnits)).toBe('');
    expect(describeQuotedPrice(undefined, t, tUnits)).toBe('');
  });

  it('leads with the total for THIS request, then the rate it came from', () => {
    const sentence = describeQuotedPrice(
      {
        hasPricing: true,
        unitCredits: '60',
        baseCredits: '0',
        priceUnit: 'second',
        quantity: 10,
        markupCredits: '600',
      } as never,
      t,
      tUnits,
    );

    // The amount charged comes first; the per-unit rate explains it.
    expect(sentence.indexOf('price.total')).toBe(0);
    expect(sentence).toContain('600');
    expect(sentence).toContain('price.perUnit');
  });

  it('states a rate alone when the quote did not know the size of the request', () => {
    const sentence = describeQuotedPrice(
      { hasPricing: true, unitCredits: '60', baseCredits: '0', priceUnit: 'second' } as never,
      t,
      tUnits,
    );

    expect(sentence).toContain('price.perUnit');
    expect(sentence).not.toContain('price.total');
  });

  it('reads a per-CALL price as a flat amount, not as a rate per call', () => {
    // unitCredits is non-zero on purpose. With '0' the branch is taken for the
    // wrong reason (rate > 0 fails first), so the `priceUnit !== 'call'` half of
    // the guard could be deleted and this case would still pass.
    const sentence = describeQuotedPrice(
      { hasPricing: true, unitCredits: '40', baseCredits: '0', priceUnit: 'call' } as never,
      t,
      tUnits,
    );

    expect(sentence).toContain('price.flat');
    expect(sentence).not.toContain('price.perUnit');
  });

  it('carries the floor and the ceiling, because a rate alone understates a model that has one', () => {
    // "4 credits per second" on a model whose minimum is 8 describes a price no
    // short call can actually cost.
    const sentence = describeQuotedPrice(
      {
        hasPricing: true,
        unitCredits: '4',
        baseCredits: '0',
        priceUnit: 'second',
        minCredits: '8',
        maxCredits: '400',
      } as never,
      t,
      tUnits,
    );

    expect(sentence).toContain('price.min');
    expect(sentence).toContain('price.max');
  });

  it('says nothing when the published amounts are all zero, rather than quoting zero', () => {
    expect(
      describeQuotedPrice(
        { hasPricing: true, unitCredits: '0', baseCredits: '0', priceUnit: 'call' } as never,
        t,
        tUnits,
      ),
    ).toBe('');
  });
});

/**
 * The one thing this file introduced that the dialog did not already do.
 *
 * <p>Extracting the arithmetic was a move; replacing String(rate) with the
 * app locale's grouping was a change, and the repo's rule for numbers is that
 * a reader sees them grouped the way the rest of the page groups them. Every
 * other case here uses 4, 8, 40, 60, 400, 600 - values where grouping and
 * fraction digits are invisible, so reverting the change passed all of them.
 */
describe('credit amounts follow the app locale', () => {
  const localeCookie = (value: string | null) => {
    Object.defineProperty(document, 'cookie', {
      value: value == null ? '' : `NEXT_LOCALE=${value}`,
      configurable: true,
      writable: true,
    });
  };

  afterEach(() => localeCookie(null));

  it('groups a large amount, which is the case the raw number gets wrong', () => {
    localeCookie('fr');
    const sentence = describeQuotedPrice(
      { hasPricing: true, unitCredits: '600000', baseCredits: '0', priceUnit: 'second' } as never,
      t,
      tUnits,
    );

    // A French reader whose every other figure on the page reads 600 000 was
    // shown 600000 here. Asserted as "not the raw number" plus "grouped",
    // since the separator itself is the platform's business, not this test's.
    expect(sentence).not.toContain('600000');
    expect(sentence).toMatch(/600.000/);
  });

  it('groups under the default locale too, so this is not a French-only path', () => {
    const sentence = describeQuotedPrice(
      { hasPricing: true, unitCredits: '0', baseCredits: '1234567', priceUnit: 'call' } as never,
      t,
      tUnits,
    );

    expect(sentence).not.toContain('1234567');
  });

  it('does not pad a whole number with decimals it does not have', () => {
    const sentence = describeQuotedPrice(
      { hasPricing: true, unitCredits: '60', baseCredits: '0', priceUnit: 'second' } as never,
      t,
      tUnits,
    );

    // 60.00 credits per second states a precision the catalogue does not have.
    expect(sentence).toContain('60');
    expect(sentence).not.toMatch(/60[.,]0/);
  });
});

describe('localizedUnit', () => {
  it('translates the unit instead of dropping the wire token into a translated sentence', () => {
    // `priceUnit` is an English enum token; unlocalised it produced "60 credits
    // per second" inside a French page.
    expect(localizedUnit('second', tUnits)).toBe('source.priceUnits.second');
  });

  it('is empty for an absent unit, so the sentence does not gain a stray word', () => {
    expect(localizedUnit(undefined, tUnits)).toBe('');
  });
});
