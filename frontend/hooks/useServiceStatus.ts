'use client';

import { useQuery } from '@tanstack/react-query';
import { IS_CE } from '@/lib/edition';
import type { ServiceStatus } from '@/lib/status/types';

/**
 * Platform status for the in-app surfaces (the user-menu dot and the incident
 * strip).
 *
 * Deliberately NOT `apiClient`: that client targets `/api/proxy` -> gateway with
 * an OIDC token, and this feature exists precisely so the status path does not
 * depend on the backend it reports on. `GET /api/status` is our own Next route,
 * unauthenticated (someone who cannot sign in is exactly who needs the answer)
 * and already cached server-side, so a plain fetch is the correct call here.
 *
 * Cloud-only, like the rest of the feature: a self-hosted install gets no status
 * surface, so the query never runs in CE.
 *
 * Polling budget: one request per minute per VISIBLE tab. react-query does not
 * refetch on an interval while the tab is hidden (made explicit below), and the
 * response is cached upstream for 30 s, so extra tabs cost nothing upstream.
 */

const POLL_INTERVAL_MS = 60_000;

export interface UseServiceStatusResult {
  /** Payload, or null while loading / on error / in CE. */
  status: ServiceStatus | null;
  isLoading: boolean;
  isError: boolean;
}

async function fetchStatus(): Promise<ServiceStatus> {
  const res = await fetch('/api/status', { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`status ${res.status}`);
  return (await res.json()) as ServiceStatus;
}

export function useServiceStatus(): UseServiceStatusResult {
  const { data, isPending, isError } = useQuery({
    queryKey: ['service-status'],
    queryFn: fetchStatus,
    enabled: !IS_CE,
    refetchInterval: POLL_INTERVAL_MS,
    // Explicit: no polling behind a hidden tab (a background tab must not keep
    // waking the network for a page nobody is looking at).
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
    staleTime: POLL_INTERVAL_MS,
    // One failed poll must not retry-storm; the next interval tick is soon enough.
    retry: false,
  });

  return { status: data ?? null, isLoading: isPending, isError };
}
