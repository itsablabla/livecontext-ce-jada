// @vitest-environment jsdom
/**
 * The sidebar's Studio filter, and the one thing it must not be.
 *
 * <p>A page of conversations is chosen by recency. Narrowing THAT page answers "the studio
 * conversations among the most recent ones", which is empty for anyone whose recent activity is
 * chat and is indistinguishable, on screen, from having none. So the filter asks the server for
 * studio conversations, and the assertion below is written to fail on a narrowing implementation:
 * the studio row it expects is deliberately NOT in the loaded list.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const push = vi.fn();
const pathname = '/app/chat';

// The conversations already loaded into the sidebar (the recency window).
const loadedConversations = [
  { id: 'c1', title: 'An ordinary chat', model: 'gpt', provider: 'openai', userId: 'u1', createdAt: '', updatedAt: '', messageCount: 2 },
  { id: 'c2', title: 'An agent chat', agentId: 'a1', model: 'gpt', provider: 'openai', userId: 'u1', createdAt: '', updatedAt: '', messageCount: 2 },
  // One studio conversation IS in the window, which is what makes the filter offer itself at all.
  { id: 'c3', title: 'A studio thread in the window', kind: 'studio', model: 'flux-1', provider: 'flux', userId: 'u1', createdAt: '', updatedAt: '', messageCount: 2 },
];

// What the SERVER answers for kind=studio. The second row is outside the loaded window on purpose:
// a narrowing implementation can never produce it.
const studioFromServer = {
  content: [
    loadedConversations[2],
    { id: 'c9', title: 'An older studio thread', kind: 'studio', model: 'flux-1', provider: 'flux', userId: 'u1', createdAt: '', updatedAt: '', messageCount: 2 },
  ],
};

// Deliberately ARGUMENT-AWARE, and that is the point of it.
//
// A double that answers studio rows whatever it is asked cannot see the filter being dropped: the
// component would request the unfiltered listing, be handed studio rows anyway, and every
// assertion below would still pass while production rendered every chat under the Studio heading.
// So an unfiltered request gets the unfiltered answer, exactly as the server would give it.
const getConversations = vi.fn(async (_page?: number, _size?: number, kind?: string) => (
  kind === 'studio' ? studioFromServer : { content: loadedConversations }
));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => pathname, useRouter: () => ({ push }) }));
vi.mock('next/navigation', () => ({ useSearchParams: () => ({ get: () => null }) }));
vi.mock('@/lib/api/conversationApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/conversationApi')>()),
  conversationApi: { getConversations: (...args: unknown[]) => getConversations(...(args as [])) },
}));
// The sidebar reads its own list hook, which is `useConversationList` +
// `useConversationMutations` and nothing else - no message store. Mocking those
// two rather than the sidebar hook itself keeps the real merge (shared cache +
// server rows, de-duplicated and ordered) under test.
vi.mock('@/hooks/conversation/useConversationList', () => ({
  useConversationList: () => ({
    conversations: loadedConversations,
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
  useUnifiedApp: () => ({ state: { conversations: loadedConversations, hasMore: false } }),
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isLoading: false, user: { sub: 'u1', email: 'u@e.com' } }),
}));
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => ({ view: 'chat', conversationId: undefined, isDetailPage: false }),
}));
vi.mock('@/hooks/useIsStreaming', () => ({ useIsStreaming: () => false }));
vi.mock('@/lib/hooks/useOrgScopedQuery', () => ({ useOrgScopedQuery: () => ({ data: undefined }) }));
// Stand in for react-query: the studio query is the only one this component makes that carries a
// key, so it is answered by key and everything else gets the empty default.
vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: ({ queryKey, queryFn, enabled }: {
    queryKey: readonly unknown[]; queryFn?: () => unknown; enabled?: boolean;
  }) => {
    // The keys come from `queryKeys.conversations` now: ['conversations','kind','studio'] for the
    // list and a fourth 'any' segment for the existence probe. Matched on the shape rather than on
    // a literal, so a key that moves again fails loudly here instead of silently returning nothing.
    const isKindQuery = Array.isArray(queryKey)
      && queryKey[0] === 'conversations' && queryKey[1] === 'kind' && queryKey[2] === 'studio';
    if (!isKindQuery || !enabled) return { data: undefined, isLoading: false };
    const isProbe = queryKey[3] === 'any';
    // The real queryFn is RUN, not just held. Returning the fixture without ever calling it made
    // the request invisible: the component could ask the server for the unfiltered listing and
    // this double would hand back studio rows regardless, so every assertion in the file passed
    // while production rendered every chat under the Studio heading. Its promise is not awaited -
    // the data below is still the fixture - but the ARGUMENTS it was called with are now
    // observable, which is the half that was missing.
    try { queryFn?.(); } catch { /* the double's own answer is what renders */ }
    return { data: isProbe ? { content: [studioFromServer.content[0]] } : studioFromServer, isLoading: false };
  },
}));
vi.mock('@/hooks/useProjects', () => ({
  useProjects: () => ({ projects: [], loading: false }),
  useProjectMutations: () => ({ deleteProject: vi.fn() }),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getAgentAvatars: vi.fn(() => Promise.resolve([])) } }));
