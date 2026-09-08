import { findNodeClassById, matchNodeClass } from './nodeClasses';
import type { BuilderNodeData } from '../types';

/**
 * Palette item -> the key the per-plan gate is stored under.
 *
 * <p>The gate is keyed by the backend's node type, the same string the admin
 * screen lists (it reads `node_type_documentation`). For most nodes the palette
 * id already IS that string once its dashes become underscores (`merge`, `code`,
 * `send_email`, `respond_to_webhook`...); {@link NODE_TYPE_OVERRIDES} names the
 * ones where it is not.
 *
 * <p><b>Not the class's `kind`.</b> That was the first thing tried and it is
 * wrong for a third of the palette: two dozen classes share the generic kinds
 * `action` / `output` / `crud`, so `code`, `send_email` and every table
 * operation would all have resolved to a key nothing can gate.
 *
 * <p>Getting one of these wrong is silent in the worst direction: the node shows
 * no lock, gets added, and fails at run time against the backend gate. That is
 * what `planFeatureKeys.test.ts` exists to catch - it asserts every mapping
 * lands in the set of types the backend actually documents.
 */
const NODE_TYPE_OVERRIDES: Record<string, string> = {
  // The palette calls the agent's shape "reasoning"; the engine and the docs
  // call the node "agent".
  'ai-agent': 'agent',
  'if-else': 'decision',
  'user-approval': 'approval',
  'while-group': 'loop',
  // The five table operations share one palette `kind` ("crud") and are five
  // distinct node types to everything else.
  'create-row': 'insert_row',
  'read-row': 'get_rows',
  'update-row': 'update_row',
  'delete-row': 'delete_row',
  'create-column': 'create_column',
  'find-row': 'find_rows',
  // Every MCP palette entry is the same executed node type.
  'mcp': 'mcp',
  'mcp-tool': 'mcp',
  'mcp-resource': 'mcp',
  'mcp-interface': 'interface',
  // Triggers: the palette suffixes them, the engine uses the bare kind.
  'webhook-trigger': 'webhook',
  'schedule-trigger': 'schedule',
  'manual-trigger': 'manual',
  'chat-trigger': 'chat',
  'form-trigger': 'form',
  // A tables trigger EXECUTES as 'datasource' and is DOCUMENTED as 'table'. The
  // admin screen offers what is documented, so the key has to be 'table' - the
  // backend gate applies the same alias.
  'tables-trigger': 'table',
  'workflows-trigger': 'workflow',
  'error-trigger': 'error',
};

/** Exposed for the mapping test. */
export const PALETTE_NODE_TYPE_OVERRIDES = NODE_TYPE_OVERRIDES;

/**
 * The gate key for a palette entry, or null when the entry is navigation rather
 * than a node (a category tile, the "Tables" drill-in).
 */
/**
 * Palette entries that are navigation, not nodes: a category tile opens a list,
 * it never lands on the canvas, so there is nothing to gate.
 */
const NAVIGATION_ONLY_IDS = new Set(['triggers']);

export function nodeFeatureKey(paletteId: string | null | undefined): string | null {
  if (!paletteId || NAVIGATION_ONLY_IDS.has(paletteId)) return null;
  const override = NODE_TYPE_OVERRIDES[paletteId];
  if (override) return `node:${override}`;
  // Only for ids the palette actually knows: an arbitrary string must not be
  // turned into a plausible-looking key that silently matches nothing.
  if (!findNodeClassById(paletteId)) return null;
  return `node:${paletteId.toLowerCase().replace(/-/g, '_')}`;
}

/**
 * Keys for a catalog entry, most specific first, matching the precedence the
 * backend applies: one endpoint can be held back further than the integration it
 * belongs to, never the reverse.
 */
export function catalogFeatureKeys(
  apiSlug: string | null | undefined,
  toolSlug?: string | null,
): string[] {
  const keys: string[] = [];
  if (toolSlug) keys.push(`tool:${toolSlug.toLowerCase()}`);
  if (apiSlug) keys.push(`api:${apiSlug.toLowerCase()}`);
  return keys;
}

/**
 * The gate keys for a node ON THE CANVAS, most specific first.
 *
 * <p>Distinct from {@link nodeFeatureKey}, which answers for a PALETTE entry.
 * A node's identity can change after it is dropped: dragging the Instagram
 * integration gives a node with only an `apiSlug`, and picking `publish_media`
 * in the inspector then adds the `toolSlug`. Keying off the palette entry alone
 * would answer for what was dragged rather than for what the node now calls,
 * which is exactly how a restricted endpoint reached a workflow unmarked.
 */
export function featureKeysForBuilderNode(data: BuilderNodeData | null | undefined): string[] {
  const anyData = data as Record<string, any> | null | undefined;
  const toolSlug: string | undefined = anyData?.toolData?.toolSlug;
  const apiSlug: string | undefined = anyData?.apiData?.apiSlug ?? anyData?.toolData?.apiSlug;
  if (toolSlug || apiSlug) {
    return catalogFeatureKeys(apiSlug, toolSlug);
  }
  const klass = matchNodeClass(data ?? null);
  const key = nodeFeatureKey(klass?.id);
  return key ? [key] : [];
}
