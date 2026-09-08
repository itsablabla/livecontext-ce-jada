// @vitest-environment node
/**
 * The key combination behind the home page's quick-open button.
 *
 * Tested apart from any component because the risky part of a global shortcut
 * is not where it navigates, it is which keystrokes it swallows from the
 * person typing. Every "ignores" case below is a keystroke the app must leave
 * alone.
 */
import { describe, expect, it } from 'vitest';
import {
  QUICK_OPEN_ARIA_KEYSHORTCUTS,
  QUICK_OPEN_SHORTCUT,
  matchesQuickOpenShortcut,
} from '../quickOpenShortcut';

const event = (over: Partial<Parameters<typeof matchesQuickOpenShortcut>[0]> = {}) => ({
  key: 'O',
  shiftKey: true,
  ctrlKey: true,
  metaKey: false,
  altKey: false,
  repeat: false,
  isComposing: false,
  ...over,
});

describe('quick-open shortcut matching', () => {
  it('matches Ctrl+Shift+O', () => {
    expect(matchesQuickOpenShortcut(event())).toBe(true);
  });

  it('matches Cmd+Shift+O, the same combination on a Mac keyboard', () => {
    expect(matchesQuickOpenShortcut(event({ ctrlKey: false, metaKey: true }))).toBe(true);
  });

  it('ignores the letter typed on its own', () => {
    expect(matchesQuickOpenShortcut(event({ shiftKey: false, ctrlKey: false }))).toBe(false);
  });

  it('ignores Ctrl+O, which browsers use to open a file', () => {
    expect(matchesQuickOpenShortcut(event({ shiftKey: false }))).toBe(false);
  });

  it('ignores Shift+O, which is how the capital letter is typed', () => {
    expect(matchesQuickOpenShortcut(event({ ctrlKey: false }))).toBe(false);
  });

  it('ignores Ctrl+Alt+Shift+O, because Ctrl+Alt is how AltGr reaches a keyboard', () => {
    expect(matchesQuickOpenShortcut(event({ altKey: true }))).toBe(false);
  });

  it('ignores every other letter held with the same modifiers', () => {
    expect(matchesQuickOpenShortcut(event({ key: 'K' }))).toBe(false);
  });

  it('ignores the repeats of a held-down chord, so the page opens once', () => {
    expect(matchesQuickOpenShortcut(event({ repeat: true }))).toBe(false);
  });

  it('ignores a keystroke mid-composition, which belongs to the character being formed', () => {
    expect(matchesQuickOpenShortcut(event({ isComposing: true }))).toBe(false);
  });

  it('says no rather than throwing when the event carries no key at all', () => {
    // Synthetic events, and some older browsers on dead keys, leave `key`
    // undefined; a matcher that called `.toLowerCase()` on it would throw
    // inside a global keydown listener, on every keystroke.
    expect(matchesQuickOpenShortcut(event({ key: undefined as unknown as string }))).toBe(false);
  });

  it('matches the character, never the physical key position', () => {
    // The `KeyO` position emits `R` on Dvorak. Matching position would swallow
    // Ctrl+Shift+R, which is hard-reload - a keystroke that is not ours to take.
    expect(matchesQuickOpenShortcut(event({ key: 'R' }))).toBe(false);
  });
});

describe('quick-open shortcut declaration', () => {
  it('does not reuse the chord the search bar already owns', () => {
    // GlobalSearchBar binds Ctrl/Cmd+K. A shared chord would fire both.
    expect(matchesQuickOpenShortcut(event({ key: 'k', shiftKey: false }))).toBe(false);
    expect(QUICK_OPEN_SHORTCUT.key).not.toBe('k');
  });

  it('announces both platform spellings in the W3C key names screen readers expect', () => {
    // Derived from the descriptor rather than typed out again, so moving the
    // shortcut cannot leave the announcement pointing at the old keys.
    expect(QUICK_OPEN_ARIA_KEYSHORTCUTS).toBe('Control+Shift+O Shift+Meta+O');
    expect(QUICK_OPEN_ARIA_KEYSHORTCUTS).toContain(QUICK_OPEN_SHORTCUT.key.toUpperCase());
  });
});
