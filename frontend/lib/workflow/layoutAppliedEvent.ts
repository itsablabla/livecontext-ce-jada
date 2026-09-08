/**
 * The window event a builder path fires right after it has re-laid a graph itself.
 *
 * It exists so the vertical canvas can straighten its column on MEASURED widths once
 * the browser has painted (the layout runs from label estimates, which drive the cross
 * axis in top-to-bottom mode and are wrong by a few pixels per node).
 *
 * It lives here, next to the other cross-surface workflow buses, rather than on the
 * component that listens: a hook dispatches it and a component answers, so neither owns
 * the contract. It carries the workflow it is about, because several builder canvases
 * are mounted at once (the right side panel mounts its own) and an unscoped announcement
 * would make a canvas move nodes because a DIFFERENT workflow was edited - the exact
 * bug `isEventForWorkflow` documents for the save/run events.
 */
export const LAYOUT_APPLIED_EVENT = 'workflowLayoutApplied';

export interface LayoutAppliedDetail {
  /** The workflow whose graph was just laid out. */
  workflowId?: string | null;
}

/** Announce that THIS workflow's graph was just re-laid by an automatic layout. */
export function dispatchLayoutApplied(workflowId?: string | null): void {
  window.dispatchEvent(
    new CustomEvent<LayoutAppliedDetail>(LAYOUT_APPLIED_EVENT, { detail: { workflowId } }),
  );
}
