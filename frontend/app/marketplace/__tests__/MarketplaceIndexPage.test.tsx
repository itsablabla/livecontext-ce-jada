/**
 * @vitest-environment jsdom
 */
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PublicPublicationSummary } from '@/lib/marketplace/publicPublications';

// The reader is `server-only` and talks to the gateway; the page's contract with
// it is what this suite is about, so it is stubbed here.
const fetchAllPublicPublications = vi.fn();
vi.mock('@/lib/marketplace/publicPublications', () => ({
  fetchAllPublicPublications: (...args: unknown[]) => fetchAllPublicPublications(...args),
  fetchMarketplacePage: vi.fn(),
}));

// The chrome mounts a theme provider and the whole footer; none of it is under
// test and it drags in intl-context-free assertions of its own.
vi.mock('@/components/landing/LandingShell', () => ({
  LandingShell: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

// Capture the structured data instead of parsing it back out of a script tag.
const jsonLd: unknown[] = [];
vi.mock('@/components/seo/JsonLd', () => ({
  default: ({ data }: { data: Record<string, unknown> }) => {
    jsonLd.push(data);
    return null;
  },
}));

// The card has its own suite. Here it only has to prove it was rendered.
vi.mock('../_components/PublicationCardSsr', () => ({
  default: ({ publication }: { publication: PublicPublicationSummary }) => (
    <article data-testid="card">{publication.title}</article>
  ),
}));

import MarketplaceIndexPage from '../page';

function publication(index: number): PublicPublicationSummary {
  return {
    id: `pub-${index}`,
    publicSlug: `app-${index}`,
    title: `App ${index}`,
    description: `What app ${index} does, in a sentence long enough to be a real description of it.`,
    publisherName: 'Ada',
    publisherId: 'user-1',
    publisherHandle: 'ada',
    publisherAvatarUrl: null,
    categorySlug: null,
    categoryName: null,
    categoryColor: null,
    averageRating: 0,
    reviewCount: 0,
    useCount: 0,
    publishedAt: null,
    updatedAt: null,
    publicationType: 'WORKFLOW',
    displayMode: 'APPLICATION',
    creditsPerUse: 0,
    hasShowcase: true,
    nodeIcons: [],
    agentCount: 0,
    interfaceCount: 0,
    workflowCount: 0,
    skillCount: 0,
    datasourceCount: 0,
    planSnapshot: null,
  };
}

/** Only the parts of the emitted structured data these tests read back. */
interface CollectionPageJsonLd {
  mainEntity: {
    numberOfItems: number;
    itemListElement: Array<{
      url?: string;
      item: {
        url: string;
        '@type': string;
        name: string;
        description: string;
        image: string;
        aggregateRating?: unknown;
      };
    }>;
  };
}

async function renderPage() {
  render(await MarketplaceIndexPage());
}

beforeEach(() => {
  jsonLd.length = 0;
  fetchAllPublicPublications.mockReset();
});

describe('marketplace index page', () => {
  it('renders the WHOLE catalogue, not one API page of it', async () => {
    // The regression: the page called `fetchMarketplacePage()`, whose default
    // size is 24, so every listing past the 24th was linked from no crawlable
    // page anywhere on the site.
    const publications = Array.from({ length: 79 }, (_, i) => publication(i));
    fetchAllPublicPublications.mockResolvedValue({ publications, truncated: false });

    await renderPage();

    expect(screen.getAllByTestId('card')).toHaveLength(79);
    expect(screen.getByText(/79 published listings/)).toBeTruthy();
  });

  it('walks the catalogue rather than reading a single page', async () => {
    fetchAllPublicPublications.mockResolvedValue({ publications: [], truncated: false });
    await renderPage();
    expect(fetchAllPublicPublications).toHaveBeenCalled();
  });

  it('describes every linkable listing in the ItemList, with its own image', async () => {
    fetchAllPublicPublications.mockResolvedValue({
      publications: [publication(0), publication(1)],
      truncated: false,
    });

    await renderPage();

    const collection = jsonLd[0] as CollectionPageJsonLd;
    expect(collection.mainEntity.numberOfItems).toBe(2);
    const [first] = collection.mainEntity.itemListElement;
    // The URL lives INSIDE `item`, not beside it: a sibling `url` and a nested
    // `item` are Google's two different carousel shapes, and emitting both asks
    // the parser to pick.
    expect(first.url).toBeUndefined();
    expect(first.item).toMatchObject({
      '@type': 'SoftwareApplication',
      name: 'App 0',
      url: 'https://livecontext.ai/marketplace/app-0',
      // The listing's OWN OpenGraph card. Eighty entries sharing one picture is
      // what makes a listing page look templated.
      image: 'https://livecontext.ai/marketplace/app-0/opengraph-image',
    });
    expect(first.item.description).toContain('What app 0 does');
  });

  it('never claims a rating a listing does not have', async () => {
    fetchAllPublicPublications.mockResolvedValue({
      publications: [publication(0)],
      truncated: false,
    });

    await renderPage();

    const collection = jsonLd[0] as CollectionPageJsonLd;
    // An aggregateRating with reviewCount 0 is invalid structured data and
    // earns a Search Console error.
    expect(collection.mainEntity.itemListElement[0].item.aggregateRating).toBeUndefined();
  });

  it('leaves a slugless listing out of the structured data but still shows it', async () => {
    const noSlug = { ...publication(1), publicSlug: null };
    fetchAllPublicPublications.mockResolvedValue({
      publications: [publication(0), noSlug],
      truncated: false,
    });

    await renderPage();

    const collection = jsonLd[0] as {
      mainEntity: { numberOfItems: number; itemListElement: unknown[] };
    };
    // It has no canonical URL to advertise, but it is still a real listing.
    expect(collection.mainEntity.numberOfItems).toBe(1);
    expect(collection.mainEntity.itemListElement).toHaveLength(1);
    expect(screen.getAllByTestId('card')).toHaveLength(2);
  });

  it('says so rather than showing an empty grid when the read fails', async () => {
    fetchAllPublicPublications.mockResolvedValue({ publications: [], truncated: true });
    await renderPage();

    expect(screen.getByText(/No published listings right now/)).toBeTruthy();
    expect(screen.queryAllByTestId('card')).toHaveLength(0);
  });

  it('warns when the walk stopped early, so a partial catalogue is never silent', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    fetchAllPublicPublications.mockResolvedValue({
      publications: [publication(0)],
      truncated: true,
    });

    await renderPage();

    expect(warn).toHaveBeenCalledWith(expect.stringContaining('stopped early'));
    warn.mockRestore();
  });
});
