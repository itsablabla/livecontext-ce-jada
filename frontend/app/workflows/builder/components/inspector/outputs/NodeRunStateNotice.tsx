/**
 * NodeRunStateNotice - what the run columns say while a node has no data yet.
 *
 * A step row is only written when the node produces a result, so a node that is
 * still executing, or parked on a signal, has nothing to fetch. Both run columns
 * used to render the same flat "No data" for that, which reads as "this node ran
 * and produced nothing" - the opposite of what is happening. This states the
 * live fact instead, and says which column the reader is looking at:
 *
 *  - Params : the values shown are the CONFIGURED ones, not yet resolved.
 *  - Output : the result is still being computed, or is waiting on a signal.
 */

'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Loader2, PauseCircle } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import type { PendingSignal } from '@/lib/websocket/ws-types';

/** Which live state the selected node is in, when it has no persisted data. */
export type NodeLiveState = 'running' | 'awaiting';

interface NodeRunStateNoticeProps {
  state: NodeLiveState;
  /** Which column is rendering this, so the explanation matches what is shown. */
  column: 'params' | 'output';
  /** Signals the node is parked on (only meaningful when state is 'awaiting'). */
  pendingSignals?: PendingSignal[];
  className?: string;
}

/**
 * Signal types a node can be parked on. Kept in step with the backend
 * SignalType enum - an unknown value falls back to the raw name rather than
 * rendering a missing translation key.
 */
const SIGNAL_LABEL_KEY: Record<string, string> = {
  WAIT_TIMER: 'signalWaitTimer',
  USER_APPROVAL: 'signalUserApproval',
  WEBHOOK_WAIT: 'signalWebhookWait',
  INTERFACE_SIGNAL: 'signalInterface',
  AGENT_EXECUTION: 'signalAgentExecution',
  BROWSER_USER_TAKEOVER: 'signalBrowserTakeover',
};

export function NodeRunStateNotice({
  state,
  column,
  pendingSignals = [],
  className,
}: NodeRunStateNoticeProps) {
  const t = useTranslations('workflowBuilder.inspector.runData');

  const isRunning = state === 'running';

  const tone = isRunning
    ? 'border-blue-200 bg-blue-50 text-blue-700 dark:border-blue-800 dark:bg-blue-950/30 dark:text-blue-300'
    : 'border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-300';

  const Icon = isRunning ? Loader2 : PauseCircle;

  const title = isRunning ? t('stateRunning') : t('stateAwaiting');

  const description = isRunning
    ? column === 'params'
      ? t('stateRunningParams')
      : t('stateRunningOutput')
    : column === 'params'
      ? t('stateAwaitingParams')
      : t('stateAwaitingOutput');

  return (
    <div
      data-testid={`node-run-state-${state}`}
      className={clsx('rounded-md border px-2.5 py-2', tone, className)}
    >
      <div className="flex items-center gap-2">
        <Icon className={clsx('h-3.5 w-3.5 flex-shrink-0', isRunning && 'animate-spin')} />
        <span className="text-sm font-medium">{title}</span>
      </div>
      <p className="mt-1 text-sm opacity-90">{description}</p>
      {state === 'awaiting' && pendingSignals.length > 0 && (
        <ul className="mt-1.5 space-y-0.5">
          {pendingSignals.map((signal) => (
            <li key={signal.id} className="text-sm">
              <span className="font-medium">{signalLabel(t, signal.signalType)}</span>
              {signal.expiresAt && (
                <span className="opacity-80">
                  {' · '}
                  {t('signalExpiresAt', { date: formatUtcDateTime(signal.expiresAt) })}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function signalLabel(t: (key: string) => string, signalType: string): string {
  const key = SIGNAL_LABEL_KEY[signalType];
  return key ? t(key) : signalType;
}
