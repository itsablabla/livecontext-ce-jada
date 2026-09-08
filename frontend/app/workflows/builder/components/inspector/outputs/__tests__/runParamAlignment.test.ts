import { describe, it, expect } from 'vitest';
import {
  buildParamAlignment,
  flattenPlannedParams,
  mergeResolvedAliases,
  normalizeParamKey,
  NOT_ECHOED_BY_DESIGN,
} from '../runParamAlignment';

describe('normalizeParamKey', () => {
  it('folds the differences a rename preserves: case and separators', () => {
    expect(normalizeParamKey('timeoutSeconds')).toBe(normalizeParamKey('timeout_seconds'));
    expect(normalizeParamKey('body-type')).toBe(normalizeParamKey('bodyType'));
  });

  it('keeps genuinely different names apart', () => {
    expect(normalizeParamKey('timeout')).not.toBe(normalizeParamKey('timeoutSeconds'));
  });
});

describe('mergeResolvedAliases', () => {
  it('replaces a template with its resolved companion under the base key', () => {
    expect(mergeResolvedAliases({ message: '{{tpl}}', resolvedMessage: 'hi' })).toEqual({
      message: 'hi',
    });
  });

  it('leaves keys without a companion untouched', () => {
    expect(mergeResolvedAliases({ url: 'https://x', method: 'GET' })).toEqual({
      url: 'https://x',
      method: 'GET',
    });
  });

  it('does not mistake a short key starting with "resolved" for an alias', () => {
    // "resolved" itself is 8 chars: there is no base key to derive.
    expect(mergeResolvedAliases({ resolved: true })).toEqual({ resolved: true });
  });
});

describe('buildParamAlignment', () => {
  it('reports nothing when every configured parameter came back under its own key', () => {
    const alignment = buildParamAlignment(
      { url: 'https://x', method: 'GET' },
      { url: 'https://x', method: 'GET', extra_engine_field: 1 },
    );
    expect(alignment.mismatches).toEqual([]);
    expect(alignment.configuredKeys).toEqual(['url', 'method']);
  });

  it('flags a parameter the run reported under a differently-formatted name', () => {
    const alignment = buildParamAlignment(
      { timeoutSeconds: '30' },
      { timeout_seconds: 30 },
    );
    expect(alignment.mismatches).toEqual([
      {
        key: 'timeoutSeconds',
        status: 'renamed',
        configuredExpression: '30',
        runtimeKey: 'timeout_seconds',
      },
    ]);
  });

  it('flags a parameter the run did not report at all', () => {
    const alignment = buildParamAlignment({ folder: 'INBOX' }, { limit: 10 });
    expect(alignment.mismatches).toEqual([
      { key: 'folder', status: 'not_reported', configuredExpression: 'INBOX' },
    ]);
  });

  it('does not flag a parameter whose reported value is null or empty', () => {
    // Reported-with-no-value is a value; only an ABSENT key is a mismatch.
    const alignment = buildParamAlignment({ subject: 'x' }, { subject: null });
    expect(alignment.mismatches).toEqual([]);
  });

  it('does not flag a key inherited from Object.prototype', () => {
    // `{}.toString` exists on every object: a hasOwnProperty-free lookup would
    // silently call this parameter "reported".
    const alignment = buildParamAlignment({ toString: 'x' }, {});
    expect(alignment.mismatches).toEqual([
      { key: 'toString', status: 'not_reported', configuredExpression: 'x' },
    ]);
  });

  it('reports every mismatching parameter, not just the first', () => {
    const alignment = buildParamAlignment(
      { a: '1', bKey: '2', c: '3' },
      { b_key: 2, c: 3 },
    );
    expect(alignment.mismatches.map((m) => [m.key, m.status])).toEqual([
      ['a', 'not_reported'],
      ['bKey', 'renamed'],
    ]);
  });
});

