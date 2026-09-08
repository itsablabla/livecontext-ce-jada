// @vitest-environment jsdom
/**
 * The composer against the REAL dictionaries, in two languages.
 *
 * <p>This suite exists for one bug class, and it is a class that has shipped here twice. The price
 * unit reaches the screen through `priceUnitLabel`, which prepends `source.` to the key ITSELF, so a
 * translator bound one level too deep produces `credentials.source.source.priceUnits.second` - which
 * resolves to nothing, and next-intl then prints the key path, on screen, in every locale. The
 * failure is silent: no throw, no warning, just machine text in front of a reader.
 *
 * <p>A stubbed translator cannot see it. The dialog this composer replaced had a suite for exactly
 * this, and deleting that suite with the dialog would have left the class unguarded on the surface
 * that inherited the code. So the assertion is a WORD a reader would recognise, in a language where
 * it differs from the key, rather than the absence of a prefix.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';

// A published rate per SECOND: the one shape whose unit has to travel through the unit dictionary.
vi.mock('@/hooks/useGenerationQuote', () => ({
  useGenerationQuote: () => ({
    quote: {
      hasPricing: true,
      priceUnit: 'second',
      baseCredits: '0',
      unitCredits: '60',
    },
    quantity: 5,
    // The question has been answered: the composer only states a price once it has.
    settled: true,
  }),
}));
vi.mock('@/hooks/useGenerationOptions', () => ({ useGenerationOptions: () => ({}) }));
vi.mock('@/components/studio/StudioPayerControl', () => ({
  StudioPayerControl: () => <div data-testid="payer" />,
}));

import { StudioComposer } from '../StudioComposer';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

const MODEL: GenerationModel = {
  model: 'seedance-2',
  kind: 'video',
  label: 'Seedance 2.0',
  provider: 'seedance',
  iconSlug: null,
  apiToolId: 'tool-1',
  integrationName: 'seedance',
  accepts: ['prompt', 'duration_seconds'],
  required: [],
  limits: {},
  billedOn: 'duration_seconds',
  measuredUnit: 'second',
  defaultQuantity: '5',
  price: { unit: 'second', baseCredits: '0', unitCredits: '60' },
  async: true,
};

function renderIn(locale: 'en' | 'fr') {
  const messages = (locale === 'fr' ? frMessages : enMessages) as Record<string, unknown>;
  return render(
    <NextIntlClientProvider locale={locale} messages={messages}>
      <StudioComposer
        models={[MODEL]}
        selectedModel={MODEL}
        onSelectModel={() => {}}
        onSubmit={async () => true}
      />
    </NextIntlClientProvider>,
  );
}

afterEach(cleanup);

describe('StudioComposer - the real dictionaries', () => {
  it('names the price unit in English, through the unit dictionary', () => {
    renderIn('en');

    const price = screen.getByText(/credits per/i);
    expect(price.textContent).toContain('second');
    // The failure this guards is a key path on screen. Both halves are named because the wrong
    // binding produces the deeper one and the wrong namespace produces the shallower.
    expect(price.textContent).not.toContain('priceUnits');
    expect(price.textContent).not.toContain('credentials.');
  });

  it('translates that unit, which is the whole reason it goes through a dictionary', () => {
    renderIn('fr');

    // A real translated word, not the absence of a prefix: an English unit inside a French sentence
    // is exactly what a wrong-but-resolving binding would leave behind.
    const price = screen.getByText(/crédits par/i);
    expect(price.textContent).toContain('seconde');
    expect(price.textContent).not.toContain('priceUnits');
  });

  it('says what the model is called, so the whole namespace wiring is exercised', () => {
    // Guards the composer's own namespace as well as the shared one: a component rendered under a
    // provider it does not match shows key paths everywhere, and the price alone would not say so.
    renderIn('fr');

    expect(screen.getByPlaceholderText(/Seedance 2\.0/)).toBeInTheDocument();
    expect(screen.queryByText(/^studio\./)).toBeNull();
  });
});
