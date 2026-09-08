import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import postcss from 'postcss';
import tailwind from '@tailwindcss/postcss';

/**
 * The cap the whole fix rests on is a CLASS, so something has to prove it still
 * produces CSS.
 *
 * Moving it out of an inline style is what let a call site's own `max-w-*` win
 * again, but it also traded a guaranteed DOM property for a rule Tailwind has to
 * generate. `calc(100vw-1rem)` is not valid CSS on its own - Tailwind normalises
 * the operators inside an arbitrary value - and `var(--x, fallback)` puts a
 * comma inside the brackets. If either stopped being handled, the class would
 * emit nothing, every class-name assertion would still pass, and the menus would
 * quietly overflow again: jsdom parses no stylesheet and cannot see it.
 *
 * So this compiles the two classes with the project's own Tailwind and reads the
 * declaration back.
 */
const CAPS = [
  ['popover and tooltip', 'max-w-[var(--radix-popper-available-width,calc(100vw-1rem))]', '--radix-popper-available-width'],
  ['select', 'max-w-[var(--radix-select-content-available-width,calc(100vw-1rem))]', '--radix-select-content-available-width'],
] as const;

async function compile(classNames: string[]): Promise<string> {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tw-cap-'));
  const probe = path.join(dir, 'probe.html');
  fs.writeFileSync(probe, `<div class="${classNames.join(' ')}"></div>`);
  try {
    const result = await postcss([tailwind()]).process(
      `@import "tailwindcss" source(none);
@source "${probe.split(path.sep).join('/')}";
`,
      // `from` decides where `@import "tailwindcss"` is resolved from, so it has
      // to sit inside the project even though nothing is written there. The
      // probe markup itself lives in the temp dir and is named absolutely.
      { from: path.join(process.cwd(), 'menu-viewport-cap.probe.css') },
    );
    return result.css;
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

describe('the viewport cap compiles to real CSS', () => {
  it.each(CAPS)('%s', async (_label, className, variable) => {
    const css = await compile([className]);

    const rule = css
      .split('}')
      .map((block) => block.trim())
      .find((block) => block.includes(variable) && block.includes('max-width'));

    expect(rule, `Tailwind emitted no max-width rule for ${className}`).toBeDefined();
    // The whole declaration, not "it mentions the variable somewhere": emitting
    // `var(--x;calc(...))` would satisfy a contains-check and be dropped by the
    // browser. The comma separating the variable from its fallback is the part
    // that makes this a var() the browser will honour, and the fallback is what
    // caps the menu on the frame before Radix has measured anything - and in
    // Select's item-aligned mode, where the variable is never set.
    // Compared with the whitespace taken out, so the assertion is about the
    // DECLARATION rather than about formatting.
    expect(rule?.replace(/\s+/g, '')).toContain(
      `max-width:var(${variable},calc(100vw-1rem))`,
    );
  }, 30000);

  it('is the class the components actually carry', async () => {
    // Compiling a string this file made up would prove nothing about the app, so
    // the class is read back out of the sources it has to work in.
    const sources = ['components/ui/popover.tsx', 'components/ui/tooltip.tsx', 'components/ui/select.tsx']
      .map((file) => fs.readFileSync(path.join(process.cwd(), file), 'utf8'))
      .join('\n');

    for (const [, className] of CAPS) {
      expect(sources).toContain(className);
    }
  });
});
