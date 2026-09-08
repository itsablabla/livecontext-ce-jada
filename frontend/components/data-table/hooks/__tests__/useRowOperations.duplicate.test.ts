// @vitest-environment jsdom
/**
 * Duplicating the selected rows, and pointing at the copies.
 *
 * There is no bulk "duplicate" on the server, so this is one POST per selected row and the result
 * is only as good as what it sends and what it reports back: a copy that drops the row's data is
 * an empty row, and a partial failure that reports success is a user who never notices half their
 * copies are missing. The copies then land wherever the table's order puts them, which is why the
 * hook publishes their ids for the grid to flag.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

// The key AND its values: a message that silently drops its interpolation (a reason the server
// gave, a count) would otherwise be indistinguishable from one that carries it.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key} ${JSON.stringify(values)}` : key,
}));

vi.mock('../../utils/authenticatedFetch', () => ({
  authenticatedFetch: vi.fn(),
}));

import { useRowOperations } from '../useRowOperations';
import { TABLE_REVEAL_WINDOW_MS } from '../../tableStyles';
import { normalizeRows } from '../../utils/dataTableUtils';
import { authenticatedFetch } from '../../utils/authenticatedFetch';
import type { DataSourceItemRow, PaginationState } from '../../types';

const mockFetch = authenticatedFetch as unknown as ReturnType<typeof vi.fn>;

const pagination: PaginationState = {
  currentPage: 2,
  pageSize: 20,
  totalItems: 40,
  totalPages: 2,
  nextCursor: null,
  hasMore: false,
};

/**
 * Rows as the GRID actually holds them: server payloads run through the real normalizer.
 *
 * Hand-built fixtures are what let the identity bug below ship. `normalizeRow` writes `_callId`
 * (and `id`) INTO every row's data on the way in, so a fixture that omits them asserts a shape
 * production never produces - and a copy that carries them back to the server inherits its
 * source's identity. The fixture has to come from the same code path the user's rows do.
 */
const rows: DataSourceItemRow[] = normalizeRows(
  [
    { id: 1, data: { name: 'ada' }, priority: 3, created_at: '2026-01-01T00:00:00Z' },
    { id: 2, data: { name: 'grace' }, priority: 3, created_at: '2026-01-01T00:00:00Z' },
    { id: 3, data: { name: 'edsger' }, priority: 3, created_at: '2026-01-01T00:00:00Z' },
  ],
  { tenantId: 't', dataSourceId: 42 } as never,
);

const addToast = vi.fn();
const fetchData = vi.fn().mockResolvedValue(undefined);

/** Normal mode by default; pass a jsonPath to get the nested view. */
const setup = (over: { jsonPath?: string; sortConfig?: { key: string; direction: 'asc' | 'desc' } | null } = {}) =>
  renderHook(() =>
    useRowOperations({
      dataSourceId: 42,
      jsonPath: over.jsonPath,
      workflowContext: null,
      rows,
      setRows: vi.fn(),
      pagination,
      sortConfig: over.sortConfig ?? null,
      fetchData,
      addToast,
    }),
  );

/** The grid keys a normal-mode selection by row id. */
const byId = (r: DataSourceItemRow) => String(r.id);

/** What POST /items answers with: the whole created row, not just its id. */
const created = (id: number) => ({
  ok: true,
  status: 200,
  json: async () => ({
    id, data_source_id: 42, tenant_id: 't', data: { name: 'ada' },
    priority: 3, created_at: '2026-02-01T00:00:00Z', updated_at: null,
  }),
});

/**
 * What the item-create endpoint actually answers with when it refuses: a bare status, no body and
 * no error header (`ResponseEntity.badRequest().build()`). A fixture with a tidy `{message}` would
 * certify a reason the server never sends.
 */
const refused = () => ({
  ok: false,
  status: 400,
  statusText: 'Bad Request',
  headers: { get: () => null },
  clone: () => ({ json: async () => { throw new Error('no body'); } }),
  text: async () => '',
});

