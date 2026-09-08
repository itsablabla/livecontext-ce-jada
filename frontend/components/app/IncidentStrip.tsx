'use client';

import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { AlertTriangle, ExternalLink, Wrench, X } from 'lucide-react';
import { isLive } from '@/lib/status/incidentIo';
import { useServiceStatus } from '@/hooks/useServiceStatus';
import type { StatusIncident } from '@/lib/status/types';

/**
 * In-app strip announcing an incident or a maintenance that is happening RIGHT
 * NOW, so a user does not have to guess whether the platform or their workflow is
 * at fault.
 *
 * Mounted in the /app layout next to the modals rather than inside `AppShell`:
 * AppShell renders two different arrangements (see its header comment) and moving
 * a child between those branches remounts the subtree, so a strip added there
 * would risk tearing down a running canvas or stream. A fixed overlay at the top
 * needs no layout surgery and cannot affect the dock arrangements at all.
 *
 * Only LIVE entries show up: a maintenance that is merely scheduled belongs on
 * the status page, not in the user's face. Dismissal is remembered per incident
 * AND per provider status token, so the strip does not nag after being read, yet
 * comes back when the situation actually changes (e.g. investigating -> fixing).
 */

const DISMISS_KEY = 'lc.incidentStrip.dismissed';

/** Identity of what the user dismissed: the entry, at that stage of its life. */
function signatureOf(incident: StatusIncident): string {
  return `${incident.id}:${incident.status}`;
}

function readDismissed(): string[] {
  try {
    const raw = window.localStorage.getItem(DISMISS_KEY);
    const parsed = raw ? JSON.parse(raw) : null;
    return Array.isArray(parsed) ? parsed.filter((entry) => typeof entry === 'string') : [];
  } catch {
    // Private window, blocked site data, or a corrupted value: nothing dismissed.
    return [];
  }
}

function writeDismissed(signatures: string[]): void {
  try {
    // Bounded: only the few most recent matter, and this must never grow forever.
    window.localStorage.setItem(DISMISS_KEY, JSON.stringify(signatures.slice(-20)));
  } catch {
    // Storage unavailable: the strip simply reappears on the next load.
  }
}

export default function IncidentStrip() {
  const t = useTranslations('platformStatus');
  const { status } = useServiceStatus();
  const [dismissed, setDismissed] = useState<string[] | null>(null);

  // Read once on mount: localStorage does not exist during SSR, and reading it in
  // render would desync server and client HTML. Until it is read `dismissed` is
  // null and nothing renders, so an already-dismissed strip does not flash up
  // for one frame before disappearing again.
  useEffect(() => {
    setDismissed(readDismissed());
  }, []);

  const live = (status?.incidents ?? []).filter(isLive);
  const incident =
    dismissed === null ? undefined : live.find((entry) => !dismissed.includes(signatureOf(entry)));

  if (!incident) return null;

  const isMaintenance = incident.kind === 'maintenance';
  const Icon = isMaintenance ? Wrench : AlertTriangle;
  const tone = isMaintenance
    ? 'border-amber-500/40 bg-amber-50 dark:bg-amber-950/40 text-amber-900 dark:text-amber-100'
    : 'border-red-500/40 bg-red-50 dark:bg-red-950/40 text-red-900 dark:text-red-100';

  const handleDismiss = () => {
    const next = [...(dismissed ?? []), signatureOf(incident)];
    setDismissed(next);
    writeDismissed(next);
  };

  return (
    <div className="fixed top-3 left-1/2 -translate-x-1/2 z-[60] w-[min(92vw,44rem)] px-2">
      <div
        role="status"
        className={`flex items-start gap-2.5 rounded-xl border px-3.5 py-2.5 shadow-lg backdrop-blur-sm ${tone}`}
      >
        <Icon className="h-4 w-4 mt-0.5 shrink-0" aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium truncate">
            {isMaintenance ? t('strip.maintenance') : t('strip.incident')}
            {': '}
            {incident.name}
          </p>
          {incident.message && (
            <p className="text-sm mt-0.5 opacity-90 line-clamp-2">{incident.message}</p>
          )}
        </div>
        <a
          href={incident.url || status?.statusPageUrl || '/status'}
          target="_blank"
          rel="noopener noreferrer"
          className="text-sm font-medium underline whitespace-nowrap mt-0.5 flex items-center gap-1"
        >
          {t('strip.view')}
          <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
        </a>
        <button
          type="button"
          onClick={handleDismiss}
          aria-label={t('strip.dismiss')}
          className="shrink-0 rounded-md p-0.5 hover:bg-black/10 dark:hover:bg-white/10 cursor-pointer"
        >
          <X className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
