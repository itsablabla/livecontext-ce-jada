/**
 * "Every multi-step header looks the same" is a property of EIGHT files, so no
 * test that renders one of them can hold it. And copies are exactly how this
 * broke: the header was extracted into ModalStepIndicator after existing six
 * times character for character, and two more copies survived the extraction.
 *
 * They drifted the moment they were left alone. The generation modal's copy did
 * swap in a check on a finished step, but it was the only header with no GREEN,
 * so done and current read as two shades of the same thing - and the
 * publication-review copy, whose comment still said "same pattern as
 * ShareWorkflowModal", drew its steps on the label rung while the pattern it
 * named had moved to the Button rung.
 *
 * So this is the source-level guard: the header is DEFINED once, and every
 * surface that shows steps takes it from there. The rendered shape and states
 * are asserted in ModalStepIndicator.test.tsx.
 *
 * ## What this can and cannot see
 *
 * A copy is recognised by what it DRAWS, and the rule below is deliberately a
 * disjunction of the shapes real copies took. It caught both survivors, but it
 * is a heuristic over source text: a header that maps `stages`, tracks
 * `status === 'completed'` and pads differently is a copy this cannot name. The
 * per-call-site checks below are the exact half; treat the scan as a net, not a
 * proof.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

const FRONTEND = join(__dirname, '../../..');

/** Where the step header is allowed to be written. */
const THE_ONE_DEFINITION = join('components', 'ui', 'ModalStepIndicator.tsx');

/**
 * What a step header looks like in source: a row built by mapping a sequence,
 * with a branch on whether one of them is finished.
 *
 * Deliberately NOT keyed on `aria-current="step"` - the publication-review copy
 * never had it, so that signature would have declared the app clean while two
 * copies were live. A copy is recognised by what it DRAWS.
 *
 * The "finished" clause is a vocabulary rather than two variable names, and the
 * pill padding is gone from the rule entirely: a drifting copy is MORE likely to
 * change its padding than to keep it, so requiring `px-3 py-1.5` let a live copy
 * through (CredentialWizard, which writes `status === "completed"` and was
 * missed for exactly that reason).
 */
const STEP_HEADER = (source: string) =>
  /\b(steps|stages|phases|credentialStates)\??\.map\(/i.test(source)
  && /isCompleted|isDone|isFinished|status === ['"](completed|done)['"]/.test(source);

/**
 * Rows that LOOK like a step header to the rule above and are deliberately not
 * this component. Each one needs a reason, not just a path: an entry here is a
 * decision, and an undocumented one is how the eighth copy hid.
 */
const NOT_A_STEP_HEADER = [
  {
    file: join('app', 'shared', 'components', 'StepIndicator.tsx'),
    // The full-PAGE wizard indicator (/local-mcp, settings/developers): a title
    // and a description under each step, a percentage progress bar, and a
    // separate stacked layout under `sm`. Flattening it into a modal header
    // would delete those, and a page is not a dialog.
    why: 'the full-page wizard indicator, which carries descriptions and a progress bar',
  },
  {
    file: join('components', 'credentials', 'CredentialWizard.tsx'),
    // Its items are SERVICES to configure, each with its own logo and its own
    // per-service state, not the steps of the dialog. Folding it in would turn
    // a service checklist into a step row. It takes the shared RUNG, not the
    // shared component.
    why: 'a per-service checklist, not the steps of the dialog',
  },
];

/**
 * Every surface that shows a multi-step header, and must not re-draw one.
 *
 * <p>The generation modal used to be on this list and is gone: the studio replaced it, and a
 * composer has no steps to show. Removing the row rather than pointing it somewhere else is the
 * honest edit - there is no surface left to hold to the rule.
 */
const CALL_SITES = [
  ['the agent modal', join('components', 'chat', 'CreateAgentModal.tsx')],
  ['the data-source modal', join('components', 'chat', 'CreateDataSourceModal.tsx')],
  ['the interface modal', join('components', 'chat', 'CreateInterfaceModal.tsx')],
  ['the add-column modal', join('components', 'data-table', 'modals', 'AddColumnModal.tsx')],
  ['the project modal', join('components', 'project', 'ProjectMultiStepModal.tsx')],
  ['the share-workflow modal', join('components', 'workflow', 'ShareWorkflowModal.tsx')],
  [
    'the publication review',
    join('app', '[locale]', 'app', 'settings', 'publication-review', 'components', 'PublicationComparisonView.tsx'),
  ],
] as const;

function tsxFilesUnder(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === '.next' || entry === '__tests__') continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) tsxFilesUnder(full, out);
    else if (entry.endsWith('.tsx')) out.push(full);
  }
  return out;
}

describe('the multi-step header lives in one place', () => {
  it('is defined once, and nowhere else in the app', () => {
    const sources = [
      // Every directory that can hold a component, not just the two obvious
      // ones: a header parked under contexts/ or hooks/ was invisible before.
      ...['components', 'app', 'contexts', 'hooks'].flatMap((dir) => tsxFilesUnder(join(FRONTEND, dir))),
    ];

    const allowed = new Set([
      THE_ONE_DEFINITION,
      ...NOT_A_STEP_HEADER.map((entry) => entry.file),
    ]);

    const definitions = sources
      .filter((file) => STEP_HEADER(readFileSync(file, 'utf8')))
      .map((file) => relative(FRONTEND, file))
      .filter((file) => !allowed.has(file));

    expect(definitions).toEqual([]);
  });

  it('still finds the one place it IS defined, so the rule cannot rot into matching nothing', () => {
    // Without this, deleting a clause from STEP_HEADER would make the scan above
    // pass by finding no header anywhere, which is the failure mode a negative
    // assertion cannot see.
    expect(STEP_HEADER(readFileSync(join(FRONTEND, THE_ONE_DEFINITION), 'utf8'))).toBe(true);
  });

  for (const { file, why } of NOT_A_STEP_HEADER) {
    it(`keeps the documented exception on the shared rung: ${why}`, () => {
      // It is not the shared component, so nothing forces its shape. Pin the
      // rung so it cannot drift back below the steps it sits beside.
      const source = readFileSync(join(FRONTEND, file), 'utf8');
      expect(source).not.toMatch(/px-3 py-1\.5 rounded-(md|full|2xl)/);
    });
  }

  for (const [what, file] of CALL_SITES) {
    const source = readFileSync(join(FRONTEND, file), 'utf8');

    it(`${what} takes its steps from the shared header`, () => {
      expect(source).toContain('ModalStepIndicator');
      expect(source).toMatch(/from ['"]@\/components\/ui\/ModalStepIndicator['"]/);
    });

    it(`${what} does not hand-write the step pill`, () => {
      // Every rung, including the CURRENT one: a hand-written pill that happens
      // to be on the right rung today is still a copy, and the earlier version
      // of this assertion listed every rung except the one in use.
      expect(source).not.toMatch(/px-3 py-1\.5 rounded-(sm|md|lg|xl|2xl|3xl|full)/);
    });
  }
});
