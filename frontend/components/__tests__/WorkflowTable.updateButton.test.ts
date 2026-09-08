import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * The bulk bar's Update button, on the workflow list.
 *
 * The agent list has had one since July (single selection, Pencil, opens the
 * edit modal) and the applications board before that; the workflow list offered
 * Move, Clone and Delete and no way to rename or cap a workflow without opening
 * its builder. This pins the button, its gate, and the fact that the two lists
 * agree on what "update one selected thing" looks like.
 *
 * Asserted over the source: rendering WorkflowTable means standing up folders,
 * favorites, pagination, four API clients and a router, for a claim about which
 * control appears when exactly one row is ticked.
 */

const ROOT = process.cwd();
const read = (file: string) => fs.readFileSync(path.join(ROOT, file), 'utf8');

const WORKFLOWS = read('components/WorkflowTable.tsx');
const AGENTS = read('components/AgentTable.tsx');

/** The bulk bar of a list component: from SelectionActionBar to its close tag. */
function bulkBar(source: string): string {
  const start = source.indexOf('<SelectionActionBar');
  const end = source.indexOf('</SelectionActionBar>', start);
  expect(start, 'no SelectionActionBar in this list').toBeGreaterThan(-1);
  expect(end).toBeGreaterThan(start);
  return source.slice(start, end);
}

describe('the workflow list can update the one workflow you selected', () => {
  const BAR = bulkBar(WORKFLOWS);

  it('offers an Update button in the bulk bar', () => {
    expect(BAR).toContain("t('common.update')");
    expect(BAR).toContain('<Pencil');
  });

  it('offers it for ONE selection only', () => {
    // "Update the selection" means nothing for several rows: the modal edits a
    // name. The gate has to sit on the button, not on the bar.
    const guard = BAR.slice(0, BAR.indexOf("t('common.update')"));
    expect(guard).toContain('selectedWorkflows.size === 1');
    // And behind the same permission as every other mutation in the bar.
    const lastGate = guard.lastIndexOf('canMutate');
    expect(lastGate, 'the Update button is not gated on canMutate').toBeGreaterThan(-1);
    expect(guard.slice(lastGate)).toContain('selectedWorkflows.size === 1');
  });

  it('opens the modal on the row the list already holds, not on an id', () => {
    // Passing an id would make the modal flash empty while it fetched values
    // the list is already showing on the card.
    // Matched loosely: pinning the exact spelling of a JSX line makes a
    // reformat fail as "the button is gone".
    expect(BAR).toMatch(/workflows\s*\.find\(/);
    expect(BAR).toMatch(/selectedWorkflows\.has\(/);
    expect(BAR).toContain('setEditingWorkflow(workflow)');
  });

  it('mounts the same modal the builder breadcrumb uses', () => {
    expect(WORKFLOWS).toContain('<EditMetadataModal');
    expect(WORKFLOWS).toContain('resourceType="workflow"');
    // Seeded with the cap as well as the name, so the fold opens on the real
    // value rather than looking like the workflow has none.
    expect(WORKFLOWS).toContain('initialBudgetCredits={editingWorkflow.budgetCredits');
    expect(WORKFLOWS).toContain('initialBudgetPeriodMode={editingWorkflow.budgetPeriodMode');
  });

  it('saves through the one endpoint that owns a workflow row', () => {
    // The same call AppHeader makes, so a workflow renamed from the list and
    // renamed from inside it cannot take two different paths.
    expect(WORKFLOWS).toContain('orchestratorApi.updateWorkflow(editingWorkflow.id');
    // And the list is told, or the card keeps showing the old name.
    // The handler, bounded by its own closing rather than by a magic length.
    const from = WORKFLOWS.indexOf('const handleSaveWorkflowMetadata');
    expect(from, 'the save handler has been renamed').toBeGreaterThan(-1);
    const handler = WORKFLOWS.slice(from, WORKFLOWS.indexOf('}, [editingWorkflow', from));
    expect(handler.length, 'the handler slice found no end').toBeGreaterThan(100);

    expect(handler).toContain('fetchWorkflows()');
    // A failed save must leave the modal OPEN, which is a question of WHERE the
    // close happens: inside the try, after the await, never in a finally. The
    // mere presence of the word `catch` said nothing about that.
    const tryBlock = handler.slice(handler.indexOf('try {'), handler.indexOf('} catch'));
    expect(tryBlock, 'the modal is closed outside the try, so a failure closes it too')
      .toContain('setEditingWorkflow(null)');
    const catchBlock = handler.slice(handler.indexOf('} catch'), handler.indexOf('} finally'));
    expect(catchBlock, 'a failed save tells the user nothing').toContain('addToast');
    expect(catchBlock, 'a failed save must not close the modal').not.toContain('setEditingWorkflow(null)');
    // `finally` runs on BOTH paths, so a close placed there shuts the modal on
    // a failure even with the one inside `try` still present - which satisfied
    // every assertion above.
    const finallyBlock = handler.slice(handler.indexOf('} finally'));
    expect(finallyBlock, 'the modal is closed in finally, so a failure closes it too')
      .not.toContain('setEditingWorkflow(null)');
  });
});

describe('the two lists agree on what Update looks like', () => {
  it('same gate, same icon, same label', () => {
    // They drifted once already: the agent list got this button and the
    // workflow list did not, so the same gesture worked on one page and not the
    // other. Neither is the source of truth, so the test is the join.
    const agentBar = bulkBar(AGENTS);
    for (const bar of [agentBar, bulkBar(WORKFLOWS)]) {
      expect(bar).toContain("t('common.update')");
      // The icon, not its exact formatting: a Prettier run over EITHER list
      // must not report that the two disagree.
      expect(bar).toMatch(/<Pencil\s+className="h-3\.5 w-3\.5"\s*\/>/);
      expect(bar).toMatch(/selected(Agents|Workflows)\.size === 1/);
    }
  });
});
