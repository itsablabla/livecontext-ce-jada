'use client';

import { useCallback, useState } from 'react';
import type { ColumnDefinition, DataSourceItemRow } from '../types';
import { authenticatedFetch } from '../utils/authenticatedFetch';
import { toWritableRowData } from '../utils/dataTableUtils';

export interface UseDataSourceCreationParams {
  dataSourceId?: number;
  displayRows: DataSourceItemRow[];
  /** Column definitions, so the new table does not inherit the read path's vector text. */
  columns?: ColumnDefinition[];
  selectedRows: Set<string>;
  selectedColumns: Set<string>;
  getRowUniqueKey: (row: DataSourceItemRow) => string;
  clearColumnSelection: () => void;
  setSelectedRows: React.Dispatch<React.SetStateAction<Set<string>>>;
  addToast: (toast: { type: 'error' | 'success' | 'warning' | 'info'; title: string; message: string }) => void;
}

export interface UseDataSourceCreationReturn {
  // State
  showCreateDataSourceModal: boolean;
  newDataSourceName: string;
  newDataSourceDescription: string;
  isCreatingDataSource: boolean;

  // Setters
  setShowCreateDataSourceModal: React.Dispatch<React.SetStateAction<boolean>>;
  setNewDataSourceName: React.Dispatch<React.SetStateAction<string>>;
  setNewDataSourceDescription: React.Dispatch<React.SetStateAction<string>>;

  // Actions
  createDataSourceFromSelection: () => Promise<void>;
}

/**
 * Hook for creating a new DataSource from selected rows/columns.
 */
export function useDataSourceCreation({
  displayRows,
  columns,
  selectedRows,
  selectedColumns,
  getRowUniqueKey,
  clearColumnSelection,
  setSelectedRows,
  addToast,
}: UseDataSourceCreationParams): UseDataSourceCreationReturn {
  const [showCreateDataSourceModal, setShowCreateDataSourceModal] = useState(false);
  const [newDataSourceName, setNewDataSourceName] = useState('');
  const [newDataSourceDescription, setNewDataSourceDescription] = useState('');
  const [isCreatingDataSource, setIsCreatingDataSource] = useState(false);

  /**
   * Create a new DataSource from the current selection
   */
  const createDataSourceFromSelection = useCallback(async () => {
    if (selectedRows.size === 0 && selectedColumns.size === 0) return;

    try {
      setIsCreatingDataSource(true);

      // Filter out parent/group rows - keep only normal data rows
      const normalRows = displayRows.filter((item): item is DataSourceItemRow =>
        !('type' in item && ((item as any).type === 'parent' || (item as any).type === 'group'))
      );

      // Get selected rows or all rows if only columns are selected
      const selectedData = selectedRows.size > 0
        ? normalRows.filter(row => selectedRows.has(getRowUniqueKey(row)))
        : normalRows;

      // Extract data from rows, minus everything the READ path added. Sending `row.data` verbatim
      // persisted `_callId`/`id` into the NEW table, where they became real columns and real values
      // - so every row of that table reported the id of the row it was derived from, and anything
      // reading identity back out of it (selection, inline edit, delete, duplicating a row) acted
      // on the wrong row. It also carried each vector column's whole embedding across as text.
      const writableData = selectedData.map(row => toWritableRowData(row, columns));

      // If columns are selected, narrow to those columns - FROM the writable data, never from
      // `row.data`: rebuilding the row out of the raw one here would put the identity and the
      // vector text straight back, which is the same defect the line above exists to fix.
      let filteredData = writableData;
      if (selectedColumns.size > 0) {
        filteredData = writableData.map(writable => {
          const filteredRowData: Record<string, any> = {};
          selectedColumns.forEach(columnField => {
            const cleanField = columnField.startsWith('data.')
              ? columnField.replace('data.', '')
              : columnField;
            if (writable[cleanField] !== undefined) {
              filteredRowData[cleanField] = writable[cleanField];
            }
          });
          return filteredRowData;
        });
      }

      // Build mapping spec from selected columns
      let mappingSpec: Record<string, string> = {};
      if (selectedColumns.size > 0) {
        selectedColumns.forEach(columnField => {
          const cleanField = columnField.startsWith('data.')
            ? columnField.replace('data.', '')
            : columnField;
          mappingSpec[cleanField] = `data.${cleanField}`;
        });
      }

      const dataSourceConfig = {
        name: newDataSourceName,
        description: newDataSourceDescription,
        sourceConfig: {},
        data: filteredData,
        createdBy: 'user',
        mappingSpec,
      };

      const response = await authenticatedFetch('/api/proxy/data-sources', {
        method: 'POST',
        body: JSON.stringify(dataSourceConfig),
      });

      if (!response.ok) {
        throw new Error('Failed to create data source from selection');
      }

      const createdName = newDataSourceName;

      // Reset form and selections
      setNewDataSourceName('');
      setNewDataSourceDescription('');
      setShowCreateDataSourceModal(false);
      setSelectedRows(new Set());
      clearColumnSelection();

      addToast({
        type: 'success',
        title: 'DataSource Created Successfully',
        message: `DataSource "${createdName}" has been created`,
      });
    } catch (err) {
      console.error('Error creating data source:', err);
      addToast({
        type: 'error',
        title: 'Error Creating DataSource',
        message: 'Failed to create data source from selection',
      });
    } finally {
      setIsCreatingDataSource(false);
    }
  }, [
    displayRows,
    columns,
    selectedRows,
    selectedColumns,
    getRowUniqueKey,
    newDataSourceName,
    newDataSourceDescription,
    clearColumnSelection,
    setSelectedRows,
    addToast,
  ]);

  return {
    // State
    showCreateDataSourceModal,
    newDataSourceName,
    newDataSourceDescription,
    isCreatingDataSource,

    // Setters
    setShowCreateDataSourceModal,
    setNewDataSourceName,
    setNewDataSourceDescription,

    // Actions
    createDataSourceFromSelection,
  };
}
