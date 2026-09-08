// @vitest-environment jsdom
/**
 * The grid flags what just appeared - the column the user created (header AND cells) and the rows
 * a duplicate produced - and nothing else.
 *
 * A data column reaches the DOM through several different `<td>` branches depending on the type
 * the user picked (the visual cell for a rating, `EditableCell` for plain text). The reveal class
 * has to ride along with each of them, so a test that only covers one branch would pass while a
 * whole family of new columns lands with no cue at all.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { DataTableGrid } from '../DataTableGrid';
import { COLUMN_REVEAL_CELL_CLASS, COLUMN_REVEAL_HEAD_CLASS, ROW_REVEAL_CLASS } from '../tableStyles';
import { normalizeRows } from '../utils/dataTableUtils';
import type { DataTableController } from '../useDataTableController';

const columns = [
  { col_id: 'data.Owner', field: 'data.Owner', header_name: 'Owner', type: 'text' },
  { col_id: 'data.Score', field: 'data.Score', header_name: 'Score', type: 'rating' },
];

// Through the real normalizer, like the rows the grid actually holds: a hand-built row omits the
// display identity `normalizeRow` injects, and a fixture that does not match production is what
// let the duplicate identity bug through in the sibling suite.
const rows = normalizeRows(
  [
    { id: 1, priority: 1, created_at: '2026-01-01T00:00:00Z', data: { Owner: 'ada', Score: 3 } },
    { id: 2, priority: 1, created_at: '2026-01-01T00:00:00Z', data: { Owner: 'grace', Score: 5 } },
  ],
  { tenantId: 't', dataSourceId: 42 } as never,
);

/**
 * The grid reads a controller, never a store: a plain object is the whole dependency. Read-only on
 * purpose - it drops the add-row form and the "+" header, which have nothing to do with the cue.
 */
function controllerWith(
  revealedColumnField: string | null,
  revealedRowIds: ReadonlySet<number> = new Set(),
): DataTableController {
  return {
    rows,
    displayRows: rows,
    columns,
    getUniqueColumns: () => columns,
    getDynamicColumns: () => columns,
    getFieldPath: (field: string) => (field.startsWith('data.') ? field.slice(5) : field),
    makeCellKey: (rowId: unknown, field: string) => `${rowId}-${field}`,
    getRowUniqueKey: (row: { id: number }) => String(row.id),
    selectedColumns: new Set<string>(),
    selectedRows: new Set<string>(),
    progressTempValues: new Map(),
    viewConfig: { showCheckbox: false },
    sortConfig: null,
    editingCellKey: null,
    hoveredCell: null,
    draggedColumn: null,
    dragOverColumn: null,
    dragPosition: null,
    tableLoading: false,
    loadingColumns: false,
    isAddingRow: false,
    isAddingRowInline: false,
    newRowPriority: 1,
    newRowData: {},
    readOnly: true,
    revealedColumnField,
    revealedRowIds,
    pagination: { currentPage: 1, pageSize: 20, totalItems: 1, totalPages: 1, nextCursor: null, hasMore: false },
    handleSort: vi.fn(),
    handleDragStart: vi.fn(),
    handleDragOver: vi.fn(),
    handleDragLeave: vi.fn(),
    handleDrop: vi.fn(),
    handleSaveEdit: vi.fn(),
    handleRowDataChange: vi.fn(),
    toggleRowSelection: vi.fn(),
    toggleColumnSelection: vi.fn(),
    selectAllRows: vi.fn(),
    clearSelection: vi.fn(),
    setSelectedRows: vi.fn(),
    setHoveredCell: vi.fn(),
    setEditingCellKey: vi.fn(),
    setProgressTempValues: vi.fn(),
    setShowAddColumnModal: vi.fn(),
    startAddingRowInline: vi.fn(),
    cancelAddingRowInline: vi.fn(),
    addNewRow: vi.fn(),
    setNewRowPriority: vi.fn(),
    openEditColumn: vi.fn(),
    loadMore: vi.fn(),
  } as unknown as DataTableController;
}

function renderGrid(revealedColumnField: string | null, revealedRowIds?: ReadonlySet<number>) {
  const { container } = render(
    <DataTableGrid
      controller={controllerWith(revealedColumnField, revealedRowIds)}
      dataSourceId={42}
      navigateTo={vi.fn()}
      dataSourceBasePath="/app/tables"
    />,
  );
  return container;
}

const headerFor = (container: HTMLElement, name: string) =>
  [...container.querySelectorAll('th')].find(th => th.textContent?.includes(name));

