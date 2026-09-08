/**
 * Wiring guard: every import tells the importer what surface it is running on.
 *
 * The interface-format lookup shares the cache entry the interface node itself uses, and a
 * canvas showing a run resolves its formats from the snapshots THAT RUN froze. All three
 * facts live in the CONTEXT the caller passes, and each fails SILENTLY when a caller
 * forgets it: with no client the lookup goes straight to the API, once per page per import,
 * on a path the agent plan-sync re-runs on every edit; with no `isRunMode` a run canvas asks
 * for a live format its nodes will not paint; with no `workflowRunId` it resolves nothing at
 * all. None of those show up as a failing behavioural test - the layout still ends up right
 * in the cases those tests cover - so the call sites are asserted at the source, the way
 * `MeasuredLayoutSync.wiring` asserts its own.
 *
 * The loader's context is covered behaviourally as well (useWorkflowLoader.loadError),
 * because there it is computed rather than written literally.
 */
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const read = (p: string) => readFileSync(join(process.cwd(), p), 'utf8');
/** Not source of ours, or not production code. */
const SKIP = new Set(['node_modules', '.next', '__tests__', 'e2e', 'coverage', 'public', 'messages', 'test-results']);
const CALL = 'WorkflowPlanImporter.importPlan(';

/** EVERY importPlan call in the file, arguments only. A file may hold several. */
function importCalls(source: string): string[] {
  const calls: string[] = [];
  for (let at = source.indexOf(CALL); at !== -1; at = source.indexOf(CALL, at + 1)) {
    calls.push(source.slice(at + CALL.length, source.indexOf(');', at)));
  }
  expect(calls.length, 'this file still calls importPlan').toBeGreaterThan(0);
  return calls;
}

/**
 * Split an argument list on its TOP-LEVEL commas, so an object or array argument counts
 * as one. A trailing comma yields no argument: `(a, b, c,)` is three, which is the whole
 * point - dropping the context leaves exactly that shape behind.
 */
function argumentsOf(call: string): string[] {
  const args: string[] = [];
  let depth = 0;
  let current = '';
  for (const char of call) {
    if ('([{'.includes(char)) depth++;
    if (')]}'.includes(char)) depth--;
    if (char === ',' && depth === 0) {
      args.push(current);
      current = '';
      continue;
    }
    current += char;
  }
  args.push(current);
  return args.map((a) => a.trim()).filter((a) => a.length > 0);
}

/** The identifier a context argument is, or spreads: `ctxRef.current` / `{ ...ctxRef.current, x }`. */
function contextIdentifier(context: string): string | null {
  const match = /^(?:\{\s*\.\.\.)?([A-Za-z_$][\w$]*)/.exec(context.trim());
  return match ? match[1] : null;
}

/**
 * Whether the object that identifier is built from names the field. Written with indexOf
 * rather than a RegExp built from a template literal, where `\s` and `\{` are eaten by the
 * literal itself and quietly produce a pattern that matches nothing.
 */
function identifierDeclares(source: string, identifier: string, field: string): boolean {
  for (const marker of [identifier + '.current = {', identifier + ' = {']) {
    for (let at = source.indexOf(marker); at !== -1; at = source.indexOf(marker, at + 1)) {
      const end = source.indexOf('}', at);
      if (end !== -1 && source.slice(at, end).includes(field)) return true;
    }
  }
  return false;
}

/**
 * Does every import carry this field?
 *
 * A field may be named in the context argument itself, or come from the context value the
 * caller passes (the loader keeps one in a ref, so that a changing run never re-registers
 * its load effect) - in which case the object THAT identifier is built from has to name it.
 * A whole-file search would not do: every one of these files mentions every field
 * somewhere, so it would assert nothing beyond "a 4th argument exists".
 *
 * `inCall` fields are the ones a shared context value cannot carry, because they are
 * resolved DURING the load rather than at render: the call itself has to add them.
 */
function everyCallCarries(source: string, field: string, inCall = false): boolean {
  return importCalls(source).every((call) => {
    const context = argumentsOf(call)[3];
    if (!context) return false;
    if (context.includes(field)) return true;
    if (inCall) return false;
    const identifier = contextIdentifier(context);
    return !!identifier && identifierDeclares(source, identifier, field);
  });
}

/** Every production caller of importPlan, and what its context has to carry. */
const CALL_SITES: Array<{
  file: string; needsRunFlag: boolean; needsRunId?: boolean; why: string;
}> = [
  {
    file: 'app/workflows/builder/hooks/useWorkflowLoader.ts',
    needsRunFlag: true,
    // A run canvas resolves its formats from the snapshots that run froze, so the flag
    // without the id would leave it resolving nothing at all.
    needsRunId: true,
    why: 'the only caller that knows a run is being shown',
  },
  {
    file: 'app/workflows/builder/hooks/useWorkflowEventListeners.ts',
    needsRunFlag: true,
    why: 'the plan-sync re-imports on every agent edit',
  },
  {
    file: 'app/workflows/builder/components/WorkflowPlanGenerator.tsx',
    // Its import lives behind the paste dialog, which a locked canvas does not show.
    needsRunFlag: false,
    why: 'a pasted plan is imported into the same canvas',
  },
];

describe('the import is told what surface it is on', () => {
  for (const { file, needsRunFlag, needsRunId, why } of CALL_SITES) {
    const name = file.split('/').pop();

    it(`${name} hands down a query client, in every import it makes (${why})`, () => {
      expect(everyCallCarries(read(file), 'queryClient')).toBe(true);
    });

    if (needsRunFlag) {
      it(`${name} names run mode, so a run canvas asks for no live format`, () => {
        expect(everyCallCarries(read(file), 'isRunMode')).toBe(true);
      });
    }

    if (needsRunId) {
      it(`${name} names the run IN the call, since it is only known once the load runs`, () => {
        // Not from the shared context value: that one is rebuilt on render, and the run's
        // internal id is learned mid-load, so a call relying on it would pass the previous
        // render's value - null, on the load that matters.
        expect(everyCallCarries(read(file), 'workflowRunId', true)).toBe(true);
      });
    }
  }

  it('names every caller in the frontend: a new one must be added here, with its context', () => {
    // Walked from disk, not listed: a guard that only knows the files it already knows
    // would pass for ever while a caller appeared in a new one - and a new caller is just
    // as likely to land outside the builder folder as inside it.
    const root = process.cwd();
    const sources: string[] = [];
    const walk = (dir: string) => {
      for (const entry of readdirSync(dir)) {
        if (SKIP.has(entry)) continue;
        const full = join(dir, entry);
        if (statSync(full).isDirectory()) walk(full);
        else if (/\.tsx?$/.test(entry) && !entry.includes('.test.')) sources.push(full);
      }
    };
    walk(root);

    const known = new Set(CALL_SITES.map((c) => c.file));
    const callers = sources
      .filter((f) => readFileSync(f, 'utf8').includes(CALL))
      .map((f) => relative(process.cwd(), f).replaceAll(sep, '/'))
      // The importer declares the method; calling it is what this guard is about.
      .filter((f) => !f.endsWith('workflowPlanImporter/WorkflowPlanImporter.ts'));

    expect(callers.filter((f) => !known.has(f))).toEqual([]);
    expect(callers.length, 'the walk still finds the known callers').toBe(CALL_SITES.length);
  });
});