describe('by-design exemptions', () => {
  it('does not flag a parameter the node type deliberately does not echo', () => {
    const alignment = buildParamAlignment(
      { assignments: [{ name: 'a' }], keepOnlySet: true },
      { keepOnlySet: true, a: 1 },
      'set',
    );
    expect(alignment.mismatches).toEqual([]);
  });

  it('flags the same key on a node type that has no exemption for it', () => {
    const alignment = buildParamAlignment({ assignments: [] }, {}, 'filter');
    expect(alignment.mismatches.map((m) => m.key)).toEqual(['assignments']);
  });

  it('flags everything when the caller gives no node type, rather than guessing', () => {
    const alignment = buildParamAlignment({ assignments: [] }, {});
    expect(alignment.mismatches.map((m) => m.key)).toEqual(['assignments']);
  });

  it('keeps the exemption list keyed by type so one node cannot cover another', () => {
    for (const entry of NOT_ECHOED_BY_DESIGN) {
      expect(entry).toMatch(/^[a-z_]+\.[A-Za-z_][A-Za-z0-9_]*$/);
    }
  });
});

describe('mergeResolvedAliases - what is NOT an alias', () => {
  it('leaves a snake_case key that merely starts with the word alone', () => {
    // `resolved_at` was renamed to `_at` by a startsWith check: the exact
    // "my parameter is not where I put it" failure this module removes.
    expect(mergeResolvedAliases({ resolved_at: '2026-01-01' })).toEqual({ resolved_at: '2026-01-01' });
  });

  it('leaves a camelCase key alone when there is no base key to merge into', () => {
    expect(mergeResolvedAliases({ resolvedIssues: 5 })).toEqual({ resolvedIssues: 5 });
  });

  it('merges only when BOTH the template and its companion are present', () => {
    expect(mergeResolvedAliases({ message: '{{tpl}}', resolvedMessage: 'hi', other: 1 })).toEqual({
      message: 'hi',
      other: 1,
    });
  });
});

describe('flattenPlannedParams', () => {
  it('lifts a typed block\'s own keys to the top, the way the node reports them', () => {
    expect(
      flattenPlannedParams({
        id: 'filter-1',
        type: 'filter',
        label: 'Filter Ops',
        filter: { conditions: [{ field: 'a' }], mode: 'and' },
      }),
    ).toEqual({ conditions: [{ field: 'a' }], mode: 'and' });
  });

  it('skips the entry metadata, which is the node itself and not its configuration', () => {
    const flattened = flattenPlannedParams({
      id: 'x',
      graphNodeId: 'x',
      type: 'wait',
      label: 'Wait',
      position: { x: 1, y: 2 },
      nodePolicy: { retries: 3 },
      mock: { output: {} },
      backEdge: {},
      description: 'note',
      credentialId: 'c1',
      params: { duration: 1000 },
    });
    expect(flattened).toEqual({ duration: 1000 });
  });

  it('lets the flat params map win over a typed block on the same key', () => {
    // `params` is the closest thing to what the node was handed.
    expect(
      flattenPlannedParams({ type: 'filter', filter: { input: 'block' }, params: { input: 'flat' } }),
    ).toEqual({ input: 'flat' });
  });

  it('keeps a top-level scalar as a configured parameter', () => {
    expect(
      flattenPlannedParams({ type: 'split', list: '{{x}}', maxItems: 5, splitStrategy: 'stop-on-error' }),
    ).toEqual({ list: '{{x}}', maxItems: 5, splitStrategy: 'stop-on-error' });
  });

  it('keeps a top-level ARRAY whole rather than spreading it', () => {
    expect(flattenPlannedParams({ type: 'fork', branches: ['a', 'b'] })).toEqual({
      branches: ['a', 'b'],
    });
  });

  it('drops null and undefined values, which configure nothing', () => {
    expect(flattenPlannedParams({ type: 'x', a: null, b: undefined, c: 0, d: false })).toEqual({
      c: 0,
      d: false,
    });
  });

  it('is empty for an entry that carries only metadata', () => {
    expect(flattenPlannedParams({ id: 'n', type: 'merge', label: 'Merge' })).toEqual({});
  });
});
