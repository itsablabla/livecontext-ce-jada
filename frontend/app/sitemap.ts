import type { MetadataRoute } from 'next';
import { IS_CE } from '@/lib/edition';
import { COMPARISONS } from './compare/_lib/comparisons';
import { DOCS_PAGES } from './docs/_nav';
import { fetchAllPublicPublications } from '@/lib/marketplace/publicPublications';
import { isIndexable, marketplacePath } from '@/lib/marketplace/indexability';
import { fetchAllIntegrations } from '@/lib/integrations/publicIntegrations';
import {
  integrationPath,
  isIndexableIntegration,
  isValidIntegrationSlug,
} from '@/lib/integrations/integrations';

// Configurable at deploy time; falls back to the production domain.
const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

/**
 * Public-indexable surface of livecontext.ai (native Next.js sitemap).
 *
 * Included:
 *  - Landing `/` - ONE entry, the apex URL. The landing lives under
 *    `app/[locale]` but its content is hardcoded English on every locale
 *    (see the LandingShell contract), so the locale URLs are byte-identical
 *    duplicates: they canonicalize to the apex (app/[locale]/page.tsx) and are
 *    deliberately NOT listed here. Re-add per-locale entries WITH a reciprocal
 *    hreflang cluster only when the landing is actually translated.
 *  - `/compare/*` - the competitor comparison pages ("n8n alternative",
 *    "Zapier alternative", ...), enumerated from their content source so the
 *    sitemap never drifts from the live pages.
 *  - Marketing / legal sub-pages - these live OUTSIDE the `[locale]` tree and
 *    render at a single bare URL with runtime locale detection
 *    (i18n/resolveRequestLocale.ts), so they have no per-locale URL variants and
 *    therefore no hreflang alternates. `/changelog` is a live public nav entry
 *    (currently placeholder content) - kept at a modest priority.
 *  - Documentation - one entry per live docs page, enumerated from the docs IA
 *    (`app/docs/_nav.ts`) so the sitemap and the sidebar never drift apart.
 *  - Integrations - `/integrations` plus one entry per public integration that
 *    passes `isIndexableIntegration`, the SAME predicate driving each page's
 *    robots meta. Walked from the catalog rather than enumerated in the repo:
 *    integrations appear whenever a batch of APIs is imported.
 *
 * Excluded:
 *  - Blog (`/blog`, `/<locale>/blog`) - withheld while the section is being
 *    reworked. The routes still render, but they are unlinked from the landing
 *    and every blog page sends `noindex, nofollow`, so listing them here would
 *    advertise URLs that refuse indexing. It is deliberately NOT disallowed in
 *    robots.ts: a crawler that cannot fetch the page never reads the noindex,
 *    so already-indexed URLs would linger in the results. Re-add the index plus
 *    one entry per post (enumerated from `lib/blog/posts.ts`, each carrying the
 *    reciprocal `blogHreflang` cluster since the blog IS translated) when it
 *    ships again.
 *
 * Excluded and disallowed in robots.ts:
 *  - Auth-gated app (`/app/*`), `/onboarding`, `/ce-setup`, `/workflows/*`,
 *    `/billing/*`, `/local-mcp`, and token URLs (`/f`, `/s`, `/w/embed`).
 *  - `/login` and `/register`: on the cloud deployment these immediately redirect
 *    to the external OIDC provider (see app/[locale]/login/page.tsx) - content-less
 *    shims with no indexable value.
 *
 * CE deployments emit an empty sitemap: robots.ts already disallows everything
 * for self-hosted editions, and the build cannot know the deployer's domain.
 */
/**
 * Rendered per request, NOT prerendered at build time.
 *
 * The rest of the sitemap is enumerated from in-repo content, but listings
 * appear whenever someone publishes, so it cannot be frozen at build. More
 * importantly, the gateway is unreachable from the CI builder: prerendering
 * bakes a sitemap with ZERO listings, and each frontend replica then serves that
 * copy until it revalidates on its own. Verified in production: the sitemap had
 * regenerated (a fresh lastmod) and still advertised no listings, because other
 * replicas were still answering from the build-time copy.
 *
 * The catalog walk keeps its own hourly cache window, so this is one gateway
 * read per hour per replica, not one per sitemap fetch.
 */
