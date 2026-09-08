import { mergeTriggerDataIntoResolved } from '@/app/workflows/builder/utils/interfaceHtmlUtils';

/**
 * The two ways a frozen showcase carries its content, and how a preview picks
 * between them.
 *
 * <p>Extracted so the authenticated card preview (`ShowcasePreview`, which
 * fetches in the browser) and the crawlable listing page (which fetches on the
 * server) cannot drift: getting this wrong does not throw, it renders an
 * interface whose placeholders were rewritten or never filled, which looks like
 * an empty app rather than an error.
 */
export interface ShowcaseItemLike {
  data?: Record<string, unknown> | null;
}

export interface ResolvedShowcaseContent {
  /**
   * Fully resolved HTML the backend already produced. When present it is used
   * verbatim and MUST render in `run` mode, so `{{var|default}}` placeholders it
   * deliberately kept are left alone.
   */
  effectiveHtml?: string;
  /** Values the template resolves its own placeholders against. */
  resolvedData?: Record<string, unknown>;
  /**
   * The mode the thumbnail has to render in. `edit` rewrites unresolved
   * placeholders to `[var]`, which would corrupt a snapshot that is already
   * resolved, so anything carrying content renders in `run`.
   */
  mode: 'run' | 'edit';
}

/**
 * Resolve the first item of a showcase render into what the interface preview
 * needs.
 *
 * @param items the render's items (only the first is shown; the rest are other epochs)
 * @param triggerData previous trigger values, merged in so templates addressing
 *        `{{trigger:name.output.field}}` resolve too
 */
export function resolveShowcaseContent(
  items: ShowcaseItemLike[] | null | undefined,
  triggerData?: Record<string, Record<string, unknown>>,
): ResolvedShowcaseContent {
  const first = items?.[0];
  if (!first) return { mode: 'edit' };

  const itemData = first.data || {};

  if (itemData._resolvedHtml) {
    return { effectiveHtml: itemData._resolvedHtml as string, mode: 'run' };
  }

  const merged = mergeTriggerDataIntoResolved(itemData, triggerData);
  const hasData = merged != null && Object.keys(merged).length > 0;
  return {
    resolvedData: hasData ? merged : undefined,
    mode: hasData ? 'run' : 'edit',
  };
}
