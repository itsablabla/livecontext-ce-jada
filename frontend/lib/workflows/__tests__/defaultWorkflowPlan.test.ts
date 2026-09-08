import { describe, expect, it } from 'vitest';
import { createEmptyWorkflowPlan, createScheduledWorkflowPlan } from '../defaultWorkflowPlan';

describe('createEmptyWorkflowPlan', () => {
  it('creates an empty workflow plan - no trigger imposed, the builder empty-canvas UI proposes them', () => {
    expect(createEmptyWorkflowPlan({
      id: 'workflow-1',
      name: 'New workflow',
      description: 'Ready for builder',
    })).toEqual({
      id: 'workflow-1',
      name: 'New workflow',
      description: 'Ready for builder',
      triggers: [],
      mcps: [],
      edges: [],
    });
  });

  it('regression: uses the "mcps" steps key so the builder loader accepts the plan (a "steps" key fails isValidPlan and skips the import)', () => {
    const plan = createEmptyWorkflowPlan({ id: 'workflow-1', name: 'New workflow' });

    // Mirrors useWorkflowLoader's isValidPlan check - the exact gate that
    // silently skipped the import when the plan carried "steps" instead.
    const isValidPlan = Array.isArray(plan.triggers)
      && Array.isArray((plan as Record<string, unknown>).mcps)
      && Array.isArray(plan.edges);

    expect(isValidPlan).toBe(true);
    expect('steps' in plan).toBe(false);
  });
});

/**
 * The plan the agenda saves when a workflow is created from an empty calendar slot.
 *
 * <p>Every assertion here is a KEY NAME, and that is the point: the backend reads
 * `params.cron` / `params.timezone` / `params.maxExecutions` (ScheduleSyncService's
 * extractScheduleConfig) and the builder's importer reads the same three. Nothing validates
 * the shape - a `cronExpression` typo saves fine, imports fine, and the schedule silently
 * defaults to hourly. The user asked for Wednesday at 16:00 and gets every hour, for ever,
 * with no error at any point.
 */
describe('createScheduledWorkflowPlan', () => {
  const base = {
    id: 'workflow-9',
    name: 'Weekly report',
    cron: '0 16 * * 3',
    timezone: 'Europe/Paris',
    triggerLabel: 'Weekly report',
  };

  it('names the schedule parameters exactly as the backend reads them', () => {
    const [trigger] = createScheduledWorkflowPlan(base).triggers;

    expect(trigger.type).toBe('schedule');
    expect(trigger.params).toEqual({
      cron: '0 16 * * 3',
      timezone: 'Europe/Paris',
      maxExecutions: null,
      enabled: true,
    });
  });

  it('keeps the steps/edges keys the builder loader gates on', () => {
    const plan = createScheduledWorkflowPlan(base);

    expect(Array.isArray(plan.mcps)).toBe(true);
    expect(Array.isArray(plan.edges)).toBe(true);
    expect('steps' in plan).toBe(false);
  });

  it('gives the trigger the id the builder would give it, from the same normaliser', () => {
    // The builder re-saves this plan the moment the user edits the workflow. A second
    // normalisation rule here would hand the same trigger two different ids.
    expect(createScheduledWorkflowPlan({ ...base, triggerLabel: 'Rapport hebdo' }).triggers[0].id)
      .toBe('rapport_hebdo');
  });

  it('still produces a usable id for a label with no ASCII in it', () => {
    // The shared normaliser answers null there; an empty trigger id is not addressable.
    expect(createScheduledWorkflowPlan({ ...base, triggerLabel: '每周报告' }).triggers[0].id)
      .toBe('schedule');
  });

  it('passes a run cap through when one is asked for', () => {
    expect(createScheduledWorkflowPlan({ ...base, maxExecutions: 3 }).triggers[0].params.maxExecutions)
      .toBe(3);
  });
});
