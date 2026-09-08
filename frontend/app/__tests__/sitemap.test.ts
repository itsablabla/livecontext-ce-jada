import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DOCS_PAGES } from '../docs/_nav';

const SITE = 'https://livecontext.ai';

/** A marketplace listing shaped like the public read path returns it. */
function listing(overrides: Record<string, unknown> = {}) {
  return {
    id: 'pub-1',
    publicSlug: 'invoice-bot',
    title: 'Invoice Bot',
    description: 'x'.repeat(200),
    publisherName: 'John Doe',
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
    ...overrides,
  };
}

/**
 * Stub the marketplace read path. The sitemap must never reach the network in a
 * unit test, and every test that does not care about listings gets an empty
 * catalog so the in-repo sections stay isolated.
 */
function mockMarketplace(publications: unknown[] = [], truncated = false) {
  vi.doMock('@/lib/marketplace/publicPublications', () => ({
    fetchAllPublicPublications: vi.fn().mockResolvedValue({ publications, truncated }),
  }));
}

/** An integration shaped like the public read path returns it. */
function integration(overrides: Record<string, unknown> = {}) {
  return {
    slug: 'slack',
    name: 'Slack',
    description: 'Post messages, manage channels and read conversations.',
    iconSlug: 'slack',
    iconUrl: null,
    toolCount: 45,
    authType: 'oauth2',
    ...overrides,
  };
}

/**
 * Stub the integration read path, for the same reason as the marketplace one.
 * Declared by every test rather than chained onto `mockMarketplace`: `vi.doMock`
 * keeps the FIRST registration for a path, so a helper registering the empty
 * default would win over a test asking for its own integrations, and that test
 * would pass on nothing.
 */
function mockIntegrations(integrations: unknown[] = [], truncated = false) {
  vi.doMock('@/lib/integrations/publicIntegrations', () => ({
    fetchAllIntegrations: vi.fn().mockResolvedValue({
      integrations,
      totalElements: integrations.length,
      truncated,
    }),
  }));
}

describe('sitemap - cloud edition', () => {
  beforeEach(() => vi.resetModules());

  it('emits one entry per live docs page, with the Overview at a higher priority', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations();
    const { default: sitemap } = await import('../sitemap');
    const entries = await sitemap();
    const urls = entries.map((e) => e.url);

    // Docs live on the subdomain at clean paths; every IA page is in the sitemap.
    const DOCS = 'https://docs.livecontext.ai';
    for (const page of DOCS_PAGES) {
      expect(urls).toContain(`${DOCS}${page.href === '/' ? '' : page.href}`);
    }
    // Overview is prioritised above its sub-pages.
    expect(entries.find((e) => e.url === DOCS)?.priority).toBe(0.6);
    expect(entries.find((e) => e.url === `${DOCS}/agents`)?.priority).toBe(0.5);
    // The apex landing root is still emitted alongside the docs.
    expect(urls).toContain(SITE);
  });

  it('emits the landing page ONCE at the apex (locale duplicates canonicalize there, not sitemap entries)', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations();
    const { default: sitemap } = await import('../sitemap');
    const { routing } = await import('@/i18n/routing');
    const urls = (await sitemap()).map((e) => e.url);

    expect(urls).toContain(SITE);
    // The landing serves identical English content on every locale URL, so
    // listing /fr, /es, ... would advertise duplicates that canonicalize away.
    for (const locale of routing.locales) {
      expect(urls).not.toContain(`${SITE}/${locale}`);
    }
  });

  it('emits the /compare hub and one entry per comparison page', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations();
    const { default: sitemap } = await import('../sitemap');
    const { COMPARISONS } = await import('../compare/_lib/comparisons');
    const entries = await sitemap();
    const urls = entries.map((e) => e.url);

    expect(urls).toContain(`${SITE}/compare`);
    for (const comparison of COMPARISONS) {
      expect(urls).toContain(`${SITE}/compare/${comparison.slug}`);
    }
    // Comparison pages are a primary SEO surface: above sub-pages, below the landing.
    expect(entries.find((e) => e.url === `${SITE}/compare/n8n-alternative`)?.priority).toBe(0.8);
  });

  it('withholds the blog while the section is being reworked', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations();
    const { default: sitemap } = await import('../sitemap');
    const { getAllPosts } = await import('@/lib/blog/posts');
    const urls = (await sitemap()).map((e) => e.url);

    // The blog routes still render but send `noindex, nofollow`. Advertising
    // them here would point crawlers at URLs that then refuse indexing.
    expect(urls).not.toContain(`${SITE}/blog`);
    const posts = getAllPosts();
    expect(posts.length).toBeGreaterThan(0); // the registry is non-empty, so this is a real exclusion
    for (const post of posts) {
      expect(urls).not.toContain(`${SITE}/blog/${post.slug}`);
    }
    expect(urls.some((url) => url.includes('/blog'))).toBe(false);
  });

});

