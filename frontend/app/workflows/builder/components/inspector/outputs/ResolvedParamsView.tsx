/**
 * ResolvedParamsView - the Params column in run mode.
 *
 * Shows what a node actually ran with: its `resolved_params`, rendered through
 * the same tree as the Output column (JsonDataTree) but with top-level keys
 * relabelled from the input-label registry, so "duration" reads "Duration (ms)".
 *
 * Two things it does beyond displaying the payload:
 *
 *  - While the node is still executing or parked on a signal there is no step
 *    row to fetch, so it falls back to the parameters the node was LAUNCHED
 *    with (the configured expressions) instead of an empty panel.
 *  - After the run it lines the configured parameters up against the reported
 *    keys and calls out any the run did not report - the front/back naming
 *    drift that used to be invisible (see runParamAlignment).
 */

'use client';

import * as React from 'react';
import { AlertTriangle, Database } from 'lucide-react';
import { useTranslations } from 'next-intl';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useRunData } from '../../../hooks/useRunData';
import { useNodeLiveState } from '../../../hooks/useNodeLiveState';
import { ItemNavigator, ALL_STATUSES_VALUE } from './ItemNavigator';
import { JsonValueTree, PrimitiveValue } from './JsonDataTree';
import { NodeRunStateNotice } from './NodeRunStateNotice';
import { RunDataViewTabs, RawJsonView, JsonTableView, type RunDataViewMode } from './RunDataViews';
import { hasTableView, pickTabularValue } from './runValueUtils';
import {
  buildParamAlignment,
  mergeResolvedAliases,
  type ParamAlignmentEntry,
} from './runParamAlignment';
import { collectDeclaredParams } from './declaredParams';
import type { StatusType } from '@/components/ui/StatusBadge';
import { detectNodeType } from '../core/types';
import { getInputLabel, humanizeKey } from '../registry/input-label-registry';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';

interface ResolvedParamsViewProps {
  workflowId: string | undefined;
  runId: string | undefined;
  stepAlias: string | undefined;
  node: Node<BuilderNodeData>;
  toolParameters?: any[];
}

