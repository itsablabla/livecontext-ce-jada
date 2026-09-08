// @vitest-environment jsdom
/**
 * The sidebar's navigation, drawn by ONE component in both of its shapes.
 *
 * The rail (collapsed) and the panel (expanded) were two components. Giving
 * them one LIST to read stopped them showing different pages; it did not stop
 * them ANSWERING differently. On /app/studio the rail lit its new-chat icon and
 * the panel's new-chat row stayed dark - the same sidebar, the same page, two
 * answers depending on whether it was open. And a click on a page was reported
 * to analytics from the rail only, so half the signal that decides which pages
 * the sidebar shows by default was never collected.
 *
 * What this file can and cannot prove, stated plainly because the distinction
 * is the whole reason the component exists:
 *
 *  - the shape-agreement tests below render ONE component twice and are
 *    therefore near-tautological BY DESIGN. They are a guard against someone
 *    re-introducing shape-specific logic inside it, not evidence that the old
 *    divergence is gone - a test importing this component cannot run at all on
 *    a tree where it does not exist.
 *  - the tests that DO fail on the pre-change tree are the ones asserting the
 *    two behaviours the panel did not have: `aria-current` on the fixed rows,
 *    and a click reported to analytics from the panel.
 *  - the real cross-surface guard, which mounts the app and reads the actual
 *    rail against the actual panel, is e2e/ce/ce-sidebar-one-navigation.spec.ts.
 *    That one fails on the pre-change build, and can be re-checked on any local
 *    e2e slot: a rebuild leaves the previous frontend image dangling, so
 *    re-tagging it and recreating the container runs the old build against the
 *    same spec.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import type { AppView } from '@/hooks/useCurrentView';

const track = vi.fn();
let currentView: AppView = 'chat';
let isDetailPage = false;
let pathname = '/app/chat';

vi.mock('@/contexts/NavigationGuardContext', () => ({ useSafeNavigate: () => vi.fn() }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => pathname }));
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => ({ view: currentView, isDetailPage, conversationId: null }),
}));
vi.mock('@/lib/analytics/analytics', () => ({ track: (...args: unknown[]) => track(...args) }));

import { DEFAULT_HIDDEN_NAV_IDS, SIDEBAR_NAV_ITEMS, findNavItem } from '@/lib/sidebar/navItems';
import { useSidebarNavStore } from '@/lib/stores/sidebar-nav-store';
import { SidebarNavigation } from '../SidebarNavigation';

type Props = React.ComponentProps<typeof SidebarNavigation>;

function renderNav(variant: Props['variant'], overrides: Partial<Props> = {}) {
  const props: Props = {
    variant,
    active: true,
    currentConversationId: null,
    onNewChat: vi.fn(),
    onNavigate: vi.fn(),
    ...overrides,
  };
  const utils = render(<SidebarNavigation {...props} />);
  return { ...utils, props };
}

/**
 * What a shape actually drew, in order, with a `*` on the entry it says you are
 * on. Read from `aria-current` rather than a class, so it is the SAME question
 * asked of both shapes - the rail draws 32px buttons and the panel draws rows,
 * and comparing markup would compare the shapes rather than the answers.
 */
function drawn(container: HTMLElement, variant: 'rail' | 'panel'): string[] {
  const nodes =
    variant === 'rail'
      ? Array.from(container.querySelectorAll('button[title]'))
      : Array.from(container.querySelectorAll('button'));
  return nodes
    .map((el) => {
      const label =
        variant === 'rail' ? el.getAttribute('title') : el.querySelector('h2, span')?.textContent?.trim();
      if (!label) return null;
      return `${label}${el.getAttribute('aria-current') === 'page' ? '*' : ''}`;
    })
    .filter(Boolean) as string[];
}

beforeEach(() => {
  window.localStorage.clear();
  track.mockClear();
  currentView = 'chat';
  isDetailPage = false;
  pathname = '/app/chat';
  useSidebarNavStore.setState({ hiddenNavIds: [...DEFAULT_HIDDEN_NAV_IDS], quickOpenNavId: 'agenda' });
});
afterEach(cleanup);

