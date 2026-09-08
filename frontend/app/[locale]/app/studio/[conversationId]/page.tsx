'use client';

import { use } from 'react';
import { StudioSurface } from '@/components/studio/StudioSurface';

/**
 * One studio thread.
 * Route: /app/studio/[conversationId]
 *
 * <p>Studio conversations have their own route rather than sharing the chat one. The alternative
 * was to dispatch inside /app/c/[id] on the conversation's kind, which means every chat open waits
 * on a request whose only job is to say "this is a chat" - and, until it lands, either flashes the
 * wrong surface or shows a spinner where the chat used to be.
 */
export default function StudioConversationPage({
  params,
}: {
  params: Promise<{ conversationId: string }>;
}) {
  const { conversationId } = use(params);
  return <StudioSurface conversationId={conversationId} />;
}
