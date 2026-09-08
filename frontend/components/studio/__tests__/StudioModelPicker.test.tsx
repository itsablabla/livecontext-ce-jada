// @vitest-environment jsdom
/**
 * Choosing what the next turn runs on: what to make, then who makes it, then whose key pays.
 *
 * <p>The picker was rewritten from a flat grouped list into three panes, and the whole rewrite
 * passed 724 existing tests without one of them failing - it had no coverage at all. What follows
 * pins the parts that can be wrong silently:
 *
 * <ul>
 *   <li><b>Each pane shows only what the previous answer allows.</b> A video pane offering an image
 *       provider is not a cosmetic slip: the reader picks it, and the composer then asks for
 *       parameters the model does not accept.
 *   <li><b>Back moves the pane and nothing else.</b> Opening the picker to look, then backing out,
 *       must leave the model and the payer exactly as they were. A back that unpicks is worse than
 *       no back at all, because it destroys a choice while pretending to undo a navigation.
 *   <li><b>The payer sits with the provider.</b> A credential belongs to an INTEGRATION, so
 *       "whose key" only means something once a provider is chosen - and it is a different question
 *       the moment the provider changes.
 * </ul>
 *
 * <p>Rendered against the REAL dictionary: the panes are told apart by their words, so a stub
 * translator returning key paths would let a picker showing the wrong pane pass every assertion.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

// The app's shared credential control: real here would pull the credential/query stack into a
// test about panes. Its PRESENCE and the props it is handed are what this suite checks - the rule
// for when a platform key may be offered at all lives inside it and has its own tests.
const credentialProps = vi.hoisted(() => ({ last: null as Record<string, unknown> | null }));
vi.mock('@/app/workflows/builder/components/inspector/CredentialSection', () => ({
  CredentialSection: (props: Record<string, unknown>) => {
    credentialProps.last = props;
    return <div data-testid="credential-section" />;
  },
}));
vi.mock('@/lib/generation/formats', () => ({
  FORMAT_ORDER: ['image', 'video', 'audio', 'voice', 'music'],
  FormatGlyph: () => <span data-testid="format-glyph" />,
  ProviderIcon: () => <span data-testid="provider-icon" />,
}));

import { StudioModelPicker } from '../StudioModelPicker';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

function model(over: Partial<GenerationModel>): GenerationModel {
  return {
    model: 'm1', kind: 'video', label: 'Model One', provider: 'Seedance',
    iconSlug: null, apiToolId: 't1', integrationName: 'seedance',
    accepts: ['prompt'], required: [], limits: {},
    billedOn: null, measuredUnit: null, defaultQuantity: null,
    price: { unit: 'call', baseCredits: '0', unitCredits: '0' }, async: false,
    ...over,
  } as unknown as GenerationModel;
}

/** A catalogue with two formats, and two providers inside the video one. */
const CATALOGUE = [
  model({ model: 'seedance-2', label: 'Seedance 2.0', provider: 'Seedance', integrationName: 'seedance', kind: 'video' }),
  model({ model: 'seedance-1', label: 'Seedance 1.0', provider: 'Seedance', integrationName: 'seedance', kind: 'video' }),
  model({ model: 'runway-3', label: 'Runway Gen-3', provider: 'Runway', integrationName: 'runway', kind: 'video' }),
  model({ model: 'flux-1', label: 'FLUX.1', provider: 'Black Forest', integrationName: 'flux', kind: 'image' }),
];

function renderPicker(over: Partial<React.ComponentProps<typeof StudioModelPicker>> = {}) {
  const onSelect = vi.fn();
  const onCredentialSourceChange = vi.fn();
  const onCredentialIdChange = vi.fn();
  render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <StudioModelPicker
        models={CATALOGUE}
        selected={null}
        onSelect={onSelect}
        credentialSource="platform"
        onCredentialSourceChange={onCredentialSourceChange}
        onCredentialIdChange={onCredentialIdChange}
        {...over}
      />
    </NextIntlClientProvider>,
  );
  return { onSelect, onCredentialSourceChange, onCredentialIdChange };
}

/** Open the picker by its trigger, which is titled the same whatever it currently reads. */
function openPicker() {
  fireEvent.click(screen.getByTitle('Change model'));
}

afterEach(() => { credentialProps.last = null; cleanup(); });

describe('StudioModelPicker - what to make comes first', () => {
  it('opens on the formats, not on a list of every model', () => {
    renderPicker();
    openPicker();

    expect(screen.getByText('What to make')).toBeInTheDocument();
    expect(screen.getByText('Video')).toBeInTheDocument();
    expect(screen.getByText('Image')).toBeInTheDocument();
    // The models themselves are two answers away.
    expect(screen.queryByText('Seedance 2.0')).toBeNull();
  });

  it('counts what is behind each format, so one model does not look like twenty', () => {
    renderPicker();
    openPicker();

    // Three video models, one image model.
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument();
  });

  it('shows only the providers of the format that was chosen', () => {
    // The narrowing is the point of the pane. A video pane offering an image provider leads the
    // reader to a model whose parameters the composer will then refuse.
    renderPicker();
    openPicker();
    fireEvent.click(screen.getByText('Video'));

    expect(screen.getByText('Seedance')).toBeInTheDocument();
    expect(screen.getByText('Runway')).toBeInTheDocument();
    expect(screen.queryByText('Black Forest')).toBeNull();
  });

  it('shows only the models of the provider that was chosen', () => {
    renderPicker();
    openPicker();
    fireEvent.click(screen.getByText('Video'));
    fireEvent.click(screen.getByText('Seedance'));

    expect(screen.getByText('Seedance 2.0')).toBeInTheDocument();
    expect(screen.getByText('Seedance 1.0')).toBeInTheDocument();
    expect(screen.queryByText('Runway Gen-3')).toBeNull();
  });

  it('groups on the INTEGRATION, not the display name', () => {
    // Two rows that read as one provider but carry different integrations are two different
    // accounts. Merging them would offer a key minted for one beside a model of the other, which
    // the execution path then substitutes silently.
    renderPicker({
      models: [
        model({ model: 'a', label: 'A', provider: 'Acme', integrationName: 'acme_eu', kind: 'video' }),
        model({ model: 'b', label: 'B', provider: 'Acme', integrationName: 'acme_us', kind: 'video' }),
      ],
    });
    openPicker();
    fireEvent.click(screen.getByText('Video'));

    expect(screen.getAllByText('Acme')).toHaveLength(2);
  });

  it('reports the whole choice when a model is picked', () => {
    const { onSelect } = renderPicker();
    openPicker();
    fireEvent.click(screen.getByText('Video'));
    fireEvent.click(screen.getByText('Seedance'));
    fireEvent.click(screen.getByText('Seedance 2.0'));

    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ model: 'seedance-2' }));
  });
});

