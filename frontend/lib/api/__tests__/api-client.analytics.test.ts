import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const analyticsMock = vi.hoisted(() => ({ track: vi.fn() }));
vi.mock('@/lib/analytics/analytics', () => analyticsMock);

import { ApiClient, ApiError } from '../api-client';

// ---------------------------------------------------------------------------
// Helpers (same response shapes as api-client.test.ts)
// ---------------------------------------------------------------------------

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : `HTTP ${status}`,
    headers: new Headers({ 'content-type': 'application/json' }),
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response;
}

function errorJsonResponse(status: number, body: Record<string, unknown> = {}) {
  return jsonResponse(body, status);
}

const WORKFLOW_ID = '3f2504e0-4f89-11d3-9a0c-0305e82c3301';

// normalizeApiPath itself is pinned in normalizeApiPath.test.ts; this file
// covers the wiring that feeds it (when the event fires and with what).
describe('ApiClient api_request_failed analytics', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  function makeClient(retries = 1) {
    const client = new ApiClient({ baseUrl: '/api/proxy', timeout: 5000, retries });
    client.setTokenProvider(vi.fn().mockResolvedValue('tok'));
    return client;
  }

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    analyticsMock.track.mockReset();
    fetchMock = vi.fn();
    global.fetch = fetchMock as unknown as typeof fetch;
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('counts a terminal 404 once, with status, method, the server error_code and a normalized id-free path, never the message', async () => {
    const client = makeClient(3);
    fetchMock.mockResolvedValueOnce(
      errorJsonResponse(404, { message: 'Workflow not found: super secret name', code: 'WORKFLOW_NOT_FOUND' }),
    );

    await expect(client.get(`/workflows/${WORKFLOW_ID}/runs/42?page=1`)).rejects.toMatchObject({ status: 404 });

    expect(analyticsMock.track).toHaveBeenCalledTimes(1);
    expect(analyticsMock.track).toHaveBeenCalledWith('api_request_failed', {
      status: 404,
      error_code: 'WORKFLOW_NOT_FOUND',
      method: 'GET',
      path: '/workflows/:id/runs/:id',
    });
    const [, props] = analyticsMock.track.mock.calls[0];
    expect(JSON.stringify(props)).not.toContain('super secret name');
    expect(JSON.stringify(props)).not.toContain(WORKFLOW_ID);
  });

  it('reports the derived HTTP_<status> code when the server body carries no code', async () => {
    const client = makeClient();
    fetchMock.mockResolvedValueOnce(errorJsonResponse(403, { message: 'Forbidden' }));

    await expect(client.delete('/items/9')).rejects.toBeInstanceOf(ApiError);

    expect(analyticsMock.track).toHaveBeenCalledWith(
      'api_request_failed',
      expect.objectContaining({ status: 403, error_code: 'HTTP_403', method: 'DELETE', path: '/items/:id' }),
    );
  });

  it('counts a failure only once after every retry is exhausted', async () => {
    const client = makeClient(1);
    fetchMock
      .mockResolvedValueOnce(errorJsonResponse(503, { message: 'down' }))
      .mockResolvedValueOnce(errorJsonResponse(503, { message: 'down' }));

    const promise = client.get('/health');
    const assertion = expect(promise).rejects.toMatchObject({ status: 503 });
    await vi.advanceTimersByTimeAsync(1000);
    await assertion;

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(analyticsMock.track).toHaveBeenCalledTimes(1);
    expect(analyticsMock.track).toHaveBeenCalledWith(
      'api_request_failed',
      expect.objectContaining({ status: 503, method: 'GET', path: '/health' }),
    );
  });

  it('emits nothing when a request fails and then succeeds on retry', async () => {
    const client = makeClient(1);
    fetchMock
      .mockResolvedValueOnce(errorJsonResponse(503, { message: 'down' }))
      .mockResolvedValueOnce(jsonResponse({ ok: true }));

    const promise = client.get('/health');
    await vi.advanceTimersByTimeAsync(1000);

    await expect(promise).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(analyticsMock.track).not.toHaveBeenCalled();
  });

  it('emits nothing for a non-ApiError failure such as a network TypeError', async () => {
    const client = makeClient();
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await expect(client.get('/unstable', { retries: 0 })).rejects.toThrow(TypeError);

    expect(analyticsMock.track).not.toHaveBeenCalled();
  });

  it('emits nothing on a successful request', async () => {
    const client = makeClient();
    fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }));

    await client.post('/items', { name: 'x' });

    expect(analyticsMock.track).not.toHaveBeenCalled();
  });

  it('still rethrows the original error unchanged when track itself throws', async () => {
    const client = makeClient();
    analyticsMock.track.mockImplementation(() => {
      throw new Error('posthog exploded');
    });
    fetchMock.mockResolvedValueOnce(errorJsonResponse(404, { message: 'Not found', code: 'NOT_FOUND' }));

    let caught: unknown;
    try {
      await client.get('/items/5');
      expect.unreachable('request must reject');
    } catch (err) {
      caught = err;
    }

    expect(analyticsMock.track).toHaveBeenCalledTimes(1);
    expect(caught).toBeInstanceOf(ApiError);
    const apiErr = caught as ApiError;
    expect(apiErr.status).toBe(404);
    expect(apiErr.code).toBe('NOT_FOUND');
    expect(apiErr.message).toBe('Not found');
  });
});
