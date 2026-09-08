'use client';

import * as React from 'react';
import clsx from 'clsx';
import { RefreshCcw } from 'lucide-react';
import { Handle, NodeProps, Position } from 'reactflow';

import { getNodeVisual } from '../../data/nodeVisuals';
import type { BuilderNodeData, DerivedNodeStatus, NodeStatus, NodeVisuals } from '../../types';
import { useValidation } from '../../contexts/ValidationContext';
import { NodeActionButtons, NodeHeader, useHoverVisibility, getIconSlug, getStatusBorderColor } from './shared';
import { findNodeClassById } from '../../nodes/nodeClasses';
import { NodeStatusBadge } from '../NodeStatusBadge';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { NodePlayButton, deriveNodeStatus } from '../NodePlayButton';
import { useNodeExecutionStatus } from '../../contexts/StepByStepContext';
import { NodeBottomBar } from './NodeBottomBar';
import { showsNodeRunActions } from './shared';


import { useWorkflowLayoutDirectionSafe } from '@/contexts/WorkflowLayoutDirectionContext';
import { getSourceHandleGeometry, getTargetHandleGeometry } from './handleGeometry';
import { NodeActivityShimmer } from './NodeActivityShimmer';
export function SplitNode({ data, selected }: NodeProps<BuilderNodeData>) {
  // Handle sides follow the canvas reading direction. Safe variant: nodes also
  // render on provider-less surfaces (marketplace preview, snapshots).
  const { direction: layoutDirection } = useWorkflowLayoutDirectionSafe();
  const targetHandle = getTargetHandleGeometry(layoutDirection);
  const sourceHandle = getSourceHandleGeometry(layoutDirection);

  const visuals = getNodeVisual('split');
  const { targetRef: nodeRef, isVisible: showActions, show } = useHoverVisibility<HTMLDivElement>();

  // Use centralized validation context for error state
  const { hasNodeErrors: checkNodeErrors } = useValidation();
  const hasError = checkNodeErrors(data.id);
  const { isRunMode, viewingEpoch } = useWorkflowMode();

  // Get node class to determine family
  const nodeClass = React.useMemo(() => findNodeClassById(data.id || ''), [data.id]);
  const nodeFamily = nodeClass?.family;

  // Step-by-step execution status for the split node itself
  const stepByStepStatus = useNodeExecutionStatus(data.id, { label: data.label, kind: 'split', status: data.status });

  // Determine effective status: use step-by-step context as source of truth in step-by-step mode
  const effectiveStatus = React.useMemo((): DerivedNodeStatus | undefined => {
    if (viewingEpoch != null) return (data as any).status;
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
    return (data as any).status;
  }, [viewingEpoch, stepByStepStatus, (data as any).status]);

  // Get border color based on status
  // Always use status color for border
  const statusBorderColor = getStatusBorderColor(effectiveStatus, hasError, isRunMode || viewingEpoch != null, data.statusCounts);
  const borderColor = statusBorderColor;
  // Don't apply skipped styling in step-by-step mode
  const isSkipped = !stepByStepStatus.isStepByStepMode && effectiveStatus === 'skipped';

  return (
    <div
      ref={nodeRef}
      className={clsx(
        'relative flex flex-col rounded-[28px] bg-white/95 dark:bg-gray-800/95 px-5 py-4',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-[var(--accent-primary)] focus-visible:outline-offset-2',
        'border-2 transition-colors backdrop-blur',
        isSkipped && !selected && 'opacity-50 focus-visible:opacity-100',
        isSkipped && selected && 'opacity-100',
      )}
      style={{
        borderColor,
        borderStyle: 'solid',
        // Selection ring outside the node border
        boxShadow: selected ? '0 0 0 2px var(--accent-primary)' : 'none',
      }}
      tabIndex={0}
    >
      <NodeActivityShimmer status={effectiveStatus} className="rounded-[26px] z-[5]" />

      <NodeHeader
        visuals={visuals}
        label={data.label}
        iconSlug={getIconSlug(data)}
        nodeId={data.id}
        nodeKind="split"
      
        nodeFamily={nodeFamily}
      />

      {/* Display current iteration if available */}
      {data.currentIteration !== undefined && (
        <div className="flex items-center gap-1 bg-white dark:bg-gray-700 rounded-md px-2 py-1 self-start mt-3">
          <RefreshCcw className="h-3 w-3 text-black dark:text-white" />
          <span className="text-[10px] font-medium text-black dark:text-white">
            Item {data.currentIteration}
          </span>
        </div>
      )}

      <NodeActionButtons
        isVisible={showActions}
        onDelete={data.onDeleteNode ? () => data.onDeleteNode?.(data.id) : undefined}
        onDuplicate={data.onDuplicateNode ? () => data.onDuplicateNode?.(data.id) : undefined}
        onHover={show}
      />

      {/* Step-by-step play button for split node in run mode */}
      {isRunMode && showsNodeRunActions(stepByStepStatus) && (
        <NodeBottomBar
          hover={{ isVisible: showActions, onHover: show }}
          borderColor={borderColor}
          isRunning={effectiveStatus === 'running'}
          playButton={{
            nodeId: data.id,
            variant: 'play',
            isAutoMode: false,
            isTriggerNode: false,
            stepByStepStatus,
          }}
        />
      )}

      {/* Status badge positioned at bottom right */}
      <div className="absolute bottom-2 right-2">
        <NodeStatusBadge status={effectiveStatus} statusCounts={(data as any).statusCounts} />
      </div>

      <Handle
        type="target"
        position={targetHandle.position}
        id="target-left"
        isConnectable={true}
        className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
        style={{
          ...targetHandle.style,
          backgroundColor: 'var(--border-color)',
          opacity: isRunMode ? 0 : 1,
          pointerEvents: isRunMode ? 'none' : 'auto'
        }}
      />
      <Handle
        type="source"
        position={sourceHandle.position}
        id={`split-${data.id}-exit`}
        isConnectable={true}
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
