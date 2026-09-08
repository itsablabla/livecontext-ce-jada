import type { AppView } from '@/hooks/useCurrentView';

/**
 * Whether the conversation list should be auto-loaded for the current app view.
 *
 * The sidebar shows conversation titles on every primary app surface (chat + all the
 * resource views), so the list must be fetched there. This is an allowlist: a view or
 * path NOT listed here renders the sidebar without conversations.
 *
 * <p>The allowlist is keyed by `AppView`, and that is the whole point of its shape: a view
 * added to `AppView` and forgotten here does not compile. Twice now a primary surface
 * shipped with a blank Chats section for exactly that reason - the aggregated Board
 * (`/app/board`), then the Agenda (`/app/agenda`) - and both times the omission was
 * invisible, because an empty conversation list looks like a workspace with no
 * conversations. Deciding is now mandatory; only the answer is a judgement call.
 *
 * <p>The paths live in the same entry rather than in a second array beside it, because the
 * second array is the half that a `Record` does not police: a route can be forgotten there
 * with the view answered correctly, and the two lists then disagree about the same surface.
 * The prefixes matter because `pathname` here still carries the locale segment for a
 * non-default locale, so a `/fr/app/...` reader is matched by the VIEW while the path check
 * is what covers a surface `useCurrentView` does not map.
 *
 * <p>Kept as a pure function so the allowlist is unit-tested too (a wrong answer for a
 * listed view fails a test instead of silently blanking the sidebar).
 */
const AUTO_LOAD: Record<AppView, { load: boolean; paths: readonly string[] }> = {
  chat: { load: true, paths: ['/app/chat', '/app/c/'] },
  data: { load: true, paths: ['/app/tables', '/app/data'] },
  files: { load: true, paths: ['/app/files'] },
  workflow: { load: true, paths: ['/app/workflow'] },
  settings: { load: true, paths: ['/app/settings'] },
  interface: { load: true, paths: ['/app/interface'] },
  agent: { load: true, paths: ['/app/agent'] },
  marketplace: { load: true, paths: ['/app/marketplace'] },
  applications: { load: true, paths: ['/app/applications'] },
  project: { load: true, paths: ['/app/project'] },
  tasks: { load: true, paths: ['/app/tasks'] },
  board: { load: true, paths: ['/app/board'] },
  agenda: { load: true, paths: ['/app/agenda'] },
  // The studio lists the same conversations in the same sidebar, so it needs them loaded.
  // Answered here rather than inherited: it used to be covered only because the studio
  // resolved to the 'chat' view, and a surface that answers this question by accident is
  // exactly what left the Board and the Agenda with a blank Chats section.
  studio: { load: true, paths: ['/app/studio'] },
};

/** Every path prefix of a view that loads, flattened once. */
const AUTO_LOAD_PATH_PREFIXES: readonly string[] = Object.values(AUTO_LOAD)
  .filter((entry) => entry.load)
  .flatMap((entry) => entry.paths);

export interface AutoLoadConversationsInput {
  isAuthenticated: boolean;
  currentView: string;
  urlConversationId: string | null;
  pathname: string | null | undefined;
}

export function shouldAutoLoadConversations({
  isAuthenticated,
  currentView,
  urlConversationId,
  pathname,
}: AutoLoadConversationsInput): boolean {
  if (!isAuthenticated) return false;
  return (
    AUTO_LOAD[currentView as AppView]?.load === true ||
    urlConversationId !== null ||
    (!!pathname && AUTO_LOAD_PATH_PREFIXES.some((p) => pathname.startsWith(p)))
  );
}
