import { describe, it, expect } from 'vitest';
import {
  collectTableColumns,
  detectUnresolvedValue,
  formatCellValue,
  formatJson,
  hasTableView,
  isTabularArray,
  LONG_STRING_CHARS,
  MAX_EMBEDDED_JSON_CHARS,
  MAX_TABLE_COLUMNS,
  parseEmbeddedJson,
  pickTabularValue,
  tabularFields,
  toClipboardText,
} from '../runValueUtils';

describe('detectUnresolvedValue', () => {
  it('flags the engine INVALID_TEMPLATE marker', () => {
    expect(detectUnresolvedValue('INVALID_TEMPLATE: bad expr')).toBe('invalid_template');
  });

  it("flags the engine's own variable-not-found marker, in either delimiter", () => {
    expect(detectUnresolvedValue('{{__UNRESOLVED__:mcp:step.output.url}}')).toBe('unresolved_variable');
    expect(detectUnresolvedValue('prefix {{__UNRESOLVED__:x}} suffix')).toBe('unresolved_variable');
    // V2TemplateAdapter.containsUnresolved guards against this spelling.
    expect(detectUnresolvedValue('${__UNRESOLVED__:x}')).toBe('unresolved_variable');
  });

  it('leaves a bare ${...} or {{...}} alone - they are legitimate values, not failures', () => {
    // A code node's JS template literal, a shell command, an HTTP body and a
    // node that echoes its configured expression on purpose (FilterNode.input)
    // all carry these. Flagging them is the false positive that made the
    // persistence layer DELETE them, which is the bug this work removed.
    expect(detectUnresolvedValue('const url = `${base}/items`;')).toBeNull();
    expect(detectUnresolvedValue('echo "${HOME}/logs"')).toBeNull();
    expect(detectUnresolvedValue('{{core:step.output.items}}')).toBeNull();
    expect(detectUnresolvedValue('<div>{{title|Untitled}}</div>')).toBeNull();
  });

  it('prefers the parse failure over the object artefact when a value carries both', () => {
    // The order at the top of detectUnresolvedValue is load-bearing: the
    // INVALID_TEMPLATE prefix describes what actually went wrong.
    expect(detectUnresolvedValue('INVALID_TEMPLATE: [object Object]')).toBe('invalid_template');
  });

  it('flags a value that IS the stringified object', () => {
    expect(detectUnresolvedValue('[object Object]')).toBe('stringified_object');
    // Whitespace around it is still the same failed value.
    expect(detectUnresolvedValue('  [object Object]  ')).toBe('stringified_object');
  });

  it('flags the CONCATENATED shapes, which are the ones a run actually produces', () => {
    // Characterization, not a regression pin: `includes` is the long-standing
    // behaviour. It is written down because a round of this work briefly narrowed
    // it to an equality check, which rendered both of these as ordinary text.
    // A set assignment `"Owner: {{core:x.output.obj}}"` whose expression resolves
    // to a map, and a list of maps joined into a string, are broken values the
    // reader has to be told about.
    expect(detectUnresolvedValue('Owner: [object Object]')).toBe('stringified_object');
    expect(detectUnresolvedValue('[object Object],[object Object]')).toBe(
      'stringified_object',
    );
  });

  it('accepts the one false positive this costs: prose quoting the literal', () => {
    // The cost of `includes`, documented rather than fixed. Badging an error
    // message that explains the artefact is redundant, not misleading - it IS
    // about a stringified object - whereas missing a real one hides a broken
    // value. The asymmetry is what decides between the two matchers.
    expect(detectUnresolvedValue('Expected a map, got [object Object]')).toBe(
      'stringified_object',
    );
  });

  it('trims before matching the INVALID_TEMPLATE prefix, like the other kinds', () => {
    expect(detectUnresolvedValue('  INVALID_TEMPLATE: {{broken}}')).toBe('invalid_template');
  });


  it('returns null for ordinary values, including non-strings', () => {
    expect(detectUnresolvedValue('https://example.com')).toBeNull();
    expect(detectUnresolvedValue(42)).toBeNull();
    expect(detectUnresolvedValue({ a: 1 })).toBeNull();
    expect(detectUnresolvedValue(null)).toBeNull();
  });
});

