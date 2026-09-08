import { normalizeLabel } from '@/app/workflows/builder/utils/labelNormalizer';

export type EmptyWorkflowPlan = {
  id: string;
  name: string;
  description?: string;
  /**
   * Intentionally empty: the builder's empty-canvas experience (EmptyCanvasChat)
   * proposes the trigger types (Trigger dropdown + AI chat + suggestions), so
   * creation must NOT impose a trigger choice. Validation only requires a
   * trigger at run time (GraphStructureRule) and is skipped on a 0-node canvas.
   */
  triggers: [];
  // Both the backend parser (parseSteps reads "mcps") and the builder loader
  // (isValidPlan requires an "mcps" array) use "mcps" as the steps key - a
  // "steps" key is silently ignored and the plan fails builder validation.
  mcps: [];
  edges: [];
};

export function createEmptyWorkflowPlan(input: {
  id: string;
  name: string;
  description?: string;
}): EmptyWorkflowPlan {
  return {
    id: input.id,
    name: input.name,
    description: input.description || undefined,
    triggers: [],
    mcps: [],
    edges: [],
  };
}

/**
 * A workflow that is created WITH its schedule already set, which the agenda does when a
 * user picks an empty slot on the calendar.
 *
 * <p>This deliberately does what {@link createEmptyWorkflowPlan} refuses to do - it imposes
 * a trigger - because here the trigger is the entire request: the user did not ask for a
 * workflow, they asked for something to happen on Wednesday at 16:00. The empty-canvas rule
 * still holds for every other creation path.
 *
 * <p>The trigger shape is the one the builder's own plan generator emits for a schedule
 * node (`type: 'schedule'` plus `params.cron` / `params.timezone`), so the workflow opens in
 * the builder as an ordinary schedule trigger the user can edit, and nothing about it says
 * it was made from the calendar. No `position` is given: the importer lays a first node out
 * exactly as it does on an empty canvas.
 *
 * <p><b>It will not fire yet, and the caller must say so.</b> A `scheduled_executions` row
 * is ACTIVE only while the workflow has a pinned production version
 * (`PinAwareTriggerSyncService`), so a workflow created here is armed the moment it is set
 * as production and not before. That is also why it does not appear on the agenda straight
 * away: the agenda draws armed schedules.
 */
export function createScheduledWorkflowPlan(input: {
  id: string;
  name: string;
  description?: string;
  cron: string;
  timezone: string;
  /** The trigger's label on the canvas, and the id the plan refers to it by. */
  triggerLabel: string;
  /** Stops the schedule after N runs; null for an open-ended one. */
  maxExecutions?: number | null;
}) {
  return {
    id: input.id,
    name: input.name,
    description: input.description || undefined,
    triggers: [
      {
        // The builder's own normaliser, imported rather than re-derived: the plan this
        // produces is re-saved by the builder the moment the user edits it, and two
        // implementations would give the same workflow two different trigger ids (its
        // `null` for a label with no ASCII alphanumerics - a Chinese or Cyrillic name - is
        // exactly the case a hand-rolled copy gets wrong).
        id: normalizeLabel(input.triggerLabel) ?? 'schedule',
        type: 'schedule',
        label: input.triggerLabel,
        params: {
          cron: input.cron,
          timezone: input.timezone,
          maxExecutions: input.maxExecutions ?? null,
          enabled: true,
        },
      },
    ],
    mcps: [],
    edges: [],
  };
}
