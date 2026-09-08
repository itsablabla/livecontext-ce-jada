/**
 * The palette's hover card opens where the Run tab's does.
 *
 * That parity IS the feature: the two panels share one slot in the side panel,
 * and a reader who has learned the Run tab's card should find the palette's in
 * the same place, at the same distance, after the same wait.
 *
 * This test used to compare the two files' `sideOffset` literals and demand
 * they be EQUAL - and they were, at 8, while the cards still landed 12px apart.
 * Radix measures `sideOffset` from the trigger's own box: the Run tab's rows run
 * edge to edge, the palette's list rows are indented `pl-3`, so the same number
 * produced a visible gap on one side and a card flush against the panel on the
 * other. What has to match is the distance from the PANEL, which is
 * `sideOffset - rowInset`. Equal literals were the bug, not the guarantee.
 *
 * Read as SOURCE because the numbers are what travel: `sideOffset` and
 * `delayDuration` are props handed to Radix and the insets are Tailwind classes,
 * so jsdom - which computes no geometry - could not tell 8 from 24.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  PALETTE_CARD_GAP_PX,
  PALETTE_LIST_ROW_INSET_PX,
} from '../DraggableNodeItem';

const root = join(__dirname, '..', '..', '..', '..', '..', '..');
const read = (rel: string) => readFileSync(join(root, rel), 'utf-8');

const RUN_PANEL = read('components/workflow/run-panel/RunStepsPanel.tsx');
const PALETTE_ROW = read('app/workflows/builder/components/palette/DraggableNodeItem.tsx');
const PALETTE_PANEL = read('app/workflows/builder/components/NodeCreatorPanel.tsx');

/** The placement props on the first TooltipContent in a file. */
function placement(source: string): { side?: string; align?: string } {
  const head = source.slice(source.indexOf('<TooltipContent')).slice(0, 400);
  return {
    side: head.match(/side="([a-z]+)"/)?.[1],
    align: head.match(/align="([a-z]+)"/)?.[1],
  };
}

/** The Run tab's offset, still written as a literal in its own file. */
function runSideOffset(): number {
  const head = RUN_PANEL.slice(RUN_PANEL.indexOf('<TooltipContent')).slice(0, 400);
  const literal = head.match(/sideOffset=\{(\d+)\}/)?.[1];
  return Number(literal);
}

/**
 * The palette's offset, written as an expression of the two exported constants.
 * Resolved rather than assumed, so this test measures what the component really
 * hands Radix.
 */
function paletteSideOffset(): number {
  const head = PALETTE_ROW.slice(PALETTE_ROW.indexOf('<TooltipContent')).slice(0, 400);
  const expression = head.match(/sideOffset=\{([^}]+)\}/)?.[1] ?? '';
  const resolved = expression
    .replace(/PALETTE_CARD_GAP_PX/g, String(PALETTE_CARD_GAP_PX))
    .replace(/PALETTE_LIST_ROW_INSET_PX/g, String(PALETTE_LIST_ROW_INSET_PX));
  if (!/^[\d+\s]+$/.test(resolved)) {
    throw new Error(`the palette offset is no longer a sum of known numbers: ${expression}`);
  }
  return resolved.split('+').reduce((total, part) => total + Number(part.trim()), 0);
}

const firstDelay = (source: string) => source.match(/delayDuration=\{(\d+)\}/)?.[1];

/**
 * Every left inset written as a literal `pl-N` in the palette panel, in px.
 *
 * Every section, not only the `space-y-1` lists: the "Frequently Used" block is
 * a 2-column grid with a different class shape and it renders the very same row
 * component, so the earlier regex, narrowed to the lists, promised to catch a
 * stray `pl-2` while being structurally unable to see the one that was there.
 *
 * Its remaining blind spots, stated rather than implied: a className built by
 * `clsx()` or a template literal, and the `px-N` shorthand. A section rewritten
 * into one of those forms would leave this green, so the invariant is "no
 * literal `pl-N` drifts", not "no inset can ever drift".
 */
function paletteInsets(): number[] {
  const classNames = [...PALETTE_PANEL.matchAll(/className="([^"]*)"/g)].map((match) => match[1]);
  return classNames
    .flatMap((value) => value.split(/\s+/))
    .filter((token) => /^pl-\d+$/.test(token))
    .map((token) => Number(token.slice('pl-'.length)) * 4);
}


/**
 * The search field, the one element in the panel that is not a row container:
 * its `pl-9` leaves room for the magnifier icon inside the input.
 */
const SEARCH_FIELD_INSET_PX = 36;

describe('the add-node palette card is placed like the Run tab card', () => {
  it('reads both sources, so a rename cannot make this pass on an empty string', () => {
    expect(RUN_PANEL).toContain('<TooltipContent');
    expect(PALETTE_ROW).toContain('<TooltipContent');
    expect(placement(RUN_PANEL).side, 'the Run tab must still place its card').toBeDefined();
    expect(runSideOffset()).toBeGreaterThan(0);
  });

  it('opens on the same side, with the same alignment', () => {
    expect(placement(PALETTE_ROW)).toEqual(placement(RUN_PANEL));
  });

  it('lands the same distance from the panel edge, not merely at the same prop value', () => {
    // Run rows are flush with the panel, so its offset IS the gap. The
    // palette's rows start `rowInset` further in, so its card only clears the
    // panel by `sideOffset - rowInset`. That difference is what the reader sees.
    const runGapFromPanel = runSideOffset();
    const paletteGapFromPanel = paletteSideOffset() - PALETTE_LIST_ROW_INSET_PX;

    expect(paletteGapFromPanel).toBe(runGapFromPanel);
    // Pre-fix this was 8 - 12 = -4: the card overlapped the panel it opened from.
    expect(paletteGapFromPanel).toBeGreaterThan(0);
  });

  it('declares the row inset the offset compensates for, and every literal inset matches it', () => {
    // The compensation is only correct while the sections really are indented
    // by that much: one left at `pl-2` would put its cards 4px off, which is
    // how the Frequently Used grid was missed the first time.
    const insets = paletteInsets();

    expect(insets.length, 'the palette must still declare insets').toBeGreaterThan(0);
    expect([...new Set(insets)].sort((a, b) => a - b)).toEqual([
      PALETTE_LIST_ROW_INSET_PX,
      SEARCH_FIELD_INSET_PX,
    ]);
  });

  it('applies the compensated offset rather than a bare literal', () => {
    const head = PALETTE_ROW.slice(PALETTE_ROW.indexOf('<TooltipContent')).slice(0, 400);

    expect(head).toContain('sideOffset={PALETTE_CARD_GAP_PX + PALETTE_LIST_ROW_INSET_PX}');
  });

  it('waits the same before opening', () => {
    // 150ms in both. The palette used to wait 1000ms, which is why its card felt
    // like a different component even when it said the same things.
    expect(firstDelay(PALETTE_PANEL)).toBe(firstDelay(RUN_PANEL));
    expect(firstDelay(PALETTE_PANEL)).toBeDefined();
  });
});