export const dynamic = 'force-dynamic';

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  if (IS_CE) {
    return [];
  }

  const now = new Date();

  // The landing page: one canonical URL. Locale variants serve identical
  // English content and canonicalize here (see the header comment).
  const landing: MetadataRoute.Sitemap = [
    { url: SITE_URL, lastModified: now, changeFrequency: 'weekly', priority: 1.0 },
  ];

  // Competitor comparison pages, enumerated from their single content source.
  const compare: MetadataRoute.Sitemap = [
    { url: `${SITE_URL}/compare`, lastModified: now, changeFrequency: 'monthly', priority: 0.7 },
    ...COMPARISONS.map((comparison) => ({
      url: `${SITE_URL}/compare/${comparison.slug}`,
      lastModified: now,
      changeFrequency: 'weekly' as const,
      priority: 0.8,
    })),
  ];

  // Non-localized public sub-pages (single URL, runtime locale detection).
  const pages: MetadataRoute.Sitemap = [
    { url: `${SITE_URL}/about`, lastModified: now, changeFrequency: 'monthly', priority: 0.6 },
    { url: `${SITE_URL}/contact`, lastModified: now, changeFrequency: 'monthly', priority: 0.6 },
    { url: `${SITE_URL}/models`, lastModified: now, changeFrequency: 'weekly', priority: 0.7 },
    { url: `${SITE_URL}/changelog`, lastModified: now, changeFrequency: 'weekly', priority: 0.5 },
    // Status mirrors live monitoring, so it changes far more often than it is
    // worth crawling; the canonical incident history lives on the externally
    // hosted status page, which is why the priority stays low.
    { url: `${SITE_URL}/status`, lastModified: now, changeFrequency: 'daily', priority: 0.4 },
    { url: `${SITE_URL}/legal/privacy`, lastModified: now, changeFrequency: 'yearly', priority: 0.3 },
    { url: `${SITE_URL}/legal/terms`, lastModified: now, changeFrequency: 'yearly', priority: 0.3 },
    { url: `${SITE_URL}/legal/mentions`, lastModified: now, changeFrequency: 'yearly', priority: 0.3 },
  ];

  // Documentation pages live on the docs.livecontext.ai subdomain at clean paths
  // (the apex /docs/* redirects there). Enumerated from the docs IA so they never
  // drift. `_nav.ts` hrefs are already clean ('/', '/agents', ...).
  const docs: MetadataRoute.Sitemap = DOCS_PAGES.map((page) => ({
    url: `https://docs.livecontext.ai${page.href === '/' ? '' : page.href}`,
    lastModified: now,
    changeFrequency: 'monthly',
    priority: page.href === '/' ? 0.6 : 0.5,
  }));

  // Marketplace: the index plus every listing that passes the indexability gate.
  // The SAME predicate drives each page's robots meta, so the sitemap can never
  // advertise a URL that then tells the crawler not to index it.
  // Pass the sitemap's own window explicitly: Next takes the SHORTEST revalidate
  // among a route's fetches, so leaving the reader's 15 minute default here
  // would quietly override the hourly window declared above and rebuild the
  // whole catalog walk four times as often as intended.
  const { publications, truncated } = await fetchAllPublicPublications({ revalidateSeconds: 3600 });
  if (truncated) {
    // Never let a partial catalog look like a complete one.
    console.warn(
      `[sitemap] marketplace walk stopped early after ${publications.length} listings; `
      + 'the sitemap is incomplete (page cap reached or a gateway read failed).',
    );
  }
  const marketplace: MetadataRoute.Sitemap = [
    { url: `${SITE_URL}/marketplace`, lastModified: now, changeFrequency: 'daily', priority: 0.8 },
    ...publications.filter(isIndexable).map((publication) => ({
      url: `${SITE_URL}${marketplacePath(publication.publicSlug)}`,
      lastModified: publication.updatedAt ? new Date(publication.updatedAt) : now,
      changeFrequency: 'weekly' as const,
      priority: 0.6,
    })),
  ];

  // Integrations: the directory plus every integration page that passes the same
  // indexability gate its own `robots` meta reads, so the sitemap can never
  // advertise a URL that then tells the crawler not to index it.
  const { integrations, truncated: integrationsTruncated } = await fetchAllIntegrations({
    revalidateSeconds: 3600,
  });
  if (integrationsTruncated) {
    console.warn(
      `[sitemap] integration walk stopped early after ${integrations.length} integrations; `
      + 'the sitemap is incomplete (page cap reached or a gateway read failed).',
    );
  }
  const integrationEntries: MetadataRoute.Sitemap = [
    { url: `${SITE_URL}/integrations`, lastModified: now, changeFrequency: 'weekly', priority: 0.8 },
    // Also validated for SHAPE: `fetchIntegration` rejects a slug that does not
    // match locally, before any gateway call, so a slug the catalog somehow holds
    // in another shape would be advertised here and 404 on its own page.
    ...integrations
      .filter((integration) => isValidIntegrationSlug(integration.slug))
      .filter(isIndexableIntegration)
      .map((integration) => ({
        url: `${SITE_URL}${integrationPath(integration.slug)}`,
        lastModified: now,
        // The catalog changes when a batch of APIs is imported, which is weeks
        // apart, not daily like a marketplace anyone can publish to.
        changeFrequency: 'monthly' as const,
        priority: 0.6,
      })),
  ];

  return [...landing, ...compare, ...pages, ...docs, ...marketplace, ...integrationEntries];
}