describe('parseEmbeddedJson', () => {
  it('parses a JSON object carried inside a string', () => {
    expect(parseEmbeddedJson('{"status":"ok","count":2}')).toEqual({ status: 'ok', count: 2 });
  });

  it('parses a JSON array and tolerates surrounding whitespace', () => {
    expect(parseEmbeddedJson('  [1, 2, 3]  ')).toEqual([1, 2, 3]);
  });

  it('refuses a JSON scalar - relabelling ordinary text as a document helps nobody', () => {
    expect(parseEmbeddedJson('42')).toBeUndefined();
    expect(parseEmbeddedJson('"hello"')).toBeUndefined();
    expect(parseEmbeddedJson('true')).toBeUndefined();
  });

  it('refuses malformed JSON and plain prose', () => {
    expect(parseEmbeddedJson('{not json}')).toBeUndefined();
    expect(parseEmbeddedJson('a normal sentence')).toBeUndefined();
  });

  it('refuses an unresolved template even when it is brace-shaped', () => {
    expect(parseEmbeddedJson('{{core:x.output}}')).toBeUndefined();
  });

  it('refuses a string past the parse budget instead of parsing a huge body twice a render', () => {
    const huge = `{"a":"${'x'.repeat(MAX_EMBEDDED_JSON_CHARS)}"}`;
    expect(parseEmbeddedJson(huge)).toBeUndefined();
  });

  it('returns undefined for non-strings', () => {
    expect(parseEmbeddedJson({ a: 1 })).toBeUndefined();
    expect(parseEmbeddedJson(null)).toBeUndefined();
  });
});

describe('toClipboardText', () => {
  it('copies a string as itself, without the display quotes', () => {
    expect(toClipboardText('hello')).toBe('hello');
  });

  it('copies objects and arrays as pretty JSON', () => {
    expect(toClipboardText({ a: 1 })).toBe('{\n  "a": 1\n}');
    expect(toClipboardText([1])).toBe('[\n  1\n]');
  });

  it('copies scalars as their text form and undefined as empty', () => {
    expect(toClipboardText(42)).toBe('42');
    expect(toClipboardText(false)).toBe('false');
    expect(toClipboardText(null)).toBe('null');
    expect(toClipboardText(undefined)).toBe('');
  });
});

describe('formatJson', () => {
  it('pretty-prints with two-space indentation', () => {
    expect(formatJson({ a: [1] })).toBe('{\n  "a": [\n    1\n  ]\n}');
  });

  it('degrades to a readable string instead of throwing on a circular structure', () => {
    const circular: Record<string, unknown> = {};
    circular.self = circular;
    // An empty string would pass a not-to-throw assertion and render nothing.
    expect(formatJson(circular)).toBe(String(circular));
    expect(formatJson(circular).length).toBeGreaterThan(0);
  });
});

describe('isTabularArray', () => {
  it('accepts a non-empty array of plain objects', () => {
    expect(isTabularArray([{ a: 1 }, { b: 2 }])).toBe(true);
  });

  it('rejects an empty array, scalars, nested arrays and nulls', () => {
    expect(isTabularArray([])).toBe(false);
    expect(isTabularArray([1, 2])).toBe(false);
    expect(isTabularArray([{ a: 1 }, [2]])).toBe(false);
    expect(isTabularArray([{ a: 1 }, null])).toBe(false);
    expect(isTabularArray({ a: 1 })).toBe(false);
  });
});

describe('collectTableColumns', () => {
  it('orders columns by first appearance across the rows', () => {
    expect(collectTableColumns([{ b: 1, a: 2 }, { c: 3, a: 4 }])).toEqual(['b', 'a', 'c']);
  });

  it('caps the column count so one wide row cannot produce an unusable grid', () => {
    const wide = Object.fromEntries(
      Array.from({ length: MAX_TABLE_COLUMNS + 10 }, (_, i) => [`col${i}`, i]),
    );
    expect(collectTableColumns([wide])).toHaveLength(MAX_TABLE_COLUMNS);
  });
});

describe('formatCellValue', () => {
  it('summarises nested values rather than expanding them in a cell', () => {
    expect(formatCellValue({ a: 1, b: 2 })).toBe('{2}');
    expect(formatCellValue([1, 2, 3])).toBe('[3]');
  });

  it('passes scalars through and renders missing values readably', () => {
    expect(formatCellValue('text')).toBe('text');
    expect(formatCellValue(7)).toBe('7');
    expect(formatCellValue(null)).toBe('null');
    expect(formatCellValue(undefined)).toBe('');
  });
});

describe('thresholds', () => {
  it('clamps long strings well below the JSON parse budget', () => {
    expect(LONG_STRING_CHARS).toBeLessThan(MAX_EMBEDDED_JSON_CHARS);
  });
});

describe('table view selection', () => {
  it('offers the table for a payload that IS an array of rows', () => {
    expect(hasTableView([{ a: 1 }])).toBe(true);
    expect(pickTabularValue([{ a: 1 }])).toEqual([{ a: 1 }]);
  });

  it('offers the table for a payload whose single field holds the rows', () => {
    const payload = { items: [{ a: 1 }], count: 1 };
    expect(hasTableView(payload)).toBe(true);
    expect(pickTabularValue(payload)).toEqual([{ a: 1 }]);
  });

  it('declines when TWO fields could be the rows - picking one would be an arbitrary choice', () => {
    const payload = { items: [{ a: 1 }], others: [{ b: 2 }] };
    expect(tabularFields(payload)).toEqual(['items', 'others']);
    expect(hasTableView(payload)).toBe(false);
    expect(pickTabularValue(payload)).toBe(payload);
  });

  it('declines a flat payload', () => {
    expect(hasTableView({ a: 1, b: 'x' })).toBe(false);
  });
});
