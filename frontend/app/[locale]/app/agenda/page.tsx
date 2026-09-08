'use client';

import { Suspense } from 'react';
import { AgendaView } from '@/components/views/AgendaView';

/**
 * Agenda page - every scheduled workflow, application and agent in the workspace laid
 * out over time. The view resolves its own window from the URL-driven current view.
 */
export default function AppAgendaPage() {
  // AgendaView reads useSearchParams (the ?focus / ?date deep link from the notification
  // bell), which Next requires to sit under a Suspense boundary.
  return (
    <Suspense fallback={null}>
      <AgendaView />
    </Suspense>
  );
}
