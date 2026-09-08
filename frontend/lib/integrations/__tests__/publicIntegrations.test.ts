import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// `server-only` throws outside a React Server Component; stub it for the unit
// test, exactly as the marketplace reader's suite does.
vi.mock('server-only', () => ({}));

import {
  fetchAllIntegrations,
  fetchIntegration,
  fetchIntegrations,
  fetchTopIntegrations,
  PUBLIC_INTEGRATIONS_REVALIDATE_SECONDS,
} from '../publicIntegrations';

const originalFetch = global.fetch;

function row(slug: string) {
  return {
    slug,
    name: slug,
    description: `What ${slug} does.`,
    iconSlug: slug,
    iconUrl: null,
    toolCount: 5,
    authType: 'bearer_token',
  };
}

function page(slugs: string[], totalElements = slugs.length) {
  return { content: slugs.map(row), totalElements };
}

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  global.fetch = fetchMock as unknown as typeof fetch;
});

afterEach(() => {
  global.fetch = originalFetch;
  vi.restoreAllMocks();
});

/** The path + query of the nth gateway call, without the host. */
function requestedPath(callIndex = 0): string {
  const url = fetchMock.mock.calls[callIndex][0] as string;
  return url.slice(url.indexOf('/api/'));
}

describe('fetchIntegrations', () => {
  it('reads the public endpoint with paging, and no credentials', async () => {
    fetchMock.mockResolvedValue(jsonResponse(page(['slack', 'github'], 731)));

    const result = await fetchIntegrations({ page: 2, size: 24 });

    expect(requestedPath()).toBe('/api/public/integrations?page=2&size=24');
    // The endpoint is anonymous by design; attaching a token here would send a
    // user's credentials on a render that has no user.
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.headers).toEqual({ Accept: 'application/json' });
    expect((init as { credentials?: string }).credentials).toBeUndefined();
    expect(result.integrations.map((i) => i.slug)).toEqual(['slack', 'github']);
    expect(result.totalElements).toBe(731);
  });

  it('sends a trimmed search term and omits it when blank', async () => {
    fetchMock.mockResolvedValue(jsonResponse(page([])));

    await fetchIntegrations({ query: '  slack  ' });
    expect(requestedPath()).toContain('q=slack');

    await fetchIntegrations({ query: '   ' });
    expect(requestedPath(1)).not.toContain('q=');
  });

  it('degrades to an empty page when the gateway answers an error', async () => {
    fetchMock.mockResolvedValue(jsonResponse(null, 503));

    const result = await fetchIntegrations();

    // A landing section or a footer column must lose itself, never the page it
    // sits on.
    expect(result).toEqual({ integrations: [], totalElements: 0, truncated: true });
  });

  it('degrades to an empty page when the gateway is unreachable', async () => {
    fetchMock.mockRejectedValue(new Error('ECONNREFUSED'));

    await expect(fetchIntegrations()).resolves.toEqual({
      integrations: [],
      totalElements: 0,
      truncated: true,
    });
  });

  it('bounds the read so a stalled gateway cannot hang the render', async () => {
    fetchMock.mockResolvedValue(jsonResponse(page([])));

    await fetchIntegrations();

    // What this bounds is the case the try/catch cannot see: a gateway that
    // accepts the connection and then never answers.
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });
});

describe('fetchTopIntegrations', () => {
  it('asks for one page of the requested size', async () => {
    fetchMock.mockResolvedValue(jsonResponse(page(['slack'])));

    await fetchTopIntegrations(8);

    expect(requestedPath()).toBe('/api/public/integrations?page=0&size=8');
  });

  it('defaults to the module cache window rather than a number written twice', async () => {
    fetchMock.mockResolvedValue(jsonResponse(page(['slack'])));

    await fetchTopIntegrations(8);

    const init = fetchMock.mock.calls[0][1] as { next?: { revalidate?: number } };
    expect(init.next?.revalidate).toBe(PUBLIC_INTEGRATIONS_REVALIDATE_SECONDS);
  });

  it('passes the caller its own cache window', async () => {
    fetchMock.mockResolvedValue(jsonResponse(page(['slack'])));

    await fetchTopIntegrations(8, 600);

    // Next takes the SHORTEST revalidate among a route's fetches and never
    // raises it, so the landing has to pass its own 600 explicitly or the page
    // would claim ten minutes of freshness while serving an hour of staleness.
    const init = fetchMock.mock.calls[0][1] as { next?: { revalidate?: number } };
    expect(init.next?.revalidate).toBe(600);
  });
});

describe('fetchAllIntegrations', () => {
  it('walks every page until one comes back short', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(page(Array.from({ length: 200 }, (_, i) => `a${i}`), 250)))
      .mockResolvedValueOnce(jsonResponse(page(Array.from({ length: 50 }, (_, i) => `b${i}`), 250)));

    const result = await fetchAllIntegrations();

    expect(result.integrations).toHaveLength(250);
    expect(result.totalElements).toBe(250);
    expect(result.truncated).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('reports truncation instead of silently serving half the catalog', async () => {
    // A directory that quietly drops half the catalog looks healthy while hiding
    // pages from search engines. The caller warns on this flag.
    fetchMock.mockResolvedValue(jsonResponse(page(Array.from({ length: 5 }, (_, i) => `a${i}`), 99)));

    const result = await fetchAllIntegrations({ pageSize: 5, maxPages: 3 });

    expect(result.integrations).toHaveLength(15);
    expect(result.truncated).toBe(true);
  });

  it('ends the walk on a failed page rather than leaving a hole in the middle', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(page(['a', 'b'], 6)))
      .mockResolvedValueOnce(jsonResponse(null, 500));

    const result = await fetchAllIntegrations({ pageSize: 2 });

    expect(result.integrations.map((i) => i.slug)).toEqual(['a', 'b']);
    expect(result.truncated).toBe(true);
  });
});

describe('fetchIntegration', () => {
  it('reads one integration by slug', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      integration: row('slack'),
      documentation: null,
      tools: [{ name: 'send_message', description: 'Post', method: 'POST' }],
      toolsTruncated: false,
    }));

    const detail = await fetchIntegration('slack');

    expect(requestedPath()).toBe('/api/public/integrations/slack');
    expect(detail?.integration.slug).toBe('slack');
    expect(detail?.tools).toHaveLength(1);
  });

  it('returns null for a malformed slug WITHOUT calling the gateway', async () => {
    expect(await fetchIntegration('../../secret')).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('returns null on 404, so the page can 404 too', async () => {
    fetchMock.mockResolvedValue(jsonResponse(null, 404));

    expect(await fetchIntegration('nope')).toBeNull();
  });

  it('THROWS when the catalog cannot be read, rather than reporting a 404', async () => {
    fetchMock.mockResolvedValue(jsonResponse(null, 500));

    // This asymmetry is the point: a 404 tells a crawler the page is gone and
    // invites it to drop the URL, so a gateway blip has to surface as a 500 the
    // crawler retries, not as a 404 that quietly deindexes an integration.
    await expect(fetchIntegration('slack')).rejects.toThrow(/slack/);
  });

  it('THROWS when the gateway is unreachable', async () => {
    fetchMock.mockRejectedValue(new Error('ECONNREFUSED'));

    await expect(fetchIntegration('slack')).rejects.toThrow(/slack/);
  });
});
