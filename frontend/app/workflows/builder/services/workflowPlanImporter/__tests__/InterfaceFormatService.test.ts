/**
 * The lookup that tells the import what shape each interface node will paint.
 *
 * Two things must hold. It refines a layout, so it can never break an import: an
 * unreachable page, or a surface that cannot call the endpoint at all, has to leave the
 * plan importing exactly as it did before. And it must not add traffic: the canvas
 * re-imports the whole plan on EVERY edit an agent makes, and the interface node fetches
 * that same entity for itself, so the two have to share one cache entry.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { QueryClient } from '@tanstack/react-query';

/**
 * Plain closures rather than `vi.fn()` spies: one path here has the API throw, and a
 * spy that is cleared between tests reports a thrown call as an unhandled test error
 * even when the caller catches it (vitest 4). The recorded ids answer the same
 * questions a spy would.
 */
const asked: string[] = [];
let answer: (id: string) => Promise<{ id: string; format?: string | null }> =
  async (id) => ({ id });
/** Run ids the snapshot listing was asked about, and what it answers. */
const askedRuns: string[] = [];
let runSnapshots: (runId: string) => Promise<Array<{ interfaceId: string; format?: string | null }>> =
  async () => [];
let publicPreview: unknown = null;

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getInterface: (id: string) => {
      asked.push(id);
      return answer(id);
    },
    getInterfaceSnapshotsForRun: (runId: string) => {
      askedRuns.push(runId);
      return runSnapshots(runId);
    },
  },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => publicPreview,
}));

import { InterfaceFormatService } from '../InterfaceFormatService';

/**
 * A client configured like the app's: it RETRIES, which is what makes the per-call
 * `retry: false` in the service a real assertion rather than a restatement of the
 * default. `AppDataProvider` retries a non-4xx three times with backoff.
 */
function appLikeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: (n: number) => n < 3, retryDelay: 1 } },
  });
}

let client: QueryClient;

beforeEach(() => {
  asked.length = 0;
  askedRuns.length = 0;
  answer = async (id) => ({ id });
  runSnapshots = async () => [];
  publicPreview = null;
  client = appLikeClient();
});

describe('which interfaces are worth a lookup', () => {
  it('asks about a page the plan gives no box for - the agent-built case', () => {
    expect(InterfaceFormatService.candidateIds([{ id: 'agent-built' }])).toEqual(['agent-built']);
  });

  it('regression: asks about a page that ALREADY carries a box, because a format can outgrow it', () => {
    // A box stored while the page was vertical says nothing about the page being A4 now,
    // and one positionless node is enough to make the layout re-run over everything.
    expect(
      InterfaceFormatService.candidateIds([
        { id: 'saved', previewWidth: 225, previewHeight: 400 } as never,
      ]),
    ).toEqual(['saved']);
  });

  it('asks nothing about a page a publication snapshot already describes', () => {
    // Its format travels IN the plan, and its readers (marketplace preview, share link)
    // often cannot call the interfaces endpoint at all.
    expect(InterfaceFormatService.candidateIds([{ id: 'published', _snapshot_format: 'vertical' }]))
      .toEqual([]);
    // Present-but-null is an answer too: that page declares no format.
    expect(InterfaceFormatService.candidateIds([{ id: 'published', _snapshot_format: null }]))
      .toEqual([]);
  });

  it('asks nothing about a COMPACT node, which is laid out from its label', () => {
    expect(InterfaceFormatService.candidateIds([{ id: 'compact', showPreview: false }])).toEqual([]);
  });

  it('asks once for a page rendered by several nodes', () => {
    expect(InterfaceFormatService.candidateIds([{ id: 'p' }, { id: 'p' }])).toEqual(['p']);
  });

  it('ignores an entry with no id, and a plan with no interfaces at all', () => {
    expect(InterfaceFormatService.candidateIds([{} as never, { id: '' }])).toEqual([]);
    expect(InterfaceFormatService.candidateIds(undefined)).toEqual([]);
  });
});

