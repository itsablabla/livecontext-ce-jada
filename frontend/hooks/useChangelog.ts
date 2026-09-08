'use client';

import { useCallback } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/lib/api/api-client';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { currentEntry } from '@/lib/changelog/latestEntry';
import { needsOnboardingRedirect, type OnboardingStatus } from '@/components/security/onboardingStatus';
import { decideChangelog, type ChangelogDecision, type ChangelogServerState } from '@/lib/changelog/decision';
import type { ChangelogEntry } from '@/lib/changelog/latestEntry';

export const CHANGELOG_STATE_QUERY_KEY = ['changelog', 'state'] as const;

export interface UseChangelogResult {
  /** The entry this build ships, or null when there is none (or it is malformed). */
  entry: ChangelogEntry | null;
  /** Whether the deployment surfaces the changelog at all (CHANGELOG_ENABLED). */
  isAvailable: boolean;
  /** What to do with the entry right now. */
  decision: ChangelogDecision;
  /** Acknowledge the entry. Idempotent; safe to call more than once. */
  markSeen: () => void;
  isLoading: boolean;
}

/**
 * Per-user state of the in-app "What's new" panel.
 *
 * <p>Both editions, unlike {@code useAppVersion}: the announcement is about the build the user is
 * running, which is as true of a cloud tenant as of a self-hosted install.
 *
 * <p>Nothing is announced until onboarding is DONE. A first run is a guided sequence (the
 * onboarding flow itself, then the welcome-gift and suggested-apps modals), and a release note has
 * no business interrupting it - the user is being shown the product, not told what changed since a
 * version they never ran.
 */
export function useChangelog(): UseChangelogResult {
  // Optional rather than required: this hook is mounted in the app layout, and any surface that
  // renders it outside the auth provider (tests, an early shell) should get a quiet no-op rather
  // than a thrown error.
  const auth = useOptionalAuth();
  const isAuthLoading = auth?.isLoading ?? false;
  const isAuthenticated = auth?.isAuthenticated ?? false;
  const userId = auth?.user?.sub;
  const queryClient = useQueryClient();
  const entry = currentEntry();

  // Same query key and endpoint FirstLoginGuard uses, so this reads its cache instead of asking
  // again: react-query dedupes by key, and the guard has normally already populated it by the time
  // the app shell renders.
  const { data: onboarding, isPending: isOnboardingPending } = useQuery({
    queryKey: ['user', 'onboarding-status', userId],
    queryFn: () => apiClient.get<OnboardingStatus>('/auth-service/api/onboarding/status'),
    enabled: !!entry && !isAuthLoading && isAuthenticated,
    staleTime: 5 * 60 * 1000,
    retry: false,
  });

  // Deliberately fails CLOSED, unlike FirstLoginGuard (which must not lock a user out of the app
  // when the status cannot be read). Here an unknown answer means staying silent for now: a missed
  // announcement costs nothing, an announcement on top of an onboarding step costs the first
  // impression.
  const onboardingDone = !!onboarding && !needsOnboardingRedirect(onboarding);

  // Nothing to ask the server when this build ships no entry: the answer could not change the
  // outcome, and a build that announces nothing should make no request at all.
  const enabled = !!entry && !isAuthLoading && isAuthenticated && onboardingDone;

  const { data, isPending } = useQuery({
    queryKey: CHANGELOG_STATE_QUERY_KEY,
    // The entry key travels with the request: the seal decision belongs to the server, which is
    // the only side that knows when THIS install first served this entry. Sending it is also
    // what stamps that moment, on the first authenticated page load after a deploy or upgrade.
    queryFn: () => apiClient.get<ChangelogServerState>('/changelog/state', {
      params: { entry: entry!.key },
    }),
    enabled,
    // The answer only changes when this user acknowledges, and that path writes the cache itself.
    staleTime: Infinity,
    retry: false,
  });

  const { mutate } = useMutation({
    mutationFn: (key: string) => apiClient.post('/changelog/seen', { key }),
    // Written optimistically and kept on failure: a lost acknowledgement would reopen the panel on
    // the next navigation, which is far more annoying than an announcement missed once. The next
    // state fetch (a fresh session) reconciles.
    onMutate: (key: string) => {
      queryClient.setQueryData<ChangelogServerState>(CHANGELOG_STATE_QUERY_KEY, (prev) =>
        prev ? { ...prev, seenKey: key } : prev);
    },
  });

  const markSeen = useCallback(() => {
    if (!entry) return;
    if (data?.seenKey === entry.key) return;
    mutate(entry.key);
  }, [entry, data?.seenKey, mutate]);

  return {
    entry,
    isAvailable: !!entry && data?.enabled === true,
    decision: decideChangelog(entry, data),
    markSeen,
    isLoading: isAuthLoading || (!!entry && isAuthenticated && (isOnboardingPending || (enabled && isPending))),
  };
}
