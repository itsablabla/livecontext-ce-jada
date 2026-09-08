/**
 * @vitest-environment jsdom
 */
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import IntegrationSearch from '../IntegrationSearch';

const originalFetch = global.fetch;
let fetchMock: ReturnType<typeof vi.fn>;

function row(slug: string, name = slug) {
  return {
    slug,
    name,
    description: `What ${name} does.`,
    iconSlug: slug,
    iconUrl: null,
    toolCount: 5,
    authType: 'oauth2',
  };
}

function jsonResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response;
}

/** Type into the box the way the suite's siblings do (user-event is not a dep here). */
function type(value: string) {
  fireEvent.change(screen.getByRole('searchbox'), { target: { value } });
}

function renderSearch(props: Partial<React.ComponentProps<typeof IntegrationSearch>> = {}) {
  return render(
    <IntegrationSearch totalCount={731} {...props}>
      <div data-testid="ssr-list">The server-rendered catalog</div>
    </IntegrationSearch>,
  );
}

beforeEach(() => {
  fetchMock = vi.fn().mockResolvedValue(jsonResponse({ content: [], totalElements: 0 }));
  global.fetch = fetchMock as unknown as typeof fetch;
});

afterEach(() => {
  global.fetch = originalFetch;
  vi.restoreAllMocks();
});

describe('IntegrationSearch', () => {
  it('shows the server-rendered list and fetches nothing until someone types', async () => {
    renderSearch();

    // The list has to be in the HTML a crawler receives, so it cannot be
    // something this component fetches on mount.
    expect(screen.getByTestId('ssr-list')).toBeTruthy();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('announces the catalog size while the box is empty', () => {
    renderSearch();

    expect(screen.getByText(/731 integrations/)).toBeTruthy();
  });

  it('queries the SERVER, not the rendered children', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ content: [row('stripe', 'Stripe')], totalElements: 1 }));
    renderSearch();

    type('stripe');

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    // Filtering the children client-side would answer "no results" for most of a
    // catalog of several hundred, because the landing only renders the top few.
    const url = fetchMock.mock.calls.at(-1)?.[0] as string;
    expect(url).toContain('/api/proxy/public/integrations?q=stripe');
    await waitFor(() => expect(screen.getByText('Stripe')).toBeTruthy());
  });

  it('replaces the server-rendered list with the matches', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ content: [row('stripe', 'Stripe')], totalElements: 1 }));
    renderSearch();

    type('stripe');

    // Wait on the RESULT, not on the children disappearing: they go as soon as
    // the box has a term, so waiting on that would pass while still loading.
    await waitFor(() => expect(screen.getByText('Stripe')).toBeTruthy());
    expect(screen.queryByTestId('ssr-list')).toBeNull();
  });

  it('debounces, so a typed word is one request and not one per keystroke', async () => {
    renderSearch();

    // Six keystrokes in a row, as fast as a person types a word.
    for (const value of ['s', 'st', 'str', 'stri', 'strip', 'stripe']) {
      type(value);
    }

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(fetchMock).toHaveBeenCalledTimes(1);
    // And the one request carries the FULL word, not a prefix from mid-typing.
    expect(fetchMock.mock.calls[0][0] as string).toContain('q=stripe');
  });

  it('says how many matched, and that the list is capped when it is', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      content: Array.from({ length: 48 }, (_, i) => row(`a${i}`, `A${i}`)),
      totalElements: 120,
    }));
    renderSearch();

    type('a');

    await waitFor(() => expect(screen.getByText(/48 of 120 matches/)).toBeTruthy());
  });

  it('tells the visitor when nothing matches, with something to do about it', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ content: [], totalElements: 0 }));
    renderSearch();

    type('zzzz');

    await waitFor(() => expect(screen.getByText(/No integration matches/)).toBeTruthy());
    expect(screen.getByText(/custom API or a raw HTTP request/)).toBeTruthy();
  });

  it('falls back to the server-rendered list when the search fails', async () => {
    fetchMock.mockResolvedValue(jsonResponse(null, 500));
    renderSearch();

    type('stripe');

    // A failed search must leave the visitor something to browse rather than an
    // empty screen.
    await waitFor(() => expect(screen.getByText(/Search is unavailable/)).toBeTruthy());
    expect(screen.getByTestId('ssr-list')).toBeTruthy();
  });

  it('restores the full list when the box is cleared', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ content: [row('stripe', 'Stripe')], totalElements: 1 }));
    renderSearch();

    type('stripe');
    await waitFor(() => expect(screen.getByText('Stripe')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: /clear search/i }));

    await waitFor(() => expect(screen.getByTestId('ssr-list')).toBeTruthy());
    expect(screen.queryByText('Stripe')).toBeNull();
  });

  it('ignores a late answer for a term the visitor has moved on from', async () => {
    // The results are tagged with the term they answer, so a response that lands
    // after the box changed is stale by construction and cannot paint over the
    // current one. Simulated here by answering the FIRST request slowly.
    let releaseFirst: (value: Response) => void = () => {};
    fetchMock
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { releaseFirst = resolve; }))
      .mockResolvedValue(jsonResponse({ content: [row('github', 'GitHub')], totalElements: 1 }));
    renderSearch();

    type('stripe');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    type('github');
    await waitFor(() => expect(screen.getByText('GitHub')).toBeTruthy());

    releaseFirst(jsonResponse({ content: [row('stripe', 'Stripe')], totalElements: 1 }));

    await waitFor(() => expect(screen.getByText('GitHub')).toBeTruthy());
    expect(screen.queryByText('Stripe')).toBeNull();
  });

  it('labels the input for screen readers and reports results politely', () => {
    renderSearch();

    // Polite, not assertive: results change on every keystroke, and an assertive
    // region would interrupt a screen-reader user mid-word.
    expect(screen.getByLabelText(/search integrations by name/i)).toBeTruthy();
    const status = document.querySelector('[aria-live]');
    expect(status?.getAttribute('aria-live')).toBe('polite');
  });
});
