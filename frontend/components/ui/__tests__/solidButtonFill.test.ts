/**
 * One solid button fill for the whole app.
 *
 * <p>There used to be two. `default` fills with `--accent-primary`, and a second
 * variant named `contrast` hardcoded `bg-black` / `dark:bg-white` for the same
 * job. Because `--accent-primary` IS near-black in light (#0b0d16) and near-white
 * in dark (#edecea), the two rendered as the same button drawn twice - one
 * following the theme tokens, one not - and which one a surface got was down to
 * whoever wrote it. "Compare plans" and "View pricing", side by side on the same
 * pricing row, were the two spellings a reader could see at once.
 *
 * <p>A THIRD spelling lived at the call sites and no edit to the variant table
 * ever reached it: a control painting the inversion into its own className
 * (`bg-black … dark:bg-white`, `bg-slate-900 … dark:bg-white`, `bg-gray-900 …
 * dark:bg-gray-100`). It appeared on `<Button>`s, on plain `<button>`s, on a
 * `role="link"` span, and once inside a `dockButtonClass()` helper where no tag
 * scan would ever see it.
 *
 * <p>A FOURTH was the inverse mistake: filling with `--accent-primary` and then
 * writing `text-white` on top. That is invisible in dark, where the token is
 * #edecea - white on white. Three live badges had it.
 *
 * <p>So the guard has one part per spelling. It covers CONTROLS - a Button, a
 * plain button, a link, or anything given a click handler or a button/link role.
 * Non-interactive black/white surfaces (progress bars, `StepIndicator` bubbles,
 * `SelectionActionBar`, the "Most popular" badge) are decoration and labels, are
 * deliberately out of scope, and are noted as such in ../README.md. The
 * radius/height half of the same system is in radiusLadder.test.ts.
 */
import { readFileSync, readdirSync, lstatSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { buttonVariants } from '@/components/ui/button';

const FRONTEND_ROOT = join(__dirname, '../../..');
const BUTTON_SOURCE = readFileSync(join(FRONTEND_ROOT, 'components/ui/button.tsx'), 'utf8');

// ---------------------------------------------------------------------------
// What counts as a near-black / near-white fill
// ---------------------------------------------------------------------------

/**
 * The named shades stop at 700, not at 900.
 *
 * <p>`bg-gray-800 dark:bg-gray-200` is the hover pair the removed `contrast`
 * variant itself used, so a set that started at 900 could not have caught half of
 * the thing this test commemorates.
 */
const DARKEST = String.raw`(?:black|(?:slate|gray|neutral|zinc|stone)-(?:700|800|900|950))`;
const LIGHTEST = String.raw`(?:white|(?:slate|gray|neutral|zinc|stone)-(?:50|100|200))`;

/** An arbitrary hex fill, judged by its channels rather than by its spelling. */
const HEX_FILL = String.raw`(?<![\w/-])bg-\[#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\]`;
const DARK_HEX_FILL = String.raw`(?<![\w/-])dark:bg-\[#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\]`;

function channels(hex: string): number[] {
  const full = hex.length === 3 ? [...hex].map((c) => c + c).join('') : hex;
  return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16));
}

const isNearBlack = (hex: string) => channels(hex).every((c) => c <= 0x33);
const isNearWhite = (hex: string) => channels(hex).every((c) => c >= 0xcc);

function anyHex(text: string, pattern: string, predicate: (hex: string) => boolean): boolean {
  return [...text.matchAll(new RegExp(pattern, 'g'))].some((m) => predicate(m[1]));
}

const NAMED_DARK_FILL = new RegExp(String.raw`(?<![\w/:-])bg-${DARKEST}(?![\w/-])`);
const NAMED_LIGHT_FILL = new RegExp(String.raw`(?<![\w/:-])bg-${LIGHTEST}(?![\w/-])`);
const NAMED_DARK_MODE_LIGHT_FILL = new RegExp(String.raw`(?<![\w/-])dark:bg-${LIGHTEST}(?![\w/-])`);

