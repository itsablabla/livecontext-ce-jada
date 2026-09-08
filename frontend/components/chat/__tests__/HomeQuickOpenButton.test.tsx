// @vitest-environment jsdom
/**
 * The home page's quick-open button.
 *
 * Three things it must do that the old bare chevron did not: SAY where it goes
 * (its label was hover-only, and its destination was hardcoded to the board),
 * follow the destination the user picked in the customize menu, and look like
 * the rest of the app's buttons instead of a bespoke translucent pill.
 *
 * The keyboard shortcut it advertises is bound by the app shell, and is tested
 * with it in `lib/sidebar/__tests__/useQuickOpenShortcut.test.tsx`.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({
  // Echoes the ICU values too, so a message that drops one is visible.
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
// The real module pulls next-intl's routing setup into the test runner.
vi.mock('@/i18n/navigation', () => ({
  Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import { DEFAULT_HIDDEN_NAV_IDS, findNavItem } from '@/lib/sidebar/navItems';
import { useSidebarNavStore } from '@/lib/stores/sidebar-nav-store';
import { HomeQuickOpenButton } from '../HomeQuickOpenButton';

beforeEach(() => {
  window.localStorage.clear();
  useSidebarNavStore.setState({ hiddenNavIds: [...DEFAULT_HIDDEN_NAV_IDS], quickOpenNavId: 'agenda' });
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('home quick-open button', () => {
  it('goes to the agenda by default', () => {
    render(<HomeQuickOpenButton />);

    expect(screen.getByTestId('home-quick-open')).toHaveAttribute('href', findNavItem('agenda')!.path);
  });

  it('follows the destination picked in the customize menu', () => {
    useSidebarNavStore.setState({ quickOpenNavId: 'workflows' });

    render(<HomeQuickOpenButton />);

    expect(screen.getByTestId('home-quick-open')).toHaveAttribute('href', findNavItem('workflows')!.path);
  });

  it('names its destination on screen, not only on hover', () => {
    render(<HomeQuickOpenButton />);

    // The label is a plain child of the button - no hover state, no max-w-0 collapse.
    const label = screen.getByText('nav.agenda');
    expect(label).toBeVisible();
    expect(label.className).not.toContain('opacity-0');
    expect(label.className).not.toContain('max-w-0');
  });

  it('is shaped like every other button in the app, not a bespoke translucent pill', () => {
    render(<HomeQuickOpenButton />);

    const button = screen.getByTestId('home-quick-open');
    const classes = button.className.split(/\s+/);
    expect(button.getAttribute('data-variant')).toBe('secondary');
    // `rounded-full` would win over the button system's own radius, which is
    // what made this control read as a one-off rather than as a button.
    expect(classes).not.toContain('rounded-full');
    expect(classes).toContain('rounded-xl');
    // The hairline border every other control carries.
    expect(classes).toContain('border');
    expect(classes).toContain('border-[var(--border-color)]');
    // It used to sit at 80% opacity, warming to full only on hover, which is
    // the "peu visible" the redesign was asked for.
    // Matched with an optional variant prefix, not `startsWith`: the old code
    // carried `hover:opacity-100`, which a plain prefix check cannot see.
    // `disabled:opacity-60` comes from the button system and is not a fade.
    expect(classes.filter((c) => /^(hover:)?opacity-/.test(c))).toEqual([]);
    expect(classes).not.toContain('transition-opacity');
  });

  it('still carries the bouncing chevron that hints at what is below', () => {
    const { container } = render(<HomeQuickOpenButton />);

    expect(container.querySelector('.animate-bounce')).not.toBeNull();
  });

  it('falls back to the default when a stored destination no longer exists', () => {
    useSidebarNavStore.setState({ quickOpenNavId: 'a-page-that-was-removed' as never });

    render(<HomeQuickOpenButton />);

    expect(screen.getByTestId('home-quick-open')).toHaveAttribute('href', findNavItem('agenda')!.path);
  });

  it('spells its keyboard shortcut on the button, so it can be discovered without a manual', () => {
    render(<HomeQuickOpenButton />);

    // Exact, not `toHaveTextContent('shortcutKeys')`: under the key-echo
    // translator mock that string is a PREFIX of `shortcutKeysMac`, so the
    // loose form would pass on the Mac spelling too and pin nothing.
    expect(screen.getByTestId('home-quick-open-shortcut').textContent).toBe('shortcutKeys');
    // The tooltip is one ICU message, not a string built by concatenation, so
    // a locale can place the parentheses its own way.
    const title = screen.getByTestId('home-quick-open').getAttribute('title')!;
    expect(title).toContain('customize.shortcutTitle');
    expect(title).toContain('"label":"nav.agenda"');
    expect(title).toContain('"keys":"shortcutKeys"');
  });

  it('keeps the keycap off the resting button and reveals it on hover or focus', () => {
    // At rest the button says where it goes and nothing else. The keys are the
    // answer to a second question, asked by someone already pointing at it.
    // jsdom computes no styles, so this pins the classes, the way the pickers
    // pin their stacking order.
    render(<HomeQuickOpenButton />);

    const classes = screen.getByTestId('home-quick-open-shortcut').className.split(/\s+/);
    expect(classes).toContain('hidden');
    expect(classes).toContain('md:group-hover:inline-block');
    // Keyboard users never hover, so focus has to reveal it too.
    expect(classes).toContain('md:group-focus-visible:inline-block');
    // The old always-on spelling. Its return is exactly the regression here.
    expect(classes).not.toContain('md:inline-block');
    // The reveal hangs off the button's `group`, so the button must still be one.
    expect(screen.getByTestId('home-quick-open').className.split(/\s+/)).toContain('group');
  });

  it('switches to the Mac spelling on a Mac rather than printing Ctrl to everyone', () => {
    // The server-render half of this (both sides agreeing on the non-Mac
    // spelling before hydration) is covered in lib/utils/__tests__/platform.test.ts.
    vi.stubGlobal('navigator', { platform: 'MacIntel' });

    render(<HomeQuickOpenButton />);

    expect(screen.getByTestId('home-quick-open-shortcut').textContent).toBe('shortcutKeysMac');
  });

  it('announces the shortcut to screen readers instead of leaving the keycap to be read twice', () => {
    render(<HomeQuickOpenButton />);

    expect(screen.getByTestId('home-quick-open')).toHaveAttribute(
      'aria-keyshortcuts',
      'Control+Shift+O Shift+Meta+O',
    );
    expect(screen.getByTestId('home-quick-open-shortcut')).toHaveAttribute('aria-hidden', 'true');
  });
});
