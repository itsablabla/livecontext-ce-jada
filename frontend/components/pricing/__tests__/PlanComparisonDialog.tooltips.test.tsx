// @vitest-environment jsdom
/**
 * Every tooltip the comparison table renders, in every locale, with the REAL
 * catalogues.
 *
 * This is the test that was missing when the credits tooltip was rewritten. The
 * message moved from quoting the entry pack to quoting per-conversation prices;
 * the plan CARDS pass the whole credit-fact set and were fine, while this table
 * passed three values by name. next-intl then threw FORMATTING_ERROR and
 * rendered `pricing.compare.dimensions.creditsTooltip` as literal text, in all
 * six locales, with the whole suite green.
 *
 * Nothing string-based could have caught it. A list of "values this surface
 * supplies" is only ever a copy of what someone believed; the guard has to be
 * the component actually rendering the actual message. So: open the dialog in
 * each locale, read every tooltip it can show, and assert none of them is a raw
 * key and none of them logged a formatting error.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, cleanup, act, fireEvent } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

vi.mock('@/hooks/usePricingEvent', () => ({
  usePricingEvent: () => ({ event: null, serverTime: null, isLoading: false }),
}));

vi.mock('@/i18n/navigation', () => ({
  Link: ({ children, href, onClick }: any) => (
    <a href={typeof href === 'string' ? href : '#'} onClick={onClick}>
      {children}
    </a>
  ),
}));

import PlanComparisonDialog from '../PlanComparisonDialog';
import { openPlanComparison } from '@/lib/billing/plan-comparison-open';

const LOCALES: Record<string, any> = { en, fr, de, es, pt, zh };

/**
 * next-intl reports a missing interpolation value through `onError` and then
 * RENDERS THE KEY PATH. Both halves are asserted: the error is the precise
 * signal, the raw key is what the user actually sees.
 */
function renderIn(locale: string, onError: (error: unknown) => void) {
  // Radix portals the dialog to document.body, so everything below reads the
  // DOCUMENT, not the render container - which is empty by design.
  return render(
    <NextIntlClientProvider locale={locale} messages={LOCALES[locale]} onError={onError}>
      <PlanComparisonDialog currentPlanCode={null} />
    </NextIntlClientProvider>
  );
}

/**
 * Open every info tooltip in the dialog and return what they say.
 *
 * <p>Radix mounts a tooltip's CONTENT only while it is open, so the text is not
 * in the document at rest - the first version of this file read `document.body`
 * without opening anything and swept over an empty set. Focus is what opens
 * them without a pointer.
 *
 * <p>The formatting error is caught either way: `t(tip, values)` runs while the
 * ROW renders, so a missing value throws before any tooltip is opened. Opening
 * them is what makes the second half - the text a reader actually gets - real.
 */
function openEveryTooltip(): string[] {
  const triggers = Array.from(document.body.querySelectorAll('button')).filter((button) =>
    button.querySelector('svg.lucide-info')
  );
  const texts: string[] = [];
  for (const trigger of triggers) {
    act(() => {
      fireEvent.focus(trigger);
    });
    for (const tip of Array.from(document.body.querySelectorAll('[role="tooltip"]'))) {
      texts.push(tip.textContent ?? '');
    }
    act(() => {
      fireEvent.blur(trigger);
    });
  }
  return texts;
}

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(() => cleanup());

describe('the comparison table renders every tooltip it offers', () => {
  it.each(Object.keys(LOCALES))('%s shows no raw message key and logs no formatting error', (locale) => {
    const errors: string[] = [];
    renderIn(locale, (error) => errors.push(String(error)));
    act(() => {
      openPlanComparison();
    });

    // FeatureLabel splits "label||tooltip" and puts the tooltip in the DOM
    // behind an info button, so the text is readable without a hover.
    const text = document.body.textContent ?? '';
    expect(text.length, `${locale}: the dialog rendered nothing`).toBeGreaterThan(200);

    // A message next-intl could not format renders as its own path.
    const rawKeys = [...text.matchAll(/\b(?:pricing\.)?(?:compare|planCards)\.[a-zA-Z.]+/g)].map((m) => m[0]);
    expect(rawKeys, `${locale}: raw message keys reached the DOM`).toEqual([]);

    // And the underlying cause, named rather than inferred.
    expect(errors.filter((e) => /FORMATTING_ERROR|MISSING_MESSAGE/.test(e))).toEqual([]);
  });

  it('really did render the credits tooltip, so the sweep is not passing over an empty table', () => {
    const errors: string[] = [];
    renderIn('en', (error) => errors.push(String(error)));
    act(() => {
      openPlanComparison();
    });

    const text = openEveryTooltip().join(' ');
    // The three per-conversation figures the tooltip interpolates. If any value
    // were missing, next-intl would have rendered the key instead, and if the
    // tooltip were absent the sweep above would prove nothing.
    for (const figure of ['80', '300', '3']) {
      expect(text, `the credits tooltip is missing the ${figure}-credit figure`).toContain(figure);
    }
    expect(text).toContain('Credits pay for what the models and tools actually process');
    expect(errors).toEqual([]);
  });

  it('renders a tooltip on more than one row, so one broken message cannot hide behind another', () => {
    renderIn('en', () => {});
    act(() => {
      openPlanComparison();
    });
    // FeatureLabel's info button is the marker: a row with an explanation has
    // one, a row without has none.
    const infoButtons = document.body.querySelectorAll('button svg.lucide-info');
    expect(infoButtons.length).toBeGreaterThan(1);
  });
});
