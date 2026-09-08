import { describe, expect, it } from 'vitest';
import { agendaErrorText } from '../agendaErrors';
import { ApiError } from '@/lib/api/api-client';

/**
 * Which sentence a refusal becomes.
 *
 * Every branch is a promise about what to do next, and a wrong one is invisible: the page
 * renders a confident, grammatical sentence either way, and only someone who knows what the
 * server actually refused can tell it is the wrong sentence. While these lived inside a
 * 500-line component behind a `t()` closure they could not be exercised at all, which is
 * how `SCHEDULE_REJECTED` reached review with a dedicated branch and no coverage.
 */

function refusal(reason: string, detail?: string, status = 422): unknown {
  // ApiError is (message, status, code, details) - the refusal lives in the FOURTH
  // argument. Passing it third makes it the error CODE, agendaFailureOf reads nothing,
  // and every case falls to the generic message.
  return new ApiError('HTTP 422: Unprocessable Entity', status, undefined, { reason, detail });
}

describe('agendaErrorText', () => {
  it('maps every refusal the server can name to its own sentence', () => {
    // Enumerated rather than spot-checked: a missing case does not throw, it silently
    // shows the generic message on a refusal the platform explained precisely.
    const cases: Array<[string, string]> = [
      ['PATTERN_NOT_SHIFTABLE', 'errors.patternNotShiftable'],
      ['WEEKDAY_SET_NOT_MATCHED', 'errors.weekdaySetNotMatched'],
      ['DAY_OF_MONTH_UNSAFE', 'errors.dayOfMonthUnsafe'],
      ['NOT_ARMED', 'errors.notArmed'],
      ['NOT_THE_NEXT_OCCURRENCE', 'errors.notTheNextOccurrence'],
      ['VIEWER_ROLE', 'errors.viewerRole'],
      ['NOT_FOUND', 'errors.notFound'],
      ['SCHEDULE_REJECTED', 'errors.scheduleRejected'],
    ];

    for (const [reason, key] of cases) {
      expect(agendaErrorText(refusal(reason)), reason).toEqual({ key });
    }
  });

  it('gives every reason a DISTINCT key', () => {
    // Two reasons sharing a sentence is how a user gets told to fix their cron when the
    // real problem was their role.
    const keys = [
      'PATTERN_NOT_SHIFTABLE', 'WEEKDAY_SET_NOT_MATCHED', 'DAY_OF_MONTH_UNSAFE',
      'NOT_ARMED', 'NOT_THE_NEXT_OCCURRENCE', 'VIEWER_ROLE', 'NOT_FOUND',
      'SCHEDULE_REJECTED',
    ].map((reason) => agendaErrorText(refusal(reason)).key);

    expect(new Set(keys).size).toBe(keys.length);
  });

  describe('EXECUTION_REFUSED', () => {
    it("prefers the provider's own words when it sent any", () => {
      // "Provider deepseek is not configured" tells the user what to change. Replacing it
      // with "the run was refused" throws away the only actionable part.
      expect(agendaErrorText(refusal('EXECUTION_REFUSED', 'Provider deepseek is not configured')))
        .toEqual({ key: null, detail: 'Provider deepseek is not configured' });
    });

    it('falls back to its own sentence when the server sent no detail', () => {
      // Without this the user would be shown an empty string.
      expect(agendaErrorText(refusal('EXECUTION_REFUSED')))
        .toEqual({ key: 'errors.executionRefused' });
    });
  });

  describe('anything else', () => {
    it('shows a server detail it does not recognise, rather than swallowing it', () => {
      // A reason added server-side before this file learns about it still says something
      // useful instead of degrading to "something went wrong".
      expect(agendaErrorText(refusal('SOME_NEW_REASON', 'the schedule is being migrated')))
        .toEqual({ key: null, detail: 'the schedule is being migrated' });
    });

    it('falls back to the generic sentence for an unrecognised reason with no detail', () => {
      expect(agendaErrorText(refusal('SOME_NEW_REASON'))).toEqual({ key: 'errors.generic' });
    });

    it('falls back to the generic sentence for a network failure', () => {
      // Not an ApiError at all: the request never reached the server, so there is no
      // reason to read. Reading `.message` here would print "Failed to fetch" at the user.
      expect(agendaErrorText(new TypeError('Failed to fetch')))
        .toEqual({ key: 'errors.generic' });
    });

    it('survives a thrown non-error', () => {
      expect(agendaErrorText(undefined)).toEqual({ key: 'errors.generic' });
      expect(agendaErrorText('boom')).toEqual({ key: 'errors.generic' });
    });
  });
});
