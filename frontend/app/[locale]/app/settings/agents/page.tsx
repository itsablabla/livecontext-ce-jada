'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { User } from 'lucide-react';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useAuth } from '@/lib/providers/smart-providers';
import { Button } from '@/components/ui/button';
import { SettingsPageSkeleton } from '@/components/skeletons';
import { AgentChatDefaults } from '@/components/settings/AgentChatDefaults';

/**
 * Settings > Agents & Chat: the per-(user, workspace) defaults that seed every agent
 * conversation and the general chat. Same editor as the Agents page "Settings" tab
 * (/app/agent?view=settings) - both mount <AgentChatDefaults />, one store.
 */
export default function AgentSettingsPage() {
  const { isAuthenticated, isAuthChecking } = useAuthGuard();
  const { loginWithRedirect } = useAuth();
  const tSettings = useTranslations('settings');

  // The shared skeleton, not a local one: it promises the shape that actually lands
  // (the same PageHeader plus one full-width panel), so the page does not re-shape
  // itself the moment the defaults arrive.
  if (isAuthChecking) {
    return <SettingsPageSkeleton />;
  }

  if (!isAuthenticated) {
    return (
      <div className="min-h-[300px] flex items-center justify-center">
        <div className="text-center">
          <h1 className="text-2xl font-bold text-theme-primary mb-4">
            {tSettings('unauthorized')}
          </h1>
          <p className="text-theme-secondary mb-6">{tSettings('mustBeLoggedIn')}</p>
          <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
            <User className="w-4 h-4 mr-1" />
            {tSettings('signIn')}
          </Button>
        </div>
      </div>
    );
  }

  return <AgentChatDefaults />;
}