vi.mock('@/components/project/ProjectMultiStepModal', () => ({
  getProjectIcon: () => null,
  ProjectMultiStepModal: () => null,
}));
vi.mock('@/components/dm/DmSidebarList', () => ({ DmSidebarList: () => <div data-testid="dm-sidebar-list" /> }));
vi.mock('@/components/sharing/ShareLinkDialog', () => ({ ShareLinkDialog: () => null }));

import { ConversationSidebar } from '../ConversationSidebar';

function renderSidebar() {
  return render(<ConversationSidebar onConversationSelect={vi.fn()} onNewChat={vi.fn()} onNavigate={vi.fn()} />);
}

function openFilterMenu() {
  fireEvent.click(screen.getByTitle('sidebar.filterChats'));
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ConversationSidebar - the Studio filter', () => {
  it('offers Studio beside the other kinds of conversation', () => {
    renderSidebar();
    openFilterMenu();

    expect(screen.getByText('sidebar.studioChats')).toBeInTheDocument();
  });

  it('lists studio conversations the loaded window does NOT contain', () => {
    // The whole point. On a narrowing implementation this row cannot exist, because it was never in
    // the list the sidebar had loaded.
    renderSidebar();
    openFilterMenu();
    fireEvent.click(screen.getByText('sidebar.studioChats'));

    expect(screen.getByText('An older studio thread')).toBeInTheDocument();
  });

  it('shows only studio conversations once the filter is on', () => {
    renderSidebar();
    openFilterMenu();
    fireEvent.click(screen.getByText('sidebar.studioChats'));

    expect(screen.queryByText('An ordinary chat')).not.toBeInTheDocument();
    expect(screen.queryByText('An agent chat')).not.toBeInTheDocument();
  });

  it('shows every conversation again when the filter goes back to all', () => {
    renderSidebar();
    openFilterMenu();
    fireEvent.click(screen.getByText('sidebar.studioChats'));
    openFilterMenu();
    fireEvent.click(screen.getByText('sidebar.allChats'));

    expect(screen.getByText('An ordinary chat')).toBeInTheDocument();
  });

  it('ASKS the server for studio conversations, rather than filtering what it already has', () => {
    // The request itself, because it is what makes the narrowing happen server-side. Every other
    // assertion here observes the rendered result, and with an argument-aware double those now
    // fail too when the filter is dropped - but only this one names the reason.
    renderSidebar();
    openFilterMenu();
    fireEvent.click(screen.getByText('sidebar.studioChats'));

    expect(getConversations).toHaveBeenCalledWith(0, 50, 'studio');
  });

});
