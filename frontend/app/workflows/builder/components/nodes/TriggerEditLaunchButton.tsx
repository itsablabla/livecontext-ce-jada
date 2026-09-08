'use client';

import * as React from 'react';
import { createPortal } from 'react-dom';
import { Play, Bug } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { canvasNodeButtonClass, canvasNodeButtonShimmerRadiusClass } from '@/components/ui/canvas-chrome';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { usePortalMenu } from '../../hooks/usePortalMenu';
import type { TriggerButtonVariant } from '../NodePlayButton';

interface TriggerEditLaunchButtonProps {
  /** Node id used as the entry point for the step-by-step execution */
  nodeId: string;
  /** Trigger sub-type - drives the icon and shimmer color */
  variant: TriggerButtonVariant;
  /** Border color synced with node status (same pattern as NodeBottomBar buttons) */
  borderColor: string;
}

// Distinct shimmer color per trigger type - mirrors NodePlayButton's palette.
const SHIMMER_BY_VARIANT: Record<TriggerButtonVariant, string> = {
  play: 'rgba(34, 197, 94, 0.35)',
  lightning: 'rgba(245, 158, 11, 0.35)',
  message: 'rgba(59, 130, 246, 0.35)',
  form: 'rgba(217, 70, 239, 0.35)',
  webhook: 'rgba(99, 102, 241, 0.35)',
  schedule: 'rgba(6, 182, 212, 0.35)',
  workflow: 'rgba(16, 185, 129, 0.35)',
  table: 'rgba(249, 115, 22, 0.35)',
  error: 'rgba(239, 68, 68, 0.35)',
};

/**
 * Edit-mode launcher rendered at the bottom of every trigger node.
 *
 * <p>Clicking it opens a small portal menu offering two start modes:
 * <ul>
 *   <li><b>Auto</b> - dispatches {@code workflowViewStart} (full automatic run).</li>
 *   <li><b>Step-by-step</b> - dispatches {@code workflowStartStepByStep} with
 *       {@code startFromNode}, entering debug mode paused on this trigger.</li>
 * </ul>
 *
 * <p>The menu reuses the portal + outside-click + Escape + scroll-dismiss
 * pattern from TriggerPanel's overflow menu, so positioning survives the
 * draggable canvas and clipping parents.
 */
/** The menu's resting width; the clamp and `min-w-[200px]` read the same number. */
const LAUNCH_MENU_WIDTH = 200;

export function TriggerEditLaunchButton({ nodeId, variant, borderColor }: TriggerEditLaunchButtonProps) {
  const t = useTranslations('workflowBuilder.canvas');
  // Audit 2026-07-02 - VIEWER role in an org workspace is read-only: launching a
  // run auto-saves the plan and the backend 403s VIEWER, so the launcher hides
  // (useWorkflowExecution also no-ops the events as a second line of defense).
  const canMutate = useCanMutateInCurrentOrg();
  // Names the workflow on both start events: several canvases can be mounted at
  // once (the right side panel embeds its own), and an unscoped start ran them all.
  const { workflowId } = useWorkflowMode();
  // Anchored just below the button, centered on its horizontal axis.
  const { open, isVisible, toggle, close, triggerRef, menuRef, menuStyle } = usePortalMenu('below', LAUNCH_MENU_WIDTH);

  const startAuto = React.useCallback(() => {
    close();
    // Auto mode: enter run mode and fire THIS trigger directly. handleStartEvent
    // fires only the selected trigger (no-payload types) when startFromNode is set;
    // the header run button sends no startFromNode and keeps firing all root triggers.
    window.dispatchEvent(new CustomEvent('workflowViewStart', { detail: { workflowId, startFromNode: nodeId } }));
  }, [nodeId, close, workflowId]);

  const startStepByStep = React.useCallback(() => {
    close();
    window.dispatchEvent(new CustomEvent('workflowStartStepByStep', {
      detail: { workflowId, startFromNode: nodeId },
    }));
  }, [nodeId, close, workflowId]);

  // After every hook (rules of hooks): read-only VIEWERs get no launcher at all.
  if (!canMutate) return null;

  const borderStyle = { borderWidth: 2, borderStyle: 'solid' as const, borderColor };
  const shimmerColor = SHIMMER_BY_VARIANT[variant] ?? SHIMMER_BY_VARIANT.play;

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        onClick={toggle}
        onMouseDown={(e) => e.stopPropagation()}
        className={cn(canvasNodeButtonClass, 'cursor-pointer nodrag nopan', open && 'ring-2 ring-[var(--accent-primary)] ring-offset-1')}
        style={borderStyle}
        title={t('runWorkflow')}
        aria-haspopup="menu"
        aria-expanded={open}
      >
        <span
          // The shared constant, not a hand-written radius: an overlay whose
          // corners disagree with the button's leaves a visibly clipped scan.
          className={`absolute inset-0 ${canvasNodeButtonShimmerRadiusClass} pointer-events-none`}
          style={{
            background: `linear-gradient(90deg, transparent 0%, ${shimmerColor} 50%, transparent 100%)`,
            backgroundSize: '200% 100%',
            animation: 'shimmer-scan 4s ease-in-out infinite',
          }}
        />
        <span className="relative z-10">
          <Play className="h-3.5 w-3.5" strokeWidth={2} fill="currentColor" />
        </span>
      </button>

      {isVisible && createPortal(
        <div
          ref={menuRef}
          role="menu"
          className="fixed z-[9999] min-w-[200px] max-w-[calc(100vw-1rem)] bg-theme-primary rounded-2xl p-2 border border-gray-300/70 dark:border-gray-600/70 shadow-2xl animate-in fade-in-0 zoom-in-95 duration-150 nodrag nopan"
          style={menuStyle}
          onMouseDown={(e) => e.stopPropagation()}
        >
          <div className="space-y-1">
            <button
              role="menuitem"
              onClick={startAuto}
              className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
            >
              <Play className="h-4 w-4 flex-shrink-0" strokeWidth={2} fill="currentColor" />
              <span className="text-sm flex-1 text-left truncate">{t('runAuto')}</span>
            </button>
            <button
              role="menuitem"
              onClick={startStepByStep}
              className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
            >
              <Bug className="h-4 w-4 flex-shrink-0" strokeWidth={2} />
              <span className="text-sm flex-1 text-left truncate">{t('runStepByStep')}</span>
            </button>
          </div>
        </div>,
        document.body,
      )}
    </>
  );
}
