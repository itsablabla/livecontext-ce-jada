/**
 * The rerun request has to CARRY the epoch, and only when one was chosen.
 *
 * The backend reads `?epoch=N` and treats a named epoch as a different resolution branch:
 * omitted, it replays the run's most recent fire; named, it reopens the one asked for and runs
 * the checks that go with reopening. So dropping the parameter here does not fail, it replays
 * the WRONG fire - the canvas would confirm a restart of epoch 1 and redo epoch 9, reporting
 * success. That silence is why this is pinned at the wire.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ExecutionService } from '../execution.service';
import { apiClient } from '../../api-client';

vi.mock('../../api-client', () => ({
  apiClient: { post: vi.fn(async () => ({})) },
}));

const mockedPost = vi.mocked(apiClient.post);
const urlOf = () => mockedPost.mock.calls[0][0];

describe('ExecutionService.rerunFromStep - epoch', () => {
  let service: ExecutionService;

  beforeEach(() => {
    service = new ExecutionService();
    vi.clearAllMocks();
  });

  it('names the epoch when the caller chose one', async () => {
    await service.rerunFromStep('run_1', 'mcp:step_a', undefined, 2);
    expect(urlOf()).toBe('/v2/workflows/dag/runs/run_1/rerun/mcp%3Astep_a?epoch=2');
  });

  it('names epoch 0 - the run FIRST fire is a real epoch, not a missing one', async () => {
    // A plain falsy check on the epoch would drop this one and replay the newest instead.
    await service.rerunFromStep('run_1', 'mcp:step_a', undefined, 0);
    expect(urlOf()).toBe('/v2/workflows/dag/runs/run_1/rerun/mcp%3Astep_a?epoch=0');
  });

  it('sends no epoch when the caller chose none, which is the pre-existing default path', async () => {
    await service.rerunFromStep('run_1', 'mcp:step_a');
    expect(urlOf()).toBe('/v2/workflows/dag/runs/run_1/rerun/mcp%3Astep_a');
  });

  it('keeps the plan body independent of the epoch', async () => {
    // The two are set by different callers; a plan refresh must not start depending on which
    // epoch is being replayed, nor the other way round.
    const plan = { nodes: [] };
    await service.rerunFromStep('run_1', 'mcp:step_a', plan, 3);
    expect(urlOf()).toContain('?epoch=3');
    expect(mockedPost.mock.calls[0][1]).toEqual({ plan });
  });
});
