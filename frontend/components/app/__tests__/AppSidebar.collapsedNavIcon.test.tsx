// @vitest-environment jsdom
/**
 * A navigation entry must look the same whether the left sidebar is expanded or
 * collapsed.
 *
 * The collapsed rail drew each entry as a `ghostGray` Button, and that variant
 * pins every nested svg to the button's own colour (`[&_svg]:!text-current` over
 * `--text-primary`). So the icon came out full BLACK at rest, and hover flipped
 * the whole tile to a dark background with a light icon - while the very same
 * entry, one click away in the expanded panel, was a muted `text-theme-secondary`
 * icon that only warms to `text-theme-primary` over a discreet `bg-surface-hover`.
 *
 * This pins the expanded treatment on the collapsed rail, and pins the size,
 * which was NOT part of the complaint and must not drift while fixing the colour.
 */
import '@testing-library/jest-dom/vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

vi.mock('@/hooks/useAppVersion', () => ({
  useAppVersion: () => ({ version: null, isLoading: false, isError: false }),
}));
vi.mock('next-intl', () => ({ useLocale: () => 'en', useTranslations: () => (k: string) => k }));
vi.mock('@/i18n/navigation', () => ({
  usePathname: () => '/en/app/chat',
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));
vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: () => ({ data: [] }),
  useQueryClient: () => ({ invalidateQueries: vi.fn(() => Promise.resolve()), setQueryData: vi.fn() }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (selector: (s: { currentOrgId: string | null; setCurrentOrg: () => void }) => unknown) =>
    selector({ currentOrgId: null, setCurrentOrg: vi.fn() }),
}));

import { Store } from 'lucide-react';
import { NavIconButton } from '../AppSidebar';
import { SIDEBAR_NAV_ROW_ICON_CLASS } from '@/lib/sidebar/navRowStyles';

/**
 * The row the expanded panel renders for the same entry, READ FROM ITS SOURCE.
 *
 * <p>It was a copied literal, with a comment claiming it tracked the row. It did
 * not: restyling ConversationSidebar left this file untouched and the suite
 * green, which is the exact drift the assertion says it prevents.
 *
 * <p>It then scraped the class string out of ConversationSidebar with a regex,
 * which was true but brittle: the rows now share ONE exported class list with
 * the overflow row that ends the block, so the panel no longer spells it
 * inline. Importing that list is the same claim without the regex - but ONLY
 * while something really renders that list, which is what
 * `expandedPanelUsesTheSharedIcon` below pins. Without that second half the
 * import would compare the rail against a constant nothing renders, and the
 * cross-file guard this test exists to be would quietly become a tautology.
 *
 * <p>The consumer is now SidebarNavigation, the single component that draws the
 * navigation in both of the sidebar's shapes. That this test had to be pointed
 * at a new file is the guard working: the rows moved, and it said so.
 */
function expandedRowIconClasses(): string[] {
  return SIDEBAR_NAV_ROW_ICON_CLASS.split(/\s+/).filter(Boolean);
}

/** Does the expanded panel actually draw its rows from that list? */
function expandedPanelUsesTheSharedIcon(): boolean {
  const source = readFileSync(join(__dirname, '../SidebarNavigation.tsx'), 'utf8');
  return source.includes('className={SIDEBAR_NAV_ROW_ICON_CLASS}');
}

function renderRail(isActive = false) {
  render(<NavIconButton icon={Store} title="Marketplace" onClick={() => undefined} isActive={isActive} />);
  const button = screen.getByTitle('Marketplace');
  return { button, icon: button.querySelector('svg') as SVGElement };
}

afterEach(cleanup);

describe('collapsed sidebar rail - a nav icon looks like its expanded row', () => {
  it('rests in the muted colour, not the full-black one the ghostGray variant forced', () => {
    const { icon } = renderRail();

    expect(icon.getAttribute('class')).toContain('text-theme-secondary');
  });

  it('does not let the button pin the icon colour, which is what made it black', () => {
    // `[&_svg]:!text-current` is the ghostGray marker: with it present, the icon
    // classes above are dead code and the icon takes the button's colour.
    const { button } = renderRail();

    expect(button.className).not.toContain('[&_svg]:!text-current');
  });

  it('warms to the primary colour on hover and while active, like the expanded row', () => {
    const { icon } = renderRail();

    expect(icon.getAttribute('class')).toContain('group-hover:text-theme-primary');
    expect(icon.getAttribute('class')).toContain('group-[.bg-surface-hover]:text-theme-primary');
  });

  it('carries the group the two rules above need to fire', () => {
    // Without `group` on the button, both variants are inert and the icon never
    // changes colour - the failure mode would be silent.
    const { button } = renderRail();

    expect(button.className.split(/\s+/)).toContain('group');
  });

  it('uses the discreet surface hover, not the inverted dark tile', () => {
    const { button } = renderRail();

    expect(button.className).toContain('hover:bg-surface-hover');
    expect(button.className).not.toContain('hover:bg-[var(--text-primary)]');
  });

  it('marks the active entry with the same surface the expanded row uses', () => {
    const { button } = renderRail(true);

    expect(button.className).toContain('bg-surface-hover');
  });

  it('keeps the rail size: a 32px box holding a 16px icon', () => {
    // The complaint was about colour only. A size change here would silently
    // reflow the whole collapsed rail.
    const { button, icon } = renderRail();

    expect(button.className).toContain('w-8');
    expect(button.className).toContain('h-8');
    expect(icon.getAttribute('class')).toContain('w-4');
    expect(icon.getAttribute('class')).toContain('h-4');
  });

  it('applies every colour rule the expanded row applies', () => {
    // Read out of SidebarNavigation.tsx at run time, so the day that row is
    // restyled this test says the rail drifted instead of quietly passing.
    const { icon } = renderRail();
    const railClasses = (icon.getAttribute('class') ?? '').split(/\s+/);

    // The comparison is only worth anything if the panel draws from this list.
    expect(expandedPanelUsesTheSharedIcon(), 'SidebarNavigation no longer renders SIDEBAR_NAV_ROW_ICON_CLASS').toBe(true);

    for (const cls of expandedRowIconClasses()) {
      if (cls === 'mr-2') continue; // spacing before the label; the rail has no label
      expect(railClasses, cls).toContain(cls);
    }
  });
});
