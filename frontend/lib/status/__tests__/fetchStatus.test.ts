/**
 * Tests for the server-side assembly of the status payload.
 *
 * Two independent upstreams, four combinations, and the same rule in all of them:
 * whatever answered is reported, whatever did not is declared missing, and the
 * headline never rounds a gap up to "operational". Also pinned: the probe URL may
 * be given as a base or as a full file path (the unit exposes /status.json), and
 * an upstream that returns a 500 or non-JSON is treated as absent rather than
 * throwing into the page render.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { fetchServiceStatus, resetStatusCacheForTests, statusPageUrl } from '../fetchStatus';

const PROBE_BODY = {
  generatedAt: '2026-09-05T10:00:00Z',
  windowDays: 90,
  components: [
    { id: 'api', name: 'API', state: 'operational', history: [{ date: '2026-09-05', ok: 10, total: 10 }] },
  ],
};

const WIDGET_BODY = {
  ongoing_incidents: [{ id: 'i1', name: 'Chat is slow', status: 'investigating' }],
  in_progress_maintenances: [],
  scheduled_maintenances: [],
};

/** Route each URL to a canned response; anything unrouted rejects like a dead host. */
function stubFetch(routes: Record<string, unknown | Error>) {
  // The options argument is declared so a case can assert on the request itself
  // (cache policy, abort signal), not only on the URL.
  return vi.fn(async (url: string, _options?: RequestInit) => {
    for (const [fragment, value] of Object.entries(routes)) {
      if (url.includes(fragment)) {
        if (value instanceof Error) throw value;
        return { ok: true, json: async () => value } as unknown as Response;
      }
    }
    throw new Error(`unreachable: ${url}`);
  });
}

const ORIGINAL_ENV = { ...process.env };

beforeEach(() => {
  resetStatusCacheForTests();
  process.env.STATUS_DATA_URL = 'http://probe.internal.test:9109';
  process.env.STATUS_WIDGET_API_URL = 'https://status.example.com/widget';
  process.env.STATUS_PAGE_URL = 'https://status.example.com';
});

afterEach(() => {
  process.env = { ...ORIGINAL_ENV };
  vi.unstubAllGlobals();
});

describe('fetchServiceStatus', () => {
  it('merges both sources and floors the headline at degraded while an incident runs', async () => {
    vi.stubGlobal('fetch', stubFetch({ '9109': PROBE_BODY, '/widget': WIDGET_BODY }));

    const status = await fetchServiceStatus();

    expect(status.dataAvailable).toBe(true);
    expect(status.incidentsSource).toBe('ok');
    expect(status.components.map((component) => component.id)).toEqual(['api']);
    expect(status.incidents.map((entry) => entry.id)).toEqual(['i1']);
    expect(status.windowDays).toBe(90);
    expect(status.generatedAt).toBe('2026-09-05T10:00:00Z');
    expect(status.statusPageUrl).toBe('https://status.example.com');
    expect(status.overall).toBe('degraded');
  });

  it('opts out of the framework data cache, and bounds each attempt', async () => {
    // Both matter and both were learned the hard way: with the data cache on, a
    // stale SUCCESS was served after the upstream started 404ing (failures are
    // not cached there, so the old entry stays) and the page kept reporting a
    // healthy feed. The abort signal keeps one hung upstream from holding a
    // render open. The memo in this module is the only cache.
    const fetchMock = stubFetch({ '9109': PROBE_BODY, '/widget': WIDGET_BODY });
    vi.stubGlobal('fetch', fetchMock);

    await fetchServiceStatus();

    const options = fetchMock.mock.calls[0][1] as RequestInit & { next?: unknown };
    expect(options.cache).toBe('no-store');
    expect(options.next).toBeUndefined();
    expect(options.signal).toBeInstanceOf(AbortSignal);
  });

  it('appends status.json when given a base URL, and leaves a full path alone', async () => {
    const fetchMock = stubFetch({ 'status.json': PROBE_BODY, '/widget': WIDGET_BODY });
    vi.stubGlobal('fetch', fetchMock);
    await fetchServiceStatus();
    expect(fetchMock.mock.calls[0][0]).toBe('http://probe.internal.test:9109/status.json');

    fetchMock.mockClear();
    process.env.STATUS_DATA_URL = 'http://probe.internal.test:9109/custom.json';
    await fetchServiceStatus();
    expect(fetchMock.mock.calls[0][0]).toBe('http://probe.internal.test:9109/custom.json');
  });

  it('keeps the incidents when the probe is unreachable', async () => {
    vi.stubGlobal('fetch', stubFetch({ '/widget': WIDGET_BODY }));

    const status = await fetchServiceStatus();

    expect(status.dataAvailable).toBe(false);
    expect(status.components).toEqual([]);
    expect(status.windowDays).toBe(0);
    expect(status.sampleIntervalSeconds).toBeNull();
    expect(status.incidents).toHaveLength(1);
    expect(status.overall).toBe('degraded');
  });

  it('marks a CONFIGURED but unreachable incident feed, and drops the all-clear', async () => {
    vi.stubGlobal('fetch', stubFetch({ '9109': PROBE_BODY }));

    const status = await fetchServiceStatus();

    expect(status.incidentsSource).toBe('unreachable');
    expect(status.incidents).toEqual([]);
    expect(status.components).toHaveLength(1);
    // Green probes, but no view of declared incidents: the page must not claim
    // everything is fine on evidence it does not have.
    expect(status.overall).toBe('unknown');
  });

  it('does not treat an UNCONFIGURED feed as a blind spot', async () => {
    // The launch configuration: probes only. Reporting "unknown" here would make
    // the page useless from day one, and nothing actually failed to answer.
    delete process.env.STATUS_WIDGET_API_URL;
    vi.stubGlobal('fetch', stubFetch({ '9109': PROBE_BODY }));

    const status = await fetchServiceStatus();

    expect(status.incidentsSource).toBe('not_configured');
    expect(status.overall).toBe('operational');
  });

  it('reports unknown when both upstreams are down', async () => {
    vi.stubGlobal('fetch', stubFetch({}));

    const status = await fetchServiceStatus();

    expect(status.dataAvailable).toBe(false);
    expect(status.incidentsSource).toBe('unreachable');
    expect(status.overall).toBe('unknown');
  });

  it('treats a non-200 or non-JSON answer as absent, not as a crash', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url.includes('9109')) return { ok: false, status: 502, json: async () => ({}) } as unknown as Response;
        return {
          ok: true,
          json: async () => {
            throw new Error('not json');
          },
        } as unknown as Response;
      }),
    );

    const status = await fetchServiceStatus();

    expect(status.dataAvailable).toBe(false);
    expect(status.incidentsSource).toBe('unreachable');
    expect(status.overall).toBe('unknown');
  });

  it('does not call an upstream that is not configured', async () => {
    delete process.env.STATUS_WIDGET_API_URL;
    const fetchMock = stubFetch({ '9109': PROBE_BODY });
    vi.stubGlobal('fetch', fetchMock);

    const status = await fetchServiceStatus();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(status.incidentsSource).toBe('not_configured');
  });
});