/**
 * A hardcoded near-black or near-white BACKGROUND, in any spelling.
 *
 * <p>The `(?![\w/-])` tail is what keeps a translucent scrim (`bg-white/40` over a
 * fullscreen iframe) out of it: an opacity modifier is a veil, not a fill. The hex
 * arm is judged on channels so that a semantic colour (`bg-[#dc5c5c]`, the
 * destructive red) is not swept up with it.
 */
function hasHardcodedFill(text: string): boolean {
  return (
    NAMED_DARK_FILL.test(text) ||
    NAMED_LIGHT_FILL.test(text) ||
    anyHex(text, HEX_FILL, (hex) => isNearBlack(hex) || isNearWhite(hex))
  );
}

/**
 * A hand-rolled theme-inverting solid: DARK in light mode, LIGHT in dark mode -
 * inverted against the page it sits on, which is what made it read as a button.
 *
 * <p>Deliberately one direction only. The opposite pair (`bg-white` +
 * `dark:bg-slate-900`) is the ordinary panel / dropdown surface, which follows the
 * page rather than standing against it, and is not this defect.
 */
function isThemeInvertingSolid(text: string): boolean {
  const light = NAMED_DARK_FILL.test(text) || anyHex(text, HEX_FILL, isNearBlack);
  const dark = NAMED_DARK_MODE_LIGHT_FILL.test(text) || anyHex(text, DARK_HEX_FILL, isNearWhite);
  return light && dark;
}

/** The accent fill wearing a literal text colour instead of its own token. */
const ACCENT_FILL = /bg-\[var\(--accent-primary\)\]/;
const LITERAL_TEXT = /(?<![\w/-])(?:dark:)?text-(?:black|white)(?![\w/-])/;

// ---------------------------------------------------------------------------
// Reading the sources
// ---------------------------------------------------------------------------

function sourceFilesUnder(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    // A test fixture may render a deliberately wrong button to assert something.
    if (entry === 'node_modules' || entry === '.next' || entry === '__tests__') continue;
    const full = join(dir, entry);
    // lstat, never stat: a broken symlink must be skipped, not throw and take the
    // whole suite down with it.
    const stats = lstatSync(full);
    if (stats.isSymbolicLink()) continue;
    if (stats.isDirectory()) sourceFilesUnder(full, out);
    // `.ts` too: the app's real class helpers (canvas-chrome, panel-tab) live
    // there, and they are exactly the category the tag scan cannot see.
    else if (entry.endsWith('.tsx') || entry.endsWith('.ts')) out.push(full);
  }
  return out;
}

interface OpeningTag {
  name: string;
  text: string;
  line: number;
}

const lineOf = (source: string, index: number) => source.slice(0, index).split('\n').length;

/**
 * Every JSX OPENING TAG in a source file, attributes only.
 *
 * <p>Reading to the first `>` does not work: an `onClick={() => …}` handler puts a
 * `>` inside the attributes, and half the call sites carry one BEFORE their
 * className - the guard would stop short and see nothing. Tracking brace depth
 * alone is not enough either: a `title="score > 90"` truncates the tag, a `{"}"}`
 * drives the depth negative and makes the tag vanish, and an apostrophe in an
 * attribute comment ("the app's primary button") opens a string that never closes
 * and swallows the rest of the file. So this tracks string literals and comments
 * as well as depth, and a `<Button>` it cannot terminate is REPORTED rather than
 * dropped: a guard that fails open is worse than none.
 *
 * <p>Attributes only, never the children: an icon child with its own `bg-white` is
 * not this control painting its fill.
 */
