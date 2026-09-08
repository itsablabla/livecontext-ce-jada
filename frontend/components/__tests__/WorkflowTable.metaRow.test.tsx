import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * The workflow card's footer meta row, which must stay ONE line.
 *
 * It accumulates: a modified date, a run count, a spending figure, a live dot, a
 * review or rejected badge, a shared globe or a private lock, and the relations
 * button. Nothing stopped the text inside each segment from wrapping, so on a
 * narrow card the date alone took two lines and pushed the rest of the row down.
 *
 * The mechanism is `truncate` on the date and `shrink-0` on every other DIRECT
 * child, so the date is the only thing that gives. That is a whole-row
 * invariant: it holds only if every child declares it, and the first version of
 * this fix missed the relations button, a 28px control that then shared the
 * shrinking with the date and lost its square before the date had finished
 * truncating.
 *
 * Asserted over the source, because jsdom computes no widths - the same way the
 * pickers pin their stacking order - and because rendering this table means
 * standing up folders, favorites, pagination and four API clients for a claim
 * about class names.
 */

const SOURCE = fs.readFileSync(
  path.join(process.cwd(), 'components/WorkflowTable.tsx'),
  'utf8'
);

/**
 * The row, found by a STABLE anchor and not by its own class string.
 *
 * Anchoring on the container's className would make every assertion below
 * depend on it: change one class and the finder misses, the guard trips, and
 * the failure names the wrong thing.
 *
 * Missing the anchor THROWS rather than yielding an empty slice. An earlier
 * version returned `''` and let three of the four invariant tests pass
 * vacuously (`expect([]).toEqual([])`), leaving one guard between a reworded
 * comment and a silently unenforced layout rule.
 */
const ROW_MARKER = '{/* ONE line, always.';

function rowSource(): string {
  const start = SOURCE.indexOf(ROW_MARKER);
  if (start === -1) {
    throw new Error(
      `WorkflowTable.metaRow: the marker "${ROW_MARKER}" is gone from WorkflowTable.tsx. ` +
        'Re-anchor this test on the row it describes rather than deleting it.'
    );
  }
  // `+ 2` to take the closing `/>` WITH it. Without it the slice stopped one
  // token short and the LAST child - the relations button, the very element
  // whose missing `shrink-0` this test exists for - never reached the scanner,
  // so restoring that bug left the suite green.
  const end = SOURCE.indexOf('/>', SOURCE.indexOf('<WorkflowRelationsMenu', start)) + 2;
  if (end <= start + 1) throw new Error('WorkflowTable.metaRow: the row no longer ends on WorkflowRelationsMenu.');
  return SOURCE.slice(start, end);
}

const ROW = rowSource();

/** The container's own class list: the first className after the marker. */
const ROW_OPEN = ROW.match(/<div className="([^"]+)"/)?.[1] ?? '';

/**
 * The row's flex ITEMS: the elements that actually become children of the flex
 * container, whatever JSX wrapping sits between them and it.
 *
 * <p>Neither indentation nor a flat className scan gets this right. Most
 * segments live inside `{condition && (<>...</>)}`, so they are indented deeper
 * than a direct child while still being flex items; and the icons inside a
 * `shrink-0` span are indented like siblings while being grandchildren, on
 * which `shrink-0` does nothing. An earlier version of this test demanded the
 * class from those icons and could not see a child written with no className at
 * all - wrong in both directions at once.
 *
 * <p>So: walk the named tags and track element depth. Fragments and
 * `{...}` expressions carry no tag, so they are transparent, which is exactly
 * what they are to the flex layout.
 */
function flexItems(row: string): { tag: string; className: string | null }[] {
  const body = row.slice(row.indexOf('>', row.indexOf('<div className=')) + 1);
  const items: { tag: string; className: string | null }[] = [];
  let depth = 0;

  // Attributes are "anything that is not a tag delimiter". The earlier version
  // tried to be quote-aware and broke on the first apostrophe inside a `//`
  // comment between attributes ("the row's ONLY control"), which silently
  // dropped that element - and it was the relations button, the one this test
  // exists for.
  for (const match of body.matchAll(/<(\/?)([A-Za-z][\w.]*)([^<>]*?)(\/?)>/g)) {
    const [, closing, tag, attrs, selfClosing] = match;
    if (closing) {
      depth -= 1;
      continue;
    }
    if (depth === 0) {
      // Either quote style. A single-quoted className read as "no className"
      // would be reported as shrinkable: a failure for the wrong reason.
      items.push({ tag, className: attrs.match(/className=["']([^"']+)["']/)?.[1] ?? null });
    }
    if (!selfClosing) depth += 1;
  }
  return items;
}

