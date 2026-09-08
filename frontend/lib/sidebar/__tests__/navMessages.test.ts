// @vitest-environment node
/**
 * Every label the sidebar's navigation resolves must exist AND be translated in
 * all six locales.
 *
 * This is the test that was missing when the navigation rows were moved from the
 * flat `sidebar.<page>` keys onto `sidebar.nav.<page>`: the destination
 * namespace had been the collapsed rail's tooltips, where five of the eight
 * entries were still the English word, so the move silently un-translated
 * Agents, Applications, Workflows, Interfaces and Tables for German, Spanish,
 * Portuguese and Chinese users. Every check below was red against that state.
 *
 * "Translated" is asserted through Chinese only, and it is a canary, not a
 * proof. Chinese is the one locale here whose script differs from English, so a
 * copied English string is detectable without a dictionary; the same rule cannot
 * be applied to German or Spanish, where "Workflows" and "Interfaces" ARE the
 * real translations and a word-for-word match is correct. So a placeholder that
 * somehow reached only German would still slip through: what this catches is the
 * realistic case, a whole namespace left untranslated, which is what happened.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { MARKETPLACE_NAV_ITEM, SIDEBAR_NAV_ITEMS } from '@/lib/sidebar/navItems';
import { QUICK_OPEN_SHORTCUT } from '@/lib/sidebar/quickOpenShortcut';

/**
 * Every label the sidebar resolves through `sidebar.nav.*`: the customizable
 * pages, plus the two fixed entries around them. Marketplace and New Chat are
 * in here because they carried the same two-keys-one-label drift the pages did
 * (a German user read "Marketplace" on the rail and "Marktplatz" in the panel).
 * `home` is deliberately absent: the entry that used to carry that label now
 * resolves `newChat`, and the key is gone from every locale, so listing it here
 * would guard a string nothing reads.
 */
const NAV_TITLE_KEYS = [
  ...SIDEBAR_NAV_ITEMS.map((item) => item.titleKey),
  MARKETPLACE_NAV_ITEM.titleKey,
  'newChat',
];

const LOCALES = ['en', 'fr', 'de', 'es', 'pt', 'zh'] as const;

/** The prose of the customize menu: every string a reader is meant to read. */
const CUSTOMIZE_PROSE_KEYS = [
  'title',
  'pagesTitle',
  'pagesHint',
  'minimumReached',
  'quickOpenTitle',
  'quickOpenHint',
  'reset',
  'shortcutAria',
  'shortcutTitle',
];

/**
 * The key names of the quick-open shortcut. Held apart from the prose because
 * they are keycap legends: `Ctrl` and `O` are printed on the physical keyboard,
 * so a locale is right to leave them in Latin script even where its prose is
 * not. Only the modifier that HAS a local name is expected to change (French
 * `Maj`, German `Umschalt`).
 */
const CUSTOMIZE_SHORTCUT_KEYS = ['shortcutKeys', 'shortcutKeysMac'];

const CUSTOMIZE_KEYS = [...CUSTOMIZE_PROSE_KEYS, ...CUSTOMIZE_SHORTCUT_KEYS];

/** The search bar spells its own chord the same way, and drifts the same way. */
const SEARCH_SHORTCUT_KEYS = ['shortcutKeys', 'shortcutKeysMac'];

/** The overflow menu's own strings, in front of the customize panel. */
const MORE_KEYS = ['title', 'back'];

interface SidebarMessages {
  nav?: Record<string, string>;
  more?: Record<string, string>;
  customize?: Record<string, string>;
  [flatKey: string]: unknown;
}

function sidebarMessages(locale: string): SidebarMessages {
  const raw = readFileSync(join(__dirname, `../../../messages/${locale}.json`), 'utf8');
  return JSON.parse(raw).sidebar as SidebarMessages;
}

/** Chinese is written in a non-Latin script; a pure-ASCII value is the English word. */
function isWrittenInChinese(value: string): boolean {
  return /[^\x00-\x7F]/.test(value);
}

