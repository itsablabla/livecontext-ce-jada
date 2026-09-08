import { describe, expect, it } from 'vitest';
import { agendaFailureOf } from '../agenda.service';
import { ApiError } from '../../api-client';

/**
 * Reading a refusal off the thrown error.
 *
 * This function exists because of a shipped defect: `apiClient` throws `ApiError` for any
 * non-2xx, so the agenda's `if (!response.success)` branches were dead and every refusal
 * reached the user as "HTTP 422: Unprocessable Entity". The server had taken the trouble
 * to say which cron shape it could not move and what to do instead; the page showed a
 * status code. Six reasons x six locales of translated sentences were unreachable.
 *
 * The tests below are deliberately about the SHAPE of what arrives, because that shape is
 * owned by `api-client.ts` and not by the agenda: it is the seam where the last bug lived.
 */
describe('agendaFailureOf', () => {
  it('reads the reason and detail off an ApiError, which is how a refusal actually arrives', () => {
    // Exactly what apiClient constructs: the parsed error body becomes `details`.
    const error = new ApiError(
      'HTTP 422: Unprocessable Entity',
      422,
      'HTTP_422',
      { success: false, reason: 'PATTERN_NOT_SHIFTABLE', detail: '*/15 * * * *' },
    );

    expect(agendaFailureOf(error)).toEqual({
      reason: 'PATTERN_NOT_SHIFTABLE',
      detail: '*/15 * * * *',
    });
  });

  it('reads a reason that carries no detail', () => {
    // NOT_ARMED, NOT_FOUND and VIEWER_ROLE have nothing to add beyond the reason itself;
    // a caller falling back on `detail` alone would show them all as a generic failure.
    const error = new ApiError('HTTP 409', 409, 'HTTP_409', { success: false, reason: 'NOT_ARMED' });

    expect(agendaFailureOf(error).reason).toBe('NOT_ARMED');
    expect(agendaFailureOf(error).detail).toBeUndefined();
  });

  it('returns nothing usable for an error that is not a refusal', () => {
    // A network failure, a thrown string, null: the caller must fall back to its generic
    // message rather than render `undefined`.
    expect(agendaFailureOf(new TypeError('Failed to fetch'))).toEqual({});
    expect(agendaFailureOf(new ApiError('HTTP 500', 500, 'HTTP_500', undefined))).toEqual({});
    expect(agendaFailureOf('boom')).toEqual({});
    expect(agendaFailureOf(null)).toEqual({});
    expect(agendaFailureOf(undefined)).toEqual({});
  });

  it('does not mistake a non-object details payload for a refusal', () => {
    // Some upstreams answer text/plain; `details` is then a string and reading `.reason`
    // off it yields undefined rather than throwing, but the guard makes that explicit.
    expect(agendaFailureOf(new ApiError('nope', 502, 'HTTP_502', 'gateway down'))).toEqual({});
  });
});
