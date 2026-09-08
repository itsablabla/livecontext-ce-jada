import type { DerivedNodeStatus } from '../types';

/**
 * The single source of truth for edge colours and arrowheads.
 *
 * <p>Stroke colour and arrowhead used to live in two files that did not know about
 * each other: {@code BuilderEdge} built a marker url as `arrow-${status}` while the
 * `<defs>` list sat in {@code BuilderCanvas} (and a second copy in the agent-fleet
 * canvas). Any status the defs list did not carry produced a url pointing at nothing,
 * and an SVG marker that does not resolve renders NOTHING - the edge silently lost
 * its arrowhead instead of falling back. That is exactly what happened to
 * `awaiting_signal`, which {@code deriveStatusFromCounts} can return for an edge.
 *
 * <p>So the arrowhead is now derived FROM this table: a status with no entry falls
 * back to the default arrow rather than to no arrow at all.
 */
export const EDGE_STATUS_COLORS: Partial<Record<DerivedNodeStatus, string>> = {
  running: '#3b82f6', // blue-500
  completed: '#10b981', // emerald-500
  failed: '#ef4444', // red-500
  skipped: '#94a3b8', // slate-400
  partial_success: '#f59e0b', // amber-500
  // A node that is waiting (wait timer, approval, interface signal) is amber on its
  // border and on its badge; its edges follow. It shares the amber of
  // partial_success the same way the node border already does - the edge LABEL
  // discriminates the two (a pause icon vs a check + cross pair).
  awaiting_signal: '#f59e0b', // amber-500
};

/** Neutral edge: no status, `pending`, or `ready` (nothing has flowed yet). */
export const EDGE_DEFAULT_COLOR = 'var(--border-color)';

/** ONE orange for looping, distinct from every status colour above. */
export const EDGE_LOOP_COLOR = '#f97316'; // orange-500

/**
 * Arrowhead marker definitions rendered once per canvas into `<defs>`.
 * Derived from {@link EDGE_STATUS_COLORS} so a new status cannot ship a colour
 * without its arrowhead.
 */
export const ARROW_MARKERS: { id: string; color: string }[] = [
  { id: 'arrow-default', color: EDGE_DEFAULT_COLOR },
  { id: 'arrow-selected', color: 'var(--accent-primary)' },
  { id: 'arrow-while-body', color: EDGE_LOOP_COLOR },
  ...Object.entries(EDGE_STATUS_COLORS).map(([status, color]) => ({ id: `arrow-${status}`, color: color as string })),
];

const ARROW_MARKER_IDS = new Set(ARROW_MARKERS.map((m) => m.id));

/** Stroke colour for an edge carrying {@code status}. */
export function getEdgeStrokeColor(status?: DerivedNodeStatus): string {
  if (!status) return EDGE_DEFAULT_COLOR;
  return EDGE_STATUS_COLORS[status] ?? EDGE_DEFAULT_COLOR;
}

/**
 * Arrowhead url for an edge carrying {@code status}, falling back to the default
 * arrow for any status without a marker of its own (`pending`, `ready`, and
 * anything added later). Never returns a url with no matching `<marker>`.
 */
export function getEdgeArrowMarkerUrl(status?: DerivedNodeStatus): string {
  const id = status ? `arrow-${status}` : 'arrow-default';
  return `url(#${ARROW_MARKER_IDS.has(id) ? id : 'arrow-default'})`;
}
