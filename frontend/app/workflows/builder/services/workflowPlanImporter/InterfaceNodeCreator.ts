/**
 * InterfaceNodeCreator - Handles creation of interface nodes from plan data
 * Extracted from NodeCreationService for single responsibility
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { normalizeLabel } from '../../utils/labelNormalizer';
import { parsePosition } from './nodeCreationHelpers';
import { SNAPSHOT_FORMAT_KEY, type InterfaceFormatMap } from './InterfaceFormatService';
import { snapBoxToFormat } from '../../utils/interfaceNodeBox';
import { resolveInterfaceFormatOrDefault } from '@/lib/interfaces/interfaceFormats';

interface InterfaceFromPlan {
  id: string;
  label?: string;
  position?: { x?: number | string; y?: number | string };
  previewWidth?: number;
  previewHeight?: number;
  showPreview?: boolean;
  variableMapping?: Record<string, string>;
  actionMapping?: Record<string, string>;
  isEntryInterface?: boolean;
  generateScreenshot?: boolean;
  generatePdf?: boolean;
  pdfFormat?: string;
  pdfLandscape?: boolean;
  generateVideo?: boolean;
  videoPreset?: string;
  videoMaxDurationSeconds?: number;
  videoMode?: string;
  videoFps?: number;
  exposeRenderedSource?: boolean;
  // Enriched snapshot fields (from publication planSnapshot)
  _snapshot_htmlTemplate?: string;
  _snapshot_cssTemplate?: string;
  _snapshot_jsTemplate?: string;
  /**
   * The page's format, frozen into a publication snapshot at publish time. Present ONLY
   * on a snapshot plan, and then always (null when the page declares no format), which
   * is why its mere presence is treated as an answer. It is what lets a marketplace
   * preview and a share link - neither of which may call the interfaces endpoint - lay
   * an A4 page out as A4.
   */
  _snapshot_format?: string | null;
}

interface InterfaceCreationResult {
  nodes: Node<BuilderNodeData>[];
  interfaceIdToNodeIdMap: Map<string, string>;
  interfaceLabelToNodeIdMap: Map<string, string>;
}

/**
 * The box an interface node will paint, or null when it cannot be known here.
 *
 * The format comes from the publication snapshot when the plan carries one (the only
 * source available to an anonymous reader), else from the lookup the import ran. With
 * neither, the caller keeps its historical default rather than guess.
 *
 * The snap is fed the plan's own box, so it behaves exactly like the node's snap effect:
 * a stored box whose ratio still matches the format is preserved (a page the user
 * resized keeps its size), one left over from another format is re-snapped. A format
 * the page does not declare still has a shape - the classic 1280x800 viewport the node
 * falls back to - so a resolved-but-empty format is a box, not an unknown.
 *
 * Compact nodes are excluded: they are laid out from their label like any other node
 * (`getNodeDimensions` only reads the box in preview mode), and writing one would put
 * a size in the plan that the node itself never writes.
 */
function resolveNodeBox(
  iface: InterfaceFromPlan,
  formats?: InterfaceFormatMap,
): { width: number; height: number } | null {
  if (iface.showPreview === false) return null;

  const declared = Object.prototype.hasOwnProperty.call(iface, SNAPSHOT_FORMAT_KEY)
    ? iface._snapshot_format ?? null
    : formats?.has(iface.id) ? formats.get(iface.id) ?? null : undefined;
  if (declared === undefined) return null;

  const viewport = resolveInterfaceFormatOrDefault(declared);
  return snapBoxToFormat(viewport, { width: iface.previewWidth, height: iface.previewHeight });
}

/**
 * Create all interface nodes from plan
 */
