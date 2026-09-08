import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * The two control clusters at the top of every resource list wrap on a phone.
 *
 * Regression (2026-09-05): at 410px the Agents header measured 440px of buttons
 * in a 403px column and the page content picked up a sideways scroll, with
 * "Create Agent" hanging off the right edge (503px in French). The header is a
 * COLUMN below `md`, so the cluster already had the full width available - what
 * it lacked was permission to use a second line. `flex-wrap` is the whole fix;
 * the `shrink-0` next to it never governed the horizontal axis in a column and
 * is kept only for the `md:` row, where it always mattered.
 *
 * The same column holds a second cluster, the visibility + sort selects, whose
 * localized labels ("Dernière modification") overflow the same way.
 *
 * A source scan rather than four render tests: each of these tables pulls in the
 * API client, the side panel, folders, templates and a publication modal, and
 * the claim is about two class lists in each of them.
 */
const COMPONENTS = path.resolve(fileURLToPath(new URL('.', import.meta.url)), '..');

const LIST_TABLES = ['AgentTable.tsx', 'WorkflowTable.tsx', 'DataSourceTable.tsx', 'InterfaceTable.tsx'];

const read = (file: string) => fs.readFileSync(path.join(COMPONENTS, file), 'utf8');

/** Class lists as written in the source, one entry per `className="..."`. */
const classLists = (source: string): string[] =>
  [...source.matchAll(/className="([^"]*)"/g)].map((m) => m[1]);

const hasCluster = (source: string, ...required: string[]) =>
  classLists(source).some((list) => {
    const classes = list.split(/\s+/);
    return required.every((c) => classes.includes(c));
  });

describe.each(LIST_TABLES)('%s header', (file) => {
  it('stacks its header into a column on a phone, which is what gives the buttons room', () => {
    // Without this the wrap below would be meaningless: in a ROW the cluster is
    // sized to its content and never reaches a second line.
    expect(hasCluster(read(file), 'flex', 'flex-col', 'md:flex-row')).toBe(true);
  });

  it('lets the create/templates cluster wrap instead of overflowing', () => {
    expect(hasCluster(read(file), 'flex', 'flex-wrap', 'items-center', 'gap-2', 'md:shrink-0')).toBe(true);
  });

  it('lets the filter cluster wrap too, where the localized labels overflow', () => {
    // Deliberately NOT `hasCluster(flex, flex-wrap, items-center, gap-2)`: the
    // header cluster above is a superset of that, so the assertion would pass
    // with the filter cluster reverted. What is asserted is a SECOND wrapping
    // cluster, the one without `md:shrink-0`.
    const wrapping = classLists(read(file)).filter((list) => {
      const classes = list.split(/\s+/);
      return (
        classes.includes('flex') &&
        classes.includes('flex-wrap') &&
        classes.includes('items-center') &&
        classes.includes('gap-2') &&
        !classes.includes('md:shrink-0')
      );
    });

    expect(wrapping.length).toBeGreaterThan(0);
  });

  it('leaves no header cluster pinned to a single unwrappable row', () => {
    // `flex … items-center gap-2` with neither `flex-wrap` nor a column parent is
    // exactly the shape that overflowed.
    const offenders = classLists(read(file)).filter((list) => {
      const classes = list.split(/\s+/);
      return (
        classes.includes('flex') &&
        classes.includes('items-center') &&
        classes.includes('gap-2') &&
        classes.includes('shrink-0') &&
        !classes.includes('flex-wrap')
      );
    });
    expect(offenders).toEqual([]);
  });
});
