'use client';

/**
 * Registers the current in-app view (`app_view`, a bounded enum from
 * `useCurrentView`) as a PostHog super-property so every event and pageview can
 * be broken down by product surface. Deliberately NOT the pathname: it embeds
 * conversation / workflow / publication ids. Renders nothing; mounted once in
 * the /app layout. Inert when analytics is not configured or not consented
 * (the facade drops the call).
 */

import { useEffect } from 'react';
import { useCurrentView } from '@/hooks/useCurrentView';
import { setAppView } from '@/lib/analytics/analytics';

export default function AppViewTracker() {
  const { view, isDetailPage } = useCurrentView();
  useEffect(() => {
    setAppView(view, isDetailPage);
    return () => setAppView(null, false);
  }, [view, isDetailPage]);
  return null;
}
