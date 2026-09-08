// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi, beforeEach } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

// Toggle the PLAN verdict per test. The tile used to be gated by the build-time edition constant;
// since 2026-09-03 it is gated by the account's plan, and this hook is the seam that answers it.
// Mocking the hook rather than the network keeps the test about the tile.
let vectorLock: { locked: boolean; requiredPlan: string | null } = { locked: false, requiredPlan: null };
vi.mock('@/hooks/useVectorFeatureLock', () => ({
  useVectorFeatureLock: () => vectorLock,
  VECTOR_FEATURE_KEY: 'feature:vector_search',
}));
// The modal only needs orchestratorApi from the api barrel; stub it so nothing hits the network.
vi.mock('@/lib/api', () => ({
  orchestratorApi: { createDataSource: vi.fn(), createColumn: vi.fn() },
}));

import { CreateDataSourceModal } from '../CreateDataSourceModal';
import { orchestratorApi } from '@/lib/api';

const createDataSourceMock = orchestratorApi.createDataSource as ReturnType<typeof vi.fn>;
const createColumnMock = orchestratorApi.createColumn as ReturnType<typeof vi.fn>;

afterEach(() => cleanup());

function renderModal() {
  render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <CreateDataSourceModal onClose={() => {}} onDataSourceCreated={() => {}} />
    </NextIntlClientProvider>,
  );
}

/** Step 1 (name → Next) then open the inline column-type picker on step 2. */
function openColumnPicker() {
  fireEvent.change(screen.getByPlaceholderText('Enter table name'), {
    target: { value: 'My Table' },
  });
  fireEvent.click(screen.getByRole('button', { name: /next/i }));
  fireEvent.click(screen.getByRole('button', { name: /add column/i }));
}

function vectorTile(): HTMLButtonElement {
  return screen.getByText('Embedding vector').closest('button') as HTMLButtonElement;
}

describe('CreateDataSourceModal - the vector column is a plan capability', () => {
  it('a plan that does not include vectors: the tile is disabled and NAMES the plan that would', () => {
    vectorLock = { locked: true, requiredPlan: 'PRO' };
    renderModal();
    openColumnPicker();

    const tile = vectorTile();
    expect(tile).toBeDisabled();
    // The plan code itself is the badge: "PRO" tells the reader what to do, "CE only" told a
    // cloud customer only that they could not have it.
    expect(within(tile).getByText('PRO')).toBeInTheDocument();
    expect(tile).toHaveAttribute(
      'title',
      'Available from the PRO plan. Upgrade to use embedding columns.',
    );
  });

  it('a plan that includes vectors: the tile is selectable with no marker', () => {
    vectorLock = { locked: false, requiredPlan: null };
    renderModal();
    openColumnPicker();

    const tile = vectorTile();
    expect(tile).not.toBeDisabled();
    expect(within(tile).queryByText('PRO')).not.toBeInTheDocument();
  });

  it('the tile is shown either way, so the capability stays discoverable', () => {
    // Hiding it would be tidier and would cost every locked account the knowledge that the
    // feature exists at all, which is the reason an upsell is a marker and not a deletion.
    vectorLock = { locked: true, requiredPlan: 'PRO' };
    renderModal();
    openColumnPicker();

    expect(screen.getByText('Embedding vector')).toBeInTheDocument();
  });

  it('a non-gated tile (Rich text) stays selectable while vectors are locked', () => {
    vectorLock = { locked: true, requiredPlan: 'PRO' };
    renderModal();
    openColumnPicker();

    const textTile = screen.getByText('Rich text').closest('button') as HTMLButtonElement;
    expect(textTile).not.toBeDisabled();
    expect(within(textTile).queryByText('PRO')).not.toBeInTheDocument();
  });
});

describe('CreateDataSourceModal - a vector column added at table-creation time carries its display contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    createDataSourceMock.mockResolvedValue({ id: 123 });
    createColumnMock.mockResolvedValue({});
  });

  /**
   * Step1 name → Next → open picker → name the column, pick the type tile by its
   * visible label, then confirm it into the staged list.
   */
  function stageColumn(tileLabel: string, colName: string) {
    fireEvent.change(screen.getByPlaceholderText('Enter table name'), {
      target: { value: 'Docs' },
    });
    fireEvent.click(screen.getByRole('button', { name: /next/i }));
    // Open the inline column adder (toggle button).
    fireEvent.click(screen.getByRole('button', { name: /add column/i }));
    // Name the column, then choose the type tile.
    fireEvent.change(screen.getByPlaceholderText('Enter column name'), {
      target: { value: colName },
    });
    fireEvent.click(screen.getByText(tileLabel).closest('button') as HTMLButtonElement);
    // Confirm the column into the staged list (the inline adder's "Add Column").
    fireEvent.click(screen.getByRole('button', { name: /add column/i }));
  }

  it('a vector column sends display={dimension,metric,label} under "display" (NOT displayConfig) so the backend accepts it', async () => {
    vectorLock = { locked: false, requiredPlan: null };
    renderModal();
    stageColumn('Embedding vector', 'embedding');

    // Submit the table.
    fireEvent.click(screen.getByRole('button', { name: /create table/i }));

    await waitFor(() => expect(createColumnMock).toHaveBeenCalledTimes(1));

    const [dsId, payload] = createColumnMock.mock.calls[0];
    expect(dsId).toBe(123);
    expect(payload).toMatchObject({
      name: 'embedding',
      type: 'vector',
      // The vector preset's defaults must reach the backend or validateVectorDimension rejects it.
      display: { dimension: 1536, metric: 'cosine', label: 'embedding' },
    });
    // Regression guard: the old payload used the wrong key, which the backend ignores.
    expect(payload).not.toHaveProperty('displayConfig');
    expect(payload.display).not.toEqual({});
  });

  it('a select column (no inline edit) still ships the preset default display.options - backend rejects an empty options list', async () => {
    // Same bug class: select/multi_select require display.options; the old wrong
    // key dropped them, so a select column created at table-creation time was
    // silently discarded too. The preset defaults must reach the backend.
    vectorLock = { locked: true, requiredPlan: 'PRO' };
    renderModal();
    stageColumn('Select', 'status');

    fireEvent.click(screen.getByRole('button', { name: /create table/i }));

    await waitFor(() => expect(createColumnMock).toHaveBeenCalledTimes(1));

    const [, payload] = createColumnMock.mock.calls[0];
    expect(payload.type).toBe('select');
    expect(payload).not.toHaveProperty('displayConfig');
    expect(Array.isArray(payload.display?.options)).toBe(true);
    expect(payload.display.options.length).toBeGreaterThan(0);
  });
});
