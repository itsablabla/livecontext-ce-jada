/**
 * Server-side reads of the public integration catalog, for the crawlable pages
 * (`/integrations`, `/integrations/{slug}`, the landing section and the footer).
 *
 * <p>Mirrors `lib/marketplace/publicPublications.ts` exactly, and for the same
 * three reasons documented there: it calls the gateway DIRECTLY rather than
 * looping back through `/api/proxy`, it does NOT use `lib/api/api-client` (that
 * client is browser-bound and a `globalThis` singleton, so giving it a token on
 * the server would share that token across concurrent requests), and it reads
 * `GATEWAY_SERVICE_URL` first because `NEXT_PUBLIC_*` variables are inlined at
 * BUILD time while the non-public one is injected into the pod at runtime.
 *
 * <p>`/api/public/integrations` is anonymous by design at the gateway, so no
 * credentials are ever attached here, and the backend serves only integrations
 * marked `visibility = 'public'`.
 */
import 'server-only';

import { gatewayBaseUrl } from '@/lib/marketplace/publicPublications';
import {
  isValidIntegrationSlug,
  mapIntegrationDetail,
  mapIntegrations,
  type PublicIntegration,
  type PublicIntegrationDetail,
  type PublicIntegrationPage,
} from './integrations';

/**
 * How long a public page may serve a stale copy of the catalog, in seconds.
 *
 * <p>An hour. The catalog changes when a batch of APIs is imported, which is a
 * deliberate operation measured in weeks, not a stream of user writes like the
 * marketplace's 15 minutes. The landing lowers this to its own window (see
 * below) because Next takes the SHORTEST revalidate among a route's fetches and
 * a longer one here would silently claim a freshness the page does not have.
 */
export const PUBLIC_INTEGRATIONS_REVALIDATE_SECONDS = 3600;

/**
 * The landing's window, matching its own ISR window (`revalidate = 600`).
 *
 * <p>Passed explicitly rather than inherited: Next lowers a route's window from
 * its fetches but never raises it, so leaving the hour above on a 600s page
 * would make the real staleness an hour while the page's own comment claims ten
 * minutes.
 */
export const LANDING_INTEGRATIONS_REVALIDATE_SECONDS = 600;

/**
 * How long a public page waits for the gateway before rendering without it.
 *
 * <p>A ceiling, not a target. What it bounds is the case the try/catch cannot
 * see: a gateway that accepts the connection and then never answers. Every
 * caller here is on the critical path of a public page, so an unbounded read is
 * a render that hangs rather than a page that degrades.
 */
const GATEWAY_READ_TIMEOUT_MS = 8000;

/** Distinguishes "this integration does not exist" from "we could not ask". */
class IntegrationsUnavailableError extends Error {}

/**
 * The outcome of one gateway read.
 *
 * <p>A flat record rather than a discriminated union on `ok`: this frontend
 * compiles with `strict: false`, and narrowing a union by a boolean literal does
 * not survive that, so `result.status` after an `if (result.ok) return` fails to
 * type-check. Every field is always present instead, and each is documented for
 * when it means anything.
 */
interface GatewayRead {
  /** True when the gateway answered 2xx. */
  ok: boolean;
  /** The parsed body. Only meaningful when {@link ok}. */
  body: unknown;
  /**
   * The HTTP status, or null when the request never completed at all.
   *
   * <p>Null is the load-bearing case: it lets a caller tell an answered 404 from
   * a gateway we could not reach. One means the page is gone, the other means we
   * could not ask, and turning the second into a 404 would invite search engines
   * to drop pages that still exist.
   */
  status: number | null;
}

async function getJson(path: string, revalidateSeconds: number): Promise<GatewayRead> {
  try {
    const res = await fetch(`${gatewayBaseUrl()}${path}`, {
      headers: { Accept: 'application/json' },
      next: { revalidate: revalidateSeconds },
      signal: AbortSignal.timeout(GATEWAY_READ_TIMEOUT_MS),
    });
    if (!res.ok) return { ok: false, body: null, status: res.status };
    return { ok: true, body: await res.json(), status: res.status };
  } catch {
    // A network failure, or the timeout above.
    return { ok: false, body: null, status: null };
  }
}

