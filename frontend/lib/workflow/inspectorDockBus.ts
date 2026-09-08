'use client';

/**
 * Cross-tree bridge for the node inspector docked into the side panel.
 *
 * The inspector is OWNED by the builder tree: it reads the run state, the
 * step-by-step context, the validation context and the ReactFlow selection, none
 * of which the side panel can see. So it is never re-created inside the panel -
 * it stays mounted where it is and is PORTALLED into a host element the panel
 * renders. React context flows through a portal, the DOM does not, which is
 * exactly the split we want here.
 *
 * Three channels, all keyed by workflow id because several canvases of DIFFERENT
 * workflows can be mounted at once (the page, plus any keepMounted workflow tab):
 *
 *  - HOST (panel -> canvas): the element to portal into. Absent means "no panel
 *    is offering a slot", and the canvas then keeps the floating window. That
 *    absence is the real gate on the docked mode, so a preference can never make
 *    the inspector unreachable on a surface with no side panel.
 *  - STATE (canvas -> panel): whether the canvas currently wants the dock, so the
 *    panel knows to show its Inspector sub-tab, and what to caption it with.
 *  - OPEN (canvas -> page): focus the Inspector sub-tab, opening the side panel
 *    if needed. Handled at page level like the Run and Add Node tabs, because the
 *    panel body is unmounted while the panel is closed - the very case a first
 *    node selection has to handle.
 *
 * Every channel is keyed by workflow id AND {@link InspectorDockSurface}, because
 * the workflow id alone is not unique: a workflow can be open on its own page and
 * in a side-panel tab at the same time (a self-referencing sub-workflow node), and
 * with one key the two canvases overwrite each other - the second one clearing the
 * tab the first just asked for. The surface tells them apart with what each side
 * already knows: the canvas knows whether it is embedded, the panel knows whether
 * it hosts a canvas, and those are the same question asked from two ends.
 */

/**
 * Which composition a slot and its canvas belong to.
 *
 * `page`   - the canvas owns the page (/app/workflow/<id>) and the panel beside it
 *            offers the slot. Canvas and inspector are side by side.
 * `embedded` - the canvas is itself a sub-tab of the panel (the Application panel,
 *            a sub-workflow tab), and that same panel offers the slot. Canvas and
 *            inspector are then two sub-tabs of one panel, shown one at a time.
 *
 * Residual limit, deliberately not solved: two EMBEDDED canvases of the SAME
 * workflow in two different panel tabs still share a key. That needs a per-tab
 * surface id the canvas has no way to learn, and the two would previously have
 * both been floating anyway.
 */
export type InspectorDockSurface = 'page' | 'embedded';

export const INSPECTOR_DOCK_STATE_EVENT = 'workflowInspectorDockStateChange' as const;
export const INSPECTOR_DOCK_HOST_EVENT = 'workflowInspectorDockHostChange' as const;
/** Focus the Inspector sub-tab of the workflow panel (and open the panel). */
export const OPEN_INSPECTOR_PANEL_EVENT = 'workflowOpenInspectorPanel' as const;

export interface InspectorDockState {
  workflowId: string;
  surface: InspectorDockSurface;
  /** True when this canvas has a node selected AND wants it rendered in the panel. */
  docked: boolean;
  /** Caption for the sub-tab: the selected node's label, when there is one. */
  label?: string;
  /**
   * Increments on every publish so an UNCHANGED state still notifies. Re-clicking
   * the node that is already selected produces no state change, and without this
   * the panel would not come back to the Inspector tab the user just asked for.
   */
  seq: number;
}

/** The composite key every channel is stored under. */
function slotKey(workflowId: string, surface: InspectorDockSurface): string {
  return `${surface}:${workflowId}`;
}

// ── Host (panel -> canvas) ──

const hostByWorkflow = new Map<string, HTMLElement>();
const hostListeners = new Map<string, Set<(host: HTMLElement | null) => void>>();

/** The element this workflow's inspector should portal into, or null. */
export function getInspectorDockHost(
  workflowId: string | null | undefined,
  surface: InspectorDockSurface,
): HTMLElement | null {
  if (!workflowId) return null;
  return hostByWorkflow.get(slotKey(workflowId, surface)) ?? null;
}

