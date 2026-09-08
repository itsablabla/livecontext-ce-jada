'use client';

import { usePathname, useSearchParams } from 'next/navigation';
import { Bot, Network, Zap, BarChart3, Brain, Settings } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { samePageUrl, showSamePageUrl } from '@/lib/navigation/showSamePageUrl';

export type AgentPageTab = 'agents' | 'skills' | 'memory' | 'fleet' | 'metrics' | 'settings';

interface AgentPageTabBarProps {
  activeTab: AgentPageTab;
  onLocalTabChange?: (tab: AgentPageTab) => void;
}

/**
 * Shared tab bar for the Agent area (Agents / Skills / Memory / Fleet / Metrics / Settings).
 * The task board lives only in the aggregated Board menu (/app/board → Tasks tab),
 * so there is no longer a "Board" tab here.
 */
export function AgentPageTabBar({ activeTab, onLocalTabChange }: AgentPageTabBarProps) {
  const t = useTranslations('emptyState.agent');
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const goToAgentView = (tab: AgentPageTab) => {
    if (onLocalTabChange) {
      onLocalTabChange(tab);
      return;
    }
    // 'agents' is the default (clean URL); every other tab is encoded in ?view=
    // so the URL stays the single source of truth (see AgentView). A tab is a change of
    // ADDRESS on the page already on screen, and going BACK to the default removes the last
    // param - which a router push of the bare pathname cannot do when the page was loaded at
    // it, so the tab did nothing on a page opened directly on `?view=skills`.
    showSamePageUrl(
      tab === 'agents' ? pathname : `${pathname}?view=${tab}`,
      samePageUrl(pathname, searchParams),
    );
  };

  const tabClass = (tab: AgentPageTab) =>
    `inline-flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium transition-all border-b-2 -mb-px whitespace-nowrap flex-shrink-0 ${
      activeTab === tab
        ? 'border-[var(--accent-primary)] text-theme-primary'
        : 'border-transparent text-theme-muted hover:text-theme-primary'
    }`;

  return (
    <div
      className="flex items-center gap-1 border-b border-theme overflow-x-auto"
      style={{ scrollbarWidth: 'none' }}
    >
      <button type="button" onClick={() => goToAgentView('agents')} className={tabClass('agents')}>
        <Bot className="h-3.5 w-3.5" />
        {t('tabAgents')}
      </button>
      <button type="button" onClick={() => goToAgentView('skills')} className={tabClass('skills')}>
        <Zap className="h-3.5 w-3.5" />
        {t('tabSkills')}
      </button>
      <button type="button" onClick={() => goToAgentView('memory')} className={tabClass('memory')}>
        <Brain className="h-3.5 w-3.5" />
        {t('tabMemory')}
      </button>
      <button type="button" onClick={() => goToAgentView('fleet')} className={tabClass('fleet')}>
        <Network className="h-3.5 w-3.5" />
        {t('tabFleet')}
      </button>
      <button type="button" onClick={() => goToAgentView('metrics')} className={tabClass('metrics')}>
        <BarChart3 className="h-3.5 w-3.5" />
        {t('tabMetrics')}
      </button>
      <button type="button" onClick={() => goToAgentView('settings')} className={tabClass('settings')}>
        <Settings className="h-3.5 w-3.5" />
        {t('tabSettings')}
      </button>
    </div>
  );
}