function openingTags(source: string): { tags: OpeningTag[]; unterminated: number[] } {
  const tags: OpeningTag[] = [];
  const unterminated: number[] = [];
  const opener = /<([A-Za-z][\w.]*)/g;
  let match: RegExpExecArray | null;

  while ((match = opener.exec(source)) !== null) {
    let depth = 0;
    let quote: string | null = null;
    let closed = false;

    for (let i = match.index + match[0].length; i < source.length; i++) {
      const ch = source[i];
      if (quote) {
        if (ch === '\\') i++;
        else if (ch === quote) quote = null;
      } else if (ch === '/' && source[i + 1] === '/') {
        const newline = source.indexOf('\n', i);
        if (newline === -1) break;
        i = newline;
      } else if (ch === '/' && source[i + 1] === '*') {
        const end = source.indexOf('*/', i + 2);
        if (end === -1) break;
        i = end + 1;
      } else if (ch === '"' || ch === "'" || ch === '`') {
        quote = ch;
      } else if (ch === '{' || ch === '(' || ch === '[') {
        depth++;
      } else if (ch === '}' || ch === ')' || ch === ']') {
        depth--;
      } else if (ch === '>' && depth === 0) {
        tags.push({
          name: match[1],
          text: source.slice(match.index, i),
          line: lineOf(source, match.index),
        });
        closed = true;
        break;
      }
    }

    // Reported only for a real control. The same `<Name` shape also matches a
    // TypeScript generic (`React.forwardRef<React.ElementRef<typeof X>, …>`),
    // which this scanner does not parse and which can never be a button.
    if (!closed && (match[1] === 'Button' || match[1] === 'button')) {
      unterminated.push(lineOf(source, match.index));
    }
  }

  return { tags, unterminated };
}

/** A tag a reader presses. */
const CONTROL_TAGS = new Set(['Button', 'button', 'Link', 'a']);

function isControl(tag: OpeningTag): boolean {
  return CONTROL_TAGS.has(tag.name) || /onClick=|role="(?:button|link)"/.test(tag.text);
}

/**
 * Identifiers whose VALUE is a theme-inverting solid.
 *
 * <p>Matching on the NAME (`*ButtonClass`) was the first attempt, and a rename
 * defeats it, so this reads the value instead: any `const x = …` or `function x()`
 * whose body carries such a class string. The extent is found by balancing
 * brackets rather than by taking a fixed number of characters, so a long helper is
 * not truncated and a short one does not spill into its neighbour.
 */
