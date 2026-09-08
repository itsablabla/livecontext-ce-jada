/**
 * Wiring guard: the import ITSELF resolves the interface formats.
 *
 * The box lookup is worth nothing if the orchestration never calls it, and a unit test
 * on the creator alone (which is handed the formats) cannot see that. This drives the
 * real `createNodes` on an agent-built plan and asserts the node comes out at the shape
 * its page declares - the shape the automatic layout then reserves.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * A plain closure, not a `vi.fn()` spy: the failure path here has the API THROW, and a
 * spy that is cleared between tests reports a thrown call as an unhandled test error
 * even when the caller catches it (vitest 4). The recorded ids give the same assertions.
 */
const asked: string[] = [];
let answer: (id: string) => Promise<{ id: string; format?: string | null }> =
  async (id) => ({ id });

const runSnapshots: Array<{ interfaceId: string; format?: string | null }> = [];
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getInterface: (id: string) => {
      asked.push(id);
      return answer(id);
    },
    getInterfaceSnapshotsForRun: async () => runSnapshots,
  },
  apiClient: { get: vi.fn(), post: vi.fn() },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({ getActivePublicPreview: () => null }));

import { NodeCreationService } from '../NodeCreationService';
import { QueryClient } from '@tanstack/react-query';

const agentBuiltPlan = (iface: Record<string, unknown>) => ({
  triggers: [],
  mcps: [],
  edges: [],
  interfaces: [iface],
}) as any;

let context: { queryClient: QueryClient; isRunMode?: boolean };

beforeEach(() => {
  asked.length = 0;
  runSnapshots.length = 0;
  answer = async (id) => ({ id });
  // The caller hands its own client down; a fresh one per test keeps them isolated.
  context = { queryClient: new QueryClient({ defaultOptions: { queries: { retry: false } } }) };
});

describe('createNodes on an agent-built plan', () => {
  it('regression: gives the interface node the box its declared format paints', async () => {
    answer = async (id) => ({ id, format: 'a4_portrait' });

    const { nodes } = await NodeCreationService.createNodes(
      agentBuiltPlan({ id: 'iface-1', label: 'Invoice Preview' }), [], context,
    );

    expect(asked).toEqual(['iface-1']);
    expect(nodes[0].style).toMatchObject({ width: 283, height: 400 });
  });

  it('regression: re-checks a page the plan already carries a box for, in case the format moved', async () => {
    answer = async (id) => ({ id, format: 'a4_portrait' });

    const { nodes } = await NodeCreationService.createNodes(
      agentBuiltPlan({ id: 'iface-1', label: 'Saved Page', previewWidth: 225, previewHeight: 400 }),
      [], context,
    );

    expect(asked).toEqual(['iface-1']);
    expect(nodes[0].style).toMatchObject({ width: 283, height: 400 });
  });

  it('asks nothing for a published plan: the snapshot carries the format', async () => {
    const { nodes } = await NodeCreationService.createNodes(
      agentBuiltPlan({ id: 'iface-1', label: 'Showcase', _snapshot_format: 'a4_portrait' }),
      [], context,
    );

    expect(asked).toEqual([]);
    expect(nodes[0].style).toMatchObject({ width: 283, height: 400 });
  });

  it('regression: imports the plan anyway when the lookup fails', async () => {
    answer = async () => { throw new Error('HTTP 500'); };

    const { nodes } = await NodeCreationService.createNodes(
      agentBuiltPlan({ id: 'iface-1', label: 'Invoice Preview' }), [], context,
    );

    expect(nodes).toHaveLength(1);
    expect(nodes[0].style).toMatchObject({ width: 400, height: 250 });
  });

  it('regression: a canvas showing a run is laid out on the box THAT RUN froze', async () => {
    // The page is vertical today; this run painted it as A4 and still does, so the live
    // format would be both a new request and the wrong value.
    answer = async (id) => ({ id, format: 'vertical' });
    runSnapshots.push({ interfaceId: 'iface-1', format: 'a4_portrait' });

    const { nodes } = await NodeCreationService.createNodes(
      agentBuiltPlan({ id: 'iface-1', label: 'Invoice Preview' }),
      [], { ...context, isRunMode: true, workflowRunId: 'run-uuid-1' },
    );

    expect(asked).toEqual([]);
    expect(nodes[0].style).toMatchObject({ width: 283, height: 400 });
  });
});
