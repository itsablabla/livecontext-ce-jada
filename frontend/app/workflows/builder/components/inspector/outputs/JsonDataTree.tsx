/**
 * JsonDataTree - the single key/value tree used by every run-mode column.
 *
 * Both run columns (resolved Params and Output) render the same payload shapes,
 * so they render them with the same component: one place decides how a string,
 * a file reference, an unresolved template or an embedded JSON document looks.
 * The Params column only differs in that its TOP-LEVEL keys are shown through a
 * human label (`labelForKey`); everything below is raw keys in both columns.
 *
 * What the tree knows how to surface, beyond plain values:
 *  - a FileRef, with view/download buttons and its properties;
 *  - a string that is really a JSON document, expandable as a nested tree;
 *  - a value the engine failed to resolve, called out instead of looking like
 *    ordinary text;
 *  - a long string, clamped with an explicit expand rather than flooding the column;
 *  - a copy button on every row, revealed on hover.
 */

'use client';

import * as React from 'react';
import clsx from 'clsx';
import { ChevronRight, GripVertical, Download, Eye } from 'lucide-react';
import { useTranslations } from 'next-intl';
import LoadingSpinner from '@/components/LoadingSpinner';
import {
  isFileRef,
  normalizeFileRef,
  fileService,
  fileRefToUrl,
  getFilePath,
  type FileRef,
} from '@/lib/api/orchestrator/file.service';
import { openAuthedFileInNewTab } from '@/lib/utils/url-auth';
import { CopyButton } from '../shared/CopyButton';
import {
  detectUnresolvedValue,
  parseEmbeddedJson,
  LONG_STRING_CHARS,
  type UnresolvedKind,
} from './runValueUtils';

// ============================================
// Tree
// ============================================

export interface JsonValueTreeProps {
  data: any;
  /** Enable drag and drop to create expressions. */
  isDraggable?: boolean;
  /** Prefix for drag expressions, e.g. "mcp:step1.output". */
  dragPrefix?: string;
  path: string[];
  showBorder?: boolean;
  /**
   * Expanded node paths (joined by '.'), owned by the caller so the tree shape
   * survives item navigation and node switches.
   */
  expandedPaths: Set<string>;
  onToggleExpand: (pathKey: string) => void;
  /**
   * Display label for a TOP-LEVEL key (path length 1). Used by the Params
   * column to show "Duration (ms)" where the payload says "duration"; nested
   * keys stay raw so a drag path always matches what is on screen.
   */
  labelForKey?: (key: string) => string;
}

export function JsonValueTree({
  data,
  isDraggable = false,
  dragPrefix,
  path,
  showBorder = false,
  expandedPaths,
  onToggleExpand,
  labelForKey,
}: JsonValueTreeProps) {
  if (data === null) {
    return <span className="text-sm font-mono text-slate-400">null</span>;
  }

  if (data === undefined) {
    return <span className="text-sm font-mono text-slate-400">undefined</span>;
  }

  if (typeof data !== 'object') {
    return <PrimitiveValue value={data} />;
  }

  // Top-level FileRef object (e.g., download_file output in DB format)
  if (!Array.isArray(data) && isFileRef(data)) {
    return (
      <TopLevelFileRefView
        data={data}
        isDraggable={isDraggable}
        dragPrefix={dragPrefix}
        path={path}
        expandedPaths={expandedPaths}
        onToggleExpand={onToggleExpand}
      />
    );
  }

  // Step output containing a file (flat format with envelope fields):
  // a file preview card first, then every field below.
  if (
    !Array.isArray(data) &&
    typeof data === 'object' &&
    '_status' in data &&
    typeof data.file_url === 'string' &&
    typeof data.file_name === 'string'
  ) {
    // Raw data without _type so normalizeFileRef detects the flat format.
    const normalized = normalizeFileRef(data as any);
    const entries = Object.entries(data);
    return (
      <div className="space-y-2">
        <FilePreviewCard fileRef={normalized} />
        <div className="space-y-1">
          {entries.map(([key, value]) => (
            <JsonNode
              key={key}
              nodeKey={key}
              value={value}
              isDraggable={isDraggable}
              dragPrefix={dragPrefix}
              path={[...path, key]}
              expandedPaths={expandedPaths}
              onToggleExpand={onToggleExpand}
              labelForKey={labelForKey}
            />
          ))}
        </div>
      </div>
    );
  }

  if (Array.isArray(data)) {
    if (data.length === 0) {
      return <span className="text-sm font-mono text-slate-400">[]</span>;
    }
    return (
      <div className={clsx(showBorder && 'pl-3 border-l border-slate-200 dark:border-slate-700')}>
        <div className="space-y-1">
          {data.map((item, index) => (
            <JsonNode
              key={index}
              nodeKey={String(index)}
              value={item}
              isDraggable={isDraggable}
              dragPrefix={dragPrefix}
              path={[...path, String(index)]}
              expandedPaths={expandedPaths}
              onToggleExpand={onToggleExpand}
              labelForKey={labelForKey}
            />
          ))}
        </div>
      </div>
    );
  }

  const entries = Object.entries(data);
  if (entries.length === 0) {
    return <span className="text-sm font-mono text-slate-400">{'{}'}</span>;
  }

  return (
    <div className={clsx(showBorder && 'pl-3 border-l border-slate-200 dark:border-slate-700')}>
      <div className="space-y-1">
        {entries.map(([key, value]) => (
          <JsonNode
            key={key}
            nodeKey={key}
            value={value}
            isDraggable={isDraggable}
            dragPrefix={dragPrefix}
            path={[...path, key]}
            expandedPaths={expandedPaths}
            onToggleExpand={onToggleExpand}
            labelForKey={labelForKey}
          />
        ))}
      </div>
    </div>
  );
}

