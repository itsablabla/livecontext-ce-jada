// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, cleanup, within } from '@testing-library/react';

import { CATALOG_MODELS, MODEL_CATALOG_STATS } from '../_components/modelsData';

// The page resolves `?provider=` on the SERVER and hands it to the catalogue, so
// the filtered list is in the HTML rather than appearing after hydration. These
// tests render the async page component directly, which is what proves that: a
// filter applied only in the client would leave every assertion below passing on
// the full list.

vi.mock('next/link', () => {
  const React = require('react');
  return {
    default: ({ href, children, ...rest }: { href: unknown; children: React.ReactNode }) =>
      React.createElement('a', { href: typeof href === 'string' ? href : '#', ...rest }, children),
  };
});

// The catalogue navigates on a filter change; jsdom has no Next router.
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: () => {} }) }));

// The shell renders the whole public chrome (header, footer, theme provider). The
// page's own content is what is under test, so the shell is reduced to a passthrough.
vi.mock('@/components/landing/LandingShell', () => ({
  LandingShell: ({ children }: { children: React.ReactNode }) => children,
}));

import ModelsPage, { generateMetadata } from '../page';

afterEach(cleanup);

/** Render the async server component the way Next would call it. */
async function renderPage(params: Record<string, string | string[] | undefined> = {}) {
  render(await ModelsPage({ searchParams: Promise.resolve(params) }));
  return screen.getByRole('list', { name: 'Models' });
}

const XAI_MODELS = CATALOG_MODELS.filter((model) => model.provider === 'xai');

describe('/models provider filter, resolved on the server', () => {
  it('lists every model when no provider is asked for', async () => {
    const list = await renderPage();
    // The list collapses past 20 rows, so this asserts the cap, not the catalogue:
    // what matters is that it is NOT narrowed to one provider.
    const rows = within(list).getAllByRole('listitem');
    expect(rows.length).toBeGreaterThan(XAI_MODELS.length);
  });

  it('narrows the list to the asked-for provider, in the server-rendered markup', async () => {
    const list = await renderPage({ provider: 'xai' });
    const rows = within(list).getAllByRole('listitem');

    expect(rows).toHaveLength(XAI_MODELS.length);
    for (const row of rows) {
      expect(row).toHaveTextContent(/grok/i);
    }
  });

  it('accepts the key in any case, because the value arrives from a URL', async () => {
    const list = await renderPage({ provider: 'XAI' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(XAI_MODELS.length);
  });

  it('shows the FULL page for a provider it does not carry, never an empty one', async () => {
    // A stale link, a hand-typed URL or a crawler's invention must not be punished
    // with a blank list: the visitor came for models, so they get models.
    const list = await renderPage({ provider: 'a-provider-we-dropped' });
    expect(within(list).getAllByRole('listitem').length).toBeGreaterThan(XAI_MODELS.length);
  });

  it('reads the first value when the param is repeated', async () => {
    const list = await renderPage({ provider: ['xai', 'anthropic'] });
    expect(within(list).getAllByRole('listitem')).toHaveLength(XAI_MODELS.length);
  });
});

describe('/models lead copy', () => {
  it('states the catalog totals in the lead, where they are labelled as the catalog', async () => {
    await renderPage();
    const lead = screen.getByText(/The catalog holds/);
    expect(lead).toHaveTextContent(String(MODEL_CATALOG_STATS.distinctModels));
    expect(lead).toHaveTextContent(String(MODEL_CATALOG_STATS.directProviders));
    expect(lead).toHaveTextContent(String(MODEL_CATALOG_STATS.aggregatorModels));
    expect(lead).toHaveTextContent(String(CATALOG_MODELS.length));
  });
});

describe('/models metadata', () => {
  it('describes the whole page when unfiltered', async () => {
    const meta = await generateMetadata({ searchParams: Promise.resolve({}) });
    expect(meta.title).toBe('AI models');
    expect(meta.alternates.canonical).toBe('/models');
  });

  it('names the provider, and counts what that view actually lists', async () => {
    const meta = await generateMetadata({ searchParams: Promise.resolve({ provider: 'xai' }) });
    expect(meta.title).toBe('xAI models');
    expect(meta.description).toContain(`The ${XAI_MODELS.length} xAI models`);
  });

  it('self-canonicalises a provider view rather than pointing it back at /models', async () => {
    // A provider view is a real subset somebody can want, not a duplicate of the
    // full list, so it is allowed to be the canonical URL of itself.
    const meta = await generateMetadata({ searchParams: Promise.resolve({ provider: 'xai' }) });
    expect(meta.alternates.canonical).toBe('/models?provider=xai');
  });

  it('falls back to the whole-page metadata for a provider it does not carry', async () => {
    const meta = await generateMetadata({ searchParams: Promise.resolve({ provider: 'nope' }) });
    expect(meta.title).toBe('AI models');
    expect(meta.alternates.canonical).toBe('/models');
  });
});