const CHILDREN = flexItems(ROW);

describe('the workflow card footer stays on one line', () => {
  it('found the row it is about, and all of it', () => {
    expect(ROW_OPEN, 'no container class list found after the marker').not.toBe('');
    expect(ROW).toContain('BudgetChip');
    expect(ROW).toContain('WorkflowRelationsMenu');
    // Every optional segment, so a test that stops seeing one says so.
    for (const marker of ['runCount', 'workflow.live', 'PENDING_REVIEW', 'REJECTED']) {
      expect(ROW, `the row no longer carries ${marker}`).toContain(marker);
    }
    expect(CHILDREN.length).toBeGreaterThan(6);
    // Named explicitly, because these two are the ones a scanner drops first:
    // the chip carries no className, and the relations button closes the row
    // and is written across several lines with comments between its attributes.
    // A slice or a regex that loses either turns the test below into a weaker
    // claim without failing.
    expect(CHILDREN.map((child) => child.tag)).toEqual(
      expect.arrayContaining(['BudgetChip', 'WorkflowRelationsMenu'])
    );
  });

  /**
   * A child may hold the contract itself instead of being told it here, and one
   * does: `<BudgetChip />` is rendered with no className and declares its own
   * `shrink-0` (pinned in BudgetChip.test.tsx). Passing the class again at this
   * call site would be inert and would read as load-bearing, so the exemption is
   * VERIFIED rather than assumed: the component's source has to still say it.
   */
  const SELF_DECLARING: Record<string, string> = {
    BudgetChip: 'components/budget/BudgetChip.tsx',
  };

  it.each(Object.entries(SELF_DECLARING))('%s still declares its own shrink-0', (tag, file) => {
    // On the ELEMENT it renders, not anywhere in the file. `shrink-0` also
    // appears in that file inside a comment and on the coin icon, so a
    // whole-file substring held even with the class deleted from the control.
    const source = fs.readFileSync(path.join(process.cwd(), file), 'utf8');
    const button = source.slice(source.indexOf('<button'), source.indexOf('</button>'));
    expect(button.length, `${tag} renders no button to carry the class`).toBeGreaterThan(0);
    expect(button, `${tag} no longer refuses to shrink, so the row must say it`).toContain('shrink-0');
  });

  it('lets nothing but the date give up width', () => {
    // The exact bug: `ml-auto` on the relations button with no `shrink-0`.
    // Flex distributes shrinkage by base size, so it started losing width at the
    // same time as the date, not after it.
    //
    // A child with NO className and no entry in SELF_DECLARING fails this,
    // deliberately: that is how a future segment added without the class gets
    // caught, which a flat className scan could never do.
    const shrinkable = CHILDREN.filter(
      (child) =>
        !SELF_DECLARING[child.tag] &&
        !/(^|\s)(shrink-0|truncate)(\s|$)/.test(child.className ?? '')
    ).map((child) => `<${child.tag} className=${JSON.stringify(child.className)}>`);
    expect(shrinkable, 'these row children can still be squeezed').toEqual([]);
  });

  it('truncates the date rather than wrapping it, which is the whole mechanism', () => {
    // `truncate` is both halves: it sets `white-space: nowrap` AND lets the
    // segment's automatic minimum size resolve to 0, which is what allows it to
    // shrink at all. Without the second, the ellipsis never fires.
    const truncating = CHILDREN.filter((child) =>
      (child.className ?? '').split(/\s+/).includes('truncate')
    );
    expect(truncating).toHaveLength(1);
    expect(truncating[0].tag).toBe('span');
  });

  it('does not clip the row, which would cut the spending chip\'s focus ring', () => {
    // `overflow-hidden` here looks like the tidy answer and is not: the
    // BudgetChip's focus ring is drawn 3px outside its box and is that
    // control's only keyboard affordance. The card root clips anyway, so this
    // buys no protection against a row that cannot fit; it only moves the clip
    // out to where the ring survives it.
    expect(ROW_OPEN).not.toContain('overflow-hidden');
  });

  it('carries no class that does nothing', () => {
    // `display: flex` is `flex-wrap: nowrap` already, and `min-width` is 0 by
    // initial value on a block box like this container, which is not itself a
    // flex item. Both were in earlier versions of this row, presented as part
    // of the reason the line holds.
    expect(ROW_OPEN).not.toContain('flex-nowrap');
    expect(ROW_OPEN).not.toContain('min-w-0');
  });
});
