/**
 * Pure helpers shared by every run-mode data view (Params column, Output column).
 *
 * They answer four questions the inspector asks about a value it got back from a
 * run, and nothing else - no React, no fetching - so each one is directly testable:
 *
 *  1. Is this string actually an unresolved template the engine failed to fill?
 *  2. Is this string actually JSON that deserves a tree instead of one long line?
 *  3. Can this value be laid out as a table (an array of row-shaped objects)?
 *  4. What exactly goes on the clipboard when the user copies it?
 */

/**
 * Longest string we will try to parse as embedded JSON. A run value can be a
 * multi-megabyte HTTP body; parsing it on every render to find out it is not
 * JSON is the kind of cost that only shows up on the biggest, slowest run.
 */
export const MAX_EMBEDDED_JSON_CHARS = 200_000;

/** Above this many characters a string is clamped behind a "show more" toggle. */
export const LONG_STRING_CHARS = 400;

/** Hard cap on table columns so a wide row cannot produce an unusable grid. */
export const MAX_TABLE_COLUMNS = 30;

/**
 * Why a value is displayed as unresolved.
 *
 * ONLY unambiguous engine artefacts are listed. A bare `${...}` or `{{...}}` is
 * deliberately NOT one: a code node's JS template literal, a shell command, an
 * HTTP body and an interface template all legitimately contain them, and several
 * nodes echo their configured expression verbatim on purpose (FilterNode reports
 * `input` as the expression it was given). Flagging those would be the same
 * false positive that made the persistence layer DELETE them, which is the bug
 * this work removed - reproducing it as a badge would only move the lie.
 */
export type UnresolvedKind =
  /** `INVALID_TEMPLATE:...` - the engine could not parse the expression. */
  | 'invalid_template'
  /** `{{__UNRESOLVED__:path}}` - the engine's own "variable not found" marker. */
  | 'unresolved_variable'
  /** JavaScript's `[object Object]` - an object concatenated into a string. */
  | 'stringified_object';

const INVALID_TEMPLATE_PREFIX = 'INVALID_TEMPLATE:';
const STRINGIFIED_OBJECT = '[object Object]';
/**
 * Emitted by TemplateEngine.resolveTemplatesSimple when a variable is missing.
 * Both delimiters are matched: the engine writes the `{{` form, while
 * V2TemplateAdapter.containsUnresolved guards against the `${` one, and the
 * marker name is unmistakable either way.
 */
const UNRESOLVED_MARKERS = ['{{__UNRESOLVED__:', '${__UNRESOLVED__:'] as const;

/**
 * Classify a value as unresolved, or null when it looks like real data.
 *
 * Only strings can be unresolved: a number, a boolean or an object came out of
 * the engine as a value, not as text that failed to be substituted.
 */
export function detectUnresolvedValue(value: unknown): UnresolvedKind | null {
  if (typeof value !== 'string') return null;
  // trimStart, not trim: only the prefix check needs it, and this runs on every
  // render for every value - including a multi-megabyte HTTP body.
  if (value.trimStart().startsWith(INVALID_TEMPLATE_PREFIX)) return 'invalid_template';
  if (UNRESOLVED_MARKERS.some((marker) => value.includes(marker))) return 'unresolved_variable';
  // `includes`, not equality: the common shape is CONCATENATION - a set
  // assignment `"Owner: {{core:x.output.obj}}"` yields `"Owner: [object Object]"`,
  // and joining a list of maps yields `"[object Object],[object Object]"`. Both
  // are genuinely broken values, and an equality check renders them as ordinary
  // text. The cost is asymmetric: the only false positive is prose that quotes
  // the literal (an error message explaining the artefact), where the badge is
  // merely redundant - it IS about a stringified object. Missing a real one
  // leaves the reader hunting a value the panel told them was fine.
  if (value.includes(STRINGIFIED_OBJECT)) return 'stringified_object';
  return null;
}

