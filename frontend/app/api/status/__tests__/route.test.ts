/**
 * Tests for `GET /api/status`.
 *
 * The route's job is small and its two properties are both load-bearing: it must
 * answer 200 with a usable payload even when everything upstream is down (a
 * status endpoint that 500s during an outage is worse than useless), and it must
 * carry a cache header, since it is the shared hop that keeps viewer count from
 * multiplying calls to incident.io.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { ServiceStatus } from '@/lib/status/types';

const fetchServiceStatus = vi.fn();

vi.mock('@/lib/status/fetchStatus', () => ({
  STATUS_REVALIDATE_SECONDS: 30,
  fetchServiceStatus: (...args: unknown[]) => fetchServiceStatus(...args),
}));

const edition = { IS_CE: false };
vi.mock('@/lib/edition/edition', () => ({
  get IS_CE() {
    return edition.IS_CE;
  },
}));

const routeModule = await import('../route');
const { GET } = routeModule;

function payload(overrides: Partial<ServiceStatus> = {}): ServiceStatus {
  return {
    overall: 'operational',
    components: [],
    incidents: [],
    windowDays: 90,
    generatedAt: '2026-09-05T10:00:00Z',
    sampleIntervalSeconds: 60,
    dataAvailable: true,
    incidentsSource: 'ok',
    statusPageUrl: null,
    ...overrides,
  };
}

beforeEach(() => {
  fetchServiceStatus.mockReset();
  edition.IS_CE = false;
});

describe('GET /api/status', () => {
  it('serves the assembled payload with a shared-cache header', async () => {
    fetchServiceStatus.mockResolvedValue(payload());

    const response = await GET();

    expect(response.status).toBe(200);
    expect(response.headers.get('Cache-Control')).toBe(
      'public, s-maxage=30, stale-while-revalidate=120',
    );
    await expect(response.json()).resolves.toMatchObject({ overall: 'operational' });
  });

  it('renders per request, never prerendered at build time', async () => {
    // A GET route handler is statically prerendered by default, which would
    // freeze a build-machine "nothing reachable" answer into the image for its
    // whole life. The upstream cost is bounded by the memo in fetchStatus, not
    // by this export.
    expect(routeModule.dynamic).toBe('force-dynamic');
  });

  it('passes an unreachable-upstream payload through unchanged, still as a 200', async () => {
    fetchServiceStatus.mockResolvedValue(
      payload({ overall: 'unknown', dataAvailable: false, incidentsSource: 'unreachable' }),
    );

    const response = await GET();

    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.overall).toBe('unknown');
    expect(body.dataAvailable).toBe(false);
    expect(body.incidentsSource).toBe('unreachable');
  });

  it('is absent in CE, and does not even ask upstream', async () => {
    edition.IS_CE = true;

    const response = await GET();

    expect(response.status).toBe(404);
    expect(fetchServiceStatus).not.toHaveBeenCalled();
  });
});
