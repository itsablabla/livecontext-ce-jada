'use client';

import { MessageSquare } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { PageHeader } from '@/components/settings/PageHeader';
import { ChatConfigPanel } from '@/components/chat/ChatConfigPanel';

interface AgentChatDefaultsProps {
  /**
   * Heading level of the header. 'h1' (default) for the Settings > Agents & Chat page,
   * which owns its page title; 'h2' inside the Agents page "Settings" tab, whose sibling
   * tabs render an h2 at most - an h1 that appears only on one tab moves the page's
   * heading level around as the user switches tabs.
   */
  headingLevel?: 'h1' | 'h2';
}

/**
 * Agent & general-chat defaults - the per-(user, workspace) chat defaults (V312) that
 * seed the message composer and every NEW conversation (general chat included) in this
 * workspace: system prompt, temperature, token/iteration/timeout budgets, tools mode,
 * web search, image generation, turn limits and compaction.
 *
 * Rendered from the two places a user looks for it:
 *   - /app/agent?view=settings  (Agents page > Settings tab)
 *   - /app/settings/agents      (Settings nav > Agents & Chat)
 * Both mount the SAME <ChatConfigPanel userDefault />, whose single store is
 * GET/PUT /v3/chat/defaults, so an edit made on one surface shows up on the other. This
 * is the ONLY place the editor lives: Settings > Overview > Preferences used to carry a
 * second copy and now keeps just a "Chat defaults" row linking here.
 *
 * Width is capped at max-w-4xl, the width the same editor already has in Preferences:
 * the Agents page column is max-w-6xl and the sliders + 2-column number grids stretch
 * badly past ~900px.
 */
export function AgentChatDefaults({ headingLevel = 'h1' }: AgentChatDefaultsProps) {
  const t = useTranslations('settings.agentDefaults');

  return (
    <div className="space-y-6 max-w-4xl">
      <PageHeader
        icon={MessageSquare}
        title={t('title')}
        subtitle={t('subtitle')}
        headingLevel={headingLevel}
      />
      <ChatConfigPanel userDefault />
    </div>
  );
}

export default AgentChatDefaults;
