import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RATE_LIMIT_RETRY_ATTEMPTS, writeWithRateLimitRetry } from '../test-utils/writeWithRateLimitRetry';

/**
 * The retry helper used by every e2e write that is not safe to repeat.
 *
 * The distinction it encodes is the whole point: a 429 means the server refused to look at the
 * request, so re-sending is free; a transport error means the outcome is UNKNOWN, and re-sending
 * either duplicates the side effect or collides with the first attempt. Measured on the
 * step-by-step suite, the shared helper retried a write that had already SUCCEEDED (answered 200
 * in 201 ms, client timed out at 60 s), and the second attempt was correctly refused as already
 * completed. That refusal is what turned a slow-but-passing run red.
 */
describe('writeWithRateLimitRetry', () => {
  const waitForTimeout = vi.fn().mockResolvedValue(undefined);
  const page = { waitForTimeout } as never;

  const responseWith = (status: number, headers: Record<string, string> = {}) => ({
    status: () => status,
    headers: () => headers,
  }) as never;

  beforeEach(() => {
    waitForTimeout.mockClear();
  });

  it('returns a non-429 response without retrying', async () => {
    const factory = vi.fn().mockResolvedValue(responseWith(200));

    const response = await writeWithRateLimitRetry(page, factory);

    expect(response.status()).toBe(200);
    expect(factory).toHaveBeenCalledTimes(1);
  });

  it('retries a 429, because the server never looked at the request', async () => {
    const factory = vi.fn()
      .mockResolvedValueOnce(responseWith(429))
      .mockResolvedValueOnce(responseWith(201));

    const response = await writeWithRateLimitRetry(page, factory);

    expect(response.status()).toBe(201);
    expect(factory).toHaveBeenCalledTimes(2);
  });

  it('gives the last 429 back rather than throwing, so the caller asserts on the status', async () => {
    const factory = vi.fn().mockResolvedValue(responseWith(429));

    const response = await writeWithRateLimitRetry(page, factory);

    expect(response.status()).toBe(429);
    expect(factory).toHaveBeenCalledTimes(RATE_LIMIT_RETRY_ATTEMPTS);
    expect(waitForTimeout).toHaveBeenCalledTimes(RATE_LIMIT_RETRY_ATTEMPTS - 1);
  });

  it('honours Retry-After instead of its own backoff', async () => {
    const factory = vi.fn()
      .mockResolvedValueOnce(responseWith(429, { 'retry-after': '2' }))
      .mockResolvedValueOnce(responseWith(200));

    await writeWithRateLimitRetry(page, factory);

    expect(waitForTimeout).toHaveBeenCalledWith(2000);
  });

  it('backs off exponentially when the server gives no Retry-After', async () => {
    const factory = vi.fn()
      .mockResolvedValueOnce(responseWith(429))
      .mockResolvedValueOnce(responseWith(429))
      .mockResolvedValueOnce(responseWith(200));

    await writeWithRateLimitRetry(page, factory);

    expect(waitForTimeout.mock.calls.map(([ms]) => ms)).toEqual([1000, 2000]);
  });

  it('never re-sends after a transport error, and says why', async () => {
    // The regression this helper exists for. A blind retry here reports a state bug that the
    // server does not have.
    const timeout = new Error('apiRequestContext.post: Timeout 60000ms exceeded');
    timeout.name = 'TimeoutError';
    const factory = vi.fn().mockRejectedValue(timeout);

    await expect(writeWithRateLimitRetry(page, factory)).rejects.toThrow(
      /NOT retried because the server may already have applied it/,
    );
    expect(factory).toHaveBeenCalledTimes(1);
  });

  it('keeps the original error as `cause`, so the failing layer stays identifiable', async () => {
    const timeout = new Error('apiRequestContext.post: Timeout 60000ms exceeded');
    timeout.name = 'TimeoutError';
    const factory = vi.fn().mockRejectedValue(timeout);

    await writeWithRateLimitRetry(page, factory).then(
      () => { throw new Error('expected a rejection'); },
      (error: Error) => {
        expect(error.cause).toBe(timeout);
        expect((error.cause as Error).name).toBe('TimeoutError');
      },
    );
  });
});
