/**
 * The table's "this just appeared" cues (a new column's header and cells, a duplicated row) are
 * applied from React state and pulled when the window closes, so the stylesheet owns everything
 * the DOM cannot show: what each cue looks like, and how long it plays. Three properties are
 * load-bearing and invisible from a rendered tree:
 *
 *  - the class the grid applies must actually name an animation (a renamed keyframe, or a class
 *    that only ever existed in the TSX, leaves the thing highlighted by nothing at all);
 *  - that animation must be no longer than the window the hook holds the class for, or it is cut
 *    off mid-flash when the flag comes off;
 *  - it must END at the element's plain appearance, because the class removal that follows has
 *    to be invisible.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// From the leaf module on purpose: this test needs the class names and the window, not React.
import {
  COLUMN_REVEAL_CELL_CLASS,
  COLUMN_REVEAL_HEAD_CLASS,
  ROW_REVEAL_CLASS,
  TABLE_REVEAL_WINDOW_MS,
} from '@/components/data-table/tableStyles';

const css = readFileSync(join(process.cwd(), 'app', 'globals.css'), 'utf8');

const REVEAL_CLASSES = [COLUMN_REVEAL_HEAD_CLASS, COLUMN_REVEAL_CELL_CLASS, ROW_REVEAL_CLASS];

/**
 * Every declaration block that applies to a class, in source order (base rule, then any media
 * override). Tolerates a selector LIST, since two of these classes deliberately share one rule:
 * `.a, .b { ... }` must count for both, or dropping a class from the list reads as "unchanged".
 */
function rulesFor(className: string): string[] {
  return [...css.matchAll(new RegExp(`\\.${className}\\s*(?:,\\s*[^{]*)?\\{([^}]*)\\}`, 'g'))].map(m => m[1]);
}

/**
 * Body of every `@media (prefers-reduced-motion: reduce)` block.
 *
 * Brace-counted rather than matched with a regex: a lazy `[\s\S]*?` walks straight out of the
 * media block and happily finds the class's BASE rule further down the file, which is how a
 * missing override reads as present.
 */
function reducedMotionBlocks(): string[] {
  const blocks: string[] = [];
  const at = /@media\s*\(prefers-reduced-motion:\s*reduce\)\s*\{/g;
  for (let m = at.exec(css); m; m = at.exec(css)) {
    let depth = 1;
    let i = m.index + m[0].length;
    const start = i;
    while (i < css.length && depth > 0) {
      if (css[i] === '{') depth++;
      else if (css[i] === '}') depth--;
      i++;
    }
    blocks.push(css.slice(start, i - 1));
  }
  return blocks;
}

function animationIn(rule: string): { name: string; seconds: number } {
  const match = rule.match(/animation:\s*([\w-]+)\s+([\d.]+)s/);
  expect(match, `no animation shorthand in rule: ${rule.trim()}`).toBeTruthy();
  return { name: match![1], seconds: Number(match![2]) };
}

/** The keyframes body for a name, or a failure naming the missing one. */
function framesOf(name: string): string {
  const frames = css.match(new RegExp(`@keyframes ${name}\\s*\\{([\\s\\S]*?)\\n\\}`));
  expect(frames, `@keyframes ${name} not found`).toBeTruthy();
  return frames![1];
}

/** Every animation any of the reveal classes actually asks for, base rules and overrides alike. */
function everyRevealAnimation(): string[] {
  const names = REVEAL_CLASSES.flatMap(c => rulesFor(c).map(rule => animationIn(rule).name));
  return [...new Set(names)];
}

describe('table reveal cues - the stylesheet carries them', () => {
  it.each(REVEAL_CLASSES)('animates .%s with keyframes that exist', className => {
    const rules = rulesFor(className);
    expect(rules.length, `no rule for .${className} in globals.css`).toBeGreaterThan(0);

    for (const rule of rules) {
      const { name } = animationIn(rule);
      expect(css).toContain(`@keyframes ${name}`);
    }
  });

  it.each(REVEAL_CLASSES)('keeps .%s shorter than the window the grid holds it for', className => {
    for (const rule of rulesFor(className)) {
      const { seconds } = animationIn(rule);
      expect(seconds * 1000).toBeLessThanOrEqual(TABLE_REVEAL_WINDOW_MS);
    }
  });

  it.each(REVEAL_CLASSES)('still says something under reduced motion on .%s', className => {
    // Losing a new column off the right edge, or a copy among a hundred rows, is exactly as
    // likely for someone who asked for less motion, so the answer stays and only the movement
    // goes. `step-end` also keeps it an ANIMATION, not `animation: none`.
    const override = reducedMotionBlocks()
      .map(block => block.match(new RegExp(`\\.${className}\\s*(?:,\\s*[^{]*)?\\{([^}]*)\\}`)))
      .find(Boolean);
    expect(override, `no reduced-motion override for .${className}`).toBeTruthy();
    expect(override![1]).toMatch(/animation:\s*[\w-]+\s+[\d.]+s\s+step-end/);
  });

  it('ends every reveal animation at the element\'s plain appearance', () => {
    // Derived from the rules, not from a list written here: a cue that switched to a new keyframe
    // would otherwise leave this test checking an animation nothing uses any more.
    for (const name of everyRevealAnimation()) {
      const frames = framesOf(name);
      const lastFrame = frames.match(/100%\s*\{([^}]*)\}/);
      expect(lastFrame, `@keyframes ${name} has no 100% frame`).toBeTruthy();

      // Whatever it paints, it must end at zero alpha - a ring, a wash, or both.
      const painted = lastFrame![1].match(/rgba\([^)]*\)/g) ?? [];
      expect(painted.length, `@keyframes ${name} paints nothing at 100%`).toBeGreaterThan(0);
      for (const colour of painted) {
        expect(colour, `@keyframes ${name} still paints at 100%`).toMatch(/,\s*0\)$/);
      }
    }
  });

  it('never animates opacity, because the cue arrives after the element has been painted', () => {
    // These classes come from React state one render AFTER the refetch that produced the elements,
    // so the elements have already shown themselves once. A fade starting at 0 would blink them
    // out and back in - and on a row it would composite the sticky cells inside it for the whole
    // cue. Colour is the only thing safe to animate here.
    for (const name of everyRevealAnimation()) {
      expect(framesOf(name), `@keyframes ${name} animates opacity`).not.toMatch(/opacity\s*:/);
    }
  });

  it('never persists the last frame past the animation', () => {
    // `forwards` would keep the final paint applied after the animation, so removing the class
    // would become a second, visible step on a cue whose whole point is to settle by itself.
    for (const className of REVEAL_CLASSES) {
      for (const rule of rulesFor(className)) {
        expect(rule).not.toMatch(/animation-fill-mode/);
        expect(rule).not.toMatch(/animation:[^;]*\b(forwards|both)\b/);
      }
    }
  });
});
