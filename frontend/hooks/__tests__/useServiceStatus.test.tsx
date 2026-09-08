/**
 * @vitest-environment jsdom
 *
 * Tests for the in-app status poll.
 *
 * What matters: it reads OUR Next route (not the gateway, which is part of what
 * it reports on), it is silent in CE, it does not retry-storm on a failure, and
 * it does not keep waking the network behind a hidden tab. Each of those is a
 * property someone could plausibly "simplify" away.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import * as React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const edition = { IS_CE: false };
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return edition.IS_CE;
  },
}));

const { useServiceStatus } = await import('../useServiceStatus');

const PAYLOAD = {
  overall: 'operational',
  components: [],
  incidents: [],
  windowDays: 90,
  generatedAt: null,
  sampleIntervalSeconds: 60,
  dataAvailable: true,
  incidentsSource: 'ok',
  statusPageUrl: null,
};

function wrapper({ children }: { children: React.ReactNode }) {
  // Deliberately NOT overriding `retry` here: react-query retries 3 times by
  // default, so the "called once" assertion below only holds if the HOOK sets
  // retry: false. Overriding it in the wrapper would test the wrapper.
  const client = new QueryClient();
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  edition.IS_CE = false;
  vi.unstubAllGlobals();
});

describe('useServiceStatus options', () => {
  it('polls once a minute, and not behind a hidden tab', async () => {
    // Asserted on the options actually handed to react-query: these four are the
    // whole polling budget (one request per minute per VISIBLE tab), and each is
    // the kind of line that gets "simplified" away without a failing test.
    const captured: Record<string, unknown>[] = [];
    vi.doMock('@tanstack/react-query', async (importOriginal) => {
      const actual = await importOriginal<typeof import('@tanstack/react-query')>();
      return {
        ...actual,
        useQuery: (options: Record<string, unknown>) => {
          captured.push(options);
          return { data: undefined, isPending: true, isError: false };
        },
      };
    });

    // Non-literal specifier: a literal one is resolved at compile time and the
    // query string makes it an unknown module. At runtime it re-imports the same
    // file against the mock declared just above.
    const modulePath = '../useServiceStatus?options';
    const { useServiceStatus: hook } = (await import(modulePath)) as typeof import('../useServiceStatus');
    renderHook(() => hook(), { wrapper });

    expect(captured).toHaveLength(1);
    expect(captured[0].refetchInterval).toBe(60_000);
    expect(captured[0].refetchIntervalInBackground).toBe(false);
    expect(captured[0].refetchOnWindowFocus).toBe(true);
    expect(captured[0].retry).toBe(false);
    vi.doUnmock('@tanstack/react-query');
  });
});

describe('useServiceStatus', () => {
  it('reads our own /api/status route, not the gateway', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => PAYLOAD }) as Response);
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useServiceStatus(), { wrapper });

    await waitFor(() => expect(result.current.status).not.toBeNull());
    expect(fetchMock).toHaveBeenCalledWith('/api/status', {
      headers: { Accept: 'application/json' },
    });
    expect(result.current.status?.overall).toBe('operational');
    expect(result.current.isError).toBe(false);
  });

  it('never calls anything in CE, and reports no status', async () => {
    edition.IS_CE = true;
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useServiceStatus(), { wrapper });

    // A disabled query stays pending forever; what matters is the silence.
    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.current.status).toBeNull();
  });

  it('surfaces a failure as an error, with no status to render', async () => {
    const fetchMock = vi.fn(async () => ({ ok: false, status: 503 }) as Response);
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useServiceStatus(), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.status).toBeNull();
    // One attempt: a status endpoint that is down must not be hammered.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
