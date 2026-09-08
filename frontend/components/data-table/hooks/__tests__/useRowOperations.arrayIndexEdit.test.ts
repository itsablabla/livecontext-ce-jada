// @vitest-environment jsdom
/**
 * `array_index` is read-only only where it IS the index, i.e. during nested navigation, where the
 * view injects it as the item's position rather than reading it from the table.
 *
 * Restoring data columns that share a system column's name made `array_index` a legal user column
 * again at root level. Two guards decide its fate and they must agree: the one that builds the
 * PATCH, and the one that applies the optimistic update. Gating only the first sends the write and
 * then discards it locally, so the cell saves and visually reverts.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock('../../utils/authenticatedFetch', () => ({
  authenticatedFetch: vi.fn(),
}));

import { useRowOperations } from '../useRowOperations';
import { authenticatedFetch } from '../../utils/authenticatedFetch';
import { createViewConfig, getRowLevelExportFields } from '../../viewConfig';
import type { DataSourceItemRow, PaginationState } from '../../types';

const mockFetch = authenticatedFetch as unknown as ReturnType<typeof vi.fn>;

const pagination: PaginationState = {
  currentPage: 1, pageSize: 20, totalItems: 1, totalPages: 1, nextCursor: null, hasMore: false,
};

const rowWith = (data: Record<string, unknown>): DataSourceItemRow => ({
  id: 1,
  data_source_id: 42,
  tenant_id: 't',
  data,
  priority: 0,
  created_at: '2026-01-01T00:00:00Z',
  updated_at: null,
} as DataSourceItemRow);

function setup(jsonPath: string | undefined, rows: DataSourceItemRow[], rowLevelFields?: string[]) {
  const setRows = vi.fn();
  const hook = renderHook(() =>
    useRowOperations({
      dataSourceId: 42,
      jsonPath,
      workflowContext: null,
      rows,
      setRows,
      pagination,
      // The view's row-level lanes, exactly as the controller derives them from viewConfig.
      rowLevelFields: rowLevelFields ?? getRowLevelExportFields(createViewConfig(undefined, false, jsonPath)),
      fetchData: vi.fn(),
      addToast: vi.fn(),
    })
  );
  return { hook, setRows };
}

/** Run the updater `setRows` was called with against the given rows. */
const applyOptimistic = (setRows: ReturnType<typeof vi.fn>, rows: DataSourceItemRow[]) => {
  const updater = setRows.mock.calls.at(-1)?.[0];
  return typeof updater === 'function' ? updater(rows) : undefined;
};

beforeEach(() => {
  mockFetch.mockReset();
  mockFetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
});

describe('useRowOperations - array_index is read-only only as the injected index', () => {
  it('saves a root-level `array_index` column and keeps the new value on screen', async () => {
    const rows = [rowWith({ array_index: 3, name: 'x' })];
    const { hook, setRows } = setup(undefined, rows);

    await act(async () => {
      await hook.result.current.handleSaveEdit(1, 'array_index', '9');
    });

    expect(mockFetch).toHaveBeenCalledTimes(1);
    const [, init] = mockFetch.mock.calls[0];
    expect(JSON.parse((init as { body: string }).body)).toEqual({
      patch: [{ op: 'replace', path: 'array_index', value: 9 }],
    });

    const next = applyOptimistic(setRows, rows) as DataSourceItemRow[];
    expect(next[0].data.array_index).toBe(9);
  });

  it('refuses to save the injected index during nested navigation', async () => {
    const rows = [rowWith({ array_index: 0, name: 'x' })];
    const { hook, setRows } = setup('payload.items', rows);

    await act(async () => {
      await hook.result.current.handleSaveEdit(1, 'array_index', '9');
    });

    expect(mockFetch).not.toHaveBeenCalled();
    expect(setRows).not.toHaveBeenCalled();
  });

  it('saves a nested `priority` into the item, not onto the parent row', async () => {
    // `priority` is a row-level lane at root and item data when nested, exactly like `array_index`.
    // Ungated it patched the PARENT row's priority while the grid showed the item's value.
    const rows = [rowWith({ priority: 3, name: 'x' })];
    const { hook, setRows } = setup('payload.items', rows);

    await act(async () => {
      await hook.result.current.handleSaveEdit(1, 'priority', '9');
    });

    const [, init] = mockFetch.mock.calls[0];
    expect(JSON.parse((init as { body: string }).body)).toEqual({
      patch: [{ op: 'replace', path: 'payload.items/priority', value: 9 }],
    });
    // And the optimistic update writes the item, not row.priority.
    const next = applyOptimistic(setRows, rows) as DataSourceItemRow[];
    expect(next[0].data.priority).toBe(9);
    expect(next[0].priority).toBe(0);
  });

  it('leaves the priority lane on its own path', async () => {
    const rows = [rowWith({ name: 'x' })];
    const { hook } = setup(undefined, rows);

    await act(async () => {
      await hook.result.current.handleSaveEdit(1, 'priority', '5');
    });

    const [, init] = mockFetch.mock.calls[0];
    expect(JSON.parse((init as { body: string }).body)).toEqual({
      patch: [{ op: 'replace', path: 'priority', value: 5 }],
    });
  });
});
