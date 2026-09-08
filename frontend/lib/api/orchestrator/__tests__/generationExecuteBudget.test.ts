import { describe, expect, it, vi, beforeEach } from 'vitest';

/**
 * The third caller of a generation, and the one the budget rule forgot.
 *
 * <p>The MCP tool and the `agent:generate` node were both sized on how long
 * catalog can legitimately take (a submit that can burn the upstream read
 * ceiling, then a poll loop). The browser was left on the API client's default
 * 30 seconds, which is shorter than the operation by more than an order of
 * magnitude.
 *
 * <p>Giving up does NOT cancel anything: the server finishes the generation,
 * stores the asset and COMMITS the charge. So the default budget turned a
 * paid, delivered generation into "Request timeout" on screen. This pins the
 * one thing the client can control, that it does not abandon the request.
 */

const mocks = vi.hoisted(() => ({ post: vi.fn() }));

vi.mock('../../api-client', () => ({
  apiClient: { post: mocks.post, get: vi.fn() },
}));

import { generationService } from '../generation.service';

beforeEach(() => {
  // Cleared, not just re-stubbed: reading calls[0] across tests otherwise
  // asserts against the previous test's request.
  vi.clearAllMocks();
  mocks.post.mockResolvedValue({ success: true, data: {} });
});

describe('generationService.execute budget', () => {
  it('does not abandon the request, because abandoning it does not stop the charge', async () => {
    await generationService.execute({ model: 'seedance-2.0', params: { prompt: 'a boat' } });

    const [, , options] = mocks.post.mock.calls[0];
    expect(options).toBeDefined();
    // 0 is "no timeout" in this client. Anything else is a number smaller than
    // the operation, and the client picking it decides nothing except whether
    // the user is told the truth.
    expect(options.timeout).toBe(0);
  });

  it('never retries, because a generation that did not answer is still running and would be paid for twice', async () => {
    // The client retries a 5xx by default, and an edge proxy that gives up on a
    // long generation answers 502/504/524. That is not a request to replay: the
    // first submission is still running upstream, so a retry starts a SECOND
    // generation and the customer pays for two. The 30-second abort used to
    // hide this by turning the same event into an AbortError, which is not
    // retried, so removing the timeout WITHOUT this is worse than leaving both.
    await generationService.execute({ model: 'seedance-2.0', params: { prompt: 'a boat' } });

    const [, , options] = mocks.post.mock.calls[0];
    expect(options.retries).toBe(0);
  });

  it('still sends the model, the params and the payer unchanged', async () => {
    // The budget is the only thing this test is about; it must not have
    // quietly altered the request while fixing it.
    await generationService.execute({
      model: 'seedance-2.0',
      params: { prompt: 'a boat', duration_seconds: 10 },
      credential_source: 'user',
    });

    const [path, body] = mocks.post.mock.calls[0];
    expect(path).toBe('/generation/execute');
    expect(body).toEqual({
      model: 'seedance-2.0',
      params: { prompt: 'a boat', duration_seconds: 10 },
      credential_source: 'user',
    });
  });
});
