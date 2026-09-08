import type { ChangelogEntry } from './latestEntry';

/** What `GET /api/changelog/state?entry=<key>` answers. */
export interface ChangelogServerState {
  /** Whether this deployment surfaces the changelog at all (CHANGELOG_ENABLED). */
  enabled: boolean;
  /** Entry key this user already acknowledged, or null when they never acknowledged one. */
  seenKey: string | null;
  /**
   * Acknowledge the requested entry without ever showing it: this account was created after the
   * install started announcing it.
   *
   * Decided by the server because only the server knows when THIS install first served the entry.
   * The entry's own publication date cannot answer it: a developer writes that date, and a
   * self-hosted install adopts the release whenever it upgrades, so using it would silently seal
   * every user who signed up between the two.
   */
  seal: boolean;
}

/**
 * - `hidden`: say nothing, and show no unread dot.
 * - `announce`: open the panel once, then acknowledge.
 * - `seal`: acknowledge WITHOUT showing anything. Reserved for an account that never lacked what
 *   the entry announces; leaving it unacknowledged would surface the entry later, at a random
 *   moment, which is worse.
 */
export type ChangelogDecision = 'hidden' | 'announce' | 'seal';

/**
 * Decides what to do with the entry this build ships, given what the server knows about the user.
 *
 * Kept as a pure function on purpose: this is the client half of the policy (show once, never
 * replay a backlog), and it is the part that must be provable without mounting a modal.
 */
export function decideChangelog(
  entry: ChangelogEntry | null,
  state: ChangelogServerState | null | undefined,
): ChangelogDecision {
  // No entry, no state yet, or the deployment switched the feature off. The state is required:
  // announcing before the server answers would show the panel again on every load to a user who
  // already dismissed it.
  if (!entry || !state || !state.enabled) return 'hidden';

  // Equality, not ordering. On a rollback the running build ships an older entry, whose key does
  // not match the newer acknowledgement, and announcing it again is the correct behaviour: it IS
  // what the user is now running.
  if (state.seenKey === entry.key) return 'hidden';

  return state.seal ? 'seal' : 'announce';
}