describe('one navigation, two shapes', () => {
  // Near-tautological on purpose - see the file header. What it catches is a
  // future `if (variant === ...)` around anything a reader can see.
  it.each([
    ['the chat home', 'chat' as AppView, '/app/chat'],
    ['the studio home', 'studio' as AppView, '/app/studio'],
    ['a page', 'agenda' as AppView, '/app/agenda'],
    ['the marketplace', 'marketplace' as AppView, '/app/marketplace'],
  ])('says the same thing on %s whether it is open or closed', (_label, view, path) => {
    currentView = view;
    pathname = path;

    const rail = renderNav('rail');
    const railEntries = drawn(rail.container, 'rail');
    cleanup();

    const panel = renderNav('panel');
    const panelEntries = drawn(panel.container, 'panel');

    expect(railEntries).toEqual(panelEntries);
  });

  it('lights the new-chat entry on the studio home in BOTH shapes', () => {
    // The regression this component exists for. The rail treated /app/studio as
    // the home surface (correctly - the studio is a MODE of the home) and the
    // panel did not, so the sidebar said "you are nowhere" once expanded. The
    // predicate itself is pinned clause by clause in
    // lib/sidebar/__tests__/newChatEntryActive.test.ts.
    currentView = 'studio';
    pathname = '/app/studio';

    const rail = renderNav('rail');
    expect(drawn(rail.container, 'rail')[0]).toBe('nav.newChat*');
    cleanup();

    const panel = renderNav('panel');
    expect(drawn(panel.container, 'panel')[0]).toBe('nav.newChat*');
  });

  it('leaves the new-chat entry dark on an open conversation, in both shapes', () => {
    currentView = 'chat';
    pathname = '/app/c/abc';

    const rail = renderNav('rail', { currentConversationId: 'abc' });
    expect(drawn(rail.container, 'rail')[0]).toBe('nav.newChat');
    cleanup();

    const panel = renderNav('panel', { currentConversationId: 'abc' });
    expect(drawn(panel.container, 'panel')[0]).toBe('nav.newChat');
  });

  it('draws the pages the user kept, in the panel exactly as in the rail', () => {
    useSidebarNavStore.setState({ hiddenNavIds: ['board', 'interfaces', 'tables'] });

    const panel = renderNav('panel');

    expect(screen.queryByText('nav.tables')).toBeNull();
    expect(screen.queryByText('nav.board')).toBeNull();
    expect(screen.getByText('nav.agenda')).toBeInTheDocument();
    expect(drawn(panel.container, 'panel')).toEqual([
      'nav.newChat*',
      'nav.marketplace',
      'nav.agenda',
      'nav.agents',
      'nav.applications',
      'nav.workflows',
      'nav.files',
      'more.title',
    ]);
  });
});

describe('what a click does', () => {
  it.each(['rail' as const, 'panel' as const])('routes through the shell from the %s', (variant) => {
    const { props } = renderNav(variant);
    const agenda = findNavItem('agenda')!;

    fireEvent.click(
      variant === 'rail' ? screen.getByTitle('nav.agenda') : screen.getByText('nav.agenda'),
    );

    expect(props.onNavigate).toHaveBeenCalledWith(agenda.path);
  });

  it.each(['rail' as const, 'panel' as const])('reports the page from the %s', (variant) => {
    // The panel reported NOTHING before this component existed, so the only
    // record of which pages people use came from readers who keep the sidebar
    // collapsed - and that record is what the default hidden set is based on.
    renderNav(variant);

    fireEvent.click(
      variant === 'rail' ? screen.getByTitle('nav.agenda') : screen.getByText('nav.agenda'),
    );

    expect(track).toHaveBeenCalledWith('nav_item_clicked', { to_view: 'agenda' });
  });

  it.each(['rail' as const, 'panel' as const])('opens a fresh conversation from the %s', (variant) => {
    const { props } = renderNav(variant);

    fireEvent.click(
      variant === 'rail' ? screen.getByTitle('nav.newChat') : screen.getByText('nav.newChat'),
    );

    expect(props.onNewChat).toHaveBeenCalled();
    expect(props.onNavigate).not.toHaveBeenCalled();
  });

  it('reports a page reached through the overflow menu too', () => {
    const { props } = renderNav('panel');

    fireEvent.click(screen.getByTestId('sidebar-more-trigger'));
    fireEvent.click(screen.getByTestId('sidebar-more-page-board'));

    expect(props.onNavigate).toHaveBeenCalledWith(findNavItem('board')!.path);
    expect(track).toHaveBeenCalledWith('nav_item_clicked', { to_view: 'board' });
  });
});

