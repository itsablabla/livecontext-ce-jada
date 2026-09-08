'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Copy, Trash2 } from 'lucide-react';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { canvasNodeButtonClass, canvasNodeButtonShimmerRadiusClass } from '@/components/ui/canvas-chrome';
import { NodePlayButton, deriveNodeStatus, type TriggerButtonVariant } from '../NodePlayButton';

import { useWorkflowLayoutDirectionSafe } from '@/contexts/WorkflowLayoutDirectionContext';
import { getSideAttachment } from './handleGeometry';

export function ShimmerOverlay({ color }: { color: string }) {
  return (
    <span
      className={`absolute inset-0 ${canvasNodeButtonShimmerRadiusClass} pointer-events-none`}
      style={{
        background: `linear-gradient(90deg, transparent 0%, ${color} 50%, transparent 100%)`,
        backgroundSize: '200% 100%',
        animation: 'shimmer-scan 2.5s ease-in-out infinite',
      }}
    />
  );
}

export interface BottomButton {
  key: string;
  icon: React.ReactNode;
  title: string;
  onClick: (e: React.MouseEvent) => void;
  shimmer?: boolean;
  shimmerColor?: string;
  /**
   * This button IS a run affordance, so the whole bar shows without waiting for hover -
   * the same reveal `runnableNow` gives a ready play button below, and honoured in RUN MODE
   * only for the same reason. Set it on a button that REPLACES the play (the focus-epoch
   * trigger play), never on a contextual one: the reveal is the cue that says "you can run
   * this now", and spending it on anything else makes the bar permanent and the cue
   * meaningless.
   */
  revealsBar?: boolean;
}

interface PlayButtonConfig {
  nodeId: string;
  variant: TriggerButtonVariant;
  isAutoMode: boolean;
  isTriggerNode: boolean;
  stepByStepStatus: {
    isStepByStepMode: boolean;
    isReady: boolean;
    canExecute: boolean;
    isExecuting: boolean;
    isRerunning: boolean;
    isRunning: boolean;
    isFailed: boolean;
    isSkipped: boolean;
    isCompleted: boolean;
    canRerun: boolean;
    /** The run's persisted mode, terminality NOT folded in - drives the rerun label. */
    isSteppedRun: boolean;
    /** Backend step id - names the exact trigger in the open-tab event. */
    stepId?: string;
    executeStep: () => void;
    rerunStep: () => void;
  };
}

export interface HoverActionsConfig {
  onDelete?: () => void;
  onDuplicate?: () => void;
}

export interface BarHoverConfig {
  /** Whether the node is currently hovered (from useHoverVisibility) */
  isVisible: boolean;
  /** Keeps the hover-visibility alive while the pointer is over the bar */
  onHover?: () => void;
}

interface NodeBottomBarProps {
  /** Border color synced with node status */
  borderColor: string;
  /** Whether the node is currently running (for shimmer) */
  isRunning: boolean;
  /** Static contextual buttons (agent config/conversation, subworkflow, interface) */
  buttons?: BottomButton[];
  /** Play/rerun button config rendered via NodePlayButton with position="bottom-center" */
  playButton?: PlayButtonConfig;
  /**
   * Something else already occupies the node's attachment band (a file strip, the
   * interface pagination), so push this bar one notch further out. Was
   * `extraTopOffset` while the band was always BELOW the node; the band follows the
   * reading direction now (below in horizontal, beside in vertical), so the name no
   * longer names an axis.
   */
  extraOffset?: boolean;
  /** Slot rendered before the standard buttons (e.g. trigger pin button) */
  leadingSlot?: React.ReactNode;
  /** Slot rendered after the play button (e.g. edit-mode trigger launcher menu) */
  trailingSlot?: React.ReactNode;
  /**
   * Node hover state (from useHoverVisibility). When provided, the WHOLE bar -
   * contextual buttons, play, slots and delete/duplicate - is revealed only
   * while the node is hovered, so every bottom button shares the same
   * hover-to-reveal logic. JS-driven (not CSS group-hover) on purpose: the bar
   * sits outside the node's box, so it needs the useHoverVisibility grace
   * delay to stay interactive while the pointer crosses the 8px gap.
   * Omitted → always visible.
   */
  hover?: BarHoverConfig;
  /**
   * Edit-mode delete / duplicate actions rendered at the end of the row with
   * the same square-button style as the other buttons; hidden entirely in
   * run / preview-only mode. Revealed with the rest of the bar via `hover`.
   */
  hoverActions?: HoverActionsConfig;
}

/**
 * Centralized bottom bar for node buttons.
 * Renders all contextual + execution buttons in a single centered row below
 * the node, revealed on node hover when `hover` is provided.
 */