/** A refusal that DOES carry a message, as a proxy or gateway in front of it may. */
const refusedWithMessage = (message: string) => ({
  ok: false,
  status: 403,
  statusText: 'Forbidden',
  headers: { get: () => null },
  clone: () => ({ json: async () => ({ message }) }),
});

async function duplicate(result: { current: ReturnType<typeof useRowOperations> }, keys: string[]) {
  await act(async () => {
    await result.current.duplicateSelectedRows(new Set(keys), byId);
  });
}

beforeEach(() => {
  mockFetch.mockReset();
  addToast.mockReset();
  fetchData.mockClear();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useRowOperations - duplicating the selected rows', () => {
  it('writes one copy per selected row, carrying its data and priority', async () => {
    mockFetch.mockResolvedValueOnce(created(11)).mockResolvedValueOnce(created(12));
    const { result } = setup();

    await duplicate(result, ['1', '3']);

    expect(mockFetch).toHaveBeenCalledTimes(2);
    const bodies = mockFetch.mock.calls.map(([, init]) => JSON.parse(init.body));
    expect(bodies).toEqual([
      { data: { name: 'ada' }, priority: 3 },
      { data: { name: 'edsger' }, priority: 3 },
    ]);
    // The URL is the plain item-create endpoint: there is no bulk duplicate to batch into.
    expect(mockFetch.mock.calls[0][0]).toBe('/api/proxy/data-sources/42/items');
  });

  it('reports a full success as a success, naming how many were copied', async () => {
    mockFetch.mockResolvedValueOnce(created(11)).mockResolvedValueOnce(created(12));
    const { result } = setup();

    await duplicate(result, ['1', '2']);

    expect(addToast).toHaveBeenCalledWith(expect.objectContaining({
      type: 'success',
      message: expect.stringContaining('"count":2'),
    }));
  });

  it('leaves a vector column out of the copy', async () => {
    // The read path merges the WHOLE embedding into the row's data as text, so a copy that keeps
    // it writes kilobytes of numbers into JSONB - invisible in the grid, and inherited by every
    // later copy of that row.
    const withVector = normalizeRows(
      [{ id: 9, data: { name: 'ada', embedding: '[-0.0095,-0.0216,0.0299]' }, priority: 1, created_at: '2026-01-01T00:00:00Z' }],
      { tenantId: 't', dataSourceId: 42 } as never,
    );
    mockFetch.mockResolvedValue(created(11));
    const { result } = renderHook(() =>
      useRowOperations({
        dataSourceId: 42, jsonPath: undefined, workflowContext: null,
        rows: withVector, setRows: vi.fn(), pagination, sortConfig: null,
        columns: [
          { col_id: 'data.name', field: 'data.name', header_name: 'Name', type: 'text' },
          { col_id: 'data.embedding', field: 'data.embedding', header_name: 'Embedding', type: 'vector' },
        ] as never,
        fetchData, addToast,
      }),
    );

    await act(async () => {
      await result.current.duplicateSelectedRows(new Set(['9']), byId);
    });

    expect(JSON.parse(mockFetch.mock.calls[0][1].body).data).toEqual({ name: 'ada' });
  });

  it('warns that copies are not searchable yet when the table has a vector column', async () => {
    // Nothing re-embeds a copy, so on a vector table it is missing from similarity search. Left
    // unsaid, the user finds out through a search that quietly does not return their copies.
    const withVector = normalizeRows(
      [{ id: 9, data: { name: 'ada', embedding: '[0.1,0.2]' }, priority: 1, created_at: '2026-01-01T00:00:00Z' }],
      { tenantId: 't', dataSourceId: 42 } as never,
    );
    mockFetch.mockResolvedValue(created(11));
    const { result } = renderHook(() =>
      useRowOperations({
        dataSourceId: 42, jsonPath: undefined, workflowContext: null,
        rows: withVector, setRows: vi.fn(), pagination, sortConfig: null,
        columns: [
          { col_id: 'data.name', field: 'data.name', header_name: 'Name', type: 'text' },
          { col_id: 'data.embedding', field: 'data.embedding', header_name: 'Embedding', type: 'vector' },
        ] as never,
        fetchData, addToast,
      }),
    );

    await act(async () => {
      await result.current.duplicateSelectedRows(new Set(['9']), byId);
    });

    expect(addToast).toHaveBeenCalledWith(expect.objectContaining({
      type: 'success',
      message: expect.stringContaining('duplicateRowsSuccessMessageVector'),
    }));
  });

  it('says nothing about embeddings on a table that has no vector column', async () => {
    mockFetch.mockResolvedValue(created(11));
    const { result } = setup();

    await duplicate(result, ['1']);

    expect(addToast).toHaveBeenCalledWith(expect.objectContaining({
      type: 'success',
      message: expect.stringContaining('duplicateRowsSuccessMessage '),
    }));
  });

  it('leaves the rows nobody selected alone', async () => {
    mockFetch.mockResolvedValue(created(11));
    const { result } = setup();

    await duplicate(result, ['2']);

    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(JSON.parse(mockFetch.mock.calls[0][1].body).data).toEqual({ name: 'grace' });
  });

  it('points at the ids the server assigned, then stops', async () => {
    // Matching on ids, not positions: a copy lands wherever the table's order puts it.
    vi.useFakeTimers();
    mockFetch.mockResolvedValueOnce(created(11)).mockResolvedValueOnce(created(12));
    const { result } = setup();

    await duplicate(result, ['1', '2']);
    expect([...result.current.revealedRowIds]).toEqual([11, 12]);

    act(() => {
      vi.advanceTimersByTime(TABLE_REVEAL_WINDOW_MS);
    });
    expect(result.current.revealedRowIds.size).toBe(0);
  });

  it('never sends the identity the grid injected, so a copy is not its original', async () => {
    // The bug this pins: `normalizeRow` writes `_callId` (and `id`) into every rendered row's data.
    // Persisted, they come back through `extractCallId` and the COPY reports its SOURCE's id, so it
    // shares selection, inline edits and deletion with it - and the reveal points at a row that,
    // as far as the grid is concerned, does not exist.
    expect(rows[0].data).toMatchObject({ _callId: 1, id: 1 });

    mockFetch.mockResolvedValue(created(11));
    const { result } = setup();

    await duplicate(result, ['1']);

    const sent = JSON.parse(mockFetch.mock.calls[0][1].body).data;
    expect(sent).toEqual({ name: 'ada' });
    expect(sent).not.toHaveProperty('_callId');
    expect(sent).not.toHaveProperty('id');
  });

  it('keeps a user column named `id` that carries its own value', async () => {
    // normalizeRow leaves a real `id` column alone on the way in, so the copy must keep it: the
    // strip is targeted at what the grid injected, not at every field with that name.
    const withOwnId = normalizeRows(
      [{ id: 7, data: { id: 'CUST-42', name: 'ada' }, priority: 1, created_at: '2026-01-01T00:00:00Z' }],
      { tenantId: 't', dataSourceId: 42 } as never,
    );
    mockFetch.mockResolvedValue(created(11));
    const { result } = renderHook(() =>
      useRowOperations({
        dataSourceId: 42, jsonPath: undefined, workflowContext: null,
        rows: withOwnId, setRows: vi.fn(), pagination, sortConfig: null, fetchData, addToast,
      }),
    );

    await act(async () => {
      await result.current.duplicateSelectedRows(new Set(['7']), byId);
    });

    expect(JSON.parse(mockFetch.mock.calls[0][1].body).data).toEqual({ id: 'CUST-42', name: 'ada' });
  });

  it('goes to page 1 under the default order, where the server puts the copies', async () => {
    // The table is newest-first by default, so a copy made from page 2 lands on page 1. Refetching
    // page 2 would leave the user with a green toast, no copies and no cue.
    mockFetch.mockResolvedValue(created(11));
    const { result } = setup();

    await duplicate(result, ['1']);

    expect(fetchData).toHaveBeenCalledWith(1, pagination.pageSize);
  });

  it('treats an explicit "created_at desc" as newest first, not as a custom order', async () => {
    // The trap: reading "has a sortConfig" as "the copies sort near their originals". Sorting by
    // created_at descending IS the server's own default order, so the copies are on page 1 - and
    // refetching the page the user was standing on shows a green toast and no copies.
    mockFetch.mockResolvedValue(created(11));
    const { result } = setup({ sortConfig: { key: 'created_at', direction: 'desc' } });

    await duplicate(result, ['1']);

    expect(fetchData).toHaveBeenCalledWith(1, pagination.pageSize);
  });

  it('stays put under an order it cannot predict, rather than moving the user for nothing', async () => {
    // Under a sort of the user's own the copies carry their originals' values and the server adds
    // no tiebreaker, so nothing here knows which page they landed on.
    mockFetch.mockResolvedValue(created(11));
    const { result } = setup({ sortConfig: { key: 'data.name', direction: 'asc' } });

    await duplicate(result, ['1']);

    expect(fetchData).toHaveBeenCalledWith(pagination.currentPage, pagination.pageSize);
  });

  it('stays on the page after a partial, so the rows that failed can still be retried', async () => {
    // The trap: jumping to page 1 (where the copies are) unloads the originals that were NOT
    // copied, and the next Duplicate then reports "nothing selected here" instead of retrying.
    mockFetch.mockResolvedValueOnce(created(11)).mockResolvedValueOnce(refused());
    const { result } = setup();

    await duplicate(result, ['1', '2']);

    expect(fetchData).toHaveBeenCalledWith(pagination.currentPage, pagination.pageSize);
  });

  it('counts rows selected on another page as not copied, instead of dropping them silently', async () => {
    // A selection survives paging, but only the loaded rows can be copied.
    mockFetch.mockResolvedValue(created(11));
    const { result } = setup();

    await duplicate(result, ['1', '4041', '4042']);

    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(addToast).toHaveBeenCalledWith(expect.objectContaining({
      type: 'warning',
      message: expect.stringContaining('"done":1,"total":3'),
    }));
  });

  it('says so when the whole selection is on another page, instead of doing nothing at all', async () => {
    // The worst version of this bug: no request, no toast, no state change. The user clicks and
    // concludes the button is broken.
    const { result } = setup();

    await duplicate(result, ['4041', '4042']);

    expect(mockFetch).not.toHaveBeenCalled();
    expect(addToast).toHaveBeenCalledWith(expect.objectContaining({
      type: 'warning',
      message: expect.stringContaining('duplicateRowsReasonNotLoaded'),
    }));
  });

  it('repeats the server reason in the partial message too, not only the total failure', async () => {
    mockFetch.mockResolvedValueOnce(created(11)).mockResolvedValueOnce(refusedWithMessage('viewers cannot write'));
    const { result } = setup();

    await duplicate(result, ['1', '2']);

    expect(addToast).toHaveBeenCalledWith(expect.objectContaining({
      type: 'warning',
      message: expect.stringContaining('viewers cannot write'),
    }));
  });

  it('falls back to the status line when the refusal carries no body at all', async () => {
    // The common case: the endpoint answers `badRequest().build()`. "400 Bad Request" is thin, but
    // it is the truth, and it beats an English placeholder inside a translated sentence.
    mockFetch.mockResolvedValue(refused());
    const { result } = setup();

    await duplicate(result, ['1']);

    expect(addToast).toHaveBeenCalledWith(expect.objectContaining({
      type: 'error',
      message: expect.stringContaining('400'),
    }));
  });

  it('says how many landed when only some of them did', async () => {
    // Silent partial success is the failure mode that matters here: the user sees copies appear
    // and has no reason to count them.
    mockFetch.mockResolvedValueOnce(created(11)).mockResolvedValueOnce(refused());
    const { result } = setup();

    await duplicate(result, ['1', '2']);

    expect(addToast).toHaveBeenCalledWith(expect.objectContaining({
      type: 'warning',
      message: expect.stringContaining('"done":1,"total":2'),
    }));
    // Only the copy that exists is pointed at.
    expect([...result.current.revealedRowIds]).toEqual([11]);
  });

  it('drops the cue when the user navigates to another table', async () => {
    // Without the scope wired through, the ids of one table's copies keep flagging whatever rows
    // share those ids in the next table - and no other test would notice the argument going away.
    mockFetch.mockResolvedValue(created(11));
    const { result, rerender } = renderHook(
      ({ id }: { id: number }) =>
        useRowOperations({
          dataSourceId: id, jsonPath: undefined, workflowContext: null,
          rows, setRows: vi.fn(), pagination, sortConfig: null, fetchData, addToast,
        }),
      { initialProps: { id: 42 } },
    );

    await act(async () => {
      await result.current.duplicateSelectedRows(new Set(['1']), byId);
    });
    expect(result.current.revealedRowIds.size).toBe(1);

    rerender({ id: 43 });

    expect(result.current.revealedRowIds.size).toBe(0);
  });

  it('keeps at most DUPLICATE_CONCURRENCY writes in flight', async () => {
    // One POST per row with no bound would turn a page-size selection into a burst of parallel
    // requests. Nothing else in the suite copies enough rows to see the ceiling.
    let inFlight = 0;
    let peak = 0;
    const release: Array<() => void> = [];
    mockFetch.mockImplementation(() => {
      inFlight++;
      peak = Math.max(peak, inFlight);
      return new Promise(resolve => release.push(() => { inFlight--; resolve(created(11)); }));
    });
    const many = normalizeRows(
      Array.from({ length: 9 }, (_, i) => ({
        id: 100 + i, data: { name: `row-${i}` }, priority: 1, created_at: '2026-01-01T00:00:00Z',
      })),
      { tenantId: 't', dataSourceId: 42 } as never,
    );
    const { result } = renderHook(() =>
      useRowOperations({
        dataSourceId: 42, jsonPath: undefined, workflowContext: null,
        rows: many, setRows: vi.fn(), pagination, sortConfig: null, fetchData, addToast,
      }),
    );

    await act(async () => {
      const done = result.current.duplicateSelectedRows(
        new Set(many.map(r => String(r.id))), byId,
      );
      // Let every slot that is allowed to start, start - then drain, releasing as they come.
      for (let i = 0; i < 40 && release.length; i++) {
        release.shift()!();
        await Promise.resolve();
      }
      while (release.length) release.shift()!();
      await done;
    });

    expect(peak).toBeLessThanOrEqual(4);
    expect(peak).toBeGreaterThan(1);
  });

  it('does nothing at all in a nested view', async () => {
    // There a "row" is one element inside a JSON array: copying it is a patch on its parent, not
    // a new row, and writing one anyway would silently create junk at the table's root.
    const { result } = setup({ jsonPath: 'items' });

    await duplicate(result, ['1']);

    expect(mockFetch).not.toHaveBeenCalled();
    expect(addToast).not.toHaveBeenCalled();
  });

  it('does nothing in a workflow view, where the rows are step output and not table rows', async () => {
    const { result } = renderHook(() =>
      useRowOperations({
        dataSourceId: 42, jsonPath: undefined,
        workflowContext: { workflowId: 'w1', runId: 'r1' },
        rows, setRows: vi.fn(), pagination, sortConfig: null, fetchData, addToast,
      }),
    );

    await act(async () => {
      await result.current.duplicateSelectedRows(new Set(['1']), byId);
    });

    expect(mockFetch).not.toHaveBeenCalled();
    expect(addToast).not.toHaveBeenCalled();
  });

  it('keeps a user `id` column whose value happens to equal the row id', async () => {
    // A value comparison cannot tell this apart from the id the grid injects; only recording what
    // was injected can. Here the user's own column holds 7 and the row's id is also 7.
    const collide = normalizeRows(
      [{ id: 7, data: { id: 7, name: 'ada' }, priority: 1, created_at: '2026-01-01T00:00:00Z' }],
      { tenantId: 't', dataSourceId: 42 } as never,
    );
    mockFetch.mockResolvedValue(created(11));
    const { result } = renderHook(() =>
      useRowOperations({
        dataSourceId: 42, jsonPath: undefined, workflowContext: null,
        rows: collide, setRows: vi.fn(), pagination, sortConfig: null, fetchData, addToast,
      }),
    );

    await act(async () => {
      await result.current.duplicateSelectedRows(new Set(['7']), byId);
    });

    expect(JSON.parse(mockFetch.mock.calls[0][1].body).data).toEqual({ id: 7, name: 'ada' });
  });

  it('calls a total failure an error, and repeats the reason the server gave', async () => {
    // "0 of 3 copied" as an amber partial is the wrong sentence: nothing was copied. And without
    // the reason, a viewer-role 403 and a dropped connection look identical.
    mockFetch.mockRejectedValue(new Error('network down'));
    const { result } = setup();

    await duplicate(result, ['1', '2']);

    expect(addToast).toHaveBeenCalledWith(expect.objectContaining({
      type: 'error',
      message: expect.stringContaining('network down'),
    }));
    expect(result.current.revealedRowIds.size).toBe(0);
    expect(result.current.isDuplicatingRows).toBe(false);
  });

  it('does not refetch or point at anything when nothing was written', async () => {
    mockFetch.mockResolvedValue(refused());
    const { result } = setup();

    await duplicate(result, ['1']);

    expect(fetchData).not.toHaveBeenCalled();
    expect(result.current.revealedRowIds.size).toBe(0);
  });

  it('takes down the cue left by an earlier duplicate when this one copies nothing', async () => {
    // Otherwise the highlight sits on rows this run did not produce, right beside a message
    // saying nothing was copied.
    mockFetch.mockResolvedValue(created(11));
    const { result } = setup();
    await duplicate(result, ['1']);
    expect(result.current.revealedRowIds.size).toBe(1);

    mockFetch.mockResolvedValue(refused());
    await duplicate(result, ['2']);

    expect(result.current.revealedRowIds.size).toBe(0);
  });

  it('reports how many copies were written, so the caller can keep a failed selection', async () => {
    mockFetch.mockResolvedValueOnce(created(11)).mockResolvedValueOnce(refused());
    const { result } = setup();

    let written: number | undefined;
    await act(async () => {
      written = await result.current.duplicateSelectedRows(new Set(['1', '2']), byId);
    });

    expect(written).toBe(1);
  });

  it('holds the button down for the whole write, and lets it go afterwards', async () => {
    let release: (v: unknown) => void = () => {};
    mockFetch.mockImplementation(() => new Promise(r => { release = r; }));
    const { result } = setup();

    let done!: Promise<number>;
    await act(async () => {
      done = result.current.duplicateSelectedRows(new Set(['1']), byId);
    });
    expect(result.current.isDuplicatingRows).toBe(true);

    await act(async () => {
      release(created(11));
      await done;
    });
    expect(result.current.isDuplicatingRows).toBe(false);
  });

  it('moves the cue to the second duplicate when two happen inside one window', async () => {
    vi.useFakeTimers();
    mockFetch.mockResolvedValue(created(11));
    const { result } = setup();
    await duplicate(result, ['1']);

    act(() => { vi.advanceTimersByTime(TABLE_REVEAL_WINDOW_MS / 2); });
    mockFetch.mockResolvedValue(created(12));
    await duplicate(result, ['2']);

    act(() => { vi.advanceTimersByTime(TABLE_REVEAL_WINDOW_MS / 2 + 10); });
    // The first duplicate's timer has come and gone; it must not take the second one's cue with it.
    expect([...result.current.revealedRowIds]).toEqual([12]);
  });
});