interface JsonNodeProps {
  nodeKey: string;
  value: any;
  isDraggable?: boolean;
  dragPrefix?: string;
  path: string[];
  expandedPaths: Set<string>;
  onToggleExpand: (pathKey: string) => void;
  labelForKey?: (key: string) => string;
}

export function JsonNode({
  nodeKey,
  value,
  isDraggable = false,
  dragPrefix,
  path,
  expandedPaths,
  onToggleExpand,
  labelForKey,
}: JsonNodeProps) {
  // Path key is shared with the lifted state - joined with '.' so nested arrays
  // and objects produce stable, unique keys.
  const pathKey = path.join('.');
  const isExpanded = expandedPaths.has(pathKey);
  const handleToggle = React.useCallback(() => onToggleExpand(pathKey), [onToggleExpand, pathKey]);

  // Only the top level is relabelled: a nested key is also a drag path segment.
  const displayKey = labelForKey && path.length === 1 ? labelForKey(nodeKey) : nodeKey;

  const isFileRefValue = isFileRef(value);
  const isExpandable = value !== null && typeof value === 'object';
  const isArray = Array.isArray(value);
  const itemCount = isExpandable
    ? isArray
      ? value.length
      : Object.keys(value).filter((k) => k !== '_type').length
    : 0;

  const fullPath = dragPrefix ? `${dragPrefix}.${path.join('.')}` : path.join('.');

  const handleDragStart = (e: React.DragEvent) => {
    if (!isDraggable) return;
    e.stopPropagation();
    e.dataTransfer.setData('text/plain', `{{${fullPath}}}`);
    e.dataTransfer.effectAllowed = 'copy';
  };

  // Primitive value - inline
  if (!isExpandable) {
    return (
      <div
        className={clsx(
          'group/row flex items-start justify-between gap-1 text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1',
          isDraggable
            ? 'cursor-grab active:cursor-grabbing hover:bg-slate-50 dark:hover:bg-slate-800'
            : 'cursor-default hover:bg-slate-50 dark:hover:bg-slate-800',
        )}
        draggable={isDraggable}
        onDragStart={handleDragStart}
        title={isDraggable ? fullPath : undefined}
      >
        <div className="flex items-start gap-2 flex-1 min-w-0">
          {isDraggable && (
            <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0 mt-0.5" />
          )}
          <span className="truncate max-w-[120px] flex-shrink-0 text-sm" title={displayKey}>
            {displayKey}
          </span>
          <span className="text-slate-400 flex-shrink-0">:</span>
          <PrimitiveValue value={value} />
        </div>
        <CopyButton
          value={value}
          className="flex-shrink-0 opacity-0 group-hover/row:opacity-100 focus-visible:opacity-100"
        />
      </div>
    );
  }

  if (isFileRefValue) {
    return (
      <FileObjectNode
        nodeKey={displayKey}
        fileRef={value}
        isDraggable={isDraggable}
        dragPrefix={dragPrefix}
        path={path}
        isExpanded={isExpanded}
        onToggle={handleToggle}
        onDragStart={handleDragStart}
        fullPath={fullPath}
      />
    );
  }

  // Object or Array - expandable
  return (
    <div className="flex flex-col gap-1">
      <div
        className={clsx(
          'group/row flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1',
          'cursor-pointer hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800',
        )}
        draggable={isDraggable}
        onDragStart={handleDragStart}
        onClick={handleToggle}
        title={isDraggable ? fullPath : displayKey}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          {isDraggable && (
            <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0" />
          )}
          <span className="truncate flex-1 min-w-0 text-sm" title={displayKey}>
            {displayKey}
          </span>
          <ChevronRight
            className={clsx(
              'h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform flex-shrink-0 mr-2',
              isExpanded && 'rotate-90',
            )}
          />
        </div>
        <span className="text-sm font-mono text-orange-600 dark:text-orange-400 flex-shrink-0">
          {isArray ? `[${itemCount}]` : `{${itemCount}}`}
        </span>
        <CopyButton
          value={value}
          className="flex-shrink-0 ml-1 opacity-0 group-hover/row:opacity-100 focus-visible:opacity-100"
        />
      </div>

      {isExpanded && (
        <JsonValueTree
          data={value}
          isDraggable={isDraggable}
          dragPrefix={dragPrefix}
          path={path}
          showBorder={true}
          expandedPaths={expandedPaths}
          onToggleExpand={onToggleExpand}
          labelForKey={labelForKey}
        />
      )}
    </div>
  );
}