/**
 * Offer (or withdraw, with `null`) a slot for this workflow's inspector.
 *
 * Call it from a layout effect on a ref callback, and withdraw on unmount: a
 * stale element left here is a detached node, and the portal would render into
 * nothing with no error to show for it.
 */
export function setInspectorDockHost(
  workflowId: string,
  surface: InspectorDockSurface,
  host: HTMLElement | null,
): void {
  if (!workflowId) return;
  const key = slotKey(workflowId, surface);
  const previous = hostByWorkflow.get(key) ?? null;
  if (previous === host) return;
  if (host) hostByWorkflow.set(key, host);
  else hostByWorkflow.delete(key);
  hostListeners.get(key)?.forEach(listener => listener(host));
}

/** Subscribe to host changes for one workflow surface. Returns the unsubscribe function. */
export function subscribeInspectorDockHost(
  workflowId: string,
  surface: InspectorDockSurface,
  onHost: (host: HTMLElement | null) => void,
): () => void {
  const key = slotKey(workflowId, surface);
  let listeners = hostListeners.get(key);
  if (!listeners) {
    listeners = new Set();
    hostListeners.set(key, listeners);
  }
  listeners.add(onHost);
  return () => {
    listeners!.delete(onHost);
    if (listeners!.size === 0) hostListeners.delete(key);
  };
}

// ── State (canvas -> panel) ──

const stateByWorkflow = new Map<string, InspectorDockState>();
let stateSeq = 0;

export function makeEmptyInspectorDockState(
  workflowId: string,
  surface: InspectorDockSurface,
): InspectorDockState {
  return { workflowId, surface, docked: false, seq: 0 };
}

/**
 * Latest published state for a workflow.
 *
 * The panel body is unmounted while the side panel is closed, so it reads this
 * on mount rather than waiting for the next publish - otherwise reopening the
 * panel on a canvas with a node already selected would show no Inspector tab.
 */
export function getInspectorDockState(
  workflowId: string,
  surface: InspectorDockSurface,
): InspectorDockState {
  return stateByWorkflow.get(slotKey(workflowId, surface))
    ?? makeEmptyInspectorDockState(workflowId, surface);
}

/** Publish whether this canvas wants its inspector in the panel (canvas -> panel). */
export function publishInspectorDockState(
  state: Omit<InspectorDockState, 'seq'>,
): void {
  if (typeof window === 'undefined' || !state.workflowId) return;
  const next: InspectorDockState = { ...state, seq: ++stateSeq };
  stateByWorkflow.set(slotKey(state.workflowId, state.surface), next);
  window.dispatchEvent(new CustomEvent<InspectorDockState>(INSPECTOR_DOCK_STATE_EVENT, { detail: next }));
}

/** Subscribe to dock-state publishes for one workflow. Returns the unsubscribe function. */
export function subscribeInspectorDockState(
  workflowId: string,
  surface: InspectorDockSurface,
  onState: (state: InspectorDockState) => void,
): () => void {
  if (typeof window === 'undefined') return () => {};
  const handler = (event: Event) => {
    const detail = (event as CustomEvent<InspectorDockState>).detail;
    if (!detail || detail.workflowId !== workflowId || detail.surface !== surface) return;
    onState(detail);
  };
  window.addEventListener(INSPECTOR_DOCK_STATE_EVENT, handler);
  return () => window.removeEventListener(INSPECTOR_DOCK_STATE_EVENT, handler);
}

// ── Open request (canvas -> page) ──

export interface OpenInspectorPanelDetail {
  workflowId?: string;
  /**
   * Which composition asked. Required in practice: the page panel and an embedded
   * one can both be listening for the same workflow, and an unaddressed request
   * would yank BOTH to their Inspector tab.
   */
  surface?: InspectorDockSurface;
}

/** Open (and focus) the Inspector sub-tab of the workflow panel. */
export function openInspectorPanel(detail: OpenInspectorPanelDetail = {}): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<OpenInspectorPanelDetail>(OPEN_INSPECTOR_PANEL_EVENT, { detail }));
}

/** True when an open request addresses this workflow surface (or is unaddressed). */
export function isInspectorOpenRequestFor(
  detail: OpenInspectorPanelDetail | undefined,
  workflowId: string,
  surface: InspectorDockSurface,
): boolean {
  if (!detail) return true;
  if (detail.workflowId && detail.workflowId !== workflowId) return false;
  if (detail.surface && detail.surface !== surface) return false;
  return true;
}
