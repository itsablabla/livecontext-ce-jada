/**
 * The public integration directory's view model: types, pure mappers and URL
 * helpers, shared by the server pages and by the client-side search box.
 *
 * <p>Deliberately free of `server-only` and of any I/O, unlike its sibling
 * {@link file://./publicIntegrations.ts}. The search box parses the SAME payload
 * the pages render, and the alternative is a second, hand-kept copy of these
 * shapes on the client that drifts from this one the first time a field moves.
 */

/** One integration, as `/api/public/integrations` returns it. */
export interface PublicIntegration {
  /** URL segment of /integrations/{slug}, and the catalog's `api_slug`. */
  slug: string;
  name: string;
  description: string;
  /** Key into /icons/services/{iconSlug}.svg. The backend defaults it to "mcp". */
  iconSlug: string;
  /** Explicit icon URL when the integration declares one, else null. */
  iconUrl: string | null;
  /** Active endpoints exposed as tools. */
  toolCount: number;
  /** oauth2 | api_key | bearer | basic | none | custom, or null when undeclared. */
  authType: string | null;
}

/** One endpoint of an integration. */
export interface PublicIntegrationTool {
  name: string;
  description: string;
  /** HTTP verb of the underlying call, or null when the tool declares none. */
  method: string | null;
}

export interface PublicIntegrationDetail {
  integration: PublicIntegration;
  documentation: string | null;
  tools: PublicIntegrationTool[];
  /** True when `tools` is a prefix of a longer list, so the page can say so. */
  toolsTruncated: boolean;
}

/** A page of integrations plus the total the pager and the headline counts read. */
export interface PublicIntegrationPage {
  integrations: PublicIntegration[];
  totalElements: number;
  /** True when the walk stopped early, so a caller can avoid claiming completeness. */
  truncated: boolean;
}

function asString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

function asNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

/**
 * Map one raw integration.
 *
 * <p>Defensive on purpose, like the marketplace mapper it mirrors: these pages
 * render whatever the catalog happens to contain, including rows written before
 * a given field existed. A row with no slug or no name cannot make a card or a
 * URL, so it is dropped rather than rendered half-empty.
 */
export function mapIntegration(raw: unknown): PublicIntegration | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const row = raw as Record<string, unknown>;

  const slug = asString(row.slug);
  const name = asString(row.name);
  if (!slug || !name) return null;

  return {
    slug,
    name,
    description: asString(row.description) ?? '',
    // "mcp" is the catalog's own fallback glyph, and the icon file exists, so a
    // row with no icon still renders a mark rather than a broken image.
    iconSlug: asString(row.iconSlug) ?? 'mcp',
    iconUrl: asString(row.iconUrl),
    toolCount: asNumber(row.toolCount),
    authType: asString(row.authType),
  };
}

/** Map a page payload. A non-array `content` yields an empty list rather than a throw. */
export function mapIntegrations(payload: unknown): PublicIntegration[] {
  if (typeof payload !== 'object' || payload === null) return [];
  const list = (payload as Record<string, unknown>).content;
  if (!Array.isArray(list)) return [];
  return list
    .map(mapIntegration)
    .filter((item): item is PublicIntegration => item !== null);
}

/** Internal to {@link mapIntegrationDetail}: an endpoint row. */
function mapIntegrationTool(raw: unknown): PublicIntegrationTool | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const row = raw as Record<string, unknown>;
  const name = asString(row.name);
  if (!name) return null;
  return {
    name,
    description: asString(row.description) ?? '',
    method: asString(row.method),
  };
}

export function mapIntegrationDetail(payload: unknown): PublicIntegrationDetail | null {
  if (typeof payload !== 'object' || payload === null) return null;
  const body = payload as Record<string, unknown>;

  const integration = mapIntegration(body.integration);
  if (!integration) return null;

  return {
    integration,
    documentation: asString(body.documentation),
    tools: Array.isArray(body.tools)
      ? body.tools.map(mapIntegrationTool).filter((t): t is PublicIntegrationTool => t !== null)
      : [],
    toolsTruncated: body.toolsTruncated === true,
  };
}

/**
 * Slug shape the catalog's own generator produces: lowercase alphanumerics
 * joined by single hyphens.
 *
 * <p>Validated BEFORE any fetch, for the same reason the marketplace validates
 * its own: /integrations/{slug} is a dynamic route, so every URL a scanner
 * invents would otherwise become one gateway request from the SSR pod, all
 * sharing a single anonymous rate-limit bucket. Rejecting junk locally turns a
 * URL scan into cheap local 404s instead of load that would make legitimate
 * pages render empty.
 *
 * <p>Byte-identical to the marketplace's own validator, and copied on purpose
 * rather than shared: that one lives in `publicPublications.ts`, which is
 * `server-only`, and this module has to stay importable by the client search
 * box. Extracting a third module for eight lines is the "mauvaise abstraction"
 * AGENTS.md warns about; the two are independent by design, and if the catalog's
 * slug shape ever diverges from the marketplace's, they SHOULD diverge here.
 */
