import { mapProbePayload } from './probeData';
import { mapWidgetPayload } from './incidentIo';
import { deriveOverall } from './overall';
import type { IncidentsSource, ServiceStatus } from './types';

/**
 * Server-side assembly of the status payload: one cached hop, merging the two
 * sources.
 *
 * Why this runs server-side and cached rather than from each browser:
 *  - incident.io asks that the Widget API be proxied through your own backend
 *    with caching instead of being hit per viewer;
 *  - the probe file is served on a private VLAN address (STATUS_DATA_URL), so it is not
 *    reachable from a browser at all, by design - no internet-facing port on the
 *    box that also holds the CI runner.
 *
 * Failure is per-source and never fatal: a dead probe still shows incidents, a
 * dead incident.io still shows components, and both dead renders an explicit
 * "unavailable" state. See deriveOverall for why that is never "operational".
 */

/** How long an upstream answer is reused. The only cache window in the feature. */
export const STATUS_REVALIDATE_SECONDS = 30;

/** An upstream must not hold a render open; 4 s is far above both p99s. */
const UPSTREAM_TIMEOUT_MS = 4000;

/** Probe snapshot on ops-host. Accepts a base URL or a full path to the file. */
function probeUrl(): string | null {
  const raw = (process.env.STATUS_DATA_URL ?? '').trim();
  if (!raw) return null;
  if (/\.json($|\?)/.test(raw)) return raw;
  return `${raw.replace(/\/+$/, '')}/status.json`;
}

/** incident.io status-page Widget API URL (read off its Widget API settings). */
function widgetUrl(): string | null {
  const raw = (process.env.STATUS_WIDGET_API_URL ?? '').trim();
  return raw || null;
}

/**
 * Canonical, externally hosted status page. It must stay reachable when we are
 * not, which is why it is an incident.io domain and not a route of ours.
 *
 * Read server-side only and handed to the client INSIDE the payload, so it is a
 * plain runtime env var: a `NEXT_PUBLIC_` name would promise build-time inlining
 * that nothing here needs, and would mean rebuilding the image to change a link.
 */
export function statusPageUrl(): string | null {
  const raw = (process.env.STATUS_PAGE_URL ?? '').trim();
  return raw || null;
}

/**
 * Cross-module cache of each upstream answer, plus the in-flight request for it.
 *
 * Two reasons it is explicit rather than left to Next's data cache:
 *  - the page (a server component) and `GET /api/status` (a route handler) are
 *    bundled into SEPARATE module registries, so a plain module-level Map gives
 *    each of them its own copy - which showed up as the page reporting an outage
 *    while the endpoint still served "operational" from its own stale window.
 *    `Symbol.for` resolves to one slot on `globalThis` for the whole process, so
 *    both surfaces read the same answer at the same time.
 *  - concurrent viewers. Without the in-flight map, a poll from N tabs arriving
 *    together all miss the cache and all go upstream, which is precisely the
 *    burst incident.io asks callers not to send. Sharing the promise makes the
 *    stampede one request.
 *
 * Failures are cached for the same window: an unreachable probe must not turn a
 * burst of viewers into a burst of connection attempts. And because failures are
 * cached HERE, an upstream that starts failing surfaces within one window - which
 * is why the fetch below opts out of Next's data cache entirely.
 */
interface CacheEntry {
  value: unknown | null;
  expiresAt: number;
}
interface UpstreamCache {
  entries: Map<string, CacheEntry>;
  inflight: Map<string, Promise<unknown | null>>;
}
const CACHE_SLOT = Symbol.for('livecontext.status.upstreamCache');

function cache(): UpstreamCache {
  const host = globalThis as unknown as Record<symbol, UpstreamCache | undefined>;
  const existing = host[CACHE_SLOT];
  if (existing) return existing;
  const created: UpstreamCache = { entries: new Map(), inflight: new Map() };
  host[CACHE_SLOT] = created;
  return created;
}

/** Test-only: drop the shared cache so cases do not leak into each other. */
export function resetStatusCacheForTests(): void {
  const { entries, inflight } = cache();
  entries.clear();
  inflight.clear();
}

async function fetchJson(url: string, cacheSeconds: number): Promise<unknown | null> {
  const { entries, inflight } = cache();
  const now = Date.now();

  const cached = entries.get(url);
  if (cached && cached.expiresAt > now) return cached.value;

  const pending = inflight.get(url);
  if (pending) return pending;

  const request = (async () => {
    let value: unknown | null = null;
    try {
      const res = await fetch(url, {
        headers: { Accept: 'application/json' },
        signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
        // `no-store`, with the memo above as the ONLY cache. Measured, not
        // assumed: with `next: { revalidate }` the data cache kept serving the
        // last SUCCESSFUL body after the upstream started failing (it does not
        // cache failures, so the previous entry just stays), and the page went
        // on reporting a healthy incident feed for as long as it was asked.
        // A status page that cannot notice its own source going away is the
        // single worst failure this file can have, so the caching is ours.
        cache: 'no-store',
      });
      // Unreachable, timed out, non-200 or not JSON: the caller degrades, never throws.
      value = res.ok ? await res.json() : null;
    } catch {
      value = null;
    }
    entries.set(url, { value, expiresAt: Date.now() + Math.max(1, cacheSeconds) * 1000 });
    inflight.delete(url);
    return value;
  })();

  inflight.set(url, request);
  return request;
}

export async function fetchServiceStatus(
  revalidateSeconds = STATUS_REVALIDATE_SECONDS,
): Promise<ServiceStatus> {
  const probe = probeUrl();
  const widget = widgetUrl();

  const [probePayload, widgetPayload] = await Promise.all([
    probe ? fetchJson(probe, revalidateSeconds) : Promise.resolve(null),
    widget ? fetchJson(widget, revalidateSeconds) : Promise.resolve(null),
  ]);

  const snapshot = mapProbePayload(probePayload);
  const incidentSnapshot = mapWidgetPayload(widgetPayload);

  const components = snapshot?.components ?? [];
  const incidents = incidentSnapshot?.incidents ?? [];
  const dataAvailable = snapshot !== null;
  // Not configured is a setup fact, unreachable is a blind spot. Only the second
  // one is allowed to hold back an all-clear (see deriveOverall).
  const incidentsSource: IncidentsSource = !widget
    ? 'not_configured'
    : incidentSnapshot !== null
      ? 'ok'
      : 'unreachable';

  return {
    overall: deriveOverall({ components, incidents, dataAvailable, incidentsSource }),
    components,
    incidents,
    windowDays: snapshot?.windowDays ?? 0,
    generatedAt: snapshot?.generatedAt ?? null,
    sampleIntervalSeconds: snapshot?.sampleIntervalSeconds ?? null,
    dataAvailable,
    incidentsSource,
    statusPageUrl: statusPageUrl(),
  };
}