export function ResolvedParamsView({
  workflowId,
  runId,
  stepAlias,
  node,
  toolParameters,
}: ResolvedParamsViewProps) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  const ti = useTranslations('workflowBuilder.inspector');
  const { isRunMode } = useWorkflowMode();
  const [statusFilter, setStatusFilter] = React.useState<string>(ALL_STATUSES_VALUE);
  const activeStatusFilter: StatusType | null =
    statusFilter === ALL_STATUSES_VALUE ? null : (statusFilter as StatusType);
  const {
    totalItems,
    isLoading,
    error,
    currentIndex,
    currentItem,
    goToIndex,
    getObjectAtPath,
    availableStatuses,
  } = useRunData({
    workflowId,
    runId,
    stepAlias,
    dataType: 'input',
    enabled: !!workflowId && !!runId && !!stepAlias,
    statusFilter: activeStatusFilter,
  });

  const [data, setData] = React.useState<Record<string, any> | null>(null);
  const [isLoadingData, setIsLoadingData] = React.useState(false);
  const [viewMode, setViewMode] = React.useState<RunDataViewMode>('tree');
  const [expandedPaths, setExpandedPaths] = React.useState<Set<string>>(() => new Set());

  const toggleExpand = React.useCallback((pathKey: string) => {
    setExpandedPaths((prev) => {
      const next = new Set(prev);
      if (next.has(pathKey)) next.delete(pathKey);
      else next.add(pathKey);
      return next;
    });
  }, []);

  // Same contract as RunDataPreview: this instance is REUSED across node
  // switches, so the open paths and the loaded object have to be reset when the
  // panel starts pointing at something else.
  React.useEffect(() => {
    setExpandedPaths(new Set());
    setData(null);
    setViewMode('tree');
  }, [workflowId, stepAlias, runId]);

  // Load data when the displayed row changes. Keyed on currentItem.id and
  // guarded against out-of-order responses - same contract as RunDataPreview
  // (a page merge or targeted jump can swap WHICH row sits at the same index,
  // and a slow stale fetch must not overwrite the newer row's data).
  const loadSeqRef = React.useRef(0);
  React.useEffect(() => {
    if (totalItems === 0) return;

    const seq = ++loadSeqRef.current;
    const loadData = async () => {
      setIsLoadingData(true);
      try {
        const result = await getObjectAtPath('');
        if (seq !== loadSeqRef.current) return; // stale response
        // Every backend node persists its resolved configuration under `resolved_params`
        // (single source of truth - see StepDataPersistenceService.extractInputData).
        // If the fetched object already IS the unwrapped map (legacy/empty case), use it as-is.
        const resolvedParams = result && typeof result === 'object'
          ? (result.resolved_params ?? result)
          : result;
        setData(resolvedParams);
      } catch {
        if (seq !== loadSeqRef.current) return;
        setData(null);
      } finally {
        if (seq === loadSeqRef.current) setIsLoadingData(false);
      }
    };

    loadData();
  }, [currentIndex, currentItem?.id, totalItems, getObjectAtPath]);

  const nodeType = detectNodeType(node);

  // Build tool parameter label map for MCP nodes
  const toolParamLabels = React.useMemo(() => {
    if (nodeType !== 'tool' || !toolParameters) return null;
    const map: Record<string, string> = {};
    for (const param of toolParameters) {
      if (param.name) {
        map[param.name] = param.title || param.label || humanizeKey(param.name);
      }
    }
    return map;
  }, [nodeType, toolParameters]);

  // Resolve label for a given key
  const getLabel = React.useCallback(
    (key: string): string => {
      if (toolParamLabels && toolParamLabels[key]) {
        return toolParamLabels[key];
      }
      return getInputLabel(nodeType, key);
    },
    [nodeType, toolParamLabels],
  );

  const { liveState, pendingSignals } = useNodeLiveState(node, { isRunMode });

  // What the node declares in edit mode, read through the REAL plan generator
  // so it is exactly what the backend was handed. An MCP tool node is excluded
  // on purpose: its parameters come from the selected tool's schema, and the
  // catalog decides their names, so this comparison has nothing to say there.
  // Keyed on what the plan generator actually reads - node.id, node.data and
  // node.type - rather than the node object: ReactFlow re-creates that object
  // on every status tick and every drag frame, and this runs the REAL plan
  // generator. `nodeType` is not a substitute for `node.type`: detectNodeType
  // collapses several node.type values onto one inspector type, so a change
  // between two of them would leave a stale plan entry.
  // Suppressed rather than satisfied: both rules want `node` itself in the deps,
  // which is the bug - ReactFlow hands back a new object every tick. Passing a
  // reconstructed `{id, type, data}` would silence them honestly but would also
  // feed the plan generator a node this file invented, so the narrower lie is
  // the suppression.
  /* eslint-disable react-hooks/exhaustive-deps, react-hooks/memo-dependencies */
  const configuredParams = React.useMemo(
    () => (nodeType === 'tool' ? {} : collectDeclaredParams(node)),
    [nodeType, node.id, node.type, node.data],
  );
  /* eslint-enable react-hooks/exhaustive-deps, react-hooks/memo-dependencies */

  const merged = React.useMemo(
    () => (data && typeof data === 'object' && !Array.isArray(data) ? mergeResolvedAliases(data) : null),
    [data],
  );

  const alignment = React.useMemo(
    () => (merged ? buildParamAlignment(configuredParams, merged, nodeType) : null),
    [configuredParams, merged, nodeType],
  );

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-6">
        <LoadingSpinner size="xs" />
        <span className="ml-2 text-sm text-slate-500">{ti('loading')}</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className="py-4 text-center">
        <p className="text-sm text-red-500">{error}</p>
      </div>
    );
  }

  if (totalItems === 0) {
    const configuredEntries = Object.entries(configuredParams);
    return (
      <div className="space-y-2" data-testid="resolved-params-view">
        <ItemNavigator
          currentIndex={0}
          totalItems={0}
          onIndexChange={goToIndex}
          itemLabel={ti('item')}
          statusOptions={availableStatuses}
          statusFilter={statusFilter}
          onStatusFilterChange={setStatusFilter}
        />
        {liveState ? (
          <>
            <NodeRunStateNotice state={liveState} column="params" pendingSignals={pendingSignals} />
            {configuredEntries.length > 0 && (
              <ConfiguredParamsList entries={configuredEntries} getLabel={getLabel} />
            )}
          </>
        ) : (
          <div className="py-4 text-center">
            <Database className="h-6 w-6 mx-auto mb-2 text-slate-300 dark:text-slate-600" />
            <p className="text-sm text-slate-500">{t('noResolvedParams')}</p>
          </div>
        )}
      </div>
    );
  }

  const tableAvailable = hasTableView(merged);
  const effectiveViewMode: RunDataViewMode =
    viewMode === 'table' && !tableAvailable ? 'tree' : viewMode;

  return (
    <div className="space-y-2" data-testid="resolved-params-view">
      <ItemNavigator
        currentIndex={currentIndex}
        totalItems={totalItems}
        onIndexChange={goToIndex}
        itemLabel={ti('item')}
        statusOptions={availableStatuses}
        statusFilter={statusFilter}
        onStatusFilterChange={setStatusFilter}
      />

      {isLoadingData ? (
        <div className="space-y-2">
          {[1, 2, 3].map((i) => (
            <div
              key={i}
              className="h-5 w-full rounded bg-slate-200 dark:bg-slate-700 animate-pulse"
            />
          ))}
        </div>
      ) : merged ? (
        <>
          <RunDataViewTabs
            mode={effectiveViewMode}
            onModeChange={setViewMode}
            tableAvailable={tableAvailable}
            copyValue={merged}
            columnId="params"
          />
          {Object.keys(merged).length === 0 ? (
            <p className="py-4 text-center text-sm text-slate-500">{t('noResolvedParams')}</p>
          ) : effectiveViewMode === 'json' ? (
            <RawJsonView data={merged} />
          ) : effectiveViewMode === 'table' ? (
            <JsonTableView data={pickTabularValue(merged)} />
          ) : (
            <JsonValueTree
              data={merged}
              path={[]}
              showBorder={false}
              expandedPaths={expandedPaths}
              onToggleExpand={toggleExpand}
              labelForKey={getLabel}
            />
          )}
          {alignment && alignment.mismatches.length > 0 && (
            <ParamMismatchSection mismatches={alignment.mismatches} getLabel={getLabel} />
          )}
        </>
      ) : data !== null ? (
        <PrimitiveValue value={data} />
      ) : (
        <p className="py-4 text-center text-sm text-slate-500">{t('noResolvedParams')}</p>
      )}
    </div>
  );
}

