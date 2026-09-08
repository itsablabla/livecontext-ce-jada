// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: api }));

import { interfaceService } from '../interface.service';

/**
 * What the two snapshot reads actually PUT ON THE WIRE.
 *
 * Both used to send `runId`, while interface-service declares
 * `@RequestParam UUID workflowRunId` on both endpoints: they could only ever answer 400.
 * Nothing noticed, because neither had a caller - and nothing would have noticed after the
 * fix either. Every other test in this feature mocks `orchestratorApi`, and the browser
 * e2e cannot see it: the run-format lookup catches the 400 and falls through to the live
 * interface, which in that fixture declares the same format, so the canvas looks right.
 *
 * The parameter NAME is therefore the contract, and this is the layer where it is still
 * data rather than a URL.
 */
beforeEach(() => {
  vi.clearAllMocks();
  api.get.mockResolvedValue([]);
});

describe('the run a snapshot read asks about', () => {
  it('regression: names workflowRunId, which is the parameter the endpoint declares', async () => {
    await interfaceService.getInterfaceSnapshotsForRun('run-uuid-1');

    expect(api.get).toHaveBeenCalledWith('/interfaces/snapshots', {
      params: { workflowRunId: 'run-uuid-1' },
    });
  });

  it('regression: the single-interface read names it too', async () => {
    api.get.mockResolvedValue({ interfaceId: 'iface-1' });

    await interfaceService.getInterfaceSnapshot('iface-1', 'run-uuid-1');

    expect(api.get).toHaveBeenCalledWith('/interfaces/iface-1/snapshot', {
      params: { workflowRunId: 'run-uuid-1' },
    });
  });
});
