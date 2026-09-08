/**
 * A row's identity is FILLED IN when missing, never substituted for a value that is there.
 *
 * `0` and `''` are ids a CRUD or agent step really returns. The two normalizers used to test
 * truthiness, so such an id was replaced by the synthetic row id AND recorded in
 * `_injectedDataKeys` - which means `stripInjectedIdentity` then removed the key on the way out:
 * the value was destroyed, not merely hidden, and every copy of the row inherited the loss.
 *
 * `normalizeRow` is the normalizer for the BACKEND nested routes (the tables page's
 * `/items/nested`, the workflow `stepId` route, aggregated steps); `withDisplayIdentity` serves the
 * client-side expansion. Both are covered here because both receive the navigated item's own data.
 */
import { describe, it, expect } from 'vitest';

import {
  displayIdOf,
  normalizeRow,
  stripInjectedIdentity,
  withDisplayIdentity,
} from '../dataTableUtils';
import type { DataSourceItemRow } from '../../types';

const ctx = { tenantId: 't', dataSourceId: 42, jsonPath: 'output.rows' };

describe('normalizeRow - identity is filled in, not substituted', () => {
  it.each([
    ['zero', 0],
    ['an empty string', ''],
  ])('keeps %s as the item id', (_label, id) => {
    const result = normalizeRow(
      { id: 3, data: { id, email: 'a@b.c' } },
      ctx as never,
      { isWorkflowStep: true },
    );

    expect(result.data.id).toBe(id);
    expect(result._injectedDataKeys).not.toContain('id');
    // Nothing was injected, so a write-back keeps the item's own id.
    expect(stripInjectedIdentity(result).id).toBe(id);
    expect(displayIdOf(result)).toBe(id);
  });

  it('fills in a MISSING id and declares it, so a writer can take it back out', () => {
    const result = normalizeRow(
      { id: 3, data: { email: 'a@b.c' } },
      ctx as never,
      { isWorkflowStep: true },
    );

    expect(result.data.id).toBe(3);
    expect(result._injectedDataKeys).toContain('id');
    expect(stripInjectedIdentity(result)).not.toHaveProperty('id');
  });

  it('treats an explicit null as missing', () => {
    const result = normalizeRow(
      { id: 3, data: { id: null, email: 'a@b.c' } },
      ctx as never,
      { isWorkflowStep: true },
    );

    expect(result.data.id).toBe(3);
    expect(result._injectedDataKeys).toContain('id');
  });
});

describe('withDisplayIdentity - same rule on the client-side expansion', () => {
  it.each([
    ['zero', 0],
    ['an empty string', ''],
  ])('keeps %s and injects nothing', (_label, id) => {
    const { data, injectedDataKeys } = withDisplayIdentity({ id, a: 1 }, 99);

    expect(data.id).toBe(id);
    expect(injectedDataKeys).toEqual([]);
  });

  it.each([
    ['undefined', undefined],
    ['null', null],
  ])('fills in %s and declares it', (_label, id) => {
    const { data, injectedDataKeys } = withDisplayIdentity({ id, a: 1 } as Record<string, unknown>, 99);

    expect(data.id).toBe(99);
    expect(injectedDataKeys).toEqual(['id']);
  });
});

describe('displayIdOf - an INJECTED id is not the item\'s own', () => {
  it('reports the row id, not the key the read path wrote, even when they differ', () => {
    // The `!injected` guard is what tells "we wrote this id" from "the item owns it". A value
    // comparison cannot: an item may legitimately hold an id equal to the row's.
    const injectedRow = {
      id: 99,
      data_source_id: 0,
      tenant_id: 't',
      data: { id: 7, name: 'x' },
      priority: 0,
      created_at: '2026-01-01T00:00:00Z',
      updated_at: null,
      _jsonPath: 'output.rows',
      _injectedDataKeys: ['id'],
    } as unknown as DataSourceItemRow;

    expect(displayIdOf(injectedRow)).toBe(99);
    // Same row without the record: the field is then the item's own and wins.
    expect(displayIdOf({ ...injectedRow, _injectedDataKeys: [] } as DataSourceItemRow)).toBe(7);
  });
});

describe('displayIdOf - a non-scalar id can never reach the cell', () => {
  const rowWith = (id: unknown): DataSourceItemRow => ({
    id: 99,
    data_source_id: 0,
    tenant_id: 't',
    data: { id },
    priority: 0,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: null,
    _jsonPath: 'output.rows',
  } as DataSourceItemRow);

  it.each([
    ['an object', { $oid: 'deadbeef' }],
    ['an array', [1, 2]],
  ])('falls back to the row id for %s (React cannot render it)', (_label, id) => {
    expect(displayIdOf(rowWith(id))).toBe(99);
  });
});