export function NodeBottomBar({ borderColor, isRunning, buttons, playButton, extraOffset, leadingSlot, trailingSlot, hover, hoverActions }: NodeBottomBarProps) {
  const { direction: layoutDirection } = useWorkflowLayoutDirectionSafe();
  const t = useTranslations('workflowBuilder.nodes');
  const { isRunMode, isPreviewOnly } = useWorkflowMode();

  const hasButtons = (buttons && buttons.length > 0);
  const showPlay = !!playButton;
  const hasPersistent = hasButtons || showPlay || !!leadingSlot || !!trailingSlot;

  // Delete/duplicate are edit-only (same gating as the legacy top-hover buttons).
  const showHoverActions = !!hoverActions && !isRunMode && !isPreviewOnly
    && !!(hoverActions.onDelete || hoverActions.onDuplicate);

  if (!hasPersistent && !showHoverActions) return null;

  // Effective status of the play button (same derivation as the rendered button
  // below). When a node is runnable right now in run mode - its play button is
  // in the "ready" state - reveal the whole bar WITHOUT waiting for hover, so the
  // run affordance is directly visible with its blue shimmer. This is the cue
  // that replaces the old green node-body "ready" shimmer: a trigger's run button
  // (auto mode) and every runnable node's run button (step-by-step mode) show up
  // on their own the moment they can be executed.
  const playStatus = playButton
    ? (playButton.isTriggerNode && playButton.stepByStepStatus.isReady
        ? 'ready'
        : deriveNodeStatus(playButton.stepByStepStatus))
    : undefined;
  // canExecute is required: the play button only renders its ready state when
  // status === 'ready' AND canExecute, so gating the reveal on it too avoids
  // revealing an empty bar in the (near-unreachable) ready-but-not-executable
  // case.
  const runnableNow = isRunMode && playStatus === 'ready' && !!playButton?.stepByStepStatus.canExecute;

  // Same reveal for a button that stands IN for the play. While one epoch is focused the
  // normal play is gone (the run is read-only there) and FlowNode puts back a launcher as a
  // plain button; without this it would only appear on hover, so the affordance the
  // all-epochs view shows on its own would be hidden on exactly the view that needs it - and
  // the pin/unpin sharing the bar would hide with it.
  // Run mode only, exactly like `runnableNow`: the flag stands in for a RUN affordance, and
  // the bar also carries the edit-only delete/duplicate. Without this gate a flagged
  // edit-mode button would pin Trash and Copy permanently visible under every node.
  const revealedByButton = isRunMode && !!buttons?.some(b => b.revealsBar);

  const isRevealed = runnableNow || revealedByButton || (hover ? hover.isVisible : true);
  // Children re-enable pointer events only while revealed: a child with an
  // unconditional pointer-events-auto would stay clickable through the
  // invisible (opacity-0) bar.
  const interactiveCls = isRevealed ? 'pointer-events-auto' : 'pointer-events-none';
  const borderStyle = { borderWidth: 2, borderStyle: 'solid' as const, borderColor };

  return (
    // pointer-events-none on the row so the gaps between buttons (and the row
    // itself) never swallow canvas clicks; each interactive child re-enables
    // its own pointer events while the bar is revealed.
    <div
      className={`absolute z-10 flex gap-1.5 nodrag nopan pointer-events-none transition-opacity duration-200 ease-out ${
        layoutDirection === 'vertical' ? 'flex-col' : 'flex-row'
      }`}
      style={{
        // The bar hangs off whichever edge the flow does NOT use: below the node in
        // horizontal, beside it in vertical (where bottom is the source handle).
        ...getSideAttachment(layoutDirection, 8, extraOffset ? 32 : 0),
        opacity: isRevealed ? 1 : 0,
      }}
      onMouseEnter={hover?.onHover}
    >
      {leadingSlot && <div className={`${interactiveCls} flex gap-1.5`}>{leadingSlot}</div>}

      {/* Static contextual buttons */}
      {buttons?.map(({ key, icon, title, onClick, shimmer, shimmerColor }) => (
        <button
          key={key}
          onClick={(e) => { e.stopPropagation(); onClick(e); }}
          className={`${canvasNodeButtonClass} ${interactiveCls}`}
          style={borderStyle}
          title={title}
          // Keyed, not titled: the title is translated, so a test that matched on it would
          // pass in English and fail in every other locale.
          data-testid={`node-bar-${key}`}
        >
          {(shimmer ?? isRunning) && <ShimmerOverlay color={shimmerColor ?? 'rgba(59, 130, 246, 0.3)'} />}
          <span className="relative z-10">{icon}</span>
        </button>
      ))}

      {/* Play/rerun button */}
      {playButton && (
        <div className={`${interactiveCls} flex gap-1.5`}>
          <NodePlayButton
            nodeId={playButton.nodeId}
            status={playButton.isTriggerNode && playButton.stepByStepStatus.isReady ? 'ready' : deriveNodeStatus(playButton.stepByStepStatus)}
            canExecute={playButton.stepByStepStatus.canExecute}
            isLoading={playButton.stepByStepStatus.isExecuting || playButton.stepByStepStatus.isRerunning}
            onExecute={() => playButton.stepByStepStatus.executeStep()}
            onRerun={!playButton.isTriggerNode && playButton.stepByStepStatus.canRerun ? () => playButton.stepByStepStatus.rerunStep() : undefined}
            variant={playButton.variant}
            triggerId={playButton.stepByStepStatus.stepId}
            isAutoMode={playButton.isAutoMode}
            isStepByStepMode={playButton.stepByStepStatus.isSteppedRun}
            position="bottom-center"
            borderColor={borderColor}
          />
        </div>
      )}

      {trailingSlot && <div className={`${interactiveCls} flex gap-1.5`}>{trailingSlot}</div>}

      {/* Edit-mode delete/duplicate - same style, at the end of the centered row */}
      {showHoverActions && (
        <>
          {hoverActions!.onDelete && (
            <button
              onClick={(e) => { e.stopPropagation(); hoverActions!.onDelete!(); }}
              className={`${canvasNodeButtonClass} ${interactiveCls}`}
              style={borderStyle}
              title={t('deleteNode')}
            >
              <span className="relative z-10"><Trash2 className="h-3 w-3" strokeWidth={2} /></span>
            </button>
          )}
          {hoverActions!.onDuplicate && (
            <button
              onClick={(e) => { e.stopPropagation(); hoverActions!.onDuplicate!(); }}
              className={`${canvasNodeButtonClass} ${interactiveCls}`}
              style={borderStyle}
              title={t('duplicateNode')}
            >
              <span className="relative z-10"><Copy className="h-3 w-3" strokeWidth={2} /></span>
            </button>
          )}
        </>
      )}
    </div>
  );
}
