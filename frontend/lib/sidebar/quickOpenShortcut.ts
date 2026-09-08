'use client';

import { useTranslations } from 'next-intl';
import { useIsMacPlatform } from '@/lib/utils/platform';

/**
 * The keys that open the page picked in the sidebar's customize menu.
 *
 * Fixed rather than user-configurable: the destination is already a
 * preference, and one setting deciding WHERE it goes is easier to hold in your
 * head than two deciding where AND how.
 *
 * Ctrl/Cmd + Shift + O reads as "open", and unlike an arrow or a bare letter it
 * types nothing when a text field has focus, which matters because the home
 * composer holds the caret by default on the page this shortcut serves.
 *
 * It is a deliberate override: Chrome and Edge open the bookmark manager on
 * Ctrl+Shift+O and Firefox opens the Library. None of the three RESERVE it (a
 * page can cancel it, unlike Ctrl+N/T/W), and macOS leaves Cmd+Shift+O free.
 * The app already makes the same trade on Ctrl/Cmd+K, which is Firefox's
 * focus-the-search-bar chord, so this is the established line rather than a new
 * one. If it ever needs to move: the letter lives in the constant below, and
 * the keycap spelling in the `customize.shortcutKeys` / `shortcutKeysMac`
 * message keys.
 */
export const QUICK_OPEN_SHORTCUT = {
  /**
   * The character only. The modifiers (Shift, plus Ctrl or Cmd) are not fields
   * here because nothing could read them: the matcher tests `event.shiftKey`
   * and `event.ctrlKey || event.metaKey` directly, and the ARIA string below
   * spells them the W3C way. Declaring them as data would be config that lies -
   * flipping it would change no behaviour anywhere.
   */
  key: 'o',
} as const;

/**
 * Both platform spellings, for `aria-keyshortcuts`. Screen readers expect the
 * W3C key names, not the glyphs a sighted user reads off the keycap.
 *
 * Both are announced to everyone rather than only the one this keyboard has:
 * `aria-keyshortcuts` accepts a space-separated list precisely so a control can
 * declare its alternatives, and narrowing it would make the attribute depend on
 * a value that is unknown until after hydration - a changing accessible
 * description for a saving of four words.
 */
export const QUICK_OPEN_ARIA_KEYSHORTCUTS = [
  // Modifiers in WAI-ARIA's canonical order (Alt, Control, Shift, Meta).
  `Control+Shift+${QUICK_OPEN_SHORTCUT.key.toUpperCase()}`,
  `Shift+Meta+${QUICK_OPEN_SHORTCUT.key.toUpperCase()}`,
].join(' ');

type ShortcutEvent = Pick<
  KeyboardEvent,
  'key' | 'shiftKey' | 'ctrlKey' | 'metaKey' | 'altKey' | 'repeat' | 'isComposing'
>;

/**
 * Whether a keyboard event is the quick-open shortcut. Kept pure so the
 * combination can be tested without mounting anything: the risky part of a
 * global shortcut is not where it navigates, it is which keystrokes it
 * swallows from the person typing.
 */
export function matchesQuickOpenShortcut(event: ShortcutEvent): boolean {
  // A held-down chord should open the page once, not once per repeat.
  if (event.repeat) return false;
  // Mid-composition (IME), the keystroke belongs to the character being formed.
  if (event.isComposing) return false;
  if (!event.shiftKey) return false;
  if (!(event.ctrlKey || event.metaKey)) return false;
  // Alt is excluded rather than ignored: Ctrl+Alt is how AltGr reaches a
  // keyboard, so accepting it would steal characters people are typing.
  if (event.altKey) return false;
  // Matched on the CHARACTER, not on `code`. Matching the physical position
  // would fire on whatever that key emits under another layout - on Dvorak the
  // `KeyO` position is `R`, so a `code` match would swallow Ctrl+Shift+R,
  // which is hard-reload.
  return typeof event.key === 'string' && event.key.toLowerCase() === QUICK_OPEN_SHORTCUT.key;
}

/**
 * How the shortcut is spelled on this keyboard. Translated, because this app
 * already ships `Maj` to French readers and `Umschalt` to German ones, and
 * resolved through `useIsMacPlatform` so the server render agrees with the
 * client's first paint.
 */
export function useQuickOpenShortcutLabel(): string {
  const t = useTranslations('sidebar.customize');
  const isMac = useIsMacPlatform();
  return t(isMac ? 'shortcutKeysMac' : 'shortcutKeys');
}
