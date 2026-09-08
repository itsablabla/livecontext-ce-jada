import { describe, it, expect } from 'vitest';
import { shouldAutoLoadConversations } from '../shouldAutoLoadConversations';

const base = {
  isAuthenticated: true,
  currentView: 'chat',
  urlConversationId: null as string | null,
  pathname: '/app/chat',
};

/**
 * One representative URL per view that loads conversations. Written out rather than read
 * from the module so the test states what the routes ARE, instead of agreeing with whatever
 * the module happens to contain.
 */
const VIEW_PATHS = {
  chat: '/app/chat',
  data: '/app/tables',
  files: '/app/files',
  workflow: '/app/workflow/abc',
  settings: '/app/settings',
  interface: '/app/interface/abc',
  agent: '/app/agent',
  marketplace: '/app/marketplace',
  applications: '/app/applications',
  project: '/app/project/abc',
  tasks: '/app/tasks',
  board: '/app/board',
  agenda: '/app/agenda',
} as const;

describe('shouldAutoLoadConversations', () => {
  // Regression: the aggregated Board surface blanked the sidebar because 'board' was
  // missing from the auto-load allowlist. These two pin it (view AND path).
  it('auto-loads on the Board view so conversation titles stay visible', () => {
    expect(
      shouldAutoLoadConversations({ ...base, currentView: 'board', pathname: '/app/other' }),
    ).toBe(true);
  });

  it('auto-loads on the /app/board path', () => {
    expect(
      shouldAutoLoadConversations({ ...base, currentView: 'unknown', pathname: '/app/board' }),
    ).toBe(true);
  });

  it('auto-loads on the /app/studio path, on the PATH alone', () => {
    // The studio lists the same conversations in the same sidebar. It used to be covered only
    // because useCurrentView's initialiser made every unmatched path resolve to 'chat', so an
    // honest default there would have blanked the sidebar on this route without failing anything.
    // Asserting on the path with an unknown view is what pins the decision rather than the accident.
    expect(
      shouldAutoLoadConversations({ ...base, currentView: 'unknown', pathname: '/app/studio' }),
    ).toBe(true);
    expect(
      shouldAutoLoadConversations({ ...base, currentView: 'unknown', pathname: '/app/studio/abc' }),
    ).toBe(true);
  });

  // Regression: the Agenda shipped with the same omission the Board had - a primary
  // surface missing from the allowlist, so /app/agenda rendered the sidebar with an empty
  // Chats section while every other resource view listed its conversations.
  it('auto-loads on the Agenda view so conversation titles stay visible', () => {
    expect(
      shouldAutoLoadConversations({ ...base, currentView: 'agenda', pathname: '/app/other' }),
    ).toBe(true);
  });

  it('auto-loads on the /app/agenda path', () => {
    expect(
      shouldAutoLoadConversations({ ...base, currentView: 'unknown', pathname: '/app/agenda' }),
    ).toBe(true);
  });

  it('auto-loads on a known resource view (workflow)', () => {
    expect(
      shouldAutoLoadConversations({ ...base, currentView: 'workflow', pathname: '/x' }),
    ).toBe(true);
  });

  it('auto-loads when a conversation id is in the URL even on an unlisted surface', () => {
    expect(
      shouldAutoLoadConversations({
        ...base,
        currentView: 'unknown',
        pathname: '/somewhere',
        urlConversationId: 'conv-1',
      }),
    ).toBe(true);
  });

  it('auto-loads on the /app/data alias of the tables surface', () => {
    // `useCurrentView` maps both /app/tables and /app/data to the `data` view, so the two
    // are the same surface; only one of them was listed as a path prefix.
    expect(
      shouldAutoLoadConversations({ ...base, currentView: 'unknown', pathname: '/app/data' }),
    ).toBe(true);
  });

  it('gives every loading view at least one path of its own', () => {
    // The half a `Record<AppView, ...>` cannot police. The type forces a new view to be
    // ANSWERED, not to be given a route, and `{ load: true, paths: [] }` compiles - which
    // is the same omission wearing a different hat: the view resolves for an `/en` reader
    // and the path check, the one that carries a `/fr/app/...` URL, matches nothing.
    for (const view of Object.keys(VIEW_PATHS) as Array<keyof typeof VIEW_PATHS>) {
      expect(
        shouldAutoLoadConversations({ ...base, currentView: 'unknown', pathname: VIEW_PATHS[view] }),
        `${view} loads conversations but no path prefix reaches it`,
      ).toBe(true);
    }
  });

  it('does NOT auto-load on an unlisted view with no matching path / conversation', () => {
    expect(
      shouldAutoLoadConversations({ ...base, currentView: 'unknown', pathname: '/somewhere' }),
    ).toBe(false);
  });

  it('never auto-loads when unauthenticated, even on the Board', () => {
    expect(
      shouldAutoLoadConversations({
        ...base,
        isAuthenticated: false,
        currentView: 'board',
        pathname: '/app/board',
      }),
    ).toBe(false);
  });
});
