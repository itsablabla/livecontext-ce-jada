'use client';

import * as React from 'react';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import {
  applyAwaitingSignalToNodes,
  updateNodesFromBatchSteps,
  updateDecisionNodesFromPredecessors,
  type BatchStepData,
  type BatchEdgeData,
} from '../services/statusUpdater';
import {
  applyAwaitingSourceToEdges,
  updateEdgesFromBatch,
  updateLoopInternalEdges,
} from '../services/edgeStatusService';
import { nodesHaveChanged, edgesHaveChanged } from '../utils/graphCompare';
import { isTerminalStepStatus } from '../utils/statusCounts';
import { normalizeLabel, coreKey, mcpKey } from '../utils/labelNormalizer';
import { nodeRegistry } from '../registry/nodeRegistry';
import { streamDebug } from '@/contexts/workflow-run/streamingDebug';

interface RunState {
  batchSteps?: BatchStepData[];
  batchEdges?: BatchEdgeData[];
  loops?: Array<{ loopId: string; payload: any }>;
  decisionEvaluations?: Array<{
    coreId: string;
    selectedBranch: string;
    skippedBranches?: string[];
  }>;
  workflowStatus?: { status: string; durationMs?: number };
  [key: string]: any;
}

interface UseRunStateProcessingOptions {
  runState: RunState | null;
  workflowLoaded: boolean;
  /**
   * Flips false→true once the canvas nodes are committed. The paint effects
   * below bail on an empty {@code nodesRef} and key off refs + {@code
   * workflowLoaded} only - so a pinned/async-loaded graph that flips
   * {@code workflowLoaded} true BEFORE its nodes are committed leaves the
   * status badges/edge counts unpainted until a remount. Including this reactive
   * flag re-fires the paint the moment the nodes exist. (Flips once per load.)
   */
  nodesReady?: boolean;
  nodesRef: React.MutableRefObject<Node<BuilderNodeData>[]>;
  edgesRef: React.MutableRefObject<Edge[]>;
  setNodes: (nodes: Node<BuilderNodeData>[] | ((prev: Node<BuilderNodeData>[]) => Node<BuilderNodeData>[])) => void;
  setEdges: (edges: Edge[] | ((prev: Edge[]) => Edge[])) => void;
  setWorkflowStatus: (status: 'cancelled' | 'running' | 'paused' | 'completed' | 'failed') => void;
  workflowId?: string;
  effectiveRunId?: string;
  /** When true, suppress live SSE updates (user is viewing a historical epoch) */
  isViewingHistoricalEpoch?: boolean;
}

/**
 * Hook to process run state updates from streaming/context
 * Handles:
 * - batchSteps processing -> node status updates
 * - batchEdges processing -> edge status updates
 * - loops processing -> loop iteration updates
 * - decisionEvaluations processing -> decision node status updates
 * - workflowStatus processing -> workflow completion handling
 */
