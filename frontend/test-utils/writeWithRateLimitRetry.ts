/** Shared test helper exported independently of the private E2E fixture tree. */
export const RATE_LIMIT_RETRY_ATTEMPTS = 3;

type Response = { status(): number; headers(): Record<string, string> };
type Clock = { waitForTimeout(milliseconds: number): Promise<void> };

export async function writeWithRateLimitRetry<T extends Response>(
  page: Clock,
  factory: () => Promise<T>
): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    let response: T;
    try {
      response = await factory();
    } catch (cause) {
      throw new Error('Write NOT retried because the server may already have applied it', { cause });
    }
    if (response.status() !== 429 || attempt === RATE_LIMIT_RETRY_ATTEMPTS - 1) {
      return response;
    }
    const header = response.headers()['retry-after'];
    const seconds = header === undefined ? NaN : Number(header);
    const dateDelay = header === undefined ? NaN : Date.parse(header) - Date.now();
    const delay = Number.isFinite(seconds) && seconds >= 0
      ? seconds * 1000
      : Number.isFinite(dateDelay)
        ? Math.max(0, dateDelay)
        : 1000 * 2 ** attempt;
    await page.waitForTimeout(delay);
  }
}
