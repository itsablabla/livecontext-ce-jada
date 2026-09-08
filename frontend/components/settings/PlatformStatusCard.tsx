'use client';

import { Activity, AlertTriangle, Clock, ExternalLink } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { useServiceStatus } from '@/hooks/useServiceStatus';
import { severityOf } from '@/lib/status/overall';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';

/**
 * Settings > Information "Platform status" card: the current state of the
 * LiveContext cloud, and the way through to the full status page.
 *
 * It used to be a row in the user menu, which put a live-polling traffic light
 * in the menu people open to sign out or switch workspace. Information is where
 * the reader is already asking what this install IS - version, edition, licences
 * - so the platform's state belongs beside those rather than in a control menu.
 *
 * Cloud only, and gated by the caller: a self-hosted install measures nothing
 * about our cloud, and we make no availability promise about someone else's box.
 * `useServiceStatus` is disabled in CE too, so a stray mount would render the
 * loading state forever rather than a claim.
 *
 * It shares its query key with the incident strip that the /app layout mounts on
 * every page, so having both on screen costs ONE request between them.
 */

const DOT_COLOR: Record<'green' | 'amber' | 'red' | 'grey', string> = {
  green: 'bg-emerald-500',
  amber: 'bg-amber-500',
  red: 'bg-red-500',
  grey: 'bg-gray-400',
};

function Row({ icon: Icon, label, children }: {
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex items-center justify-between gap-3 py-1.5">
      <div className="flex items-center gap-2 text-theme-secondary">
        <Icon className="h-3.5 w-3.5 shrink-0" />
        <span className="text-sm">{label}</span>
      </div>
      <div className="min-w-0 text-right">{children}</div>
    </div>
  );
}

export default function PlatformStatusCard() {
  const t = useTranslations('platformStatus');
  const locale = useLocale();
  const { status, isLoading, isError } = useServiceStatus();

  return (
    <div className="rounded-xl border border-theme p-6" data-testid="platform-status-card">
      <div className="flex items-center gap-3 mb-5">
        <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center shrink-0">
          <Activity className="w-5 h-5 text-theme-primary" />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-theme-primary">{t('card.title')}</h2>
          <p className="text-sm text-theme-secondary">{t('card.subtitle')}</p>
        </div>
      </div>

      {isLoading ? (
        <div className="space-y-3" aria-hidden="true">
          <div className="h-4 w-2/3 rounded bg-theme-tertiary animate-pulse" />
          <div className="h-4 w-1/2 rounded bg-theme-tertiary animate-pulse" />
        </div>
      ) : !status || isError ? (
        // Never "operational" for something we failed to measure: a card that
        // cannot reach the status feed says so, in the same words the headline
        // uses for an unmeasured platform.
        <p className="text-sm text-theme-secondary">{t('card.loadError')}</p>
      ) : (
        <>
          <div className="space-y-0.5">
            <Row icon={Activity} label={t('card.state')}>
              <span className="inline-flex items-center gap-2">
                <span
                  className={`h-1.5 w-1.5 shrink-0 rounded-full ${DOT_COLOR[severityOf(status.overall)]}`}
                  aria-hidden="true"
                />
                <span className="text-sm font-medium text-theme-primary">
                  {t(`headline.${status.overall}`)}
                </span>
              </span>
            </Row>

            {status.incidents.length > 0 && (
              <Row icon={AlertTriangle} label={t('card.incidents')}>
                <span className="text-sm font-medium text-theme-primary tabular-nums">
                  {status.incidents.length}
                </span>
              </Row>
            )}

            {/* Only when the probe actually wrote a timestamp. Showing "just now"
                for a payload that carries none would date a measurement that did
                not happen. */}
            {status.generatedAt && (
              <Row icon={Clock} label={t('card.lastChecked')}>
                <span className="text-sm text-theme-secondary">
                  {formatUtcDateTime(status.generatedAt, { locale })}
                </span>
              </Row>
            )}
          </div>

          {/* A new tab, as the menu row did: checking the status must not cost
              the page the reader was on, which may be a running workflow. */}
          <a
            href="/status"
            target="_blank"
            rel="noopener noreferrer"
            className="mt-5 inline-flex items-center gap-1.5 text-sm text-theme-secondary hover:text-theme-primary transition-colors"
          >
            {t('strip.view')}
            <ExternalLink className="h-3.5 w-3.5" />
          </a>
        </>
      )}
    </div>
  );
}