describe('sitemap - marketplace listings', () => {
  beforeEach(() => vi.resetModules());

  it('emits the marketplace hub plus one entry per indexable listing', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([listing(), listing({ id: 'pub-2', publicSlug: 'expense-sorter' })]);
    mockIntegrations();
    const { default: sitemap } = await import('../sitemap');
    const entries = await sitemap();
    const urls = entries.map((e) => e.url);

    expect(urls).toContain(`${SITE}/marketplace`);
    expect(urls).toContain(`${SITE}/marketplace/invoice-bot`);
    expect(urls).toContain(`${SITE}/marketplace/expense-sorter`);
  });

  it('uses the listing updatedAt as lastModified so crawlers see real freshness', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([listing()]);
    mockIntegrations();
    const { default: sitemap } = await import('../sitemap');
    const entry = (await sitemap()).find((e) => e.url === `${SITE}/marketplace/invoice-bot`);

    expect(entry?.lastModified).toEqual(new Date('2026-07-02T10:00:00Z'));
  });

  it('omits a listing whose description is too thin to index', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([listing({ publicSlug: 'thin-app', description: 'too short' })]);
    mockIntegrations();
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    // The sitemap and the page's robots meta read the SAME predicate. Listing a
    // noindex URL here would advertise a page that then refuses indexing.
    expect(urls).not.toContain(`${SITE}/marketplace/thin-app`);
  });

  it('omits a listing that has no slug yet', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([listing({ publicSlug: null })]);
    mockIntegrations();
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    expect(urls.some((url) => url.startsWith(`${SITE}/marketplace/`))).toBe(false);
  });

  it('still emits the in-repo sections when the marketplace read fails', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([], true);
    mockIntegrations();
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    // A gateway blip must not empty the sitemap of the landing and docs.
    expect(urls).toContain(SITE);
    expect(urls).toContain('https://docs.livecontext.ai');
    expect(urls).toContain(`${SITE}/marketplace`);
  });
});

describe('sitemap - integrations', () => {
  beforeEach(() => vi.resetModules());

  it('emits the directory plus one entry per indexable integration', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations([integration(), integration({ slug: 'github', name: 'GitHub' })]);
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    expect(urls).toContain(`${SITE}/integrations`);
    expect(urls).toContain(`${SITE}/integrations/slack`);
    expect(urls).toContain(`${SITE}/integrations/github`);
  });

  it('omits an integration too thin to index', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations([integration({ slug: 'tiny', toolCount: 1, description: 'An API.' })]);
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    // The sitemap and the page's robots meta read the SAME predicate. Listing a
    // noindex URL here would advertise a page that then refuses indexing.
    expect(urls).not.toContain(`${SITE}/integrations/tiny`);
    // The directory itself stays, whatever the catalog holds.
    expect(urls).toContain(`${SITE}/integrations`);
  });

  it('omits an integration whose slug is not the shape the page accepts', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations([integration({ slug: 'Not A Slug' }), integration({ slug: 'github' })]);
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    // `fetchIntegration` rejects a malformed slug locally, before any gateway call,
    // so advertising one here would be a sitemap entry whose own page 404s.
    expect(urls).toContain(`${SITE}/integrations/github`);
    expect(urls.some((url) => url.includes('Not A Slug'))).toBe(false);
    expect(urls.filter((url) => url.startsWith(`${SITE}/integrations/`))).toHaveLength(1);
  });

  it('still emits the in-repo sections when the catalog read fails', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations([], true);
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    expect(urls).toContain(SITE);
    expect(urls).toContain(`${SITE}/integrations`);
  });
});

describe('sitemap - community edition', () => {
  beforeEach(() => vi.resetModules());

  it('is empty on a self-hosted edition (never indexed)', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: true }));
    mockMarketplace([listing()]);
    mockIntegrations();
    const { default: sitemap } = await import('../sitemap');

    // Even with a full catalog available, a self-hosted install advertises
    // nothing: robots.ts already disallows everything there.
    expect(await sitemap()).toEqual([]);
  });
});