describe('StudioModelPicker - going back', () => {
  it('walks out one pane at a time', () => {
    renderPicker();
    openPicker();
    fireEvent.click(screen.getByText('Video'));
    fireEvent.click(screen.getByText('Seedance'));
    expect(screen.getByText('Seedance 2.0')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByText('Runway')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByText('What to make')).toBeInTheDocument();
  });

  it('goes back from the whole header row, not only its arrow', () => {
    // The most repeated action in a stepped menu, and a 14px arrow is a small target for it. The
    // label beside it names where you ARE, which is where Back returns to, so the row is the
    // honest hit area. Asserted through the accessible name, which both share.
    renderPicker();
    openPicker();
    fireEvent.click(screen.getByText('Video'));
    fireEvent.click(screen.getByText('Seedance'));

    const header = screen.getByRole('button', { name: 'Back' });
    // The header IS the button: its label sits inside it, so clicking the words goes back.
    expect(header).toHaveTextContent('Seedance');

    fireEvent.click(header);
    expect(screen.getByText('Runway')).toBeInTheDocument();
  });

  it('offers no way back from the first pane, rather than a dead control', () => {
    // A disabled arrow invites a press that does nothing. There is nothing behind this pane.
    renderPicker();
    openPicker();

    expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
  });

  it('does NOT unpick the model on the way back', () => {
    // Back is a navigation, not an undo. A reader who opens the picker to look around and backs out
    // still has the model they arrived with.
    const { onSelect } = renderPicker({ selected: CATALOGUE[0] });
    openPicker();
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));

    expect(onSelect).not.toHaveBeenCalled();
  });

  it('does NOT change the payer on the way back either', () => {
    const { onCredentialSourceChange } = renderPicker({ selected: CATALOGUE[0], credentialSource: 'user' });
    openPicker();
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));

    expect(onCredentialSourceChange).not.toHaveBeenCalled();
  });
});

describe('StudioModelPicker - reopening lands where the reader already is', () => {
  it('opens on the selected model’s own pane, not back at the start', () => {
    // Both the sibling models and the payer live there, which is what someone reopening the control
    // has come for. The stepped path is still behind it, one Back away.
    renderPicker({ selected: CATALOGUE[0] });
    openPicker();

    expect(screen.getByText('Seedance 1.0')).toBeInTheDocument();
    expect(screen.queryByText('What to make')).toBeNull();
  });

  it('opens on the formats when nothing is selected yet', () => {
    renderPicker({ selected: null });
    openPicker();

    expect(screen.getByText('What to make')).toBeInTheDocument();
  });
});

describe('StudioModelPicker - whose key pays', () => {
  it('offers the credential control beside the models it applies to', () => {
    renderPicker({ selected: CATALOGUE[0] });
    openPicker();

    expect(screen.getByTestId('credential-section')).toBeInTheDocument();
  });

  it('does not offer it before a provider is chosen', () => {
    // A credential belongs to an integration: with no provider picked there is nothing the choice
    // could apply to, and it would become a different choice one pane later.
    renderPicker({ selected: null });
    openPicker();

    expect(screen.queryByTestId('credential-section')).toBeNull();
  });

  it('asks about the INTEGRATION of the provider on screen, not a generic one', () => {
    // What the control is keyed on decides which platform key it looks for and which of the
    // reader's keys it lists. Handed the wrong integration it would offer a key for another
    // provider, which the execution path then substitutes silently.
    renderPicker({ selected: CATALOGUE[0] });
    openPicker();

    expect(credentialProps.last).toMatchObject({ integration: 'seedance' });
  });

  it('prices the call as a GENERATION, so the platform option follows the published rate', () => {
    // `isGeneration` is what makes the control require a published generation price before it
    // offers the platform key at all. Dropping it would offer a platform option priced on the
    // credential-wide default - a rate the run is then refused for.
    renderPicker({ selected: CATALOGUE[0], quantity: 6 });
    openPicker();

    expect(credentialProps.last).toMatchObject({ isGeneration: true, quantity: 6 });
  });

  it('hands the payer through, so the control shows which side is paying', () => {
    renderPicker({ selected: CATALOGUE[0], credentialSource: 'user' });
    openPicker();

    expect(credentialProps.last).toMatchObject({ credentialSource: 'user' });
  });

  it('does NOT repeat the price the composer already shows', () => {
    renderPicker({ selected: CATALOGUE[0] });
    openPicker();

    expect(credentialProps.last).toMatchObject({ showPlatformPricingNotes: false });
  });
});
