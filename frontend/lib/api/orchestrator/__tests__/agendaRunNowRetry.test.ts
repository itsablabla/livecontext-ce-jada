/**
 * @vitest-environment jsdom
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { agendaService } from '../agenda.service';
import { apiClient } from '../../api-client';

/**
 * Running a schedule early must never be retried.
 *
 * It is the one non-idempotent call in the agenda: it starts a run that spends credits and
 * has real side effects, and - unlike the cron daemon's path - it carries no deterministic
 * request id to deduplicate on. `apiClient` retries a 5xx or a network failure by default,
 * and both can arrive AFTER the orchestrator has already started the run (a pod recycling,
 * a proxy timing out while the execution queue blocks). The default therefore turned one
 * click into two runs and still reported success.
 *
 * The guard is a single `{ retries: 0 }` argument, which is exactly the kind of thing that
 * gets dropped by a later refactor with no visible symptom until a user is billed twice.
 * These tests count the requests that actually leave.
 */
describe('agendaService.runNow retry policy', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    apiClient.setTokenProvider(async () => 'test-token');
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  const serverError = () =>
    new Response(JSON.stringify({ error: 'boom' }), {
      status: 500,
      headers: { 'Content-Type': 'application/json' },
    });

  it('sends exactly ONE request when the server answers 500', async () => {
    // A 5xx is the dangerous case: the run may already have started server-side, so a
    // second attempt is a second run.
    fetchMock.mockResolvedValue(serverError());

    await expect(agendaService.runNow('sched-1', true)).rejects.toThrow();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('sends exactly ONE request when the connection fails', async () => {
    // Identical exposure: the request may have been delivered and executed before the
    // socket died, so the client cannot know whether retrying duplicates the run.
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(agendaService.runNow('sched-1', true)).rejects.toThrow();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('still retries a MOVE, which is idempotent', async () => {
    // The contrast is the point: writing the same fire time twice changes nothing, so a
    // move keeps the client's default resilience. Opting everything out would be a
    // different, quieter mistake.
    fetchMock.mockResolvedValue(serverError());

    await expect(agendaService.move('sched-1', new Date('2026-09-03T14:30:00Z'), 'NEXT'))
      .rejects.toThrow();

    expect(fetchMock.mock.calls.length).toBeGreaterThan(1);
  });

  it('posts run-now to the schedule it was given, with the keep flag', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ success: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    await agendaService.runNow('sched-42', false);

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/agenda/schedules/sched-42/run-now');
    expect(JSON.parse(String(init.body))).toEqual({ keepNextOccurrence: false });
  });
});