describe('upstream memoisation', () => {
  it('collapses SIMULTANEOUS viewers into one call per source', async () => {
    // Concurrent, not sequential: this is the real shape of a poll from N tabs,
    // and a cache written only after the await lets every one of them through,
    // which is the burst incident.io asks callers not to send.
    const fetchMock = stubFetch({ '9109': PROBE_BODY, '/widget': WIDGET_BODY });
    vi.stubGlobal('fetch', fetchMock);

    await Promise.all([fetchServiceStatus(), fetchServiceStatus(), fetchServiceStatus()]);

    expect(fetchMock).toHaveBeenCalledTimes(2); // two sources, one call each
  });

  it('serves a later call from the memo without going upstream again', async () => {
    const fetchMock = stubFetch({ '9109': PROBE_BODY, '/widget': WIDGET_BODY });
    vi.stubGlobal('fetch', fetchMock);

    await fetchServiceStatus();
    await fetchServiceStatus();

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('memoises a FAILING upstream too, so an outage cannot become a retry storm', async () => {
    const fetchMock = vi.fn(async () => {
      throw new Error('connection refused');
    });
    vi.stubGlobal('fetch', fetchMock);

    await Promise.all([fetchServiceStatus(), fetchServiceStatus()]);
    await fetchServiceStatus();

    expect(fetchMock).toHaveBeenCalledTimes(2); // one per source, not per call
  });

  it('shares one cache between separately bundled callers', async () => {
    // The page (server component) and the route handler are bundled into
    // separate module registries, so a module-level Map gives each its own copy
    // and lets the two surfaces disagree about whether the platform is up. A
    // second module instance must therefore hit the SAME cache.
    const fetchMock = stubFetch({ '9109': PROBE_BODY, '/widget': WIDGET_BODY });
    vi.stubGlobal('fetch', fetchMock);
    await fetchServiceStatus();

    // Non-literal specifier: a literal one is resolved at compile time and the
    // query string makes it an unknown module. Vitest resolves it at runtime to a
    // FRESH instance of the same file, which is exactly the situation being tested.
    const secondPath = '../fetchStatus?instance=2';
    const secondInstance = (await import(secondPath)) as typeof import('../fetchStatus');
    const status = await secondInstance.fetchServiceStatus();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(status.overall).toBe('degraded');
  });

  it('goes back upstream once the window has passed', async () => {
    const fetchMock = stubFetch({ '9109': PROBE_BODY, '/widget': WIDGET_BODY });
    vi.stubGlobal('fetch', fetchMock);
    const clock = vi.spyOn(Date, 'now');

    clock.mockReturnValue(1_000_000);
    await fetchServiceStatus(30);
    clock.mockReturnValue(1_000_000 + 29_000);
    await fetchServiceStatus(30);
    expect(fetchMock).toHaveBeenCalledTimes(2);

    clock.mockReturnValue(1_000_000 + 31_000);
    await fetchServiceStatus(30);
    expect(fetchMock).toHaveBeenCalledTimes(4);

    clock.mockRestore();
  });
});

describe('statusPageUrl', () => {
  it('is null when unset or blank, so nothing links to an empty href', () => {
    process.env.STATUS_PAGE_URL = '   ';
    expect(statusPageUrl()).toBeNull();
    delete process.env.STATUS_PAGE_URL;
    expect(statusPageUrl()).toBeNull();
  });
});