describe('resolving the formats', () => {
  it('reports the declared format of each page', async () => {
    answer = async (id) => ({ id, format: 'a4_portrait' });

    const formats = await InterfaceFormatService.fetchFormats(['a', 'b'], { queryClient: client });

    expect(formats.get('a')).toBe('a4_portrait');
    expect(formats.get('b')).toBe('a4_portrait');
  });

  it('reports a page that declares no format as null, which is a shape, not an unknown', async () => {
    const formats = await InterfaceFormatService.fetchFormats(['a'], { queryClient: client });

    expect(formats.has('a')).toBe(true);
    expect(formats.get('a')).toBeNull();
  });

  it('regression: a page that fails to answer is simply absent, and the others still resolve', async () => {
    answer = async (id) => {
      if (id === 'gone') throw new Error('HTTP 404');
      return { id, format: 'vertical' };
    };

    const formats = await InterfaceFormatService.fetchFormats(['gone', 'ok'], { queryClient: client });

    // Absent, not null: the creator must keep its historical default for it rather than
    // treat the failure as "this page declares no format".
    expect(formats.has('gone')).toBe(false);
    expect(formats.get('ok')).toBe('vertical');
  });

  it('makes no request when there is nothing to resolve', async () => {
    const formats = await InterfaceFormatService.fetchFormats([], { queryClient: client });

    expect(asked).toEqual([]);
    expect(formats.size).toBe(0);
  });
});

describe('surfaces that must not be asked', () => {
  it('regression: a marketplace preview, where the endpoint is auth-gated', async () => {
    publicPreview = { publicationId: 'pub', showcaseRunId: 'run' };

    const formats = await InterfaceFormatService.fetchFormats(['a'], { queryClient: client });

    expect(asked).toEqual([]);
    expect(formats.size).toBe(0);
  });

  it('regression: a published plan, whose pages are answered by the snapshot before any request', async () => {
    // The data, not an auth check, is what keeps the anonymous surfaces quiet: every
    // published page carries the key, so nothing reaches fetchFormats at all.
    const ids = InterfaceFormatService.candidateIds([
      { id: 'showcase', _snapshot_format: 'a4_portrait' },
      { id: 'unset-but-published', _snapshot_format: null },
    ]);

    expect(ids).toEqual([]);
    expect(await InterfaceFormatService.fetchFormats(ids, { queryClient: client })).toEqual(new Map());
    expect(asked).toEqual([]);
  });
});

describe('sharing the app cache instead of keeping one', () => {
  it('regression: a second import is served from cache, so an agent build does not re-ask per edit', async () => {
    answer = async (id) => ({ id, format: 'square' });

    await InterfaceFormatService.fetchFormats(['a'], { queryClient: client });
    const second = await InterfaceFormatService.fetchFormats(['a'], { queryClient: client });

    expect(asked).toEqual(['a']);
    expect(second.get('a')).toBe('square');
  });

  it('reads what the interface node already fetched, under the same key', async () => {
    // The node's own query (`['interface', id]`, useInterfaceById) has run first.
    client.setQueryData(['interface', 'a'], { id: 'a', format: 'mobile' });

    const formats = await InterfaceFormatService.fetchFormats(['a'], { queryClient: client });

    expect(asked).toEqual([]);
    expect(formats.get('a')).toBe('mobile');
  });

  it('regression: an invalidation is respected, so a format the user just changed lands', async () => {
    answer = async (id) => ({ id, format: 'square' });
    await InterfaceFormatService.fetchFormats(['a'], { queryClient: client });

    // What the inspector's refetch and the chat's `interfaceModified` both end up doing.
    await client.invalidateQueries({ queryKey: ['interface', 'a'] });
    answer = async (id) => ({ id, format: 'a4_portrait' });
    const after = await InterfaceFormatService.fetchFormats(['a'], { queryClient: client });

    expect(after.get('a')).toBe('a4_portrait');
  });

  it('two canvases importing at once share one request', async () => {
    let release: (() => void) | null = null;
    answer = (id) => new Promise((resolve) => {
      release = () => resolve({ id, format: 'mobile' });
    });

    const both = Promise.all([
      InterfaceFormatService.fetchFormats(['a'], { queryClient: client }),
      InterfaceFormatService.fetchFormats(['a'], { queryClient: client }),
    ]);
    await vi.waitFor(() => expect(release).not.toBeNull());
    release!();
    const [first, second] = await both;

    expect(asked).toEqual(['a']);
    expect(first.get('a')).toBe('mobile');
    expect(second.get('a')).toBe('mobile');
  });

  it('does not cache a failure: the next import asks again', async () => {
    answer = async () => { throw new Error('HTTP 500'); };
    await InterfaceFormatService.fetchFormats(['a'], { queryClient: client });

    answer = async (id) => ({ id, format: 'banner' });
    const retry = await InterfaceFormatService.fetchFormats(['a'], { queryClient: client });

    expect(asked).toEqual(['a', 'a']);
    expect(retry.get('a')).toBe('banner');
  });
});

