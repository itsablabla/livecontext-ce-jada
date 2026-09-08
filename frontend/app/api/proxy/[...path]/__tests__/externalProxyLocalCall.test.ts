// @vitest-environment node
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { NextRequest, NextResponse } from 'next/server';

/**
 * `/api/proxy/external-proxy` must run the local handler IN PROCESS.
 *
 * It used to re-fetch `${request.nextUrl.origin}/api/external-proxy`. In CE that works, because
 * Next serves every path itself. In cloud it cannot: the ingress routes `/api` to the GATEWAY
 * and `/api/external-proxy` has no carve-out, so the hop left the pod, came back as the
 * gateway's 404, and every external call from the MCP Test tab failed in production while
 * passing in CE, in e2e and in unit tests. Confirmed against production on 2026-09-05 (a POST
 * returns the Spring 404 body).
 *
 * Both halves matter, so both are asserted: the handler is invoked, AND no HTTP request is made
 * for it. A future "just carve the path out of the ingress" would satisfy the first and undo the
 * reason for the second, which is that the SSRF-guarded fetcher stays unreachable from outside.
 */

type ExternalProxyPost = (request: NextRequest) => Promise<Response>;

const externalProxyPost = vi.fn<ExternalProxyPost>(
  async () => NextResponse.json({ status: 200, data: { ok: true } }, { status: 200 }),
);

vi.mock('@/app/api/external-proxy/route', () => ({
  POST: (request: NextRequest) => externalProxyPost(request),
}));

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  externalProxyPost.mockClear();
  fetchMock = vi.fn(async () => new Response('{}', { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function params(path: string[]): { params: Promise<{ path: string[] }> } {
  return { params: Promise.resolve({ path }) };
}

function postRequest(body: string, headers: Record<string, string> = {}): NextRequest {
  return new NextRequest('https://livecontext.ai/api/proxy/external-proxy', {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body,
  });
}

describe('/api/proxy/external-proxy', () => {
  it('calls the local handler without any network hop', async () => {
    const { POST } = await import('../route');

    const response = await POST(
      postRequest(JSON.stringify({ url: 'https://example.com', method: 'GET' }), {
        authorization: 'Bearer token-abc',
      }),
      params(['external-proxy']),
    );

    expect(externalProxyPost).toHaveBeenCalledTimes(1);
    // The bug in one assertion: a request to the public origin is exactly what the gateway 404s.
    expect(fetchMock).not.toHaveBeenCalled();
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: 200, data: { ok: true } });
  });

  it('hands the handler the caller body, bearer and target path unchanged', async () => {
    const { POST } = await import('../route');
    const body = JSON.stringify({ url: 'https://example.com/x', method: 'POST', timeout: 5000 });

    await POST(
      postRequest(body, { authorization: 'Bearer token-abc', 'x-request-id': 'req-42' }),
      params(['external-proxy']),
    );

    const forwarded = externalProxyPost.mock.calls[0][0];
    expect(forwarded.method).toBe('POST');
    expect(new URL(forwarded.url).pathname).toBe('/api/external-proxy');
    expect(forwarded.headers.get('authorization')).toBe('Bearer token-abc');
    expect(forwarded.headers.get('x-request-id')).toBe('req-42');
    expect(await forwarded.text()).toBe(body);
  });

  it('passes the handler status through, so a rejected call still reads as rejected', async () => {
    externalProxyPost.mockResolvedValueOnce(
      NextResponse.json({ error: 'Unauthorized' }, { status: 401 }),
    );
    const { POST } = await import('../route');

    const response = await POST(postRequest('{}'), params(['external-proxy']));

    expect(response.status).toBe(401);
    expect(await response.json()).toEqual({ error: 'Unauthorized' });
  });

  it('answers 405 for a method the local handler does not export, instead of reaching the gateway', async () => {
    const { GET } = await import('../route');

    const response = await GET(
      new NextRequest('https://livecontext.ai/api/proxy/external-proxy'),
      params(['external-proxy']),
    );

    expect(response.status).toBe(405);
    expect(externalProxyPost).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('leaves every other path on the gateway path (the bypass is for external-proxy only)', async () => {
    const { GET } = await import('../route');

    await GET(
      new NextRequest('https://livecontext.ai/api/proxy/workflows'),
      params(['workflows']),
    );

    expect(externalProxyPost).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/workflows');
  });
});