const cellsIn = (container: HTMLElement) => [...container.querySelectorAll('tbody td')];

/**
 * jsdom implements neither of these, and the grid optional-calls both - so without stubs the scroll
 * is a silent no-op and no test could tell the difference between "scrolled" and "never ran".
 */
let scrollIntoView: ReturnType<typeof vi.fn>;
let reduceMotion = false;

beforeEach(() => {
  scrollIntoView = vi.fn();
  (window.HTMLElement.prototype as unknown as { scrollIntoView: unknown }).scrollIntoView = scrollIntoView;
  window.matchMedia = ((query: string) => ({
    matches: query.includes('prefers-reduced-motion') && reduceMotion,
    media: query, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(), onchange: null,
  })) as unknown as typeof window.matchMedia;
});

afterEach(() => {
  cleanup();
  reduceMotion = false;
});

describe('DataTableGrid - the new column is pointed at', () => {
  it('flags the header of the revealed column only', () => {
    const container = renderGrid('data.Score');

    expect(headerFor(container, 'Score')).toHaveClass(COLUMN_REVEAL_HEAD_CLASS);
    expect(headerFor(container, 'Owner')).not.toHaveClass(COLUMN_REVEAL_HEAD_CLASS);
  });

  it('marks that header for the scroll, so an off-screen column is brought into view', () => {
    const container = renderGrid('data.Score');

    // The grid finds the column to scroll to by this attribute, not by the class.
    expect(container.querySelectorAll('th[data-column-reveal="true"]')).toHaveLength(1);
    expect(headerFor(container, 'Score')).toHaveAttribute('data-column-reveal', 'true');
  });

  it('washes the cells of a visual-cell column (rating renders its own td)', () => {
    const container = renderGrid('data.Score');

    const flagged = cellsIn(container).filter(td => td.classList.contains(COLUMN_REVEAL_CELL_CLASS));
    expect(flagged).toHaveLength(rows.length);
  });

  it('washes the cells of a plain text column too (EditableCell renders that td)', () => {
    // Different branch, same column contract: a text column is the default a user gets.
    const container = renderGrid('data.Owner');

    const flagged = cellsIn(container).filter(td => td.classList.contains(COLUMN_REVEAL_CELL_CLASS));
    expect(flagged).toHaveLength(rows.length);
    expect(flagged[0].textContent).toContain('ada');
  });

  it('flags nothing at all when no column was just added', () => {
    const container = renderGrid(null);

    expect(container.querySelector('[data-column-reveal]')).toBeNull();
    expect(container.querySelectorAll(`.${COLUMN_REVEAL_HEAD_CLASS}`)).toHaveLength(0);
    expect(container.querySelectorAll(`.${COLUMN_REVEAL_CELL_CLASS}`)).toHaveLength(0);
  });

  it('scrolls the new column into view, since the table appends it off the right edge', () => {
    renderGrid('data.Score');

    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    // Centred, not `nearest`: the "+" header is sticky at the right edge, so a minimal scroll can
    // stop with the new column tucked underneath it.
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest', inline: 'center', behavior: 'smooth' });
  });

  it('jumps there without animating for a user who asked for less motion', () => {
    // The viewport jump is the strongest motion in this cue, and the stylesheet already drops the
    // beat and the fade for these users - an animated scroll would put the movement straight back.
    reduceMotion = true;
    renderGrid('data.Score');

    expect(scrollIntoView).toHaveBeenCalledWith(expect.objectContaining({ behavior: 'auto' }));
  });

  it('scrolls nowhere when no column was just added', () => {
    renderGrid(null);

    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('flags the row a duplicate just produced, by id and not by position', () => {
    // The second row here is the one named, and it is not the first on screen: a cue keyed on
    // position would light up the wrong copy as soon as the table is sorted.
    const container = renderGrid(null, new Set([2]));

    const flagged = [...container.querySelectorAll('tbody tr')].filter(tr =>
      tr.classList.contains(ROW_REVEAL_CLASS),
    );
    expect(flagged).toHaveLength(1);
    expect(flagged[0].textContent).toContain('grace');
  });

  it('flags every copy when a duplicate produced several', () => {
    const container = renderGrid(null, new Set([1, 2]));

    expect(container.querySelectorAll(`tbody tr.${ROW_REVEAL_CLASS}`)).toHaveLength(2);
  });

  it('flags no row when nothing was duplicated', () => {
    const container = renderGrid(null);

    expect(container.querySelectorAll(`tbody tr.${ROW_REVEAL_CLASS}`)).toHaveLength(0);
  });
});