const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;
const SLUG_MAX_LENGTH = 120;

export function isValidIntegrationSlug(slug: string): boolean {
  if (typeof slug !== 'string') return false;
  if (slug.length === 0 || slug.length > SLUG_MAX_LENGTH) return false;
  return SLUG_PATTERN.test(slug);
}

/**
 * The locale the PUBLIC pages format numbers in.
 *
 * <p>AGENTS.md bans hardcoding a locale in a `toLocale*` call, because doing it
 * in the app makes a French reader of the /en app see French dates. The public
 * site is not the app: every page on it renders identical English at a single
 * bare URL for every visitor (the LandingShell contract), so there is no app
 * locale to follow, and the browser's is exactly the value that rule exists to
 * keep out. Named rather than inlined so the reason travels with each use.
 */
export const PUBLIC_SITE_LOCALE = 'en';

/** Canonical path of one integration page. */
export function integrationPath(slug: string): string {
  return `/integrations/${slug}`;
}

/** Where the integration's mark is served from. */
export function integrationIconSrc(integration: PublicIntegration): string {
  return integration.iconUrl ?? `/icons/services/${integration.iconSlug}.svg`;
}

/**
 * Every `auth_type` the catalog actually stores, with how many seeds carry it.
 *
 * <p>Counted from `scripts/api-migrations/*.json` (the importer copies
 * `auth[0].type` straight into `catalog.apis.auth_type`), not guessed from a
 * type name: `api_key` 330, `bearer_token` 290, `oauth2` 157, `basic_auth` 102,
 * `custom` 85, `none` 13.
 *
 * <p>This list exists because the first version of {@link authTypeLabel} handled
 * `bearer` and `basic`, which occur ZERO times, so 40% of the catalog rendered no
 * badge at all and nothing failed. It is the same trap AGENTS.md documents for
 * `tool_slug`: a value the code invents, and fixtures that then certify the
 * invention. Re-derive this list from the seeds before changing it.
 */
export const CATALOG_AUTH_TYPES = [
  'api_key',
  'bearer_token',
  'oauth2',
  'basic_auth',
  'custom',
  'none',
] as const;

/**
 * How the auth type reads to a visitor.
 *
 * <p>`none` becomes "No key needed", which is the one a visitor actually scans
 * for: it marks the integrations they can try without opening an account
 * anywhere. An unrecognised value falls through to null so the badge is dropped
 * rather than showing a raw enum to a stranger, which is the right behaviour for
 * a value added to the catalog later, and the wrong one to rely on for a value
 * that already exists (see {@link CATALOG_AUTH_TYPES}).
 */
export function authTypeLabel(authType: string | null): string | null {
  switch ((authType ?? '').toLowerCase()) {
    case 'oauth2':
      return 'OAuth';
    case 'api_key':
      return 'API key';
    case 'bearer_token':
      return 'Token';
    case 'basic_auth':
      return 'Basic auth';
    case 'custom':
      return 'Custom auth';
    case 'none':
      return 'No key needed';
    default:
      return null;
  }
}

/**
 * A one-line description for a card or a meta tag: trimmed, and cut on a word
 * boundary with a real ellipsis rather than mid-word.
 *
 * <p>Falls back to a sentence built from the name so a card is never blank and
 * no page ships an empty meta description (which search consoles flag).
 */
export function integrationSummary(integration: PublicIntegration, maxLength = 155): string {
  const description = integration.description.trim();
  if (description.length === 0) {
    return `Connect ${integration.name} to your AI workflows and agents with LiveContext.`;
  }
  if (description.length <= maxLength) return description;

  const cut = description.slice(0, maxLength - 1);
  const lastSpace = cut.lastIndexOf(' ');
  return `${(lastSpace > 0 ? cut.slice(0, lastSpace) : cut).trimEnd()}…`;
}

/**
 * Minimum endpoints for an integration page to be worth indexing on its own.
 *
 * <p>The substance of `/integrations/{slug}` is the endpoint list: each entry is
 * a real operation with its own name and description, so the page grows with it.
 * An integration exposing one endpoint and no description renders a name and a
 * line, which is "thin content": search engines demote it, and enough of them
 * drag down the ranking of the whole domain, including the pages that already
 * perform. Those pages still render and stay fully reachable; they just carry
 * `noindex` and stay out of the sitemap.
 */
export const MIN_INDEXABLE_TOOL_COUNT = 3;

/** The description length that earns a page its place even with fewer endpoints. */
export const MIN_INDEXABLE_DESCRIPTION_LENGTH = 80;

/**
 * Whether an integration page should be indexed.
 *
 * <p>Pure, and exported, because the SAME rule has to drive the page's robots
 * meta AND the sitemap. Any divergence produces the worst outcome: a URL
 * advertised in the sitemap that then tells the crawler not to index it.
 */
export function isIndexableIntegration(integration: PublicIntegration): boolean {
  if (integration.toolCount >= MIN_INDEXABLE_TOOL_COUNT) return true;
  return integration.description.trim().length >= MIN_INDEXABLE_DESCRIPTION_LENGTH;
}
