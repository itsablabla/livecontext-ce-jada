// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import type { AIModel } from '@/hooks/useModels';
import type { ModelCostBasis } from '@/lib/billing/model-cost-estimate';
import { ModelOptionDisplay, ModelInfoPopover } from '../ModelInfo';

/**
 * The row says what a model will cost before it is picked. Three properties
 * matter, and all three fail silently if they regress: the figure has to appear
 * at all, it has to follow the SHAPE of work the surface configures (an agent
 * that calls tools is not a classify step), and it has to be absent wherever
 * there is nothing to estimate.
 *
 * The row is deliberately query-free - the basis is passed in, exactly like
 * `upgradeRequired` - so this renders with no query client behind it, which is
 * itself part of the contract.
 */

const model: AIModel = {
  id: 'claude-sonnet-5',
  name: 'Claude Sonnet 5',
  provider: 'anthropic',
  pricing: { input: 2, output: 10 },
};

const BASIS: ModelCostBasis = {
  enabled: true,
  profiles: {
    agentConversation: { inputCoefficient: 114.33, outputCoefficient: 5.55 },
    chatConversation: { inputCoefficient: 38.85, outputCoefficient: 0.111 },
    guardrailCheck: { inputCoefficient: 17.76, outputCoefficient: 0.111 },
    classifyStep: { inputCoefficient: 1.332, outputCoefficient: 0.0666 },
  },
};

afterEach(() => {
  cleanup();
});

function renderRow(props: Partial<React.ComponentProps<typeof ModelOptionDisplay>> = {}) {
  render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <ModelOptionDisplay model={model} {...props} />
    </NextIntlClientProvider>,
  );
}

describe('ModelOptionDisplay credit estimate', () => {
  it('shows what an agent conversation on this model would cost', () => {
    renderRow({ costBasis: BASIS, costProfile: 'agentConversation' });

    expect(screen.getByText('~284 credits')).toBeInTheDocument();
  });

  it('prices the shape of work the surface configures, not one figure for all', () => {
    // Same model, same basis: only the surface differs. A classify picker that
    // quoted the agent figure would overstate the cost by ~85x.
    renderRow({ costBasis: BASIS, costProfile: 'classifyStep' });

    expect(screen.getByText('~3.3 credits')).toBeInTheDocument();
    expect(screen.queryByText('~284 credits')).not.toBeInTheDocument();
  });

  it('defaults to the dearest shape, so an un-updated call site overstates rather than understates', () => {
    renderRow({ costBasis: BASIS });

    expect(screen.getByText('~284 credits')).toBeInTheDocument();
  });

  it('says nothing at all where credits are not metered, which is every CE install', () => {
    renderRow({ costBasis: { enabled: false, profiles: {} } });

    expect(screen.queryByText(/ credits$/)).not.toBeInTheDocument();
  });

  it('says nothing before the basis has been answered', () => {
    renderRow();

    expect(screen.queryByText(/ credits$/)).not.toBeInTheDocument();
  });

  it('says nothing for a model the catalogue does not price', () => {
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelOptionDisplay model={{ id: 'x', name: 'Unpriced', provider: 'p' }} costBasis={BASIS} />
      </NextIntlClientProvider>,
    );

    expect(screen.queryByText(/ credits$/)).not.toBeInTheDocument();
  });

  it('keeps the estimate in the compact variant, where the price is dropped', () => {
    // The inspector variant hides the $/1M rates for width. The credits are the
    // one number a reader can act on there, so they survive the trim.
    renderRow({ costBasis: BASIS, variant: 'compact' });

    expect(screen.getByText('~284 credits')).toBeInTheDocument();
    expect(screen.queryByText(/per 1M/)).not.toBeInTheDocument();
  });

  it('keeps the badge in a wrapping meta line rather than forcing the row wider', () => {
    // The row renders in a ~280px workflow inspector next to badges, icons, the
    // context window and the rates. The estimate holds together as one token
    // ("~284 credits" must never break after the tilde) but the LINE wraps, so a
    // narrow host grows taller instead of scrolling sideways.
    renderRow({ costBasis: BASIS });

    const badge = screen.getByText('~284 credits');
    expect(badge).toHaveClass('whitespace-nowrap');
    expect(badge.parentElement).toHaveClass('flex-wrap');
  });
});

describe('ModelInfoPopover credit estimate', () => {
  it('repeats the estimate in the card, which is the only path a touch device has', () => {
    // The row's figure is explained by a hover tooltip, and a phone cannot hover.
    // This card opens on tap, so the number and its sentence live here too.
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelInfoPopover model={model} costBasis={BASIS} costProfile="agentConversation" />
      </NextIntlClientProvider>,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText('~284 credits')).toBeInTheDocument();
    expect(screen.getByText(/around 5 turns and 45 tool calls/)).toBeInTheDocument();
  });

  it('says nothing about credits in the card where there is nothing to estimate', () => {
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelInfoPopover model={model} costBasis={{ enabled: false, profiles: {} }} />
      </NextIntlClientProvider>,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.queryByText('Estimated cost')).not.toBeInTheDocument();
  });
});