describe('sidebar navigation labels', () => {
  LOCALES.forEach((locale) => {
    it(`${locale} names every page the sidebar can draw`, () => {
      const sidebar = sidebarMessages(locale);

      const missing = NAV_TITLE_KEYS.filter((key) => !sidebar.nav?.[key]?.trim());

      expect(missing).toEqual([]);
    });

    it(`${locale} carries every customize-menu string`, () => {
      const sidebar = sidebarMessages(locale);

      const missing = CUSTOMIZE_KEYS.filter((key) => !sidebar.customize?.[key]?.trim());

      expect(missing, locale).toEqual([]);
    });

    it(`${locale} spells the quick-open shortcut with the letter the matcher listens for`, () => {
      // A locale that translated the letter itself would print keys nobody can
      // press: the handler matches the character `o`, whatever the prose says.
      const customize = sidebarMessages(locale).customize!;

      CUSTOMIZE_SHORTCUT_KEYS.forEach((key) => {
        // The LAST character, not `toContain`: a modifier name that happens to
        // hold an `o` would satisfy a substring check without the letter ever
        // being printed.
        expect(customize[key].trim().slice(-1).toLowerCase(), `${locale}.${key}`).toBe(
          QUICK_OPEN_SHORTCUT.key,
        );
      });
    });

    it(`${locale} names the overflow menu and its way back`, () => {
      const sidebar = sidebarMessages(locale);

      const missing = MORE_KEYS.filter((key) => !sidebar.more?.[key]?.trim());

      expect(missing, locale).toEqual([]);
    });

    it(`${locale} carries the search bar's shortcut keys too`, () => {
      // Added in the same pass and just as easy to forget: a missing key here
      // falls back to English silently.
      const search = JSON.parse(
        readFileSync(join(__dirname, `../../../messages/${locale}.json`), 'utf8'),
      ).globalSearch as Record<string, string>;

      const missing = SEARCH_SHORTCUT_KEYS.filter((key) => !search[key]?.trim());

      expect(missing, locale).toEqual([]);
      expect(search.shortcutKeys.trim().slice(-1).toLowerCase(), locale).toBe('k');
      expect(search.shortcutKeysMac.trim().slice(-1).toLowerCase(), locale).toBe('k');
    });
  });

  it('translates every page name into Chinese instead of leaving the English word', () => {
    const sidebar = sidebarMessages('zh');

    const untranslated = NAV_TITLE_KEYS.filter((key) => !isWrittenInChinese(sidebar.nav![key]));

    expect(untranslated).toEqual([]);
  });

  it('translates the customize menu into Chinese too', () => {
    const sidebar = sidebarMessages('zh');

    const untranslated = CUSTOMIZE_PROSE_KEYS.filter((key) => !isWrittenInChinese(sidebar.customize![key]));

    expect(untranslated).toEqual([]);
  });

  it('translates the overflow menu into Chinese too', () => {
    const sidebar = sidebarMessages('zh');

    const untranslated = MORE_KEYS.filter((key) => !isWrittenInChinese(sidebar.more![key]));

    expect(untranslated).toEqual([]);
  });

  it('keeps the ICU placeholder in every locale that mentions the floor', () => {
    LOCALES.forEach((locale) => {
      const customize = sidebarMessages(locale).customize!;

      expect(customize.pagesHint, locale).toContain('{count}');
      expect(customize.minimumReached, locale).toContain('{count}');
      // The tooltip composes two values; a locale dropping one loses the page
      // name or the keys with no error, only a stranger tooltip.
      expect(customize.shortcutTitle, locale).toContain('{label}');
      expect(customize.shortcutTitle, locale).toContain('{keys}');
    });
  });

  it('leaves no orphaned copy of a page name outside the nav namespace', () => {
    // Two keys for one label is how the translations drifted apart in the first
    // place: the flat ones stayed translated while the nav ones did not.
    LOCALES.forEach((locale) => {
      const sidebar = sidebarMessages(locale);

      const orphans = NAV_TITLE_KEYS.filter((key) => sidebar[key] !== undefined);

      expect(orphans, locale).toEqual([]);
    });
  });
});