describe('the overflow menu belongs to the live shape only', () => {
  it('is absent from a rail that is not the live surface', () => {
    // The mobile drawer opens over a still-collapsed rail and the panel inside
    // it draws this same menu, so the rail must not add a second trigger.
    renderNav('rail', { active: false });

    expect(screen.queryByTestId('sidebar-more-trigger')).toBeNull();
  });

  it('is present on the live rail', () => {
    renderNav('rail');

    expect(screen.getByTestId('sidebar-more-trigger')).toBeInTheDocument();
  });

  it('is absent from a collapsed panel', () => {
    renderNav('panel', { active: false });

    expect(screen.queryByTestId('sidebar-more-trigger')).toBeNull();
  });

  it('tells the menu which view is live, so a hidden page can read as active', () => {
    // Applications is out of the sidebar by default, so on that page the menu is
    // the ONLY thing that can say where you are.
    currentView = 'applications';
    renderNav('panel');

    fireEvent.click(screen.getByTestId('sidebar-more-trigger'));

    expect(screen.getByTestId('sidebar-more-page-applications')).toHaveAttribute('aria-current', 'page');
  });
});

describe('the panel keeps the block it draws its rows in', () => {
  it('puts the pages and the overflow row inside the height-capped scroll region, and the fixed two above it', () => {
    // Regression (kept from the previous structure): the block is capped at 40%
    // so the Chats list below always stays visible, and new chat + Marketplace
    // must stay OUTSIDE it so they never scroll away.
    const { container } = renderNav('panel');

    const block = Array.from(container.querySelectorAll('div')).find((el) =>
      el.className.includes('max-h-[40%]'),
    )!;
    expect(block).toBeTruthy();
    expect(block).toHaveClass('overflow-y-auto');

    expect(within(block).getByText('nav.agenda')).toBeInTheDocument();
    expect(within(block).getByText('more.title')).toBeInTheDocument();
    expect(block.contains(screen.getByText('nav.newChat'))).toBe(false);
    expect(block.contains(screen.getByText('nav.marketplace'))).toBe(false);
  });

  it('draws the projects slot inside that block, above the pages', () => {
    const { container } = renderNav('panel', {
      projectsSlot: <div data-testid="projects-slot">projects</div>,
    });

    const block = Array.from(container.querySelectorAll('div')).find((el) =>
      el.className.includes('max-h-[40%]'),
    )!;
    expect(within(block).getByTestId('projects-slot')).toBeInTheDocument();

    const order = Array.from(block.querySelectorAll('[data-testid="projects-slot"], h2')).map((el) =>
      el.getAttribute('data-testid') ?? el.textContent?.trim(),
    );
    expect(order[0]).toBe('projects-slot');
  });

  it('keeps the block itself when the panel is closed, so the column does not jump', () => {
    const { container } = renderNav('panel', { active: false, projectsSlot: <div>projects</div> });

    const block = Array.from(container.querySelectorAll('div')).find((el) =>
      el.className.includes('max-h-[40%]'),
    );
    expect(block).toBeTruthy();
    expect(block!.textContent).toBe('');
  });
});

describe('what it tells a screen reader', () => {
  it.each(['rail' as const, 'panel' as const])('announces the live page from the %s', (variant) => {
    currentView = 'workflow';
    renderNav(variant);

    const live = variant === 'rail' ? screen.getByTitle('nav.workflows') : screen.getByText('nav.workflows').closest('button')!;
    const other = variant === 'rail' ? screen.getByTitle('nav.agenda') : screen.getByText('nav.agenda').closest('button')!;
    expect(live).toHaveAttribute('aria-current', 'page');
    expect(other).not.toHaveAttribute('aria-current');
  });

  it('announces the marketplace row too, which only the rail used to do', () => {
    currentView = 'marketplace';
    renderNav('panel');

    expect(screen.getByText('nav.marketplace').closest('button')).toHaveAttribute('aria-current', 'page');
  });
});

describe('the list itself', () => {
  it('keeps new chat and Marketplace whatever the user hid', () => {
    useSidebarNavStore.setState({ hiddenNavIds: ['board', 'interfaces', 'files', 'tables'] });

    renderNav('rail');

    expect(screen.getByTitle('nav.newChat')).toBeInTheDocument();
    expect(screen.getByTitle('nav.marketplace')).toBeInTheDocument();
  });

  it('draws new chat, Marketplace, the kept pages, then the overflow trigger - and nothing else', () => {
    const { container } = renderNav('rail');

    const kept = SIDEBAR_NAV_ITEMS.filter((item) => !DEFAULT_HIDDEN_NAV_IDS.includes(item.id)).map(
      (item) => `nav.${item.titleKey}`,
    );
    expect(drawn(container, 'rail')).toEqual(['nav.newChat*', 'nav.marketplace', ...kept, 'more.title']);
  });

  it('draws a page again as soon as it is shown', () => {
    useSidebarNavStore.setState({ hiddenNavIds: [] });

    renderNav('rail');

    expect(screen.getByTitle('nav.board')).toBeInTheDocument();
    expect(screen.getByTitle('nav.interfaces')).toBeInTheDocument();
  });
});