/**
 * One page of integrations, most-run first.
 *
 * <p>Degrades to an empty page: a landing section or a footer column must lose
 * itself, never the page it sits on.
 */
export async function fetchIntegrations({
  page = 0,
  size = 60,
  query,
  revalidateSeconds = PUBLIC_INTEGRATIONS_REVALIDATE_SECONDS,
}: {
  page?: number;
  size?: number;
  query?: string;
  revalidateSeconds?: number;
} = {}): Promise<PublicIntegrationPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (query && query.trim() !== '') params.set('q', query.trim());

  const result = await getJson(`/api/public/integrations?${params.toString()}`, revalidateSeconds);
  if (!result.ok) return { integrations: [], totalElements: 0, truncated: true };

  const body = result.body as Record<string, unknown>;
  return {
    integrations: mapIntegrations(result.body),
    totalElements: typeof body.totalElements === 'number' ? body.totalElements : 0,
    truncated: false,
  };
}

/**
 * The N most-run integrations, for the landing section and the footer column.
 *
 * <p>One page, not a walk: these surfaces show a handful, and the whole
 * catalogue is one click away at /integrations.
 */
export async function fetchTopIntegrations(
  limit: number,
  revalidateSeconds = PUBLIC_INTEGRATIONS_REVALIDATE_SECONDS,
): Promise<PublicIntegrationPage> {
  return fetchIntegrations({ page: 0, size: limit, revalidateSeconds });
}

/**
 * Every public integration, walked page by page, for the directory and the sitemap.
 *
 * <p>Bounded on purpose. `maxPages` caps the work one render can do, and
 * reaching that cap is REPORTED rather than silently truncating: a directory
 * that quietly drops half the catalog looks healthy while hiding pages from
 * search engines, exactly the failure the marketplace walk was built to avoid.
 * The walk also stops as soon as a page comes back short, the normal end.
 */
export async function fetchAllIntegrations({
  pageSize = 200,
  maxPages = 20,
  revalidateSeconds = PUBLIC_INTEGRATIONS_REVALIDATE_SECONDS,
} = {}): Promise<PublicIntegrationPage> {
  const all: PublicIntegration[] = [];
  let totalElements = 0;

  for (let page = 0; page < maxPages; page++) {
    const params = new URLSearchParams({ page: String(page), size: String(pageSize) });
    const result = await getJson(`/api/public/integrations?${params.toString()}`, revalidateSeconds);
    // A failed page ends the walk: continuing would produce a directory with a
    // hole in the middle of the catalog and no sign of it.
    if (!result.ok) return { integrations: all, totalElements, truncated: true };

    const body = result.body as Record<string, unknown>;
    if (typeof body.totalElements === 'number') totalElements = body.totalElements;

    const batch = mapIntegrations(result.body);
    all.push(...batch);

    const rawCount = Array.isArray(body.content) ? (body.content as unknown[]).length : 0;
    if (rawCount < pageSize) return { integrations: all, totalElements, truncated: false };
  }

  return { integrations: all, totalElements, truncated: true };
}

/**
 * One integration by slug.
 *
 * <p>Returns null when the slug is unknown or not public (the backend answers
 * 404 for both, deliberately indistinguishably) so the caller can `notFound()`.
 *
 * <p>THROWS when the catalog could not be read at all. That asymmetry is the
 * point: a 404 tells a crawler the page is gone and invites it to drop the URL,
 * so a gateway blip must surface as a 500 the crawler retries, not as a 404
 * that quietly deindexes an integration.
 */
export async function fetchIntegration(
  slug: string,
  revalidateSeconds = PUBLIC_INTEGRATIONS_REVALIDATE_SECONDS,
): Promise<PublicIntegrationDetail | null> {
  if (!isValidIntegrationSlug(slug)) return null;

  const result = await getJson(
    `/api/public/integrations/${encodeURIComponent(slug)}`,
    revalidateSeconds,
  );
  if (result.ok) return mapIntegrationDetail(result.body);
  if (result.status === 404) return null;

  throw new IntegrationsUnavailableError(
    `Could not read integration "${slug}" from the catalog`
      + `${result.status === null ? '' : ` (HTTP ${result.status})`}`,
  );
}