export function useRunStateProcessing({
  runState,
  workflowLoaded,
  nodesReady,
  nodesRef,
  edgesRef,
  setNodes,
  setEdges,
  setWorkflowStatus,
  workflowId,
  effectiveRunId,
  isViewingHistoricalEpoch,
}: UseRunStateProcessingOptions): void {
  // Process batchSteps from context and update ReactFlow nodes
  React.useEffect(() => {
    if (isViewingHistoricalEpoch) return;
    if (!runState?.batchSteps || runState.batchSteps.length === 0) {
      return;
    }
    if (!workflowLoaded || nodesRef.current.length === 0) {

      return;
    }

    console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'useRunStateProcessing batchSteps subscriber FIRES', { count: runState.batchSteps.length });
    const batchSteps = runState.batchSteps as BatchStepData[];

    // DEBUG: trace core steps received by useRunStateProcessing
    const coreInBatch = batchSteps.filter((s: any) => {
      const id = (s as any).stepId || s.normalizedStepId || s.id || '';
      return id.startsWith('core:');
    });
    streamDebug.log('RunStateProcessing', '🔍 batchSteps received:', {
      total: batchSteps.length,
      coreSteps: coreInBatch.map((s: any) => ({
        id: (s as any).stepId || s.normalizedStepId || s.id,
        status: s.status,
        statusCounts: s.statusCounts,
      })),
    });

    // Update nodes from batch steps using functional setter to always read
    // the latest positions (avoids stale ref overwriting drag positions).
    setNodes((currentNodes: Node<BuilderNodeData>[]) => {
      // The step stream cannot say "waiting": a yielded node's last step row is still
      // the RUNNING one. The run snapshot names those nodes separately, so the override
      // is applied on top of the step paint rather than inside it.
      const updatedNodes = applyAwaitingSignalToNodes(
        updateNodesFromBatchSteps(currentNodes, batchSteps),
        runState?.awaitingSignalSteps as Iterable<string> | undefined,
      );
      const hasChanges = nodesHaveChanged(currentNodes, updatedNodes);

      if (hasChanges) {
        nodesRef.current = updatedNodes as any;

        // Dispatch event for terminal steps to trigger data refetch. The predicate is shared
        // (isTerminalStepStatus) rather than inlined here: the inline list omitted
        // partial_success, which silently stopped the refresh for every node that had ever failed.
        const terminalSteps = batchSteps.filter((step: BatchStepData) =>
          isTerminalStepStatus(step.status));

        if (terminalSteps.length > 0) {
          window.dispatchEvent(new CustomEvent('stepExecutionCompleted', {
            detail: {
              workflowId,
              runId: effectiveRunId,
              steps: terminalSteps.map((s: BatchStepData) => ({
                stepAlias: s.stepAlias || s.normalizedStepId,
                status: s.status,
              })),
            }
          }));
        }

        return updatedNodes as any;
      }
      return currentNodes;
    });

    // A node parked on a signal has to reach its outgoing edges too, and only the
    // NODE carries that state (the edge protocol has no waiting lifecycle - see
    // applyAwaitingSourceToEdges). Driven off the step effect, not the edge effect:
    // entering and leaving a wait is a step event, and often arrives with no edge
    // update at all.
    setEdges((currentEdges: Edge[]) => {
      const updatedEdges = applyAwaitingSourceToEdges(
        currentEdges,
        nodesRef.current,
        runState?.awaitingSignalSteps as Iterable<string> | undefined,
      );
      if (updatedEdges === currentEdges) return currentEdges;
      edgesRef.current = updatedEdges;
      return updatedEdges;
    });
    // `awaitingSignalSteps` is a dep in its own right: entering or leaving a wait can
    // arrive on a snapshot that carries no step change at all, and without it the
    // canvas would keep the pre-wait paint until the next unrelated step event.
  }, [runState?.batchSteps, runState?.awaitingSignalSteps, workflowLoaded, nodesReady, setNodes, setEdges, nodesRef, edgesRef, workflowId, effectiveRunId, isViewingHistoricalEpoch]);

  // Process batchEdges from context and update ReactFlow edges
  React.useEffect(() => {
    if (isViewingHistoricalEpoch) return;
    if (!runState?.batchEdges || runState.batchEdges.length === 0) {
      return;
    }
    if (!workflowLoaded || edgesRef.current.length === 0) {
      streamDebug.warn('RunStateProcessing', 'batchEdges skipped:', {
        workflowLoaded,
        edgesCount: edgesRef.current.length,
        batchEdgesCount: runState.batchEdges.length,
      });
      return;
    }

    console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'useRunStateProcessing batchEdges subscriber FIRES', { count: runState.batchEdges.length });
    streamDebug.log('RunStateProcessing', 'Processing batchEdges:', runState.batchEdges.length);

    const batchEdges = runState.batchEdges as BatchEdgeData[];

    // Update edges from batch - use functional setters to read latest state.
    // Loop internal edges (e.g. body→iterate→exit) need a second pass that
    // reads the loop-node ports to colour the iteration spokes; the loader
    // used to do this inline (now removed) so we replicate it here.
    setEdges((currentEdges: Edge[]) => {
      let updatedEdges = updateEdgesFromBatch(currentEdges, batchEdges, nodesRef.current);
      updatedEdges = updateLoopInternalEdges(updatedEdges, batchEdges, nodesRef.current);
      // LAST, and deliberately so: the batch carries an all-zero entry for the edge
      // leaving a parked node, which `updateEdgesFromBatch` writes as `pending`. Applied
      // before those two, the waiting colour was overwritten on the very next edge
      // snapshot and never survived a single frame.
      updatedEdges = applyAwaitingSourceToEdges(
        updatedEdges,
        nodesRef.current,
        runState?.awaitingSignalSteps as Iterable<string> | undefined,
      );
      const hasEdgeChanges = edgesHaveChanged(currentEdges, updatedEdges);

      if (hasEdgeChanges) {
        streamDebug.log('RunStateProcessing', 'Edges updated from batchEdges (incl. loop internals)');
        edgesRef.current = updatedEdges;
        return updatedEdges;
      }
      return currentEdges;
    });

    // Update decision nodes from predecessors
    setNodes((currentNodes: Node<BuilderNodeData>[]) => {
      const updatedNodes = updateDecisionNodesFromPredecessors(currentNodes, edgesRef.current);
      const hasNodeChanges = nodesHaveChanged(currentNodes, updatedNodes);

      if (hasNodeChanges) {
        streamDebug.log('RunStateProcessing', 'Nodes updated (decision)');
        nodesRef.current = updatedNodes as any;
        return updatedNodes as any;
      }
      return currentNodes;
    });
  }, [runState?.batchEdges, runState?.awaitingSignalSteps, workflowLoaded, nodesReady, setNodes, setEdges, nodesRef, edgesRef, isViewingHistoricalEpoch]);

  // Process decisionEvaluations from context and update decision nodes
  React.useEffect(() => {
    if (isViewingHistoricalEpoch) return;
    if (!runState?.decisionEvaluations || runState.decisionEvaluations.length === 0) return;
    if (!workflowLoaded || nodesRef.current.length === 0) return;

    streamDebug.log('RunStateProcessing', 'Processing decisionEvaluations:', runState.decisionEvaluations.length);

    // Process the latest decision evaluation
    const latestEvaluation = runState.decisionEvaluations[runState.decisionEvaluations.length - 1];
    const { coreId, selectedBranch, skippedBranches } = latestEvaluation;

    // Update decision node status + mark skipped branches in one functional setter
    setNodes((currentNodes: Node<BuilderNodeData>[]) => {
      let result = currentNodes.map((node) => {
        const nodeLabelNormalized = normalizeLabel(node.data?.label || '') || '';
        const isDecisionNode =
          nodeRegistry.isDecisionLikeNode(node) &&
          (node.id === coreId ||
            node.id.includes(coreId) ||
            coreId === coreKey(node.data?.label || '') ||
            coreId.includes(nodeLabelNormalized));

        if (isDecisionNode) {
          return {
            ...node,
            data: {
              ...node.data,
              status: 'completed' as const,
              selectedBranch,
            },
          };
        }
        return node;
      });

      // Mark skipped branches
      if (skippedBranches && skippedBranches.length > 0) {
        result = result.map((node) => {
          const nodeLabelNormalized = normalizeLabel(node.data?.label || '') || '';
          const isSkippedNode = skippedBranches.some((branch: string) =>
            node.id === branch ||
            branch === mcpKey(node.data?.label || '') ||
            branch.includes(nodeLabelNormalized)
          );

          if (isSkippedNode && node.data?.status !== 'skipped') {
            return {
              ...node,
              data: {
                ...node.data,
                status: 'skipped' as const,
              },
            };
          }
          return node;
        });
      }

      nodesRef.current = result as any;
      return result as any;
    });

    // Update skipped edges
    if (skippedBranches && skippedBranches.length > 0) {
      setEdges((currentEdges: Edge[]) => {
        const updatedEdges = currentEdges.map((edge) => {
          const targetLabelNormalized = normalizeLabel(edge.data?.targetLabel || '') || '';
          const isSkippedEdge = skippedBranches.some((branch: string) =>
            edge.target === branch ||
            edge.target.includes(branch) ||
            (targetLabelNormalized && branch.includes(targetLabelNormalized))
          );

          if (isSkippedEdge) {
            return {
              ...edge,
              data: {
                ...edge.data,
                status: 'skipped' as const,
              },
            };
          }
          return edge;
        });
        edgesRef.current = updatedEdges;
        return updatedEdges;
      });
    }
  }, [runState?.decisionEvaluations, workflowLoaded, nodesReady, setNodes, setEdges, nodesRef, edgesRef, isViewingHistoricalEpoch]);

  // Handle workflow status from context (NOT suppressed - workflow completion should always be processed)
  React.useEffect(() => {
    if (!runState?.workflowStatus) return;

    const status = runState.workflowStatus.status;

    if (status === 'running') {
      setWorkflowStatus('running');
    } else if (status === 'paused') {
      setWorkflowStatus('paused');
    } else if (status === 'completed') {
      setWorkflowStatus('completed');
    } else if (status === 'failed') {
      setWorkflowStatus('failed');
    } else if (status === 'cancelled' || status === 'stopped') {
      setWorkflowStatus('cancelled');
    }

    // Dispatch completion event for finished workflows
    if (['completed', 'failed', 'cancelled', 'stopped'].includes(status)) {
      const durationMs = runState.workflowStatus.durationMs || 0;
      window.dispatchEvent(new CustomEvent('workflowExecutionCompleted', {
        detail: {
          workflowId,
          runId: effectiveRunId,
          status: status === 'failed' ? 'failed' : status,
          durationMs,
        }
      }));
      streamDebug.log('RunStateProcessing', `Dispatched workflowExecutionCompleted: ${status} (${durationMs}ms)`);
    }
  }, [runState?.workflowStatus, workflowId, effectiveRunId, setWorkflowStatus]);
}
