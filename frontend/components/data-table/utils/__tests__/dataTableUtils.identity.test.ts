/**
 * The identity the grid injects into row data, and taking it back out.
 *
 * `normalizeRow` writes `_callId` (and `id`, when the row carried none) INTO every row's data so
 * the grid can display them. Anything that writes a row's data back to the server has to undo that
 * first: `extractCallId` reads `_callId` back as the row's identity, so a row PERSISTED with one
 * reports the id of the row it was derived from, and then shares that row's identity everywhere
 * the grid keys on `row.id` - selection, inline edit, delete, and the "here is your copy" cue.
 */
import { describe, it, expect } from 'vitest';
import {
  extractCallId,
  normalizeRow,
  normalizeRows,
  stripInjectedIdentity,
  toWritableRowData,
} from '../dataTableUtils';
import type { DataSourceItemRow } from '../../types';

const ctx = { tenantId: 't', dataSourceId: 42 } as never;

const serverRow = (id: number, data: Record<string, unknown>) => ({
  id, data, priority: 1, created_at: '2026-01-01T00:00:00Z',
});

describe('normalizeRow - the identity it injects', () => {
  it('writes both keys into a plain row, and records that it did', () => {
    const row = normalizeRow(serverRow(57, { name: 'ada' }), ctx);

    expect(row.data).toEqual({ name: 'ada', id: 57, _callId: 57 });
    expect(row._injectedDataKeys).toEqual(['id', '_callId']);
  });

  it('records only `_callId` when the row brought its own id column', () => {
    const row = normalizeRow(serverRow(58, { id: 'CUST-42', name: 'ada' }), ctx);

    expect(row.data.id).toBe('CUST-42');
    expect(row._injectedDataKeys).toEqual(['_callId']);
  });

  it('records nothing when the data already carried a callId', () => {
    // This is the shape of a table built from another table's rows before the copy was cleaned up.
    const row = normalizeRow(serverRow(59, { name: 'ada', id: 57, _callId: 57 }), ctx);

    expect(row._injectedDataKeys).toEqual([]);
    // ... and the row now reports the id it inherited, not its own. That is the bug this whole
    // mechanism exists to stop from spreading.
    expect(row.id).toBe(57);
  });
});

describe('stripInjectedIdentity', () => {
  it('gives back the user data alone for a normally rendered row', () => {
    const [row] = normalizeRows([serverRow(57, { name: 'ada' })], ctx);

    expect(stripInjectedIdentity(row)).toEqual({ name: 'ada' });
  });

  it('keeps a user `id` column, including when its value equals the row id', () => {
    // A value comparison cannot tell this apart from an injected id; the recorded keys can.
    const [own] = normalizeRows([serverRow(7, { id: 7, name: 'ada' })], ctx);

    expect(stripInjectedIdentity(own)).toEqual({ id: 7, name: 'ada' });
  });

  it('removes a `_callId` no normalizer recorded, because none is ever legitimate data', () => {
    // Rows of a table built before this existed carry one as REAL stored data: nothing recorded it,
    // so only an unconditional removal keeps it from surviving every future copy of that row.
    const [legacy] = normalizeRows([serverRow(59, { name: 'ada', _callId: 57 })], ctx);

    // The normalizer found a `_callId` already there, so it did not write one and did not record
    // one - it only added the display `id` (taken FROM that inherited callId).
    expect(legacy._injectedDataKeys).toEqual(['id']);
    expect(stripInjectedIdentity(legacy)).toEqual({ name: 'ada' });
  });

  it('leaves a row that never went through the normalizer alone', () => {
    const raw = { id: 3, data: { name: 'ada' } } as unknown as DataSourceItemRow;

    expect(stripInjectedIdentity(raw)).toEqual({ name: 'ada' });
  });

  it('copies rather than mutates, so the rendered row keeps displaying its id', () => {
    const [row] = normalizeRows([serverRow(57, { name: 'ada' })], ctx);

    stripInjectedIdentity(row);

    expect(row.data).toEqual({ name: 'ada', id: 57, _callId: 57 });
    expect(extractCallId(row.data, row.id)).toBe(57);
  });

  it('survives a row with no data at all', () => {
    expect(stripInjectedIdentity({ id: 1 } as unknown as DataSourceItemRow)).toEqual({});
  });
});

describe('toWritableRowData - what a copy is allowed to persist', () => {
  const columns = [
    { field: 'data.name', type: 'text' },
    { field: 'data.embedding', type: 'vector' },
  ];

  it('leaves a vector column out entirely', () => {
    // The server's "vector preview" is the WHOLE embedding as text merged into the row's data, so
    // a copy that keeps it writes ~18 KB of numbers into JSONB - invisible, since the vector cell
    // renders vec(N) whatever the value is, and inherited by every later copy of that row.
    const [row] = normalizeRows(
      [serverRow(57, { name: 'ada', embedding: '[-0.009573,-0.021607,0.029964]' })],
      ctx,
    );

    expect(toWritableRowData(row, columns)).toEqual({ name: 'ada' });
  });

  it('still removes the injected identity', () => {
    const [row] = normalizeRows([serverRow(57, { name: 'ada' })], ctx);

    expect(toWritableRowData(row, columns)).toEqual({ name: 'ada' });
  });

  it('keeps every ordinary column, whatever its type', () => {
    const [row] = normalizeRows([serverRow(57, { name: 'ada', score: 3, done: true })], ctx);

    expect(toWritableRowData(row, [
      { field: 'data.name', type: 'text' },
      { field: 'data.score', type: 'rating' },
      { field: 'data.done', type: 'checkbox' },
    ])).toEqual({ name: 'ada', score: 3, done: true });
  });

  it('is the plain identity strip when the caller has no columns to go on', () => {
    const [row] = normalizeRows([serverRow(57, { name: 'ada', embedding: '[0.1]' })], ctx);

    expect(toWritableRowData(row)).toEqual({ name: 'ada', embedding: '[0.1]' });
  });

  it('reads a column field with no `data.` prefix too', () => {
    const [row] = normalizeRows([serverRow(57, { name: 'ada', embedding: '[0.1]' })], ctx);

    expect(toWritableRowData(row, [{ field: 'embedding', type: 'vector' }])).toEqual({ name: 'ada' });
  });
});
