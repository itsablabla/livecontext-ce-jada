import { describe, expect, it } from 'vitest';
import { listingJsonLd, listingListItem } from '../listingJsonLd';
import type { PublicPublicationSummary } from '../publicPublications';

const SITE_URL = 'https://livecontext.ai';
const OPTIONS = { siteUrl: SITE_URL, slug: 'invoice-bot' };

function publication(overrides: Partial<PublicPublicationSummary> = {}): PublicPublicationSummary {
  return {
    id: 'pub-1',
    publicSlug: 'invoice-bot',
    title: 'Invoice Bot',
    description: 'Chases unpaid invoices and files every reply against the right customer.',
    publisherName: 'Ada',
    publisherId: '42',
    publisherHandle: 'ada',
    publisherAvatarUrl: null,
    categorySlug: 'automation',
    categoryName: 'Automation',
    categoryColor: null,
    averageRating: 4.5,
    reviewCount: 12,
    useCount: 42,
    publishedAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-02T10:00:00Z',
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
    ...overrides,
  };
}

describe('listingJsonLd', () => {
  it('describes the listing at its canonical URL', () => {
    const node = listingJsonLd(publication(), OPTIONS);

    expect(node).toMatchObject({
      '@type': 'SoftwareApplication',
      name: 'Invoice Bot',
      url: 'https://livecontext.ai/marketplace/invoice-bot',
      applicationCategory: 'BusinessApplication',
      operatingSystem: 'Web',
    });
    expect(node.description).toContain('Chases unpaid invoices');
  });

  it('builds the URL from the slug it is GIVEN, not from the row field', () => {
    // The row's `publicSlug` is nullable and this repo compiles with
    // `strict: false`, so reading it here would emit ".../marketplace/null" for
    // a legacy row with no type error anywhere. The caller has to say which URL
    // it means: the listing page passes its own route's slug.
    const node = listingJsonLd(
      publication({ publicSlug: null }),
      { siteUrl: SITE_URL, slug: 'from-the-route' },
    );

    expect(node.url).toBe('https://livecontext.ai/marketplace/from-the-route');
    expect(node.image).toBe('https://livecontext.ai/marketplace/from-the-route/opengraph-image');
  });

  it('points at the listing OWN OpenGraph card, not the site-wide one', () => {
    // Eighty entries sharing one picture is what makes a catalogue look
    // templated rather than populated.
    expect(listingJsonLd(publication(), OPTIONS).image)
      .toBe('https://livecontext.ai/marketplace/invoice-bot/opengraph-image');
  });

  it('names the author only when the listing has one', () => {
    expect(listingJsonLd(publication(), OPTIONS).author)
      .toEqual({ '@type': 'Person', name: 'Ada' });
    expect(listingJsonLd(publication({ publisherName: null }), OPTIONS).author)
      .toBeUndefined();
  });

  it('never claims a rating a listing does not have', () => {
    // An aggregateRating with reviewCount 0 is invalid structured data and
    // earns a Search Console error.
    expect(listingJsonLd(publication(), OPTIONS).aggregateRating)
      .toEqual({ '@type': 'AggregateRating', ratingValue: 4.5, reviewCount: 12 });
    expect(listingJsonLd(publication({ reviewCount: 0 }), OPTIONS).aggregateRating)
      .toBeUndefined();
  });

  it('truncates the description the same way the meta description does', () => {
    const long = 'a'.repeat(400);
    const node = listingJsonLd(publication({ description: long }), OPTIONS);

    expect((node.description as string).length).toBeLessThanOrEqual(155);
  });
});

describe('listingListItem', () => {
  it('nests the listing under `item` and does not also put a url beside it', () => {
    // Those are Google's two different carousel shapes; emitting both asks the
    // parser to pick, and the nested one is the one carrying the description
    // and the image.
    const entry = listingListItem(publication(), 3, OPTIONS);

    expect(entry['@type']).toBe('ListItem');
    expect(entry.position).toBe(3);
    expect(entry.url).toBeUndefined();
    expect(entry.item).toMatchObject({
      '@type': 'SoftwareApplication',
      url: 'https://livecontext.ai/marketplace/invoice-bot',
    });
  });
});
