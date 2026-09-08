'use client';

import { StudioSurface } from '@/components/studio/StudioSurface';

/**
 * The studio, with nothing open yet.
 * Route: /app/studio
 *
 * <p>A conversation is created by the first turn, not by arriving here: a thread with no
 * generation in it is a row in the sidebar that says nothing, and the reader who opens the studio
 * and changes their mind should leave nothing behind.
 */
export default function StudioPage() {
  return <StudioSurface conversationId={null} />;
}
