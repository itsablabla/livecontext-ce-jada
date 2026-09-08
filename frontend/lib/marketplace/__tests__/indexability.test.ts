import { describe, expect, it } from 'vitest';
import {
  MIN_INDEXABLE_DESCRIPTION_LENGTH,
  MIN_INDEXABLE_DESCRIPTION_LENGTH_WITH_SHOWCASE,
  isIndexable,
  marketplacePath,
  metaDescription,
} from '../indexability';
import type { PublicPublicationSummary } from '../publicPublications';

function publication(overrides: Partial<PublicPublicationSummary> = {}): PublicPublicationSummary {
  return {
    id: 'pub-1',
    publicSlug: 'invoice-bot',
    title: 'Invoice Bot',
    description: 'x'.repeat(MIN_INDEXABLE_DESCRIPTION_LENGTH),
    publisherName: 'John Doe',
    publisherId: '42',
    publisherHandle: 'john-doe',
    publisherAvatarUrl: null,
    categorySlug: 'automation',
    categoryName: 'Automation',
    averageRating: 4.5,
    reviewCount: 12,
    useCount: 42,
    publishedAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-02T10:00:00Z',
    publicationType: 'WORKFLOW',
    categoryColor: null,
    displayMode: null,
    creditsPerUse: 0,
    hasShowcase: false,
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

describe('isIndexable', () => {
  it('accepts a listing with a slug, a title and a real description', () => {
    expect(isIndexable(publication())).toBe(true);
  });

  it('rejects a listing with no slug: there is no canonical URL to index', () => {
    // Such a row predates the backfill and is only reachable by UUID.
    expect(isIndexable(publication({ publicSlug: null }))).toBe(false);
  });

  it('rejects a description below the thin-content floor', () => {
    expect(isIndexable(publication({ description: 'Chases invoices.' }))).toBe(false);
  });

  it('rejects a description that is only whitespace padding', () => {
    // Length alone would pass this; trimming is what makes the gate meaningful.
    expect(isIndexable(publication({ description: ' '.repeat(400) }))).toBe(false);
  });

  it('rejects a whitespace-only title', () => {
    expect(isIndexable(publication({ title: '   ' }))).toBe(false);
  });

  it('accepts a description exactly at the floor (boundary is inclusive)', () => {
    const atFloor = 'a'.repeat(MIN_INDEXABLE_DESCRIPTION_LENGTH);

    expect(isIndexable(publication({ description: atFloor }))).toBe(true);
  });

  it('rejects a description one character below the floor', () => {
    const belowFloor = 'a'.repeat(MIN_INDEXABLE_DESCRIPTION_LENGTH - 1);

    expect(isIndexable(publication({ description: belowFloor }))).toBe(false);
  });

  it('does not require a category: a good listing without one is still indexable', () => {
    expect(isIndexable(publication({ categorySlug: null, categoryName: null }))).toBe(true);
  });

  describe('a listing whose page renders the published application', () => {
    // The listing page shows the frozen showcase running, plus the workflow
    // behind it, so it is not a title and a sentence any more: the description
    // floor that assumed it was kept substantive pages out of the index.
    const shortButReal = 'Turns a screenshot of any interface into a Telegram photo.';

    it('is indexable below the plain floor when it has a showcase', () => {
      expect(shortButReal.length).toBeLessThan(MIN_INDEXABLE_DESCRIPTION_LENGTH);
      expect(isIndexable(publication({ description: shortButReal, hasShowcase: true }))).toBe(true);
    });

    it('is still rejected without a showcase, where the page IS just the text', () => {
      expect(isIndexable(publication({ description: shortButReal, hasShowcase: false }))).toBe(false);
    });

    it('rejects a placeholder description however much the page renders', () => {
      // The gate's whole purpose: "test" says nothing about the listing, and a
      // rendered application does not make the words on the page meaningful.
      for (const placeholder of ['test', 'asdf', 'my workflow']) {
        expect(isIndexable(publication({ description: placeholder, hasShowcase: true }))).toBe(false);
      }
    });

    it('accepts exactly at the showcase floor and rejects one character below', () => {
      const atFloor = 'a'.repeat(MIN_INDEXABLE_DESCRIPTION_LENGTH_WITH_SHOWCASE);
      const belowFloor = 'a'.repeat(MIN_INDEXABLE_DESCRIPTION_LENGTH_WITH_SHOWCASE - 1);

      expect(isIndexable(publication({ description: atFloor, hasShowcase: true }))).toBe(true);
      expect(isIndexable(publication({ description: belowFloor, hasShowcase: true }))).toBe(false);
    });

    it('still needs a slug: a showcase does not create a URL to index', () => {
      expect(
        isIndexable(publication({ description: shortButReal, hasShowcase: true, publicSlug: null })),
      ).toBe(false);
    });
  });
});

describe('marketplacePath', () => {
  it('builds the canonical listing path', () => {
    expect(marketplacePath('invoice-bot')).toBe('/marketplace/invoice-bot');
  });
});

describe('metaDescription', () => {
  it('returns a short description unchanged', () => {
    expect(metaDescription(publication({ description: 'Chases unpaid invoices.' }))).toBe(
      'Chases unpaid invoices.',
    );
  });

  it('falls back to a generated sentence when there is no description', () => {
    // An empty meta description is flagged by search consoles.
    expect(metaDescription(publication({ description: '   ' }))).toBe(
      'Invoice Bot on the LiveContext marketplace.',
    );
  });

  it('truncates long descriptions on a word boundary, never mid-word', () => {
    // Comfortably over the 155-character default so truncation actually runs.
    const description = 'automate every single invoice reminder across all of your customer accounts '
      + 'without writing any code whatsoever today or ever again in your life, and keep '
      + 'every payment chased until it finally lands in your bank account';

    const result = metaDescription(publication({ description }));

    expect(result.length).toBeLessThanOrEqual(155);
    expect(result.endsWith('…')).toBe(true);
    const lastWord = result.slice(0, -1).trim().split(' ').pop() as string;
    expect(description).toContain(lastWord);
  });

  it('respects a caller-provided max length', () => {
    const result = metaDescription(publication({ description: 'a'.repeat(300) }), 40);

    expect(result.length).toBeLessThanOrEqual(40);
  });

  it('does not append an ellipsis when nothing was cut', () => {
    expect(metaDescription(publication({ description: 'Short and sweet.' }))).not.toContain('…');
  });
});
