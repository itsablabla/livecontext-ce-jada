// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import { DataTableToolbar } from '../DataTableToolbar';
import type { DataTableController } from '../useDataTableController';

/**
 * Row/column selection actions float in the bottom-center SelectionActionBar in
 * every context. Standalone (/app/tables/{id}) the `absolute` pill anchors to
 * <main>; embedded (side panel / modal / builder inspector) it anchors to the
 * `relative` DataTable container so it stays inside the panel. The toolbar renders
 * the same bar either way - the anchoring is decided by the container, not here.
 */

const baseController = (over: Partial<DataTableController> = {}): DataTableController => ({
  searchQuery: '',
  setSearchQuery: vi.fn(),
  showColumnFilters: false,
  setShowColumnFilters: vi.fn(),
  selectedExportFormat: '',
  setSelectedExportFormat: vi.fn(),
  selectedRows: new Set<string>(['r1-0']),
  selectedColumns: new Set<string>(),
  handleExportCSV: vi.fn(),
  handleExportExcel: vi.fn(),
  handleExportFull: vi.fn(),
  handleExportJSON: vi.fn(),
  exportLoading: false,
  setShowAddColumnModal: vi.fn(),
  setShowCreateDataSourceModal: vi.fn(),
  deleteSelectedRows: vi.fn(),
  deleteSelectedColumns: vi.fn(),
  confirmDeleteColumns: vi.fn(),
  showDeleteColumnsModal: false,
  setShowDeleteColumnsModal: vi.fn(),
  columnsToDelete: [],
  clearSelection: vi.fn(),
  clearColumnSelection: vi.fn(),
  readOnly: false,
  ...over,
} as unknown as DataTableController);

type ToolbarProps = Omit<React.ComponentProps<typeof DataTableToolbar>, 'controller'>;

function renderToolbar(controller: DataTableController, props: ToolbarProps = {}) {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <DataTableToolbar controller={controller} {...props} />
    </NextIntlClientProvider>,
  );
}

afterEach(cleanup);

describe('DataTableToolbar - selection actions placement', () => {
  it('floats the actions in the SelectionActionBar with a "{count} selected" label', () => {
    renderToolbar(baseController());
    const bar = screen.getByTestId('selection-action-bar');
    expect(within(bar).getByText('1 selected')).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Create Table' })).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Delete Rows' })).toBeInTheDocument();
  });

  it('the bar × clears BOTH row and column selection', () => {
    const clearSelection = vi.fn();
    const clearColumnSelection = vi.fn();
    renderToolbar(baseController({ clearSelection, clearColumnSelection }));
    fireEvent.click(within(screen.getByTestId('selection-action-bar')).getByTestId('selection-action-bar-clear'));
    expect(clearSelection).toHaveBeenCalledTimes(1);
    expect(clearColumnSelection).toHaveBeenCalledTimes(1);
  });

  it('hides the delete-rows action in read-only mode', () => {
    renderToolbar(baseController({ readOnly: true }));
    const bar = screen.getByTestId('selection-action-bar');
    expect(within(bar).queryByRole('button', { name: 'Delete Rows' })).not.toBeInTheDocument();
    // Create Table is not a mutation of the open table, so it stays available.
    expect(within(bar).getByRole('button', { name: 'Create Table' })).toBeInTheDocument();
  });

  it('shows the delete-columns action for a column selection, and hides it in read-only mode', () => {
    // Editable: a column selection floats a "Delete Columns" action.
    const editable = renderToolbar(baseController({ selectedRows: new Set(), selectedColumns: new Set(['c1']) }));
    const editableBar = editable.getByTestId('selection-action-bar');
    expect(within(editableBar).getByRole('button', { name: 'Delete Columns' })).toBeInTheDocument();
    cleanup();

    // Read-only: same column selection, but the destructive action is gone.
    renderToolbar(baseController({ selectedRows: new Set(), selectedColumns: new Set(['c1']), readOnly: true }));
    const roBar = screen.getByTestId('selection-action-bar');
    expect(within(roBar).queryByRole('button', { name: 'Delete Columns' })).not.toBeInTheDocument();
    // Create Table is non-destructive, so it remains.
    expect(within(roBar).getByRole('button', { name: 'Create Table' })).toBeInTheDocument();
  });

  it('offers Duplicate for a row selection and runs it', () => {
    const duplicateSelectedRows = vi.fn();
    renderToolbar(baseController({ duplicateSelectedRows }));

    const bar = screen.getByTestId('selection-action-bar');
    fireEvent.click(within(bar).getByRole('button', { name: 'Duplicate' }));

    expect(duplicateSelectedRows).toHaveBeenCalledTimes(1);
  });

  it('disables Duplicate while the copies are being written, so one click is one copy each', () => {
    const duplicateSelectedRows = vi.fn();
    renderToolbar(baseController({ duplicateSelectedRows, isDuplicatingRows: true }));

    const button = within(screen.getByTestId('selection-action-bar')).getByRole('button', { name: 'Duplicate' });
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(duplicateSelectedRows).not.toHaveBeenCalled();
  });

  it('hides Duplicate wherever a row cannot be copied as a row', () => {
    // Read-only is a mutation gate. The other two are shape gates: in a nested view a "row" is one
    // element of a JSON array (a copy is a patch on its parent), and in a workflow view the rows
    // are step output, not table rows.
    renderToolbar(baseController({ readOnly: true }));
    expect(within(screen.getByTestId('selection-action-bar')).queryByRole('button', { name: 'Duplicate' })).not.toBeInTheDocument();
    cleanup();

    renderToolbar(baseController(), { jsonPath: 'items' });
    expect(within(screen.getByTestId('selection-action-bar')).queryByRole('button', { name: 'Duplicate' })).not.toBeInTheDocument();
    cleanup();

    renderToolbar(baseController(), { workflowContext: { workflowId: 'w1', runId: 'r1' } });
    expect(within(screen.getByTestId('selection-action-bar')).queryByRole('button', { name: 'Duplicate' })).not.toBeInTheDocument();
  });

  it('offers no Duplicate for a column-only selection', () => {
    // Duplicate copies ROWS. A column selection has nothing for it to copy.
    renderToolbar(baseController({ selectedRows: new Set(), selectedColumns: new Set(['c1']) }));

    expect(within(screen.getByTestId('selection-action-bar')).queryByRole('button', { name: 'Duplicate' })).not.toBeInTheDocument();
  });

  it('renders nothing selection-related when there is no selection', () => {
    renderToolbar(baseController({ selectedRows: new Set(), selectedColumns: new Set() }));
    expect(screen.queryByTestId('selection-action-bar')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create Table' })).not.toBeInTheDocument();
  });
});
