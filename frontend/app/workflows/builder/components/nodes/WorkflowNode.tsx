'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Handle, NodeProps, Position } from 'reactflow';

import { getNodeVisual } from '../../data/nodeVisuals';
import type { BuilderNodeData, DerivedNodeStatus, NodeStatus } from '../../types';
import { useValidation } from '../../contexts/ValidationContext';
import { NodeHeader, useHoverVisibility, getIconSlug, getStatusBorderColor } from './shared';
import { findNodeClassById } from '../../nodes/nodeClasses';
import { NodeStatusBadge } from '../NodeStatusBadge';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { Workflow } from 'lucide-react';
import { NodePlayButton, deriveNodeStatus } from '../NodePlayButton';
import { NodeBottomBar } from './NodeBottomBar';
import { useNodeExecutionStatus } from '../../contexts/StepByStepContext';


import { useWorkflowLayoutDirectionSafe } from '@/contexts/WorkflowLayoutDirectionContext';
import { getSourceHandleGeometry, getTargetHandleGeometry } from './handleGeometry';
import { openWorkflowBuilderTab, requestOpenRelatedWorkflow } from '@/lib/sidePanel/openWorkflowBuilderTab';
import { NodeActivityShimmer } from './NodeActivityShimmer';
/**
 * WorkflowNode - A specialized node for workflow triggers
 *
 * This node is used for workflow triggers that reference another workflow.
 */
