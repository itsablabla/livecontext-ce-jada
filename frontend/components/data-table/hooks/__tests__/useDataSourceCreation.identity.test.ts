// @vitest-environment jsdom
/**
 * "Create table from selection" must not carry the grid's display identity into the new table.
 *
 * Sending `row.data` verbatim persisted `_callId` (and the display `id`) as REAL data in the new
 * table, where `generateMappingSpec` turns every key into a column. Rows of that table then report,
 * through `extractCallId`, the id of the row they were derived from - so they collide with each
 * other and with their sources on selection, inline edit and delete. It also disarmed the fix on
 * the duplicate path: nothing recorded those keys as injected there, so nothing could strip them.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../../utils/authenticatedFetch', () => ({
  authenticatedFetch: vi.fn(),
}));

import { useDataSourceCreation } from '../useDataSourceCreation';
import { normalizeRows } from '../../utils/dataTableUtils';
import { authenticatedFetch } from '../../utils/authenticatedFetch';

const mockFetch = authenticatedFetch as unknown as ReturnType<typeof vi.fn>;

const rows = normalizeRows(
  [
    { id: 57, data: { name: 'ada', embedding: '[-0.0095,-0.0216]' }, priority: 1, created_at: '2026-01-01T00:00:00Z' },
    { id: 58, data: { name: 'grace', embedding: '[0.0041,0.0777]' }, priority: 1, created_at: '2026-01-01T00:00:00Z' },
  ],
  { tenantId: 't', dataSourceId: 42 } as never,
);

const columns = [
  { col_id: 'data.name', field: 'data.name', header_name: 'Name', type: 'text' },
  { col_id: 'data.embedding', field: 'data.embedding', header_name: 'Embedding', type: 'vector' },
] as never;

const setup = (selectedRows: Set<string>, selectedColumns = new Set<string>()) =>
  renderHook(() =>
    useDataSourceCreation({
      dataSourceId: 42,
      displayRows: rows,
      columns,
      selectedRows,
      selectedColumns,
      getRowUniqueKey: (r) => String(r.id),
      clearColumnSelection: vi.fn(),
      setSelectedRows: vi.fn(),
      addToast: vi.fn(),
    }),
  );

beforeEach(() => {
  mockFetch.mockReset();
  mockFetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ id: 99 }) });
});

describe('useDataSourceCreation - what lands in the new table', () => {
  it('sends the user data only, not the identity the grid injected for display', async () => {
    // The rendered rows really do carry it: this is what the guard is for.
    expect(rows[0].data).toMatchObject({ _callId: 57, id: 57 });

    const { result } = setup(new Set(['57', '58']));

    await act(async () => {
      result.current.setNewDataSourceName('Copy of table');
      await result.current.createDataSourceFromSelection();
    });

    const body = JSON.parse(mockFetch.mock.calls[0][1].body);
    // The vector column goes too: the read path merges the whole embedding in as text.
    expect(body.data).toEqual([{ name: 'ada' }, { name: 'grace' }]);
    for (const rowData of body.data) {
      expect(rowData).not.toHaveProperty('_callId');
      expect(rowData).not.toHaveProperty('embedding');
    }
  });

  it('narrows from the writable data, so picking columns does not put the junk back', async () => {
    // This branch rebuilds the row from scratch, and reading `row.data` for it would restore
    // exactly what the other branch strips.
    const { result } = setup(new Set(['57']), new Set(['data.name', 'data.embedding']));

    await act(async () => {
      result.current.setNewDataSourceName('With the vector column picked');
      await result.current.createDataSourceFromSelection();
    });

    expect(JSON.parse(mockFetch.mock.calls[0][1].body).data).toEqual([{ name: 'ada' }]);
  });

  it('still narrows to the selected columns when there are any', async () => {
    // The narrowing itself must keep working: the strip is not allowed to widen the result.
    const { result } = setup(new Set(['57']), new Set(['data.name']));

    await act(async () => {
      result.current.setNewDataSourceName('Names only');
      await result.current.createDataSourceFromSelection();
    });

    const body = JSON.parse(mockFetch.mock.calls[0][1].body);
    expect(body.data).toEqual([{ name: 'ada' }]);
  });
});
