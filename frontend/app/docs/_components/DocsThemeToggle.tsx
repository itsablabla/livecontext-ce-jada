'use client';

import { Moon, Sun } from 'lucide-react';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';

/**
 * Header light/dark toggle for the BLOG surfaces (`/blog`, `/blog/[slug]`, and
 * their localized twins). It drives whichever `LandingThemeProvider` wraps it -
 * each of those pages sets its own storageKey - so flipping it never changes the
 * landing/marketing pages. Rendered through `LandingShell`'s `headerExtra` slot,
 * which sits inside that provider, so `useLandingTheme()` resolves to it here.
 *
 * The docs deliberately do NOT mount this: their header carries no theme control,
 * the footer toggle every public page already has switches the docs theme.
 */
export function DocsThemeToggle() {
  const { theme, toggle } = useLandingTheme();
  const isDark = theme === 'dark';
  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={isDark ? 'Switch to light theme' : 'Switch to dark theme'}
      title={isDark ? 'Light theme' : 'Dark theme'}
      className="inline-flex items-center justify-center w-9 h-9 rounded-full transition-colors hover:brightness-110 cursor-pointer"
      style={{
        color: 'var(--text-secondary)',
        border: '1px solid var(--border-color)',
        background: 'var(--bg-tertiary)',
      }}
    >
      {isDark ? <Sun className="w-4 h-4" aria-hidden="true" /> : <Moon className="w-4 h-4" aria-hidden="true" />}
    </button>
  );
}
