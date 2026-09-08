/**
 * Every caller of `useNodeExecutionStatus` must hand it the node's painted status.
 *
 * While ONE epoch is focused, that status is THAT epoch's outcome, and it is the only
 * per-epoch fact the hook can see: the context's own sets are built from cumulative node
 * counts and read the same in every epoch. A call site that omits it silently puts its surface
 * back on the run-wide state - it would offer a restart on a node the epoch on screen skipped
 * (the backend refuses that), and paint another fire's outcome on a node this one never
 * reached.
 *
 * The argument is OPTIONAL by type, because most of the object is (`label`, `kind`,
 * `crudOperation` are all resolved by fallback). So dropping `status` compiles, runs, and
 * passes every behavioural test that mocks the hook. There are 17 node renderers plus the
 * inspector and the context menu; pinning one or two of them by rendering the component leaves
 * the rest deletable with the suite green.
 *
 * Hence a call-site invariant, in the spirit of the backend's JsonbWritesCallsiteInvariantTest:
 * it reads the sources and fails on the omission itself rather than on one symptom of it. If a
 * new call site genuinely has no status to give, add it to ALLOWED_WITHOUT_STATUS with the
 * reason - do not silence this by deleting the assertion.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

/** Repo-relative roots that can hold a call site. */
const SEARCH_ROOTS = [
  join(__dirname, '..', '..', 'components'),
  join(__dirname, '..', '..', 'hooks'),
  join(__dirname, '..', '..', 'contexts'),
];

/**
 * Call sites that deliberately pass no status, with the reason. Empty today: every surface
 * that reads the hook renders a node the canvas paints.
 */
const ALLOWED_WITHOUT_STATUS: string[] = [];

function sourceFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      // Tests mock the hook wholesale; the invariant is about production call sites.
      if (entry === '__tests__') continue;
      out.push(...sourceFiles(full));
      continue;
    }
    if (/\.tsx?$/.test(entry) && !/\.test\.tsx?$/.test(entry)) out.push(full);
  }
  return out;
}

/**
 * Drop comments before scanning: the hook is NAMED in prose (NodePlayButton's docstring
 * explains where its flags come from), and a mention is not a call site.
 */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');
}

/** The whole argument list of each `useNodeExecutionStatus(...)` call in a file. */
function callSites(source: string): string[] {
  const sites: string[] = [];
  const marker = 'useNodeExecutionStatus(';
  let from = 0;
  for (;;) {
    const at = source.indexOf(marker, from);
    if (at === -1) break;
    let depth = 0;
    let i = at + marker.length - 1;
    for (; i < source.length; i++) {
      if (source[i] === '(') depth++;
      else if (source[i] === ')') {
        depth--;
        if (depth === 0) break;
      }
    }
    sites.push(source.slice(at, i + 1));
    from = i + 1;
  }
  return sites;
}

describe('useNodeExecutionStatus call sites', () => {
  const found = SEARCH_ROOTS.flatMap(sourceFiles)
    .flatMap((file) => {
      const source = stripComments(readFileSync(file, 'utf8'));
      // The declaration itself is not a call site.
      if (source.includes('export function useNodeExecutionStatus')) return [];
      return callSites(source).map((call) => ({
        file: relative(join(__dirname, '..', '..', '..', '..', '..'), file).replace(/\\/g, '/'),
        call,
      }));
    });

  it('finds the call sites at all, so a rename cannot make this suite vacuously green', () => {
    // The guard reads sources by name; if the hook is renamed and this list empties, every
    // assertion below would pass over nothing.
    expect(found.length).toBeGreaterThanOrEqual(17);
  });

  it('every one of them passes the node status', () => {
    const missing = found
      .filter(({ file }) => !ALLOWED_WITHOUT_STATUS.includes(file))
      .filter(({ call }) => !/\bstatus\s*:/.test(call))
      .map(({ file }) => file);

    expect(missing, 'these call sites read the run-wide state on a focused epoch').toEqual([]);
  });
});