function invertingClassSources(source: string): Map<string, number> {
  const found = new Map<string, number>();

  const readExtent = (start: number, stopAtSemicolon: boolean): string => {
    let depth = 0;
    let quote: string | null = null;
    for (let i = start; i < source.length; i++) {
      const ch = source[i];
      if (quote) {
        if (ch === '\\') i++;
        else if (ch === quote) quote = null;
      } else if (ch === '/' && source[i + 1] === '/') {
        // Same trap as in the tag scanner, and it bit here too: one apostrophe in
        // an explanatory comment ("the trigger's visible text") opens a string
        // that never closes, and the extent then runs to the end of the file and
        // picks up a class three hundred lines away.
        const newline = source.indexOf('\n', i);
        if (newline === -1) break;
        i = newline;
      } else if (ch === '/' && source[i + 1] === '*') {
        const end = source.indexOf('*/', i + 2);
        if (end === -1) break;
        i = end + 1;
      } else if (ch === '"' || ch === "'" || ch === '`') {
        quote = ch;
      } else if (ch === '{' || ch === '(' || ch === '[') {
        depth++;
      } else if (ch === '}' || ch === ')' || ch === ']') {
        depth--;
        if (!stopAtSemicolon && depth === 0) return source.slice(start, i + 1);
      } else if (stopAtSemicolon && ch === ';' && depth === 0) {
        return source.slice(start, i);
      }
    }
    return source.slice(start);
  };

  for (const match of source.matchAll(/(?:const|let)\s+([A-Za-z_$][\w$]*)[^=\n]*=/g)) {
    // Capitalised = a React component, whose whole body would be ingested. The
    // same skip is on the `function` arm below, and it is needed in BOTH: a
    // component written `const X = React.memo(function X() {…})` is declared by
    // the const, so guarding only the inner function let `PlanSelector` - which
    // renders an inverting badge - into the map.
    if (/^[A-Z]/.test(match[1])) continue;
    if (isThemeInvertingSolid(readExtent(match.index + match[0].length, true))) {
      found.set(match[1], lineOf(source, match.index));
    }
  }
  for (const match of source.matchAll(/function\s+([A-Za-z_$][\w$]*)\s*\(/g)) {
    // Capitalised = a React component by this codebase's convention, and its
    // whole body would be ingested: `PlanSelector` renders an inverting badge,
    // so it would land in the map and then flag any control that merely names
    // it. A class helper is lower-case.
    if (/^[A-Z]/.test(match[1])) continue;
    const brace = source.indexOf('{', match.index);
    if (brace === -1) continue;
    if (isThemeInvertingSolid(readExtent(brace, false))) {
      found.set(match[1], lineOf(source, match.index));
    }
  }

  return found;
}

/**
 * The identifiers a tag's className EXPRESSION refers to.
 *
 * <p>Bounded to the expression, not read to the end of the tag: everything after
 * it - a `title={…}`, a `data-*`, a handler - would otherwise be harvested too,
 * and a control that merely mentions a helper's name in a later attribute would
 * be reported for a class it does not use.
 */
function classNameIdentifiers(tag: string): string[] {
  const at = tag.indexOf('className=');
  if (at === -1) return [];

  const rest = tag.slice(at + 'className='.length);
  if (!rest.startsWith('{')) return [];

  let depth = 0;
  let quote: string | null = null;
  for (let i = 0; i < rest.length; i++) {
    const ch = rest[i];
    if (quote) {
      if (ch === '\\') i++;
      else if (ch === quote) quote = null;
    } else if (ch === '"' || ch === "'" || ch === '`') {
      quote = ch;
    } else if (ch === '{' || ch === '(' || ch === '[') {
      depth++;
    } else if (ch === '}' || ch === ')' || ch === ']') {
      depth--;
      if (depth === 0) {
        return [...rest.slice(0, i).matchAll(/[A-Za-z_$][\w$]*/g)].map((m) => m[0]);
      }
    }
  }
  return [];
}

/**
 * Class strings, one at a time.
 *
 * <p>Per LITERAL, deliberately, not per element: a ternary that puts `text-white`
 * on a green branch and `--accent-foreground` on the accent branch is correct
 * (`StepIndicator`), and reading a whole className would call that a defect.
 *
 * <p>The quoted literals get their OWN pass over the source, deliberately not one
 * alternation with the template literals. In a single alternation a backtick
 * literal is consumed whole, `lastIndex` jumps past the branches inside its
 * `${…}`, and a class written in one of those branches is never read at all -
 * which is where the accent-plus-`text-white` badge in `MonthView` lived. Two
 * passes read the branches AND keep the ternary from being judged as one string.
 *
 * <p>An unconditional `cn('bg-…', 'text-white')` splits the fill and the text
 * colour across two literals, so those calls are also checked whole. The
 * argument list has to be allowed one nesting level: every accent fill contains
 * `var(--accent-primary)`, so a paren-free pattern matches only class lists that
 * by construction can never carry one.
 */
function classStrings(source: string): { text: string; line: number }[] {
  const out: { text: string; line: number }[] = [];
  for (const match of source.matchAll(/'[^'\n]*'|"[^"]*"/g)) {
    out.push({ text: match[0], line: lineOf(source, match.index) });
  }
  for (const match of source.matchAll(/`[^`]*`/g)) {
    out.push({ text: match[0].replace(/\$\{[\s\S]*?\}/g, ' '), line: lineOf(source, match.index) });
  }
  for (const match of source.matchAll(/\b(?:cn|clsx|classNames)\(((?:[^()]|\([^()]*\))*)\)/g)) {
    if (!match[1].includes('?')) out.push({ text: match[1], line: lineOf(source, match.index) });
  }
  return out;
}

const SOURCE_FILES = [
  ...sourceFilesUnder(join(FRONTEND_ROOT, 'app')),
  ...sourceFilesUnder(join(FRONTEND_ROOT, 'components')),
];

const relative = (file: string) => file.slice(FRONTEND_ROOT.length + 1).replace(/\\/g, '/');
const read = (file: string) => readFileSync(file, 'utf8');

// ---------------------------------------------------------------------------

describe('the app has ONE solid button fill', () => {
  it('the default variant is that fill, and states it in theme tokens', () => {
    const classes = buttonVariants({ variant: 'default' });

    expect(classes).toContain('bg-[var(--accent-primary)]');
    expect(classes).toContain('text-[var(--accent-foreground)]');
    expect(classes).toContain('hover:bg-[var(--accent-hover)]');
  });

  it('no variant in the table hardcodes a near-black or near-white fill', () => {
    // The variant NAMES are read out of button.tsx, never listed here: a hand-kept
    // list would simply not mention the next `contrast` somebody adds, which is
    // the failure this test exists to prevent. (It also means this assertion fails
    // on the pre-fix source, where `contrast` was in the table.)
    const table = BUTTON_SOURCE.slice(
      BUTTON_SOURCE.indexOf('variant: {'),
      BUTTON_SOURCE.indexOf('size: {'),
    );
    const keys = table.match(/^\s+(\w+):$/gm) ?? [];
    const names = keys.map((key) => key.trim().slice(0, -1));

    // Two known members, and a count that matches the keys actually declared, so a
    // reformat that silently halves the list fails here instead of passing.
    expect(names).toContain('default');
    expect(names).toContain('destructive');
    expect(names.length).toBeGreaterThan(5);

    for (const name of names) {
      const classes = buttonVariants({ variant: name as never });
      expect(`${name}: ${hasHardcodedFill(classes)}`).toBe(`${name}: false`);
    }
  });

  it('the removed `contrast` variant resolves to nothing, not to a second dark fill', () => {
    // Unknown variant names fall through to the base classes. The point is that no
    // black fill survives under that name for a stray call site to pick up.
    expect(hasHardcodedFill(buttonVariants({ variant: 'contrast' as never }))).toBe(false);
  });

  it('the stylesheet carries no rule for the removed variant either', () => {
    // `Button` still emits `data-variant`, so a leftover
    // `.dark [data-variant="contrast"]` rule would silently repaint anything that
    // took the name back.
    expect(read(join(FRONTEND_ROOT, 'app/globals.css'))).not.toContain('[data-variant="contrast"]');
  });

  it('no control hand-paints the theme-inverting solid in its own tag', () => {
    const offenders: string[] = [];

    for (const file of SOURCE_FILES) {
      const source = read(file);
      if (!isThemeInvertingSolid(source)) continue;

      for (const tag of openingTags(source).tags) {
        if (isControl(tag) && isThemeInvertingSolid(tag.text)) {
          offenders.push(`${relative(file)}:${tag.line} <${tag.name}>`);
        }
      }
    }

    expect(offenders).toEqual([]);
  });

  it('no control borrows one from a class helper either', () => {
    // The chat header's pressed dock/activity state lived in `dockButtonClass()`,
    // outside every tag, where no tag scan could ever have seen it.
    // Resolved per file, plus the shared `.ts` modules every page can import.
    // A single global map by name would collide on the ordinary identifiers this
    // codebase reuses (`detail`, `label`) and report a file for another file's
    // string.
    const shared = new Map<string, { file: string; line: number }>();
    for (const file of SOURCE_FILES) {
      if (!file.endsWith('.ts')) continue;
      for (const [name, line] of invertingClassSources(read(file))) {
        shared.set(name, { file: relative(file), line });
      }
    }

    const offenders: string[] = [];
    for (const file of SOURCE_FILES) {
      if (!file.endsWith('.tsx')) continue;
      const source = read(file);
      const sources = new Map(shared);
      for (const [name, line] of invertingClassSources(source)) {
        sources.set(name, { file: relative(file), line });
      }

      for (const tag of openingTags(source).tags) {
        if (!isControl(tag)) continue;
        for (const identifier of classNameIdentifiers(tag.text)) {
          const origin = sources.get(identifier);
          if (origin) {
            offenders.push(
              `${relative(file)}:${tag.line} <${tag.name}> uses ${identifier} (${origin.file}:${origin.line})`,
            );
          }
        }
      }
    }

    expect(offenders).toEqual([]);
  });

  it('nothing writes a literal text colour on top of the accent fill', () => {
    // `--accent-primary` is #edecea in dark, so `text-white` on it is white on
    // white. `--accent-foreground` is the token that follows the fill.
    const offenders: string[] = [];

    for (const file of SOURCE_FILES) {
      const source = read(file);
      if (!ACCENT_FILL.test(source)) continue;

      for (const { text, line } of classStrings(source)) {
        if (ACCENT_FILL.test(text) && LITERAL_TEXT.test(text)) {
          offenders.push(`${relative(file)}:${line}`);
        }
      }
    }

    expect(offenders).toEqual([]);
  });

  it('reads every Button call site - a tag it cannot parse is reported, not skipped', () => {
    // The scans above are only worth their green if they actually saw the file. A
    // tag the parser cannot terminate would otherwise disappear without a word, so
    // the count is taken a second way too and the two must agree.
    const unparsed: string[] = [];
    let parsed = 0;
    let declared = 0;

    for (const file of SOURCE_FILES) {
      if (!file.endsWith('.tsx')) continue;
      const source = read(file);
      const { tags, unterminated } = openingTags(source);
      parsed += tags.filter((tag) => tag.name === 'Button').length;
      declared += (source.match(/<Button\b/g) ?? []).length;
      unparsed.push(...unterminated.map((line) => `${relative(file)}:${line}`));
    }

    expect(unparsed).toEqual([]);
    expect(declared).toBeGreaterThan(700);
    expect(parsed).toBe(declared);
  });
});

describe('a filter row is built from Buttons, not from its own pill', () => {
  it('the applications provenance filter uses the Button, solid when chosen', () => {
    // It was a hand-rolled `px-3 py-1.5 rounded-md` pill whose chosen state put
    // `--bg-primary` on the accent instead of `--accent-foreground`, standing on a
    // row that already carries two selects at the standard control height. Its
    // inactive state was a filled `--bg-tertiary` swatch and is now an outline,
    // which is the pairing the generation history filter row uses.
    const source = read(join(FRONTEND_ROOT, 'app/[locale]/app/applications/page.tsx'));
    const start = source.indexOf("(['all', 'installed', 'published'] as const)");
    const end = source.indexOf('{/* Visibility filter');

    // Both markers must still be there: an `indexOf` returning -1 would silently
    // widen the slice to the whole file and fail the negative assertions below for
    // entirely the wrong reason.
    expect(start).toBeGreaterThan(-1);
    expect(end).toBeGreaterThan(start);

    const filterBlock = source.slice(start, end);

    // Quote style and line breaks are prettier's business, so match the shape.
    expect(filterBlock).toMatch(
      /variant=\{\s*isActive\s*\?\s*['"]default['"]\s*:\s*['"]outline['"]\s*\}/,
    );
    expect(filterBlock).toMatch(/aria-pressed=\{\s*isActive\s*\}/);
    expect(filterBlock).not.toMatch(/rounded-md/);
    expect(filterBlock).not.toMatch(/py-1\.5/);
  });
});
