// @vitest-environment jsdom
/**
 * The control that opens the generation dialog, wherever one is offered.
 *
 * <p>It exists because both surfaces that offer a generation kept re-deriving
 * the same two answers from the catalogue, and the copy is where they drift.
 * So this suite is about the ANSWERS, not about either surface: what each reply
 * from the catalogue means, who is asked for one at all, and whether the reason
 * behind a disabled control can actually be reached.
 *
 * <p>The catalogue is stubbed at the SERVICE, so the hook and its 404
 * classification run for real. Stubbing the hook would assert the stub.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

const api = vi.hoisted(() => ({ getModels: vi.fn() }));
vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: { getModels: api.getModels },
}));

const gate = vi.hoisted(() => ({ canMutate: true }));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({}),
  useCanMutateInCurrentOrg: () => gate.canMutate,
}));


import { ApiError } from '@/lib/api/api-client';
import { GenerateEntryButton } from '../GenerateEntryButton';

function catalogue(models: unknown[] = [{ model: 'seedance-2.0', kind: 'video' }]) {
  return { models, count: models.length, kinds: ['video'] };
}

const onOpen = vi.fn();

function renderButton(count = 1) {
  // retry: false so a 5xx is one answer rather than four spaced by backoff.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      {Array.from({ length: count }, (_, i) => (
        <GenerateEntryButton key={i} variant="icon" label="generate" onOpen={onOpen} />
      ))}
    </QueryClientProvider>,
  );
}

async function settled() {
  await waitFor(() => expect(api.getModels).toHaveBeenCalled());
  await act(async () => { await Promise.resolve(); });
}

function control(): HTMLButtonElement | null {
  return screen.queryByLabelText('generate') as HTMLButtonElement | null;
}

beforeEach(() => {
  gate.canMutate = true;
  onOpen.mockClear();
  api.getModels.mockReset();
  api.getModels.mockResolvedValue(catalogue());
});
afterEach(cleanup);

describe('GenerateEntryButton - what each answer from the catalogue means', () => {
  it('offers it, enabled, when the catalogue has something to run', async () => {
    renderButton();
    await settled();

    expect(control()).toBeEnabled();
    fireEvent.click(control()!);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('drops it when the install does not serve generation at all', async () => {
    // 404: nothing anyone does in the app changes that.
    api.getModels.mockRejectedValue(new ApiError('not found', 404));
    renderButton();
    await settled();

    await waitFor(() => expect(control()).not.toBeInTheDocument());
  });

  it('regression: keeps it while the answer is still outstanding', async () => {
    // In flight says nothing about whether the feature exists. Hiding it here
    // makes the control pop into the row once the first fetch lands.
    api.getModels.mockReturnValue(new Promise(() => {}));
    renderButton();

    expect(control()).toBeEnabled();
  });

  it('regression: keeps it when the catalogue call fails outright', async () => {
    // A 5xx is not a 404. Hiding a feature on a hiccup is a lie that looks
    // exactly like the truth.
    api.getModels.mockRejectedValue(new ApiError('boom', 500));
    renderButton();
    await settled();

    expect(control()).toBeEnabled();
  });

  it('regression: keeps it when the request never reached a server at all', async () => {
    // Not an ApiError, so there is no status to read. That must not be mistaken
    // for a 404: a dropped connection says nothing about what the install has.
    api.getModels.mockRejectedValue(new TypeError('Failed to fetch'));
    renderButton();
    await settled();

    expect(control()).toBeEnabled();
  });

  it('names itself on hover in the ordinary case, not only when unavailable', async () => {
    renderButton();
    await settled();

    expect(control()!.parentElement).toHaveAttribute('title', 'generate');
  });

  it('keeps it, disabled and carrying the reason, when the catalogue is empty', async () => {
    api.getModels.mockResolvedValue(catalogue([]));
    renderButton();
    await settled();

    await waitFor(() => expect(control()).toBeDisabled());
    // The reason rides the WRAPPER, because a disabled button eats hovers.
    expect(control()!.parentElement).toHaveAttribute('title', 'empty');
    const describedBy = control()!.getAttribute('aria-describedby')!;
    expect(document.getElementById(describedBy)).toHaveTextContent('empty');
  });

  it('a disabled control opens nothing, however it is pressed', async () => {
    api.getModels.mockResolvedValue(catalogue([]));
    renderButton();
    await settled();
    await waitFor(() => expect(control()).toBeDisabled());

    fireEvent.click(control()!);

    expect(onOpen).not.toHaveBeenCalled();
  });

  it('regression: two of them on one page each own their reason', async () => {
    // A page can hold two composers (the docked chat beside the main one). With
    // a shared module id, every `aria-describedby` would point at the first
    // one's text, and the second control would explain itself with an element
    // that is not its own.
    api.getModels.mockResolvedValue(catalogue([]));
    renderButton(2);
    await settled();

    await waitFor(() => expect(screen.getAllByLabelText('generate')).toHaveLength(2));
    const [first, second] = screen.getAllByLabelText('generate');
    const firstId = first.getAttribute('aria-describedby')!;
    const secondId = second.getAttribute('aria-describedby')!;

    expect(firstId).not.toEqual(secondId);
    expect(first.parentElement!.querySelector(`#${CSS.escape(firstId)}`)).not.toBeNull();
    expect(second.parentElement!.querySelector(`#${CSS.escape(secondId)}`)).not.toBeNull();
  });

  it('hides it from a read-only viewer, and asks the catalogue nothing for them', async () => {
    // A generation writes a file into the workspace and spends credits, and the
    // endpoint takes the caller on trust, so this gate is the only one there is.
    gate.canMutate = false;
    renderButton();
    await act(async () => { await Promise.resolve(); });

    expect(control()).not.toBeInTheDocument();
    expect(api.getModels).not.toHaveBeenCalled();
  });

  it('acts only on a press, never on a hover or a focus', async () => {
    // The control used to prefetch a lazy dialog on hover and on focus. It leads to a route now,
    // whose code the router fetches, so there is nothing left to warm - and the mistake this
    // guards against is one character away: wiring the action to hover would move the reader out
    // from under a passing pointer.
    renderButton();
    await settled();

    fireEvent.mouseEnter(control()!);
    fireEvent.focus(control()!);
    expect(onOpen).not.toHaveBeenCalled();

    fireEvent.click(control()!);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('shows the word in a toolbar and only the icon in a button row', async () => {
    // The one thing a surface gets to decide. A page toolbar has room for a
    // word; a composer row that is already all icons does not.
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <GenerateEntryButton variant="toolbar" label="generate" onOpen={onOpen} />
      </QueryClientProvider>,
    );
    await settled();

    expect(screen.getByLabelText('generate')).toHaveTextContent('generate');
    cleanup();

    renderButton();
    await settled();
    expect(control()).toHaveTextContent('');
  });

  it('wears the wand, not the app-wide AI sparkle', async () => {
    // `Sparkles` is the generic "something AI" badge across some thirty files
    // (skills, suggestions, onboarding, the avatar picker, credits). Worn here
    // it said nothing about producing a file, and nothing this control does was
    // distinguishable from any other AI affordance on the same screen.
    renderButton();
    await settled();

    const icon = control()!.querySelector('svg');

    expect(icon?.getAttribute('class')).toContain('lucide-wand-sparkles');
    // The bare sparkle stays free to go on meaning "AI" everywhere else.
    expect(icon?.getAttribute('class')).not.toMatch(/lucide-sparkles\b/);
  });
});
