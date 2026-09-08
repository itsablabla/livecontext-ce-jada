'use client';

import { useQuery } from '@tanstack/react-query';
import { useAuth } from '@/lib/providers/smart-providers';
import { badgesService, type Badge } from '@/lib/api/orchestrator/badges.service';

const QUERY_KEY = ['badges', 'me'] as const;
/**
 * Trophies move on the scale of hours, not seconds, and the server evaluates
 * on every read of this endpoint - so a short stale time would turn a tab
 * switch into a metric sweep for no visible gain.
 */
const STALE_TIME_MS = 5 * 60_000;

export interface UseBadgesResult {
  badges: Badge[];
  unlockedCount: number;
  totalCount: number;
  isLoading: boolean;
  error: unknown;
}

/**
 * The signed-in user's trophy grid.
 *
 * <p>Deliberately NOT org-scoped: a badge belongs to the person, so switching
 * workspace must not change what they have earned. That is also why the query
 * key has no org prefix - an org-scoped key would refetch (and briefly show an
 * empty grid) on every workspace switch for data that cannot differ.
 */
export function useBadges(enabled = true): UseBadgesResult {
  const { isLoading: authLoading, isAuthenticated } = useAuth();

  const { data, isLoading, error } = useQuery({
    queryKey: QUERY_KEY,
    queryFn: () => badgesService.getMyBadges(),
    enabled: enabled && !authLoading && isAuthenticated,
    staleTime: STALE_TIME_MS,
  });

  const badges = data ?? [];
  return {
    badges,
    unlockedCount: badges.filter((badge) => badge.unlocked).length,
    totalCount: badges.length,
    isLoading: isLoading && enabled,
    error,
  };
}