describe('never gating the canvas', () => {
  it('regression: one attempt per page, whatever the caller client would retry', async () => {
    // The client here retries three times, like the app's. The lookup blocks node
    // creation, so a 5xx must cost one round trip, not four with backoff.
    answer = async () => { throw new Error('HTTP 500'); };

    const formats = await InterfaceFormatService.fetchFormats(['a'], { queryClient: client });

    expect(asked).toEqual(['a']);
    expect(formats.size).toBe(0);
  });

  it('regression: a page that never answers is given up on, and the import goes on', async () => {
    vi.useFakeTimers();
    try {
      answer = () => new Promise(() => {});

      const pending = InterfaceFormatService.fetchFormats(['slow'], { queryClient: client });
      await vi.advanceTimersByTimeAsync(1_600);

      expect((await pending).size).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('regression: the page that DID answer keeps its box, the one that hung takes the default', async () => {
    // The real shape of a bad day on a multi-page plan: the import is laid out from a
    // partly resolved map, and the deadline must not cost the pages that were fine.
    vi.useFakeTimers();
    try {
      answer = (id) => (id === 'quick'
        ? Promise.resolve({ id, format: 'a4_portrait' })
        : new Promise(() => {}));

      const pending = InterfaceFormatService.fetchFormats(['quick', 'slow'], { queryClient: client });
      await vi.advanceTimersByTimeAsync(1_600);
      const formats = await pending;

      expect(formats.get('quick')).toBe('a4_portrait');
      expect(formats.has('slow')).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('regression: the map the caller holds does not gain a page after it was handed over', async () => {
    // The losing branch of the race keeps running, so a straggler would otherwise appear
    // in the middle of the caller's own work - a layout that changes while it is built.
    vi.useFakeTimers();
    try {
      let land: (() => void) | null = null;
      answer = (id) => new Promise((resolve) => { land = () => resolve({ id, format: 'square' }); });

      const pending = InterfaceFormatService.fetchFormats(['slow'], { queryClient: client });
      await vi.advanceTimersByTimeAsync(1_600);
      const formats = await pending;
      land?.();
      await vi.advanceTimersByTimeAsync(10);

      expect(formats.has('slow')).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('surfaces the lookup is not for', () => {
  it('regression: a run canvas never asks for a LIVE format, which is not what it paints', async () => {
    runSnapshots = async () => [{ interfaceId: 'a', format: 'a4_portrait' }];

    const formats = await InterfaceFormatService.fetchFormats(['a'], {
      queryClient: client,
      isRunMode: true,
      workflowRunId: 'run-uuid-1',
    });

    expect(asked).toEqual([]);
    expect(formats.get('a')).toBe('a4_portrait');
  });

  it('works with no client at all, going straight to the API', async () => {
    // A caller with no React Query above it (a test, a bare route) must still resolve.
    answer = async (id) => ({ id, format: 'banner' });

    const formats = await InterfaceFormatService.fetchFormats(['a']);

    expect(asked).toEqual(['a']);
    expect(formats.get('a')).toBe('banner');
  });
});

describe('a run canvas reads what THAT run froze', () => {
  const runContext = (workflowRunId: string | null = 'run-uuid-1') => ({
    queryClient: client,
    isRunMode: true,
    workflowRunId,
  });

  it('regression: asks once for the whole run, not once per page', async () => {
    // The pre-change canvas laid every page out at 400x250 while it painted 283x400. The
    // run's snapshots answer for all of them in a single request.
    runSnapshots = async () => [
      { interfaceId: 'a', format: 'a4_portrait' },
      { interfaceId: 'b', format: 'vertical' },
    ];

    const formats = await InterfaceFormatService.fetchFormats(['a', 'b'], runContext());

    expect(askedRuns).toEqual(['run-uuid-1']);
    expect(formats.get('a')).toBe('a4_portrait');
    expect(formats.get('b')).toBe('vertical');
  });

  it('regression: prefers the FROZEN format, so a page reformatted since the run is unaffected', async () => {
    // The page is vertical today; this run painted it as A4 and still does.
    answer = async (id) => ({ id, format: 'vertical' });
    runSnapshots = async () => [{ interfaceId: 'a', format: 'a4_portrait' }];

    const formats = await InterfaceFormatService.fetchFormats(['a'], runContext());

    expect(formats.get('a')).toBe('a4_portrait');
    expect(asked).toEqual([]);
  });

  it('a page the run declared no format for is the classic box, not an unknown', async () => {
    runSnapshots = async () => [{ interfaceId: 'a' }];

    const formats = await InterfaceFormatService.fetchFormats(['a'], runContext());

    expect(formats.has('a')).toBe(true);
    expect(formats.get('a')).toBeNull();
  });

  it('regression: a page the run never froze takes its LIVE format, which is what it paints', async () => {
    // A page added to the plan after the run started, a run older than the snapshots, or a
    // snapshot write that failed. The render path falls back to the live interface for it,
    // so the layout must follow rather than reserve the default box.
    runSnapshots = async () => [{ interfaceId: 'other', format: 'square' }];
    answer = async (id) => ({ id, format: 'a4_portrait' });

    const formats = await InterfaceFormatService.fetchFormats(['a'], runContext());

    expect(asked).toEqual(['a']);
    expect(formats.get('a')).toBe('a4_portrait');
  });

  it('matches the run its snapshots however the uuid is spelled', async () => {
    runSnapshots = async () => [{ interfaceId: 'AB-CD', format: 'square' }];

    const formats = await InterfaceFormatService.fetchFormats(['ab-cd'], runContext());

    // Keyed by the PLAN's spelling, which is what the creator looks the box up with.
    expect(formats.get('ab-cd')).toBe('square');
    expect(asked).toEqual([]);
  });

  it('regression: resolves nothing rather than a live format when the run id is missing', async () => {
    answer = async (id) => ({ id, format: 'vertical' });

    const formats = await InterfaceFormatService.fetchFormats(['a'], runContext(null));

    expect(asked).toEqual([]);
    expect(askedRuns).toEqual([]);
    expect(formats.size).toBe(0);
  });

  it('falls back to the live format when the run listing fails, and never fails the import', async () => {
    runSnapshots = async () => { throw new Error('HTTP 500'); };
    answer = async (id) => ({ id, format: 'vertical' });

    const formats = await InterfaceFormatService.fetchFormats(['a'], runContext());

    expect(formats.get('a')).toBe('vertical');
  });

  it('and resolves nothing at all when both reads fail', async () => {
    runSnapshots = async () => { throw new Error('HTTP 500'); };
    answer = async () => { throw new Error('HTTP 500'); };

    expect((await InterfaceFormatService.fetchFormats(['a'], runContext())).size).toBe(0);
  });

  it('a second import of the same run is served from cache', async () => {
    runSnapshots = async () => [{ interfaceId: 'a', format: 'square' }];

    await InterfaceFormatService.fetchFormats(['a'], runContext());
    const second = await InterfaceFormatService.fetchFormats(['a'], runContext());

    expect(askedRuns).toEqual(['run-uuid-1']);
    expect(second.get('a')).toBe('square');
  });

  it('regression: one attempt, whatever the caller client would retry', async () => {
    runSnapshots = async () => { throw new Error('HTTP 500'); };
    answer = async () => { throw new Error('HTTP 500'); };

    await InterfaceFormatService.fetchFormats(['a'], runContext());

    expect(askedRuns).toEqual(['run-uuid-1']);
  });

  it('regression: a run listing that never answers is given up on, and the import goes on', async () => {
    vi.useFakeTimers();
    try {
      runSnapshots = () => new Promise(() => {});

      const pending = InterfaceFormatService.fetchFormats(['a'], runContext());
      await vi.advanceTimersByTimeAsync(1_600);

      expect((await pending).size).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('works with no client at all, going straight to the API', async () => {
    runSnapshots = async () => [{ interfaceId: 'a', format: 'banner' }];

    const formats = await InterfaceFormatService.fetchFormats(['a'], {
      isRunMode: true,
      workflowRunId: 'run-uuid-1',
    });

    expect(askedRuns).toEqual(['run-uuid-1']);
    expect(formats.get('a')).toBe('banner');
  });

  it('a run id outside run mode changes nothing: the live format is what an editor paints', async () => {
    answer = async (id) => ({ id, format: 'vertical' });

    const formats = await InterfaceFormatService.fetchFormats(['a'], {
      queryClient: client,
      isRunMode: false,
      workflowRunId: 'run-uuid-1',
    });

    expect(askedRuns).toEqual([]);
    expect(formats.get('a')).toBe('vertical');
  });
});

