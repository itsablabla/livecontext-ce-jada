// @vitest-environment jsdom
/**
 * The grid points at the column the user just created.
 *
 * Adding a column refetches the whole table, so the new column simply appears - appended to the
 * right of a table that is usually wider than the viewport. Nothing distinguished it from the
 * columns that were already there, and the success toast named it without showing it. The hook
 * publishes `revealedColumnField` for a short window; the grid scrolls to that column and plays
 * the reveal animation on it.
 *
 * What these tests pin: the field the hook publishes actually addresses the new column, the flag
 * comes back off by itself, and nothing is flagged when no column was created.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../../utils/authenticatedFetch', () => ({
  authenticatedFetch: vi.fn(),
}));

import { useColumnOperations } from '../useColumnOperations';
import { TABLE_REVEAL_WINDOW_MS } from '../../tableStyles';
import { authenticatedFetch } from '../../utils/authenticatedFetch';
import type { ColumnDefinition, PaginationState } from '../../types';

const mockFetch = authenticatedFetch as unknown as ReturnType<typeof vi.fn>;

const pagination: PaginationState = {
  currentPage: 1,
  pageSize: 20,
  totalItems: 0,
  totalPages: 1,
  nextCursor: null,
  hasMore: false,
};

const existingColumn: ColumnDefinition = {
  col_id: 'data.Owner',
  field: 'data.Owner',
  header_name: 'Owner',
  type: 'text',
} as ColumnDefinition;

const addToast = vi.fn();

const setup = (columns: ColumnDefinition[] = [existingColumn]) =>
  renderHook(() =>
    useColumnOperations({
      dataSourceId: 42,
      jsonPath: undefined,
      columns,
      rows: [],
      fetchColumns: vi.fn().mockResolvedValue(undefined),
      fetchData: vi.fn().mockResolvedValue(undefined),
      pagination,
      clearColumnSelection: vi.fn(),
      addToast,
    }),
  );

/** Type a name and run the add, letting every await inside it settle. */
async function addColumnNamed(result: { current: ReturnType<typeof useColumnOperations> }, name: string) {
  act(() => result.current.setNewColumnName(name));
  await act(async () => {
    await result.current.addNewColumn();
  });
}

beforeEach(() => {
  mockFetch.mockReset();
  mockFetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ success: true }) });
  addToast.mockReset();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useColumnOperations - revealing the column that was just added', () => {
  it('publishes the field that addresses the new column, not its label', async () => {
    // The backend keys mapping_spec by the column NAME verbatim ("data." + name), and the grid
    // matches on `col.field`. A reveal keyed on anything else silently highlights nothing.
    const { result } = setup();

    await addColumnNamed(result, 'Budget');

    expect(result.current.revealedColumnField).toBe('data.Budget');
  });

  it('stops pointing at it once the window closes', async () => {
    // The flag has to come off: a row mounting later (an infinite-scroll page, a row the user
    // adds) would otherwise animate its cell in that column long after the column stopped
    // being new.
    vi.useFakeTimers();
    const { result } = setup();

    await addColumnNamed(result, 'Budget');
    expect(result.current.revealedColumnField).toBe('data.Budget');

    act(() => {
      vi.advanceTimersByTime(TABLE_REVEAL_WINDOW_MS);
    });

    expect(result.current.revealedColumnField).toBeNull();
  });

  it('moves the cue to the second column when two are added inside one window', async () => {
    // The first column's timer must not fire while the second is being pointed at, or the cue
    // dies early on the column the user is actually looking for.
    vi.useFakeTimers();
    const { result } = setup();

    await addColumnNamed(result, 'First');
    act(() => {
      vi.advanceTimersByTime(TABLE_REVEAL_WINDOW_MS / 2);
    });
    await addColumnNamed(result, 'Second');

    act(() => {
      vi.advanceTimersByTime(TABLE_REVEAL_WINDOW_MS / 2 + 10);
    });
    // Half the window after the second add: the first column's timer has come and gone, and it
    // must not have taken the second column's cue with it.
    expect(result.current.revealedColumnField).toBe('data.Second');

    act(() => {
      vi.advanceTimersByTime(TABLE_REVEAL_WINDOW_MS);
    });
    expect(result.current.revealedColumnField).toBeNull();
  });

  it('points at nothing when the backend refuses the column', async () => {
    mockFetch.mockResolvedValue({ ok: false, status: 400, json: async () => ({ success: false }) });
    const { result } = setup();

    await addColumnNamed(result, 'Budget');

    expect(result.current.revealedColumnField).toBeNull();
    expect(addToast).toHaveBeenCalledWith(expect.objectContaining({ type: 'error' }));
  });

  it('points at nothing when the name collides with an existing column', async () => {
    const { result } = setup();

    await addColumnNamed(result, 'owner');

    expect(mockFetch).not.toHaveBeenCalled();
    expect(result.current.revealedColumnField).toBeNull();
  });
});
