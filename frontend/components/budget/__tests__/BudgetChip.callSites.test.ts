import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Who renders the spending chip, and which of them let it draw its own coin.
 *
 * The chip says what its number IS with a coin glyph instead of a word, because
 * the word was the longest and least surprising part of it. Two surfaces print
 * a run's OWN cost immediately before the chip, each with a coin of their own:
 * there, a second coin between two figures reads as a second unit, so those
 * pass `showIcon={false}`.
 *
 * That is a rule ABOUT THE ROW, not about the chip, so no component test can
 * hold it: `BudgetChip.test.tsx` proves the prop works, and the board card
 * proves one rendered row ends up with exactly one coin. This is the part
 * neither covers - that every call site made the right choice, including the
 * run panel, which is too entangled to render for a claim about an icon.
 */

const ROOT = process.cwd();

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (['node_modules', '.next', '__tests__', 'e2e'].includes(entry.name)) continue;
      sourceFiles(full, out);
    } else if (/\.tsx$/.test(entry.name) && !/\.test\.tsx$/.test(entry.name)) {
      out.push(full);
    }
  }
  return out;
}

/**
 * Every PLACE the chip is rendered: one entry per `<BudgetChip`, not per file,
 * since a file may hold more than one and they need not agree.
 *
 * <p>`printsOwnCoin` reads the 1,500 characters BEFORE the chip rather than the
 * whole file. The rule is about the ROW - "does the line this chip sits on
 * already carry a coin" - and a file-wide search answers a different question:
 * add a coin anywhere else in WorkflowTable.tsx and a file-scoped check would
 * demand `showIcon={false}` on a row that has none, failing for a reason other
 * than the one it names and suggesting the wrong fix.
 */
const ROW_LOOKBEHIND = 1500;

interface ChipSite {
  file: string;
  /** The `<BudgetChip ... />` element, captured whole. */
  tag: string;
  /** The source immediately before it, which is its row. */
  row: string;
}

const CALL_SITES: ChipSite[] = ['app', 'components']
  .flatMap((dir) => sourceFiles(path.join(ROOT, dir)))
  .map((file) => ({ file: path.relative(ROOT, file).split(path.sep).join('/'), source: fs.readFileSync(file, 'utf8') }))
  .flatMap(({ file, source }) =>
    [...source.matchAll(/<BudgetChip[\s/>]/g)].map((match) => {
      const at = match.index!;
      const close = source.indexOf('/>', at);
      return {
        file,
        tag: close === -1 ? source.slice(at, at + 400) : source.slice(at, close + 2),
        row: source.slice(Math.max(0, at - ROW_LOOKBEHIND), at),
      };
    })
  );

const suppressesIcon = (tag: string) => /showIcon=\{false\}/.test(tag);
const printsOwnCoin = (row: string) => /<Coins\b/.test(row);

describe('the spending chip is rendered with the right icon decision', () => {
  it('found the call sites, so nothing below can pass over an empty list', () => {
    const files = CALL_SITES.map((entry) => entry.file);
    // Each site's element was captured WHOLE, or `suppressesIcon` reads a
    // truncated tag and answers false for the wrong reason.
    for (const site of CALL_SITES) {
      expect(site.tag.endsWith('/>'), `${site.file}: the chip element was not captured whole`).toBe(true);
    }
    expect(files).toEqual(
      expect.arrayContaining([
        'components/WorkflowTable.tsx',
        'components/AgentTable.tsx',
        'components/applications/ApplicationCard.tsx',
        'components/workflow-board/WorkflowBoardCard.tsx',
        'components/workflow/run-panel/RunStepsPanel.tsx',
      ])
    );
    expect(files.length).toBeGreaterThanOrEqual(5);
  });

  it.each(CALL_SITES.map((site, index) => [`${site.file} #${index}`, index] as const))(
    '%s draws exactly one coin for the money on its row',
    (_name, index) => {
      const site = CALL_SITES[index];
      // The rule, in one line: the row's coin count must be one. If the row
      // brings its own, the chip must not add a second; if it does not, the
      // chip is the only thing that can say what the number is.
      expect(
        suppressesIcon(site.tag),
        printsOwnCoin(site.row)
          ? `${site.file}: this row prints its own <Coins/>, so the chip must not add a second`
          : `${site.file}: this row prints no coin of its own, so the chip must keep hers`
      ).toBe(printsOwnCoin(site.row));
    }
  );

  it('still has rows on BOTH sides of that rule, or it is testing nothing', () => {
    const withCoin = CALL_SITES.filter((site) => printsOwnCoin(site.row));
    const withoutCoin = CALL_SITES.filter((site) => !printsOwnCoin(site.row));
    expect(withCoin.length, 'no call site prints its own coin any more').toBeGreaterThan(0);
    expect(withoutCoin.length, 'no call site relies on the chip for the coin').toBeGreaterThan(0);
  });
});