// ============================================
// Primitive values
// ============================================

/**
 * One scalar, rendered for what it actually is.
 *
 * A string gets three extra readings the old renderer had none of: it can be an
 * unresolved template (shown as such instead of as data), a JSON document
 * (expandable as a tree instead of one escaped line), or simply too long for a
 * 280px column (clamped with an explicit expand).
 */
export function PrimitiveValue({ value }: { value: any }) {
  if (value === null) {
    return <span className="font-mono text-sm text-slate-400">null</span>;
  }
  if (value === undefined) {
    return <span className="font-mono text-sm text-slate-400">undefined</span>;
  }
  if (typeof value === 'boolean') {
    return <span className="font-mono text-sm text-yellow-700 dark:text-yellow-300">{String(value)}</span>;
  }
  if (typeof value === 'number') {
    return <span className="font-mono text-sm text-green-700 dark:text-green-300">{value}</span>;
  }
  if (typeof value === 'string') {
    return <StringValue value={value} />;
  }
  return <span className="font-mono text-sm text-slate-500">{String(value)}</span>;
}

function StringValue({ value }: { value: string }) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  const unresolved = React.useMemo(() => detectUnresolvedValue(value), [value]);
  const embedded = React.useMemo(
    () => (unresolved ? undefined : parseEmbeddedJson(value)),
    [value, unresolved],
  );
  const [showJson, setShowJson] = React.useState(false);
  const [expandedPaths, setExpandedPaths] = React.useState<Set<string>>(() => new Set());
  const [showFull, setShowFull] = React.useState(false);

  const toggleExpand = React.useCallback((pathKey: string) => {
    setExpandedPaths((prev) => {
      const next = new Set(prev);
      if (next.has(pathKey)) next.delete(pathKey);
      else next.add(pathKey);
      return next;
    });
  }, []);

  if (unresolved) {
    return (
      <span className="flex min-w-0 flex-wrap items-center gap-1">
        <UnresolvedBadge kind={unresolved} />
        <span
          className="font-mono text-sm text-amber-700 dark:text-amber-300 break-all"
          data-testid="run-value-unresolved"
        >
          {clamp(value, showFull)}
        </span>
        {value.length > LONG_STRING_CHARS && (
          <ShowMoreToggle expanded={showFull} onToggle={() => setShowFull((v) => !v)} />
        )}
      </span>
    );
  }

  if (embedded !== undefined) {
    return (
      <span className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="flex flex-wrap items-center gap-1">
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              setShowJson((v) => !v);
            }}
            title={showJson ? t('collapseJson') : t('expandJson')}
            data-testid="run-value-json-toggle"
            className="inline-flex items-center gap-1 rounded px-1 py-0.5 text-xs font-medium bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300 hover:bg-indigo-200 dark:hover:bg-indigo-900/70 transition-colors"
          >
            <ChevronRight className={clsx('h-3 w-3 transition-transform', showJson && 'rotate-90')} />
            {t('jsonBadge')}
          </button>
          {!showJson && (
            <span className="font-mono text-sm text-blue-700 dark:text-blue-300 break-all">
              {clamp(value, false)}
            </span>
          )}
        </span>
        {showJson && (
          <span className="block">
            <JsonValueTree
              data={embedded}
              path={[]}
              showBorder
              expandedPaths={expandedPaths}
              onToggleExpand={toggleExpand}
            />
          </span>
        )}
      </span>
    );
  }

  const isLong = value.length > LONG_STRING_CHARS;
  return (
    <span className="flex min-w-0 flex-wrap items-baseline gap-1">
      <span className="font-mono text-sm text-blue-700 dark:text-blue-300 break-all">
        &quot;{clamp(value, showFull)}&quot;
      </span>
      {isLong && <ShowMoreToggle expanded={showFull} onToggle={() => setShowFull((v) => !v)} />}
    </span>
  );
}

