'use client';

import * as React from 'react';
import { createPortal } from 'react-dom';
import { RotateCcw } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';

interface RerunConfirmModalProps {
  /** Human-readable name of the step the rerun would restart from. */
  stepLabel: string;
  /**
   * The fire of the run that will be redone, or undefined for the run's most recent one.
   *
   * A run accumulates one epoch per trigger fire and each keeps its own results, so the node
   * alone does not say what is about to be re-executed: the same restart, clicked while
   * reading epoch 2, redoes different work than it does on epoch 9. Naming it is the whole
   * point of a confirmation on a run that then continues unattended.
   */
  epoch?: number;
  /** Start the rerun. */
  onConfirm: () => void;
  /** Dismiss without rerunning anything. */
  onCancel: () => void;
}

/**
 * Confirmation asked before restarting an AUTOMATIC run from a node.
 *
 * <p>On a stepped run a rerun stops at the target and waits for the user, so it is cheap and
 * reversible. On an automatic run the same gesture reruns the target and then lets the whole
 * downstream chain run again unattended, which can spend paid calls and send real messages.
 * That asymmetry is invisible on the button itself, hence this gate. Matches the app's default
 * confirmation-modal style (portal overlay, rounded card, icon + title + message,
 * Cancel | primary action).</p>
 */
export function RerunConfirmModal({ stepLabel, epoch, onConfirm, onCancel }: RerunConfirmModalProps) {
  const t = useTranslations('workflowBuilder.rerunConfirm');
  const tc = useTranslations('common');

  // Escape cancels, like every other dismissible modal in the builder.
  React.useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onCancel]);

  const titleId = 'rerun-confirm-title';

  return createPortal(
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onCancel}
      data-testid="rerun-confirm-overlay"
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        {/* Icon */}
        <div className="w-16 h-16 bg-amber-100 dark:bg-amber-950/40 rounded-2xl flex items-center justify-center mx-auto mb-5">
          <RotateCcw className="h-8 w-8 text-amber-600 dark:text-amber-400" />
        </div>

        {/* Title */}
        <h2 id={titleId} className="text-2xl font-semibold text-theme-primary text-center mb-2">
          {t('title')}
        </h2>

        {/* Message */}
        <p className="text-theme-secondary text-center mb-4">
          {t('message')}
        </p>

        {/* The node the restart starts from, shown verbatim so the user can vet it. */}
        <p className="text-sm text-theme-secondary text-center break-all font-mono bg-black/5 dark:bg-white/5 rounded-lg px-3 py-2">
          {stepLabel}
        </p>

        {/* ...and WHICH fire of the run it redoes. Always shown, both branches named: a user
            reading epoch 2 has to see that this restarts epoch 2, and one on the live state
            has to see that it does not. */}
        <p className="text-sm text-theme-secondary text-center mt-2 mb-8" data-testid="rerun-confirm-scope">
          {epoch != null ? t('scopeEpoch', { epoch }) : t('scopeLatestEpoch')}
        </p>

        {/* Actions */}
        <div className="flex gap-3">
          <Button onClick={onCancel} variant="outline" className="flex-1" data-testid="rerun-confirm-cancel">
            {tc('cancel')}
          </Button>
          <Button onClick={onConfirm} variant="default" className="flex-1" data-testid="rerun-confirm-accept">
            {t('confirm')}
          </Button>
        </div>
      </div>
    </div>,
    document.body
  );
}

export default RerunConfirmModal;
