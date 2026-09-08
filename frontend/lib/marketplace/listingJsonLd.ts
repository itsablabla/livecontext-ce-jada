import type { PublicPublicationSummary } from './publicPublications';
import { marketplacePath, metaDescription } from './indexability';

/**
 * The `SoftwareApplication` node describing one marketplace listing.
 *
 * <p>Two pages emit this for the same listing: its own page, and the index's
 * `ItemList`. They were written separately and had already drifted, which is
 * the failure mode structured data is worst at showing you: nothing errors,
 * Google simply reconciles two descriptions of one entity and the richer one
 * loses. One builder, one description.
 *
 * <p>Pure and side-effect free so both callers can be tested without rendering.
 *
 * <p><b>The slug is a required ARGUMENT, not a field read.</b> `publicSlug` is
 * nullable on the view model (rows predating the backfill have none), and this
 * repo compiles with `strict: false`, so `strictNullChecks` is off and NOTHING
 * stops a caller handing over a listing whose slug is null: the result is
 * `"url": ".../marketplace/null"` in the structured data, with no type error,
 * no lint error and no failing test. Neither a cast nor a narrowed parameter
 * type can fix that here, because the compiler is not checking. Making the slug
 * a separate required argument is what forces each caller to say which URL it
 * means - the index its filtered listing's, the listing page the one in its own
 * route.
 */
export function listingJsonLd(
  publication: PublicPublicationSummary,
  { siteUrl, slug }: { siteUrl: string; slug: string },
): Record<string, unknown> {
  const url = `${siteUrl}${marketplacePath(slug)}`;

  const node: Record<string, unknown> = {
    '@type': 'SoftwareApplication',
    name: publication.title,
    description: metaDescription(publication),
    url,
    // The listing's OWN OpenGraph card, generated per listing by the
    // `opengraph-image` route next to its page. The site-wide og-image.jpg
    // would give eighty entries one identical picture, which is what makes a
    // catalogue look templated rather than populated.
    image: `${url}/opengraph-image`,
    applicationCategory: 'BusinessApplication',
    operatingSystem: 'Web',
  };

  if (publication.publisherName) {
    node.author = { '@type': 'Person', name: publication.publisherName };
  }

  // Only claim a rating when one actually exists: an aggregateRating with
  // reviewCount 0 is invalid structured data and earns a Search Console error.
  if (publication.reviewCount > 0) {
    node.aggregateRating = {
      '@type': 'AggregateRating',
      ratingValue: publication.averageRating,
      reviewCount: publication.reviewCount,
    };
  }

  return node;
}

/**
 * One entry of the index's `ItemList`.
 *
 * <p>Carries `item` and NOT a sibling `url`. Those are Google's two different
 * carousel shapes: a bare `position` + `url` for a summary page that only
 * points at its members, and a nested `item` for one that describes them.
 * Emitting both asks the parser to pick, and the nested form is the one worth
 * having here because it carries the description and the image.
 */
export function listingListItem(
  publication: PublicPublicationSummary,
  position: number,
  options: { siteUrl: string; slug: string },
): Record<string, unknown> {
  return { '@type': 'ListItem', position, item: listingJsonLd(publication, options) };
}
