'use client';

import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useTranslations } from 'next-intl';
import { AlertTriangle, Loader2, Play, Square, XCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { canvasChromeChipRadiusClass } from '@/components/ui/canvas-chrome';
import { TERMINAL_RUN_STATUSES } from './runFormatting';
import type { RunPanelAction } from './runPanelBus';

/**
 * Which action a run in this status offers, or null when it offers none.
 *
 * Exported because a caller has to know BEFORE rendering: a surface that wraps
 * this control in its own chrome (a chip separator, a toolbar slot) must not
 * paint that chrome around nothing.
 */
export function resolveRunAction(status: string | null | undefined): RunPanelAction | null {
  const raw = status?.toUpperCase();
  if (!raw) return null;
  if (raw === 'RUNNING' || raw === 'PAUSED') return 'stop';
  if (raw === 'WAITING_TRIGGER') return 'cancel';
  // Every terminal status is reactivatable: the dispatcher rejects firing into a
  // terminal run, so the user must explicitly re-arm it.
  if (TERMINAL_RUN_STATUSES.has(raw)) return 'reactivate';
  return null;
}

export interface RunActionButtonProps {
  /** Raw run status (RUNNING / WAITING_TRIGGER / CANCELLED / ...). */
  status?: string | null;
  /** Pinned (production) version, shown as a warning in the cancel dialog. */
  pinnedVersion?: number | null;
  /** Graceful stop (RUNNING/PAUSED to WAITING_TRIGGER). Omit to hide. */
  onStop?: () => void;
  /** Hard cancel (WAITING_TRIGGER to CANCELLED), asks for confirmation. Omit to hide. */
  onCancel?: () => void;
  /** Reactivate a terminal run (to WAITING_TRIGGER). Omit to hide. */
  onReactivate?: () => void;
  /**
   * The action currently in flight, if any.
   *
   * The ACTION, not a boolean: a stop in flight flips the run's status, and by
   * the time it settles this control may already be offering `cancel` or
   * `reactivate`. A boolean would leave the new control spinning for work that
   * was never asked of it.
   */
  pendingAction?: RunPanelAction | null;
  /** The last attempt failed. Surfaced on the button so a dead click is never silent. */
  failed?: boolean;
  /** Type scale, matching the two surfaces {@link RunSummaryBar} renders on. */
  size?: 'compact' | 'panel';
  /** Rendered before the button (the summary bar's chip separator). */
  separator?: ReactNode;
  className?: string;
}

/**
 * The one stop / cancel / reactivate control of the product.
 *
 * It used to live inline in `RunSummaryBar`, which meant it existed on exactly
 * two surfaces: the canvas pill and the Run tab of the side panel. Every other
 * place a run can be launched and watched (a trigger tab, an application
 * interface, the application page) showed a live run with no way to stop it.
 * Extracting the control is what lets those surfaces carry the SAME affordance
 * (same icon, same colours, same confirmation) instead of growing their own.
 */
export function RunActionButton({
  status,
  pinnedVersion,
  onStop,
  onCancel,
  onReactivate,
  pendingAction = null,
  failed = false,
  size = 'compact',
  separator,
  className = '',
}: RunActionButtonProps) {
  const t = useTranslations();
  const [cancelConfirm, setCancelConfirm] = useState(false);
  const [mounted, setMounted] = useState(false);
  useEffect(() => { setMounted(true); return () => setMounted(false); }, []);

  /**
   * Per-instance dialog ids.
   *
   * They used to be hardcoded, which was survivable while this control existed
   * once. It now mounts up to four times at once (canvas pill, panel tab bar,
   * Run tab, application toolbar), and duplicate ids in one document make
   * `aria-labelledby` point at whichever came first - so a screen reader can
   * announce another control's dialog.
   */
  const dialogId = useId();
  const titleId = `${dialogId}-title`;
  const descriptionId = `${dialogId}-description`;

  /**
   * A real modal: focus starts inside, Tab cycles WITHIN it, Escape closes it and
   * focus returns to the control that opened it. Without the cycle a keyboard
   * user tabs straight out into the page behind and operates a canvas they were
   * asked to confirm against - a dialog by attribute only.
   */
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  useEffect(() => {
    if (!cancelConfirm) {
      openerRef.current?.focus();
      openerRef.current = null;
      return;
    }
    openerRef.current = document.activeElement as HTMLElement | null;
    dialogRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        // Consumed: other document-level Escape handlers (the fullscreen
        // application close, for one) must not also fire on a dismissal.
        e.preventDefault();
        e.stopPropagation();
        setCancelConfirm(false);
        return;
      }
      if (e.key !== 'Tab') return;
      const dialog = dialogRef.current;
      if (!dialog) return;
      const focusable = dialog.querySelectorAll<HTMLElement>(
        'button:not([disabled]), [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
      );
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (e.shiftKey && (active === first || active === dialog)) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [cancelConfirm]);

  const isPanel = size === 'panel';
  const actionBtnCls = isPanel ? 'w-6 h-6' : 'w-5 h-5';
  const actionIconCls = isPanel ? 'w-3 h-3' : 'w-2.5 h-2.5';

  const action = resolveRunAction(status);
  const handler = action === 'stop' ? onStop
    : action === 'cancel' ? onCancel
    : action === 'reactivate' ? onReactivate
    : undefined;

  const onClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    if (action === 'cancel') setCancelConfirm(true);
    else handler?.();
  }, [action, handler]);

  if (!action || !handler) return null;

  // Only the action ON SCREEN spins, so the spinner never describes other work.
  const pending = pendingAction === action;
  const isReactivate = action === 'reactivate';
  const label = isReactivate
    ? t('workflow.reactivateRun.title')
    : action === 'cancel'
      ? t('workflow.cancelRun.title')
      : t('workflow.mode.stopWorkflow');
  const tone = isReactivate
    ? 'bg-green-100 dark:bg-green-900/30 text-green-600 dark:text-green-400 hover:bg-green-200 dark:hover:bg-green-900/50'
    : 'bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 hover:bg-red-200 dark:hover:bg-red-900/50';
  // A failure has to be legible without a mouse: the tooltip alone leaves a
  // touch user and a screen-reader user with nothing at all. So the icon
  // changes, the control gets a ring, and the accessible NAME carries it.
  const Icon = pending ? Loader2 : failed ? AlertTriangle : isReactivate ? Play : Square;
  const failedRing = failed ? ' ring-2 ring-red-500 dark:ring-red-400' : '';
  const failedLabel = failed ? `${label} - ${t('workflow.runAction.failed')}` : label;

  return (
    <>
      <span className={`flex items-center gap-2 flex-shrink-0 ${className}`}>
        {separator}
        <button
          type="button"
          data-run-action={action}
          aria-busy={pending || undefined}
          aria-label={failedLabel}
          onClick={onClick}
          disabled={pending}
          className={`flex items-center justify-center ${actionBtnCls} ${canvasChromeChipRadiusClass} ${tone}${failedRing} transition-colors disabled:opacity-60`}
          /* The failure has to be readable somewhere: most of these surfaces have
             no toast host of their own, and a stop that silently does nothing is
             the exact bug this control exists to end. The reason stays in the
             console - an API message is not translated, and this is user-facing. */
          data-run-action-failed={failed || undefined}
          title={failedLabel}
        >
          <Icon className={`${actionIconCls} ${pending ? 'animate-spin' : ''}`} />
        </button>
      </span>

      {/* Cancel confirmation modal (WAITING_TRIGGER to terminal CANCELLED).
          stopPropagation on the backdrop even though this is a portal: React
          bubbles synthetic events through the REACT tree, not the DOM one, so a
          dismissing click here would still reach the bar wrapping this control
          and open the run panel behind the modal. */}
      {mounted && cancelConfirm && createPortal(
        <div
          data-run-cancel-backdrop
          className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
          onClick={(e) => { e.stopPropagation(); setCancelConfirm(false); }}
        >
          <div
            role="alertdialog"
            aria-modal="true"
            ref={dialogRef}
            tabIndex={-1}
            aria-labelledby={titleId}
            aria-describedby={descriptionId}
            className="max-w-sm w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="text-center mb-6">
              <div className="w-14 h-14 bg-red-100 dark:bg-red-900/30 rounded-xl flex items-center justify-center mx-auto mb-4">
                <XCircle className="w-7 h-7 text-red-600 dark:text-red-400" />
              </div>
              <h3 id={titleId} className="text-lg font-semibold text-theme-primary">
                {t('workflow.cancelRun.title')}
              </h3>
              <p id={descriptionId} className="text-sm text-theme-secondary mt-2">
                {t('workflow.cancelRun.description')}
              </p>
              {pinnedVersion != null && (
                <div className="mt-3 p-3 bg-red-50 dark:bg-red-900/20 rounded-xl border border-red-200 dark:border-red-800">
                  <p className="text-xs text-red-600 dark:text-red-400">
                    {t('workflow.cancelRun.warning')}
                  </p>
                </div>
              )}
            </div>
            <div className="flex gap-3">
              <Button variant="outline" onClick={() => setCancelConfirm(false)} className="flex-1">
                {t('workflow.cancelRun.keep')}
              </Button>
              <Button
                onClick={() => { setCancelConfirm(false); onCancel?.(); }}
                className="flex-1 bg-red-600 hover:bg-red-700 text-white"
              >
                {t('workflow.cancelRun.confirm')}
              </Button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </>
  );
}