export function createInterfaceNodes(
  interfaces: InterfaceFromPlan[],
  startX: number,
  startY: number,
  /**
   * Declared format per interface id, resolved by the import (see
   * {@link InterfaceFormatService}). An id in here gets the box its node will paint; an
   * id absent from it, and not described by a snapshot, keeps the historical default -
   * so a failed or skipped lookup imports exactly as before.
   */
  formats?: InterfaceFormatMap,
): InterfaceCreationResult {
  const nodes: Node<BuilderNodeData>[] = [];
  const interfaceIdToNodeIdMap = new Map<string, string>();
  const interfaceLabelToNodeIdMap = new Map<string, string>();

  // Track how many times each interfaceId has been seen (for collision handling)
  const seenInterfaceIds = new Map<string, number>();

  // Single-entry invariant: an app has ONE entry page. The builder UI enforces it on
  // edit, but agent-written plans (MCP add_node/modify) can carry several flagged
  // interfaces - keep the FIRST and clear the rest, mirroring the backend resolver's
  // findFirst() so what the author sees matches what the showcase picks.
  let entryAssigned = false;

  for (const iface of interfaces) {
    const label = iface.label || iface.id;
    const normalizedLabel = normalizeLabel(label);

    // Track occurrences of same interfaceId (multiple nodes can reference the same DB interface)
    const occurrenceCount = (seenInterfaceIds.get(iface.id) || 0) + 1;
    seenInterfaceIds.set(iface.id, occurrenceCount);

    // First occurrence uses standard format; duplicates get a suffix to avoid nodeId collision
    const nodeId = occurrenceCount === 1
      ? `interface-${iface.id}`
      : `interface-${iface.id}--${occurrenceCount}`;

    // Map interfaceId → nodeId (first occurrence only, avoids overwrite)
    if (occurrenceCount === 1) {
      interfaceIdToNodeIdMap.set(iface.id, nodeId);
    }
    if (normalizedLabel) {
      interfaceLabelToNodeIdMap.set(normalizedLabel, nodeId);
    }
    console.log(`[InterfaceNodeCreator] Mapped interface: id="${iface.id}" label="${label}" normalized="${normalizedLabel}" -> nodeId="${nodeId}" (occurrence=${occurrenceCount})`);

    // Parse position (use parsePosition like all other node creators)
    const { position } = parsePosition(iface.position, startX, startY, `interface ${iface.id}`);

    const isEntry = iface.isEntryInterface === true && !entryAssigned;
    if (isEntry) entryAssigned = true;

    // Create interface node at the box it will PAINT (see resolveNodeBox): that is what
    // lets the automatic layout reserve the node's real shape on the very first pass,
    // including on a plain load, where nothing may move a node after the browser has
    // painted. With no format to go on: the plan's box, else the historical default.
    const box = resolveNodeBox(iface, formats);
    const previewW = box?.width ?? (iface.previewWidth || 400);
    const previewH = box?.height ?? (iface.previewHeight || 250);
    const interfaceNode: Node<BuilderNodeData> = {
      id: nodeId,
      type: 'interfaceNode',
      position,
      positionAbsolute: position,
      style: { width: previewW, height: previewH },
      data: {
        id: nodeId,
        label,
        kind: 'interface',
        interfaceData: {
          interfaceId: iface.id,
          interfaceName: label,
          // The layout reads the box from here (getNodeDimensions), so the snapped
          // value has to land in the data, not only in the node's style.
          previewWidth: box?.width ?? iface.previewWidth,
          previewHeight: box?.height ?? iface.previewHeight,
          showPreview: iface.showPreview !== false,
          variableMapping: iface.variableMapping,
          actionMapping: iface.actionMapping,
          isEntryInterface: isEntry,
          generateScreenshot: iface.generateScreenshot,
          generatePdf: iface.generatePdf,
          pdfFormat: iface.pdfFormat,
          pdfLandscape: iface.pdfLandscape,
          generateVideo: iface.generateVideo,
          videoPreset: iface.videoPreset,
          videoMaxDurationSeconds: iface.videoMaxDurationSeconds,
          videoMode: iface.videoMode,
          videoFps: iface.videoFps,
          exposeRenderedSource: iface.exposeRenderedSource,
          // Use snapshot templates from enriched plan (publication preview)
          ...(iface._snapshot_htmlTemplate && { editorExpression: iface._snapshot_htmlTemplate }),
          ...(iface._snapshot_cssTemplate && { cssTemplate: iface._snapshot_cssTemplate }),
          ...(iface._snapshot_jsTemplate && { jsTemplate: iface._snapshot_jsTemplate }),
        },
      },
    };

    nodes.push(interfaceNode);
  }

  return {
    nodes,
    interfaceIdToNodeIdMap,
    interfaceLabelToNodeIdMap,
  };
}
