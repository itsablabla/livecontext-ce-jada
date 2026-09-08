'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Check, X, PlayCircle, MinusCircle, PauseCircle } from 'lucide-react';
import type { DerivedNodeStatus, StatusCounts } from '../types';

interface EdgeStatusLabelProps {
  status: DerivedNodeStatus;
  statusCounts?: StatusCounts;
  isSkipped?: boolean;
}

/**
 * Optimized edge status label component
 * Displays status text and statusCounts badges
 */
export const EdgeStatusLabel = React.memo(function EdgeStatusLabel({
  status,
  statusCounts,
  isSkipped,
}: EdgeStatusLabelProps) {
  const t = useTranslations('nodeStatus');

  // Compute label text from status
  const labelText = React.useMemo(() => {
    if (!status || status === 'pending' || status === 'ready') return '';
    return t(status);
  }, [status, t]);

  // Extract counts from statusCounts (canonical uppercase keys only).
  // AWAITING_SIGNAL is read like the rest: `deriveStatusFromCounts` returns
  // `awaiting_signal` off that very key, so an edge could arrive here carrying
  // ONLY awaiting items - and without this line every chip was zero and the label
  // rendered as an empty white pill floating on the edge.
  const counts = React.useMemo(() => {
    if (!statusCounts) return null;
    return {
      completed: (statusCounts.COMPLETED ?? 0) + (statusCounts.SUCCESS ?? 0),
      failed: (statusCounts.FAILED ?? 0) + (statusCounts.ERROR ?? 0),
      running: (statusCounts.RUNNING ?? 0) + (statusCounts.RETRYING ?? 0),
      skipped: statusCounts.SKIPPED ?? 0,
      awaitingSignal: statusCounts.AWAITING_SIGNAL ?? 0,
    };
  }, [statusCounts]);

  // Build title from status and counts (must be before early return for hooks rules)
  const title = React.useMemo(() => {
    const parts: string[] = [];
    if (labelText) parts.push(labelText);
    if (counts) {
      if (counts.completed > 0) parts.push(`${counts.completed} ${t('completed').toLowerCase()}`);
      if (counts.failed > 0) parts.push(`${counts.failed} ${t('failed').toLowerCase()}`);
      if (counts.running > 0) parts.push(`${counts.running} ${t('running').toLowerCase()}`);
      if (counts.awaitingSignal > 0) parts.push(`${counts.awaitingSignal} ${t('awaiting_signal').toLowerCase()}`);
      if (counts.skipped > 0) parts.push(`${counts.skipped} ${t('skipped').toLowerCase()}`);
    }
    return parts.join(' • ');
  }, [labelText, counts, t]);

  // Only show if we have counts or a valid status
  if (!counts && !status) return null;

  return (
    <div className="flex items-center gap-1 rounded-md bg-white dark:bg-gray-800 px-2 py-0.5" title={title}>
      {counts ? (
        <>
          {/* Headline marker for a status the COUNTS cannot express.
              A failed node's outgoing edges are persisted as skipped:1 (that count is
              load-bearing for merge convergence), and the renderer recolours the LINE
              red so the failure is visible - see coerceStatusForFailedSource. Without
              this marker the pill beside that red line showed a lone grey "skipped 1",
              i.e. the line and the label told two different stories. The counts stay
              truthful; only a red X is prepended to name what the line is saying. */}
          {status === 'failed' && counts.failed === 0 && (
            <X className="h-3 w-3 text-red-600 dark:text-red-400" data-testid="edge-status-failed-headline" />
          )}
          {counts.completed > 0 && (
            <span className="flex items-center gap-0.5 text-green-600 dark:text-green-400" title={`${counts.completed} ${t('completed').toLowerCase()}`}>
              <Check className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.completed >= 100 ? '99+' : counts.completed}</span>
            </span>
          )}
          {counts.failed > 0 && (
            <span className="flex items-center gap-0.5 text-red-600 dark:text-red-400" title={`${counts.failed} ${t('failed').toLowerCase()}`}>
              <X className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.failed >= 100 ? '99+' : counts.failed}</span>
            </span>
          )}
          {counts.running > 0 && (
            <span className="flex items-center gap-0.5 text-blue-600 dark:text-blue-400" title={`${counts.running} ${t('running').toLowerCase()}`}>
              <PlayCircle className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.running >= 100 ? '99+' : counts.running}</span>
            </span>
          )}
          {counts.awaitingSignal > 0 && (
            <span className="flex items-center gap-0.5 text-amber-500 dark:text-amber-400" title={`${counts.awaitingSignal} ${t('awaiting_signal').toLowerCase()}`} data-testid="edge-status-awaiting">
              <PauseCircle className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.awaitingSignal >= 100 ? '99+' : counts.awaitingSignal}</span>
            </span>
          )}
          {counts.skipped > 0 && (
            <span className="flex items-center gap-0.5 text-gray-500 dark:text-gray-400" title={`${counts.skipped} ${t('skipped').toLowerCase()}`}>
              <MinusCircle className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.skipped >= 100 ? '99+' : counts.skipped}</span>
            </span>
          )}
        </>
      ) : (
        // Fallback: show status icon if no counts but status exists.
        // Same icons and colours as NodeStatusBadge - the pill on the edge and the
        // badge on its source node must not tell two different stories.
        <>
          {status === 'running' && <PlayCircle className="h-3 w-3 text-blue-600 dark:text-blue-400" />}
          {status === 'completed' && <Check className="h-3 w-3 text-green-600 dark:text-green-400" />}
          {status === 'failed' && <X className="h-3 w-3 text-red-600 dark:text-red-400" />}
          {status === 'skipped' && <MinusCircle className="h-3 w-3 text-gray-500 dark:text-gray-400" />}
          {status === 'awaiting_signal' && <PauseCircle className="h-3 w-3 text-amber-500 dark:text-amber-400" data-testid="edge-status-awaiting" />}
          {status === 'partial_success' && (
            <>
              <Check className="h-3 w-3 text-green-600 dark:text-green-400" />
              <X className="h-3 w-3 text-red-600 dark:text-red-400" />
            </>
          )}
        </>
      )}
    </div>
  );
});