export function WorkflowNode({ data, selected, id }: NodeProps<BuilderNodeData>) {
  // Handle sides follow the canvas reading direction. Safe variant: nodes also
  // render on provider-less surfaces (marketplace preview, snapshots).
  const { direction: layoutDirection } = useWorkflowLayoutDirectionSafe();
  const targetHandle = getTargetHandleGeometry(layoutDirection);
  const sourceHandle = getSourceHandleGeometry(layoutDirection);

  const visuals = getNodeVisual('entry');
  const { targetRef: nodeRef, isVisible: showActions, show } = useHoverVisibility<HTMLDivElement>();
  const { isRunMode, viewingEpoch, workflowId: hostWorkflowId } = useWorkflowMode();

  // Get node class to determine family
  const nodeClass = React.useMemo(() => findNodeClassById(data.id || ''), [data.id]);
  const nodeFamily = nodeClass?.family;

  // Step-by-step execution status
  const stepByStepStatus = useNodeExecutionStatus(id, { label: data.label, kind: data.kind, status: data.status });

  // Use centralized validation context for error state
  const { hasNodeErrors: checkNodeErrors } = useValidation();
  const hasError = checkNodeErrors(id);
  const sidePanel = useSidePanelSafe();
  const referencedWorkflowId: string | undefined = (data as any)?.workflowData?.workflowId || undefined;
  const referencedWorkflowName: string = (data as any)?.workflowData?.workflowName || data.label || 'Workflow';

  // Determine effective status
  const effectiveStatus = React.useMemo((): DerivedNodeStatus | undefined => {
    if (viewingEpoch != null) return data.status;
    if (stepByStepStatus.isStepByStepMode) {
      // A node parked on a signal is NOT running, but it stays in `runningSteps`:
      // yielding never rewrites the RUNNING step row, so the two sets overlap and
      // whichever is tested first wins. Awaiting is the newer, more specific fact,
      // so it goes first - otherwise the waiting state is unreachable and the node
      // reads blue "running" while its own badge shows an amber pause chip.
      if (stepByStepStatus.isAwaitingSignal) return 'awaiting_signal';
      if (stepByStepStatus.isRunning) return 'running';
      if (stepByStepStatus.isFailed) return 'failed';
      if (stepByStepStatus.isSkipped) return 'skipped';
      // The backend's PARTIAL_SUCCESS must survive: the node IS in completedSteps (that is what
      // opens its rerun gate), so testing isCompleted first would discard it and paint a node
      // carrying a failure in its own tally the same green as a clean one. Border only - the
      // rerun button reads deriveNodeStatus, which deliberately still sees 'completed'.
      if (data.status === 'partial_success') return 'partial_success';
      if (stepByStepStatus.isCompleted) return 'completed';
      if (stepByStepStatus.isReady) return 'ready';
      return 'pending';
    }
    return data.status;
  }, [viewingEpoch, stepByStepStatus, data.status]);

  // Get border color based on status
  // Always use status color for border
  const statusBorderColor = getStatusBorderColor(effectiveStatus, hasError, isRunMode || viewingEpoch != null, data.statusCounts);
  const borderColor = statusBorderColor;

  // Don't apply skipped styling in step-by-step mode
  const isSkipped = !stepByStepStatus.isStepByStepMode && effectiveStatus === 'skipped';

  // Check if node is running (for animation) - show shimmer in all modes
  const isNodeRunning = effectiveStatus === 'running';

  // Trigger detection for play button
  const nodeId = data.id || '';
  const isManualTrigger = nodeId === 'manual-trigger' || nodeId.startsWith('manual-trigger-');
  const isChatTrigger = nodeId === 'chat-trigger' || nodeId.startsWith('chat-trigger-');
  const isWebhookTrigger = nodeId === 'webhook-trigger' || nodeId.startsWith('webhook-trigger-');
  const isScheduleTrigger = nodeId === 'schedule-trigger' || nodeId.startsWith('schedule-trigger-');
  const isWorkflowsTrigger = nodeId === 'workflows-trigger' || nodeId.startsWith('workflows-trigger-') || (data.kind === 'entry' && !!(data as any)?.workflowData?.workflowId);
  const isTriggerNode = data.kind === 'entry';

  return (
    <div
      ref={nodeRef}
      className={clsx(
        'group relative rounded-2xl bg-white dark:bg-gray-800',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-[var(--accent-primary)] focus-visible:outline-offset-2',
        'border-2 transition-colors',
        isSkipped && !selected && 'opacity-50 focus-visible:opacity-100',
        isSkipped && selected && 'opacity-100',
      )}
      style={{
        borderColor,
        width: 180,
        borderStyle: 'solid',
      }}
      tabIndex={0}
    >
      <NodeActivityShimmer status={effectiveStatus} className="rounded-2xl z-[5]" />
      {/* Node content */}
      <div className="p-3 space-y-2">
        <NodeHeader
          visuals={visuals}
          label={data.label}
          iconSlug={getIconSlug(data)}
          nodeId={nodeId}
          nodeKind={data.kind}
          nodeFamily={nodeFamily}
        />

        {data.description && (
          <p className="text-sm text-slate-400 dark:text-slate-500 line-clamp-2">
            {data.description}
          </p>
        )}
      </div>

      {/* Status badge positioned at bottom right */}
      <div className="absolute bottom-2 right-2 z-10">
        <NodeStatusBadge status={effectiveStatus} statusCounts={data.statusCounts} />
      </div>

      {/* Centralized bottom bar: sub-workflow button + play/rerun + hover delete/duplicate */}
      {(() => {
        const btns: { key: string; icon: React.ReactNode; title: string; onClick: (e: React.MouseEvent) => void }[] = [];
        if (isWorkflowsTrigger && referencedWorkflowId) {
          btns.push({
            key: 'subworkflow',
            icon: <Workflow className="h-3 w-3" strokeWidth={2} />,
            title: referencedWorkflowName,
            onClick: () => {
              // Same two routes as every other "open that workflow" affordance - see
              // openWorkflowBuilderTab, which is where both now live.
              if (isRunMode) {
                requestOpenRelatedWorkflow(referencedWorkflowId, referencedWorkflowName, id, hostWorkflowId ?? undefined);
              } else {
                openWorkflowBuilderTab(sidePanel, { workflowId: referencedWorkflowId, workflowName: referencedWorkflowName });
              }
            },
          });
        }
        const showPlay = isRunMode && (stepByStepStatus.isStepByStepMode || (isWorkflowsTrigger && stepByStepStatus.isReady));
        // Hover delete/duplicate - same row + style as the persistent buttons
        // (NodeBottomBar hides them in run / preview-only mode).
        const hasHoverActions = !isRunMode && !!(data.onDeleteNode || data.onDuplicateNode);
        if (btns.length === 0 && !showPlay && !hasHoverActions) return null;
        return (
          <NodeBottomBar
            hover={{ isVisible: showActions, onHover: show }}
            borderColor={borderColor}
            isRunning={isNodeRunning}
            buttons={btns.length > 0 ? btns : undefined}
            hoverActions={hasHoverActions ? {
              onDelete: data.onDeleteNode ? () => data.onDeleteNode?.(data.id) : undefined,
              onDuplicate: data.onDuplicateNode ? () => data.onDuplicateNode?.(data.id) : undefined,
            } : undefined}
            playButton={showPlay ? {
              nodeId: id,
              variant: (isManualTrigger ? 'lightning' : isChatTrigger ? 'message' : isWebhookTrigger ? 'webhook' : isScheduleTrigger ? 'schedule' : isWorkflowsTrigger ? 'workflow' : 'play') as any,
              isAutoMode: !stepByStepStatus.isStepByStepMode,
              isTriggerNode,
              stepByStepStatus,
            } : undefined}
          />
        );
      })()}

      {/* Target handle on left (receives connections) */}
      <Handle
        type="target"
        position={targetHandle.position}
        className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
        style={{
          ...targetHandle.style,
          backgroundColor: 'var(--border-color)',
          opacity: isRunMode ? 0 : 1,
          pointerEvents: isRunMode ? 'none' : 'auto'
        }}
      />

      {/* Source handle on right (sends connections) */}
      <Handle
        type="source"
        position={sourceHandle.position}
        id="source-right"
        className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
        style={{
          ...sourceHandle.style,
          backgroundColor: 'var(--border-color)',
          opacity: isRunMode ? 0 : 1,
          pointerEvents: isRunMode ? 'none' : 'auto'
        }}
      />

    </div>
  );
}
