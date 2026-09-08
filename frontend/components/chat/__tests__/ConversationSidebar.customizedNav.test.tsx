// @vitest-environment jsdom
/**
 * The expanded panel draws exactly the pages the user kept.
 *
 * The parity guard next to this one only reads the source text, so it cannot see
 * what actually reaches the screen: that the three default-hidden pages are
 * gone for everyone, that hiding a page removes its row, and that the rows
 * still resolve their labels through the namespace the messages live in. All
 * three are asserted here against a rendered sidebar.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({
  usePathname: () => '/app/chat',
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('next/navigation', () => ({ useSearchParams: () => ({ get: () => null }) }));
// The sidebar reads its own list hook, which is `useConversationList` +
// `useConversationMutations` and nothing else - no message store. Mocking those
// two rather than the sidebar hook itself keeps the real merge (shared cache +
// server rows, de-duplicated and ordered) under test.
vi.mock('@/hooks/conversation/useConversationList', () => ({
  useConversationList: () => ({
    conversations: [],
    loading: false,
    error: null,
    hasMore: false,
    loadMoreConversations: vi.fn(),
    loadConversationById: vi.fn(),
    forceRefreshConversations: vi.fn(),
    setConversations: vi.fn(),
  }),
}));
vi.mock('@/hooks/conversation/useConversationMutations', () => ({
  useConversationMutations: () => ({
    loading: false,
    error: null,
    createConversation: vi.fn(),
    updateConversation: vi.fn(),
    deleteConversation: vi.fn(),
    clearError: vi.fn(),
  }),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({
  useUnifiedApp: () => ({ state: { conversations: [], hasMore: false } }),
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isLoading: false, user: { sub: 'u1', email: 'u@e.com' } }),
}));
// Settable, not a frozen 'chat': a constant here makes the active-row branch
// untestable, and a row that never lights up cannot tell a working
// `aria-current` from a missing one.
let mockView = 'chat';
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => ({ view: mockView, conversationId: undefined, isDetailPage: false }),
}));
vi.mock('@/hooks/useIsStreaming', () => ({ useIsStreaming: () => false }));
vi.mock('@/lib/hooks/useOrgScopedQuery', () => ({ useOrgScopedQuery: () => ({ data: undefined }) }));
vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: () => ({ data: [] }),
}));
vi.mock('@/hooks/useProjects', () => ({
  useProjects: () => ({ projects: [], loading: false }),
  useProjectMutations: () => ({ deleteProject: vi.fn() }),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getAgentAvatars: vi.fn(() => Promise.resolve([])) } }));
vi.mock('@/components/project/ProjectMultiStepModal', () => ({
  getProjectIcon: () => () => null,
  ProjectMultiStepModal: () => null,
}));
vi.mock('@/components/dm/DmSidebarList', () => ({ DmSidebarList: () => <div data-testid="dm-sidebar-list" /> }));
vi.mock('@/components/sharing/ShareLinkDialog', () => ({ ShareLinkDialog: () => null }));

import { DEFAULT_HIDDEN_NAV_IDS, SIDEBAR_NAV_ITEMS } from '@/lib/sidebar/navItems';
import { useSidebarNavStore } from '@/lib/stores/sidebar-nav-store';
import { ConversationSidebar } from '../ConversationSidebar';

function renderSidebar(onNavigate = vi.fn()) {
  render(<ConversationSidebar onConversationSelect={vi.fn()} onNewChat={vi.fn()} onNavigate={onNavigate} />);
  return onNavigate;
}

/** Rows the panel actually drew, in the order it drew them. */
function renderedNavIds(): string[] {
  return SIDEBAR_NAV_ITEMS.filter((item) => screen.queryByText(`nav.${item.titleKey}`) !== null).map(
    (item) => item.id,
  );
}