function clamp(value: string, expanded: boolean): string {
  if (expanded || value.length <= LONG_STRING_CHARS) return value;
  return `${value.slice(0, LONG_STRING_CHARS)}…`;
}

function ShowMoreToggle({ expanded, onToggle }: { expanded: boolean; onToggle: () => void }) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation();
        onToggle();
      }}
      data-testid="run-value-show-more"
      className="text-sm font-medium text-[var(--accent-primary)] hover:underline"
    >
      {expanded ? t('showLess') : t('showMore')}
    </button>
  );
}

const UNRESOLVED_LABEL_KEY: Record<UnresolvedKind, string> = {
  invalid_template: 'unresolvedInvalidTemplate',
  unresolved_variable: 'unresolvedVariable',
  stringified_object: 'unresolvedStringifiedObject',
};

function UnresolvedBadge({ kind }: { kind: UnresolvedKind }) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  return (
    <span
      data-testid="run-value-unresolved-badge"
      title={t(UNRESOLVED_LABEL_KEY[kind])}
      className="inline-flex items-center rounded px-1 py-0.5 text-xs font-medium bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300"
    >
      {t('unresolvedBadge')}
    </span>
  );
}

// ============================================
// Files
// ============================================

/**
 * Compact file card shown at the top of step outputs that carry a file.
 */
export function FilePreviewCard({ fileRef }: { fileRef: FileRef }) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  const [isDownloading, setIsDownloading] = React.useState(false);

  const handleDownload = async () => {
    setIsDownloading(true);
    try {
      await fileService.downloadAndSave(fileRef, fileRef.name);
    } catch (err) {
      console.error('Download failed:', err);
    } finally {
      setIsDownloading(false);
    }
  };

  const handlePreview = async () => {
    try {
      // View via an authenticated fetch (no token in the URL).
      const url = fileRefToUrl(fileRef, { inline: true });
      if (url) await openAuthedFileInNewTab(url);
    } catch (err) {
      console.error('Preview failed:', err);
    }
  };

  return (
    <div className="flex items-center gap-2 px-2 py-1.5 rounded-md bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700">
      <div className="flex-1 min-w-0">
        <span className="text-sm font-medium truncate block">{fileRef.name}</span>
        <span className="text-xs text-slate-500 dark:text-slate-400">
          {fileRef.mimeType} · {fileService.formatFileSize(fileRef.size)}
        </span>
      </div>
      <div className="flex items-center gap-1 flex-shrink-0">
        <button
          onClick={handlePreview}
          className="p-1 rounded text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          title={t('viewFile')}
        >
          <Eye className="h-3.5 w-3.5" />
        </button>
        <button
          onClick={handleDownload}
          disabled={isDownloading}
          className="p-1 rounded text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          title={t('downloadFile')}
        >
          <Download className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
}

/** Wraps FileObjectNode when the WHOLE payload is a file reference. */
function TopLevelFileRefView({
  data,
  isDraggable,
  dragPrefix,
  path,
  expandedPaths,
  onToggleExpand,
}: {
  data: any;
  isDraggable: boolean;
  dragPrefix?: string;
  path: string[];
  expandedPaths: Set<string>;
  onToggleExpand: (pathKey: string) => void;
}) {
  // The empty path (`""`) is unique here: this only renders when the whole
  // payload is a FileRef, so no sibling node can share it.
  const pathKey = path.join('.');
  const isExpanded = expandedPaths.has(pathKey);
  const fullPath = dragPrefix ? `${dragPrefix}.${path.join('.')}` : path.join('.');
  const handleDragStart = (e: React.DragEvent) => {
    if (!isDraggable) return;
    e.stopPropagation();
    e.dataTransfer.setData('text/plain', `{{${fullPath}}}`);
    e.dataTransfer.effectAllowed = 'copy';
  };

  return (
    <FileObjectNode
      nodeKey="file"
      fileRef={data}
      isDraggable={isDraggable}
      dragPrefix={dragPrefix}
      path={path}
      isExpanded={isExpanded}
      onToggle={() => onToggleExpand(pathKey)}
      onDragStart={handleDragStart}
      fullPath={fullPath}
    />
  );
}