/**
 * The parameters a node was launched with, shown while it is still running:
 * raw configured expressions, unresolved by definition.
 */
function ConfiguredParamsList({
  entries,
  getLabel,
}: {
  entries: Array<[string, unknown]>;
  getLabel: (key: string) => string;
}) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  return (
    <div className="space-y-1">
      <p className="px-1 text-sm font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
        {t('configuredParamsTitle')}
      </p>
      {entries.map(([key, expression]) => (
        <div
          key={key}
          className="flex items-start gap-2 rounded-sm px-1 py-1 text-sm text-[var(--text-primary)]"
        >
          <span className="truncate max-w-[120px] flex-shrink-0" title={getLabel(key)}>
            {getLabel(key)}
          </span>
          <span className="flex-shrink-0 text-slate-400">:</span>
          <span className="min-w-0 break-all font-mono text-sm text-slate-600 dark:text-slate-300">
            {typeof expression === 'string' ? expression : JSON.stringify(expression)}
          </span>
        </div>
      ))}
    </div>
  );
}

/**
 * Configured parameters the run did not report under their own key.
 *
 * `renamed` is the actionable one: the value IS in the payload, under a
 * differently-formatted key, which means the form and the node disagree on the
 * parameter's name. `not_reported` means the node never echoed it at all.
 */
function ParamMismatchSection({
  mismatches,
  getLabel,
}: {
  mismatches: ParamAlignmentEntry[];
  getLabel: (key: string) => string;
}) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  const [isOpen, setIsOpen] = React.useState(false);

  return (
    <div
      data-testid="param-alignment-mismatches"
      className="rounded-md border border-amber-200 bg-amber-50 dark:border-amber-800 dark:bg-amber-950/30"
    >
      <button
        type="button"
        onClick={() => setIsOpen((v) => !v)}
        aria-expanded={isOpen}
        className="flex w-full items-center gap-2 px-2.5 py-2 text-left text-sm font-medium text-amber-700 dark:text-amber-300"
      >
        <AlertTriangle className="h-3.5 w-3.5 flex-shrink-0" />
        <span>{t('mismatchTitle', { count: mismatches.length })}</span>
      </button>
      {isOpen && (
        <ul className="space-y-1.5 px-2.5 pb-2">
          {mismatches.map((entry) => (
            <li key={entry.key} className="text-sm text-amber-800 dark:text-amber-200">
              <span className="font-medium">{getLabel(entry.key)}</span>
              <span className="opacity-75"> ({entry.key})</span>
              <p className="opacity-90">
                {entry.status === 'renamed'
                  ? t('mismatchRenamed', { runtimeKey: entry.runtimeKey ?? '' })
                  : t('mismatchNotReported')}
              </p>
              <p className="break-all font-mono text-sm opacity-75">{entry.configuredExpression}</p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