/**
 * Parse a string that carries JSON, so the inspector can show a tree instead of
 * one unreadable quoted line.
 *
 * Deliberately strict: only an object or an array counts. A bare `"42"` or
 * `"true"` is valid JSON but rendering it as a "JSON document" would relabel
 * ordinary text the user wrote. Returns undefined for everything else.
 */
export function parseEmbeddedJson(value: unknown): unknown | undefined {
  if (typeof value !== 'string') return undefined;
  const trimmed = value.trim();
  if (trimmed.length < 2 || trimmed.length > MAX_EMBEDDED_JSON_CHARS) return undefined;
  const first = trimmed[0];
  const last = trimmed[trimmed.length - 1];
  const looksLikeJson = (first === '{' && last === '}') || (first === '[' && last === ']');
  if (!looksLikeJson) return undefined;
  // An unresolved template is not JSON to expand, even when it is brace-shaped.
  if (detectUnresolvedValue(trimmed)) return undefined;
  try {
    const parsed = JSON.parse(trimmed);
    if (parsed !== null && typeof parsed === 'object') return parsed;
    return undefined;
  } catch {
    return undefined;
  }
}

/** Pretty-print any value the way the JSON view and the clipboard show it. */
export function formatJson(value: unknown): string {
  if (value === undefined) return 'undefined';
  try {
    return JSON.stringify(value, null, 2) ?? String(value);
  } catch {
    // Circular structures cannot reach here from a fetched run payload, but a
    // caller passing live component state could - degrade instead of throwing.
    return String(value);
  }
}

/**
 * What the copy button puts on the clipboard.
 *
 * A string is copied as itself (copying `"hello"` with the quotes would be
 * useless in a terminal or an editor); everything else is copied as JSON.
 */
export function toClipboardText(value: unknown): string {
  if (typeof value === 'string') return value;
  if (value === null) return 'null';
  if (value === undefined) return '';
  if (typeof value !== 'object') return String(value);
  return formatJson(value);
}

/** True when a value can be laid out as rows: a non-empty array of plain objects. */
export function isTabularArray(value: unknown): value is Array<Record<string, unknown>> {
  if (!Array.isArray(value) || value.length === 0) return false;
  return value.every(
    (row) => row !== null && typeof row === 'object' && !Array.isArray(row),
  );
}

/**
 * Column order for a table view: keys in the order they first appear across the
 * rows, so the first row's shape leads and later rows only append what they add.
 */
export function collectTableColumns(rows: Array<Record<string, unknown>>): string[] {
  const columns: string[] = [];
  const seen = new Set<string>();
  for (const row of rows) {
    for (const key of Object.keys(row)) {
      if (seen.has(key)) continue;
      seen.add(key);
      columns.push(key);
      if (columns.length >= MAX_TABLE_COLUMNS) return columns;
    }
  }
  return columns;
}

/** Compact one-line rendering of a cell value for the table view. */
export function formatCellValue(value: unknown): string {
  if (value === null) return 'null';
  if (value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'object') {
    return Array.isArray(value) ? `[${value.length}]` : `{${Object.keys(value).length}}`;
  }
  return String(value);
}

/**
 * A step payload is almost never an array at the top level: the rows live under
 * one field (`items`, `results`, `conditions`, …). The table view offers itself
 * when exactly ONE such field exists - with two, picking one would be an
 * arbitrary choice the reader did not make, so the tree stays.
 */
export function tabularFields(data: unknown): string[] {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) return [];
  return Object.entries(data as Record<string, unknown>)
    .filter(([, value]) => isTabularArray(value))
    .map(([key]) => key);
}

/** Whether a table view can be offered for this payload. */
export function hasTableView(data: unknown): boolean {
  return isTabularArray(data) || tabularFields(data).length === 1;
}

/** The value the table view lays out: the payload, or its single row-shaped field. */
export function pickTabularValue(data: unknown): unknown {
  if (isTabularArray(data)) return data;
  const fields = tabularFields(data);
  if (fields.length === 1) return (data as Record<string, unknown>)[fields[0]];
  return data;
}