interface FileObjectNodeProps {
  nodeKey: string;
  fileRef: FileRef;
  isDraggable: boolean;
  dragPrefix?: string;
  path: string[];
  isExpanded: boolean;
  onToggle: () => void;
  onDragStart: (e: React.DragEvent) => void;
  fullPath: string;
}

function FileObjectNode({
  nodeKey,
  fileRef,
  isDraggable,
  dragPrefix,
  path,
  isExpanded,
  onToggle,
  onDragStart,
  fullPath,
}: FileObjectNodeProps) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  // Normalize DB/flattened format (file_url, file_name, …) to canonical FileRef
  const normalized = normalizeFileRef(fileRef);
  const [isDownloading, setIsDownloading] = React.useState(false);

  const handleDownload = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsDownloading(true);
    try {
      await fileService.downloadAndSave(normalized, normalized.name);
    } catch (err) {
      console.error('Download failed:', err);
    } finally {
      setIsDownloading(false);
    }
  };

  const filePath = getFilePath(normalized);

  const displayProps = [
    { key: 'path', value: filePath },
    { key: 'name', value: normalized.name },
    { key: 'mimeType', value: normalized.mimeType },
    { key: 'size', value: normalized.size },
  ];

  const handlePreview = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      // View via an authenticated fetch (no token in the URL).
      const url = fileRefToUrl(normalized, { inline: true });
      if (url) await openAuthedFileInNewTab(url);
    } catch (err) {
      console.error('Preview failed:', err);
    }
  };

  return (
    <div className="flex flex-col gap-1">
      <div
        className={clsx(
          'flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1',
          'cursor-pointer hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800',
        )}
        draggable={isDraggable}
        onDragStart={onDragStart}
        onClick={onToggle}
        title={isDraggable ? fullPath : nodeKey}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          {isDraggable && (
            <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0" />
          )}
          <span className="truncate flex-1 min-w-0 text-sm" title={nodeKey}>
            {nodeKey}
          </span>
          <ChevronRight
            className={clsx(
              'h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform flex-shrink-0 mr-2',
              isExpanded && 'rotate-90',
            )}
          />
        </div>
        <span className="text-sm font-mono text-orange-600 dark:text-orange-400 flex-shrink-0">
          {`{${displayProps.length}}`}
        </span>
        <div className="flex items-center gap-1 ml-2">
          <button
            onClick={handlePreview}
            className="p-1 rounded text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
            title={t('viewFile')}
          >
            <Eye className="h-3.5 w-3.5" />
          </button>
          <button
            onClick={handleDownload}
            disabled={isDownloading}
            className="p-1 rounded text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            title={t('downloadFile')}
          >
            {isDownloading ? <LoadingSpinner size="xs" /> : <Download className="h-3.5 w-3.5" />}
          </button>
        </div>
      </div>

      {isExpanded && (
        <div className="pl-3 border-l border-slate-200 dark:border-slate-700">
          <div className="space-y-1">
            {displayProps.map(({ key, value }) => (
              <div
                key={key}
                className={clsx(
                  'group/row flex items-start gap-2 text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1',
                  isDraggable
                    ? 'cursor-grab active:cursor-grabbing hover:bg-slate-50 dark:hover:bg-slate-800'
                    : 'cursor-default hover:bg-slate-50 dark:hover:bg-slate-800',
                )}
                draggable={isDraggable}
                onDragStart={(e) => {
                  if (!isDraggable) return;
                  e.stopPropagation();
                  const propPath = dragPrefix
                    ? `${dragPrefix}.${[...path, key].join('.')}`
                    : [...path, key].join('.');
                  e.dataTransfer.setData('text/plain', `{{${propPath}}}`);
                  e.dataTransfer.effectAllowed = 'copy';
                }}
                title={isDraggable ? `${fullPath}.${key}` : undefined}
              >
                {isDraggable && (
                  <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0 mt-0.5" />
                )}
                <span className="truncate max-w-[120px] flex-shrink-0 text-sm" title={key}>
                  {key}
                </span>
                <span className="text-slate-400 flex-shrink-0">:</span>
                <PrimitiveValue value={value} />
                <CopyButton
                  value={value}
                  className="ml-auto flex-shrink-0 opacity-0 group-hover/row:opacity-100 focus-visible:opacity-100"
                />
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