beforeEach(() => {
  mockView = 'chat';
  window.localStorage.clear();
  useSidebarNavStore.setState({ hiddenNavIds: [...DEFAULT_HIDDEN_NAV_IDS], quickOpenNavId: 'agenda' });
});
afterEach(cleanup);

describe('ConversationSidebar - the pages the user kept', () => {
  it('leaves every default-hidden page out for everyone', () => {
    renderSidebar();

    DEFAULT_HIDDEN_NAV_IDS.forEach((id) => {
      const item = SIDEBAR_NAV_ITEMS.find((entry) => entry.id === id)!;
      expect(screen.queryByText(`nav.${item.titleKey}`), id).toBeNull();
    });
  });

  it('still draws the five pages that stay by default', () => {
    renderSidebar();

    expect(renderedNavIds()).toEqual(['agenda', 'agents', 'workflows', 'tables', 'files']);
  });

  it('drops a row as soon as the page is hidden', () => {
    useSidebarNavStore.setState({ hiddenNavIds: ['board', 'interfaces', 'files'] });

    renderSidebar();

    expect(screen.queryByText('nav.files')).toBeNull();
  });

  it('brings a row back when the page is shown again', () => {
    useSidebarNavStore.setState({ hiddenNavIds: [] });

    renderSidebar();

    expect(renderedNavIds()).toEqual(SIDEBAR_NAV_ITEMS.map((item) => item.id));
  });

  it('ends the block with the overflow control, the only way to a page it is not showing', () => {
    renderSidebar();

    expect(screen.getByTestId('sidebar-more-trigger')).toBeInTheDocument();
  });

  it('keeps Home and Marketplace whatever the user hid - they are not customizable', () => {
    useSidebarNavStore.setState({ hiddenNavIds: ['board', 'interfaces', 'files', 'tables'] });

    renderSidebar();

    expect(screen.getByText('nav.newChat')).toBeInTheDocument();
    expect(screen.getByText('nav.marketplace')).toBeInTheDocument();
  });
});

describe('the panel wires its overflow menu to its own navigation', () => {
  it('routes an overflow page through the same handler its rows use', () => {
    // Otherwise a page opened from the menu would bypass whatever the shell
    // does on navigation, and behave unlike the identical row above it.
    const onNavigate = renderSidebar();

    fireEvent.click(screen.getByTestId('sidebar-more-trigger'));
    fireEvent.click(screen.getByTestId('sidebar-more-page-board'));

    expect(onNavigate).toHaveBeenCalledWith('/app/board');
  });

  it('lists exactly the pages it is not drawing as rows', () => {
    // The two halves are complements: a page is a row or it is in the menu,
    // never both and never neither.
    renderSidebar();
    // Read the rows BEFORE opening the menu: rows and menu entries carry the
    // same label now that both are drawn from the one navigation component, so
    // an open menu would make every page match twice.
    const drawnRows = renderedNavIds();
    fireEvent.click(screen.getByTestId('sidebar-more-trigger'));
    SIDEBAR_NAV_ITEMS.forEach((item) => {
      const inMenu = screen.queryByTestId(`sidebar-more-page-${item.id}`) !== null;
      expect(inMenu, item.id).toBe(!drawnRows.includes(item.id));
    });
  });
});

describe('what the panel tells a screen reader about where you are', () => {
  /** The nav row drawn for a page, by its label. */
  function navRow(titleKey: string): HTMLElement {
    return screen
      .getAllByRole('button')
      .find((el) => el.textContent?.includes(`nav.${titleKey}`))!;
  }

  it('announces the live page on its row, matching what the overflow menu does', () => {
    // Same reason as the rail: a background colour says nothing to an AT, and a
    // sidebar that announces the page on some rows and not others is worse than
    // one that never did.
    mockView = 'workflow';

    renderSidebar();

    expect(navRow('workflows')).toHaveAttribute('aria-current', 'page');
    // And exactly the live one, not every row.
    expect(navRow('agenda')).not.toHaveAttribute('aria-current');
  });
});
