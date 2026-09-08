// @vitest-environment jsdom
/**
 * Where the sidebar's overflow menu opens, on a phone and on a desktop.
 *
 * A sibling of SidebarMoreMenu.test.tsx rather than part of it: this file
 * mocks `useMobileDetection`, and `vi.mock` is file-scoped, so the suite next
 * door keeps exercising the real hook.
 *
 * Regression (2026-09-05): on a 410px viewport the sidebar is a 256px drawer
 * pinned to the left edge, and this menu opened to its RIGHT with a fixed 288px
 * body - 118px of it off screen, with no room on the left to flip into. Below
 * the row it gets the width of the screen instead.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('@/contexts/NavigationGuardContext', () => ({
  useSafeNavigate: () => vi.fn(),
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));

const isMobile = vi.fn(() => false);
vi.mock('@/hooks/useMobileDetection', () => ({
  useMobileDetection: () => isMobile(),
}));

import { useSidebarNavStore } from '@/lib/stores/sidebar-nav-store';
import { SidebarMoreMenu } from '../SidebarMoreMenu';

/** The menu content Radix renders, whatever view it is showing. */
function openedMenu(): HTMLElement {
  fireEvent.click(screen.getByTestId('sidebar-more-trigger'));
  const view =
    screen.queryByTestId('sidebar-more-overflow') ?? screen.getByTestId('sidebar-customize-panel');
  const content = view.closest('[data-side]');
  if (!content) throw new Error('The menu did not render a positioned popover');
  return content as HTMLElement;
}

beforeEach(() => {
  isMobile.mockReturnValue(false);
  useSidebarNavStore.getState().resetNavPreferences();
});

afterEach(cleanup);

describe('SidebarMoreMenu placement', () => {
  it('opens beside the sidebar on a desktop, where there is room for it', () => {
    render(<SidebarMoreMenu />);
    expect(openedMenu()).toHaveAttribute('data-side', 'right');
  });

  it('opens BELOW the row on a phone, where the drawer leaves no room to the right', () => {
    isMobile.mockReturnValue(true);
    render(<SidebarMoreMenu />);
    expect(openedMenu()).toHaveAttribute('data-side', 'bottom');
  });

  it.each([
    ['a phone', true],
    ['a desktop', false],
  ])('carries the viewport width cap on %s', (_label, mobile) => {
    // The placement change alone is not enough: this menu is `w-72` (288px), so
    // on a screen narrower than that the cap is the only thing keeping it on.
    isMobile.mockReturnValue(mobile as boolean);
    render(<SidebarMoreMenu />);
    expect(openedMenu().className).toContain(
      'max-w-[var(--radix-popper-available-width,calc(100vw-1rem))]',
    );
  });
});
