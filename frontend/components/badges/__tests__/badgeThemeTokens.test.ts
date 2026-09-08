import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Every CSS custom property the trophy components name must actually exist.
 *
 * <p>This is not hygiene, it is a bug that shipped. The badge components were
 * written against `--border-primary`, a token this app has never defined (the
 * real one is `--border-color`). An undefined custom property does not warn, does
 * not throw and does not fall back to a theme value: `border-[var(--undefined)]`
 * simply leaves `border-color` at its initial value, `currentColor`. So every
 * earned trophy tile was framed in the TEXT colour - a hard black rectangle in
 * light mode - and the only visible symptom was a wall that looked wrong.
 *
 * <p>Reading the tokens out of `globals.css` rather than listing them here is
 * the point: a token renamed in the theme fails this test instead of silently
 * turning some component's border black again.
 */

const COMPONENT_DIR = path.join(__dirname, '..');
const GLOBALS_CSS = path.join(__dirname, '..', '..', '..', 'app', 'globals.css');

/** Custom properties DEFINED anywhere in the stylesheet (`--name:` at a declaration). */
function definedTokens(): Set<string> {
  const css = fs.readFileSync(GLOBALS_CSS, 'utf8');
  const defined = new Set<string>();
  for (const match of css.matchAll(/(--[a-z0-9-]+)\s*:/gi)) defined.add(match[1]);
  return defined;
}

/** Custom properties USED as `var(--name)` in one source file. */
function usedTokens(file: string): Set<string> {
  const source = fs.readFileSync(file, 'utf8');
  const used = new Set<string>();
  for (const match of source.matchAll(/var\(\s*(--[a-z0-9-]+)/gi)) used.add(match[1]);
  return used;
}

function componentFiles(): string[] {
  return fs
    .readdirSync(COMPONENT_DIR)
    .filter((name) => name.endsWith('.tsx') || name.endsWith('.ts'))
    .map((name) => path.join(COMPONENT_DIR, name));
}

describe('badge theme tokens', () => {
  it('reads the token list out of the stylesheet rather than trusting a hand-written one', () => {
    const defined = definedTokens();
    // A parse that silently matched nothing would make every assertion below
    // vacuous, which is the failure mode this whole file exists to avoid.
    expect(defined.size).toBeGreaterThan(10);
    expect(defined).toContain('--border-color');
    expect(defined).toContain('--bg-secondary');
  });

  it.each(componentFiles().map((file) => [path.basename(file), file]))(
    '%s names only custom properties the theme defines',
    (_name, file) => {
      const defined = definedTokens();
      const unknown = [...usedTokens(file)].filter((token) => !defined.has(token));

      expect(unknown).toEqual([]);
    },
  );

  it('finds the components it claims to scan', () => {
    const names = componentFiles().map((file) => path.basename(file));
    expect(names).toContain('BadgeMedal.tsx');
    expect(names).toContain('BadgeCard.tsx');
  });

  it('would have caught the token that shipped undefined', () => {
    // Proof the check has teeth: the exact name the badges used is absent from
    // the stylesheet, so the assertion above is doing real work.
    expect(definedTokens().has('--border-primary')).toBe(false);
  });
});
