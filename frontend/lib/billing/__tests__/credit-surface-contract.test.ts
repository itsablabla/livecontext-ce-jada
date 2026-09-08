/**
 * Contracts this feature depends on that live OUTSIDE any component's render
 * output, and are therefore invisible to every rendering test.
 *
 * Both were found by mutation: renaming the gold custom properties in
 * globals.css, and re-introducing a second allowance derivation on the quota
 * page, each left the whole suite green while breaking the feature's headline
 * behaviour for real users.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = join(__dirname, '..', '..', '..');
const read = (rel: string) => readFileSync(join(root, rel), 'utf-8');

describe('the gold gauge palette is actually defined', () => {
  const css = read('app/globals.css');
  const source =
    read('components/billing/CreditBalance.tsx') +
    read('components/billing/SidebarCreditBalance.tsx');

  // Derived from the components, NOT hardcoded here: a NEW gold variable is
  // covered the moment it is used, without anyone remembering to add it.
  //
  // The name pattern is deliberately PERMISSIVE (any identifier character, not
  // just lowercase). A stricter class silently fails to match a mistyped name,
  // which drops it out of this list instead of failing on it - so renaming
  // `--credit-gold-arc-track` to something globals.css does not define made the
  // reference disappear and the suite stay green, which is the exact hole this
  // file was written to close.
  const referenced = [...new Set([...source.matchAll(/var\((--credit-gold-[A-Za-z0-9_-]+)\)/g)].map((m) => m[1]))];

  // Pinned BY NAME, not by a count. A floor of "at least 5" over 6 variables
  // buys exactly one free deletion, and the set-equality below is blind to a
  // SYMMETRIC one: delete `--credit-gold-ink` from globals.css and replace its
  // two use sites with a literal, and both halves shrink together while every
  // assertion stays green. That variable is the only colour on the "+X% over
  // your plan" text and on the ring's "+", and it exists precisely because the
  // bright metal fails contrast on white - so losing it silently is the worst
  // case this file has to prevent.
  const REQUIRED = [
    '--credit-gold-ink',
    '--credit-gold-arc',
    '--credit-gold-arc-track',
    '--credit-gold-fill-from',
    '--credit-gold-fill-to',
    '--credit-gold-track',
  ];

  it.each(REQUIRED)('still uses %s somewhere in the credit components', (name) => {
    expect(referenced).toContain(name);
  });

  it('keeps EVERY use of the gold ink, not just one', () => {
    // Membership is not enough. `--credit-gold-ink` is read in two places (the
    // ring's "+" and the gauge's sentence); replacing ONE of them with a hex
    // literal keeps the other reference alive, so the set check still passed
    // while light-mode readers got the dark-theme metal on white at ~1.8:1.
    const inkUses = [...source.matchAll(/var\(--credit-gold-ink\)/g)].length;
    expect(inkUses).toBeGreaterThanOrEqual(2);
  });

  it('never hardcodes a gold hex beside a `gold` branch', () => {
    // The shape the mutation above takes: a literal where the token belongs.
    // Every gold colour must come from a custom property, or the light and dark
    // palettes stop being able to differ at all.
    const goldHexes = [...source.matchAll(/gold\s*\?\s*'#[0-9a-fA-F]{3,8}'/g)];
    expect(goldHexes).toEqual([]);
  });

  it('names exactly the palette globals.css declares, with nothing orphaned either way', () => {
    // Bidirectional. The per-name checks below only prove source -> CSS; this
    // also catches a variable left declared in CSS after its last use, and it
    // fails on a rename from EITHER side rather than quietly resizing the list.
    const declared = [...new Set([...css.matchAll(/(--credit-gold-[A-Za-z0-9_-]+)\s*:/g)].map((m) => m[1]))];
    expect([...referenced].sort()).toEqual([...declared].sort());
  });

  // Each theme is checked against its OWN block. Searching the whole file
  // instead is an always-green assertion: the .dark declarations alone satisfy
  // it, so deleting the entire :root palette left this suite passing while every
  // light-mode user got an invalid-at-computed-value-time stroke and background.
  const rootBlock = css.slice(css.indexOf(':root {'), css.indexOf('.dark {'));
  const darkBlock = css.slice(css.indexOf('.dark {'));

  it('found both theme blocks to slice', () => {
    // Without this, a renamed selector would make both slices empty or identical
    // and turn every per-name check below into a vacuous pass.
    expect(rootBlock.length).toBeGreaterThan(0);
    expect(darkBlock.length).toBeGreaterThan(0);
    expect(rootBlock).not.toContain('.dark {');
  });

  it.each(referenced)('defines %s on :root, for the light theme', (name) => {
    expect(rootBlock).toContain(`${name}:`);
  });

  it.each(referenced)('redefines %s under .dark', (name) => {
    // A variable declared only on :root renders the light-theme gold on a dark
    // ground: legible, but wrong, and no rendering test can see it.
    expect(darkBlock).toContain(`${name}:`);
  });

  it('draws the over-allowance bar with a gold track, not the neutral one', () => {
    // The over state deliberately does not render the neutral track span, so if
    // --credit-gold-track disappears the bar loses its background entirely and
    // the fill floats unanchored. That is the specific mutation this kills.
    expect(referenced).toContain('--credit-gold-track');
  });
});

/**
 * Comments stripped before matching. These assertions read raw source, so a
 * future note that merely MENTIONS `CREDIT_TIERS` on the quota page would fail
 * CI with no behavioural change - the mirror image of a mutation that lands on
 * a comment instead of the code it looks like.
 */
function code(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

describe('the monthly allowance has exactly one derivation', () => {
  const quotaPage = code(read('app/[locale]/app/settings/quota/page.tsx'));

  it('has the quota page CALL the shared wallet hook', () => {
    // `toContain('useCreditWallet')` is satisfied by a comment mentioning it.
    // Match the call, and the destructure that takes the allowance out of it.
    expect(quotaPage).toMatch(/useCreditWallet\s*\(\s*\)/);
    expect(quotaPage).toMatch(/allowance\s*,[\s\S]{0,80}=\s*useCreditWallet/);
  });

  it('feeds the wallet card FROM that allowance, not from a constant', () => {
    // Without this, replacing the derivation with `const monthlyPlan = undefined`
    // left the ENTIRE suite green - and with it the migration's whole
    // user-visible payoff, a FREE cloud account finally seeing its 1,000-credit
    // monthly grant on the page the header dial links to.
    expect(quotaPage).toMatch(
      /monthlyPlan\s*=\s*allowance\s*!==\s*null\s*\?\s*\{\s*allowance\s*\}\s*:\s*undefined/,
    );
  });

  it('re-derives the allowance from NOTHING else on the page', () => {
    // The claim is "exactly one derivation", so name every other way to reach
    // one: the tier table, and the resolver the hook itself calls. Checking a
    // single constant let the page reach the same number by another route.
    expect(quotaPage).not.toContain('CREDIT_TIERS');
    expect(quotaPage).not.toContain('pricing-constants');
    expect(quotaPage).not.toContain('resolveMonthlyAllowance');
    expect(quotaPage).not.toContain('FREE_MONTHLY_CREDITS');
  });
});
