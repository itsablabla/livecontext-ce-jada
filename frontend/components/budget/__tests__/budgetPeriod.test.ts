import { describe, it, expect } from 'vitest';
import {
  agentDisplayPeriodMode,
  budgetCapNeverResets,
  budgetChipHasContent,
  budgetPeriodLabelKey,
  budgetPeriodLabelName,
  resolveBudgetPeriod,
  runCostGaugeState,
} from '../budgetPeriod';

describe('budgetChipHasContent', () => {
  // Every card list gates a separator (or a whole wrapper row) on this. The
  // obvious `spent != null` is WRONG and was: the server sends a rolled-over
  // period spend of 0, never null, and an agent's credits_consumed is NOT NULL
  // DEFAULT 0. So the null check is true for essentially every row, and the
  // wrapper renders a lone "." followed by nothing, on the default state of the
  // busiest pages in the app.

  it('is false for a resource that has no cap and has spent nothing', () => {
    expect(budgetChipHasContent(0, null)).toBe(false);
    expect(budgetChipHasContent(null, null)).toBe(false);
    expect(budgetChipHasContent(undefined, undefined)).toBe(false);
    expect(budgetChipHasContent(0, 0)).toBe(false);
  });

  it('is true as soon as there is something to say', () => {
    expect(budgetChipHasContent(0.5, null)).toBe(true);
    expect(budgetChipHasContent(0, 1000)).toBe(true);
    expect(budgetChipHasContent(20, 1000)).toBe(true);
  });

  it('treats a zero cap as no cap, matching the server rule', () => {
    // The backend reads cap <= 0 as unlimited; a chip that appeared for a 0 cap
    // would announce a limit that does not exist.
    expect(budgetChipHasContent(0, 0)).toBe(false);
    expect(budgetChipHasContent(0, -5)).toBe(false);
  });
});

describe('budgetPeriodLabelName', () => {
  it('names each known period', () => {
    expect(budgetPeriodLabelName('monthly')).toBe('periodMonth');
    expect(budgetPeriodLabelName('weekly')).toBe('periodWeek');
    expect(budgetPeriodLabelName('cumulative')).toBe('periodTotal');
    expect(budgetPeriodLabelName('MONTHLY')).toBe('periodMonth');
  });

  it('uses the CALLER fallback for an empty mode, because the two owners disagree', () => {
    // A workflow with no mode resets monthly; an agent with no mode never
    // resets. One shared default would promise a reset that never happens.
    expect(budgetPeriodLabelName(null)).toBe('periodMonth');
    expect(budgetPeriodLabelName(null, 'cumulative')).toBe('periodTotal');
    expect(budgetPeriodLabelName('', 'weekly')).toBe('periodWeek');
  });

  it('falls back rather than inventing a month for a value nobody defined', () => {
    expect(budgetPeriodLabelName('quarterly', 'cumulative')).toBe('periodTotal');
    expect(budgetPeriodLabelName('quarterly')).toBe('periodMonth');
  });

  it('prefixes the key for a root translator', () => {
    expect(budgetPeriodLabelKey('weekly')).toBe('budget.periodWeek');
    expect(budgetPeriodLabelKey(null, 'cumulative')).toBe('budget.periodTotal');
  });
});

describe('agentDisplayPeriodMode', () => {
  // An agent's counter is only ever reset by the resolver that enforces its
  // budget, and that resolver returns early when there is no budget. So the
  // stored cadence describes an uncapped agent's figure inaccurately, and the
  // list would announce a monthly reset that has never once happened.

  it('reports a capped agent with its real cadence', () => {
    expect(agentDisplayPeriodMode(1000, 'monthly')).toBe('monthly');
    expect(agentDisplayPeriodMode(1000, 'weekly')).toBe('weekly');
  });

  it('drops the cadence of an UNCAPPED agent, whose figure is a lifetime total', () => {
    expect(agentDisplayPeriodMode(null, 'monthly')).toBeUndefined();
    expect(agentDisplayPeriodMode(0, 'weekly')).toBeUndefined();
    expect(agentDisplayPeriodMode(undefined, 'monthly')).toBeUndefined();
  });

  it('dropping it makes the chip fall back to the caller default, which for agents is "total"', () => {
    // The two halves have to agree: dropping the mode is only correct because
    // the agent list passes fallbackPeriod="cumulative".
    expect(budgetPeriodLabelName(agentDisplayPeriodMode(null, 'monthly'), 'cumulative'))
      .toBe('periodTotal');
  });

  it('a capped agent with no stored cadence still falls back rather than inventing one', () => {
    expect(agentDisplayPeriodMode(1000, null)).toBeUndefined();
  });
});

describe('runCostGaugeState', () => {
  // The run bar shows two different numbers side by side and only ONE of them
  // may be compared to the cap. Getting that wrong tells a user their
  // automation has stopped when it has not.

  it('does NOT go red on a run whose lifetime cost passed the cap but whose period has not', () => {
    // The exact reason the period exists: a pinned workflow keeps one run for
    // months, so costCredits crosses the cap long before the period spend does.
    expect(runCostGaugeState({ budgetCredits: 10, periodSpentCredits: 2, viewingEpoch: false }))
      .toEqual({ showGauge: true, overBudget: false });
  });

  it('goes red when the PERIOD spend reaches the cap', () => {
    expect(runCostGaugeState({ budgetCredits: 10, periodSpentCredits: 10, viewingEpoch: false }).overBudget)
      .toBe(true);
    expect(runCostGaugeState({ budgetCredits: 10, periodSpentCredits: 11, viewingEpoch: false }).overBudget)
      .toBe(true);
  });

  it('hides the gauge while a single epoch is selected', () => {
    // The figure beside it is then one epoch's cost. A period total next to an
    // epoch total invites a comparison between two unrelated things.
    expect(runCostGaugeState({ budgetCredits: 10, periodSpentCredits: 10, viewingEpoch: true }))
      .toEqual({ showGauge: false, overBudget: false });
  });

  it('hides the gauge for a run that does not count against the cap', () => {
    // A builder test fire is sent a null period figure. It still shows what it
    // cost, it just does not pretend to eat the allowance.
    expect(runCostGaugeState({ budgetCredits: 10, periodSpentCredits: null, viewingEpoch: false }).showGauge)
      .toBe(false);
    expect(runCostGaugeState({ budgetCredits: 10, viewingEpoch: false }).showGauge).toBe(false);
  });

  it('hides the gauge when there is no cap to gauge against', () => {
    expect(runCostGaugeState({ periodSpentCredits: 42, viewingEpoch: false }).showGauge).toBe(false);
    expect(runCostGaugeState({ budgetCredits: 0, periodSpentCredits: 42, viewingEpoch: false }).showGauge)
      .toBe(false);
  });

  it('shows a zero-spend gauge, so a capped workflow announces its ceiling before spending', () => {
    expect(runCostGaugeState({ budgetCredits: 10, periodSpentCredits: 0, viewingEpoch: false }))
      .toEqual({ showGauge: true, overBudget: false });
  });
});

describe('budgetCapNeverResets', () => {
  // The blocked-run toast ends with "it resumes on its own next period". That
  // sentence is true of the two calendar cadences and a plain falsehood for a
  // lifetime cap, which has no next period: the workflow stays stopped until
  // its owner changes the cap. This predicate is what keeps the product from
  // telling a blocked user to sit and wait for a reset that never comes.

  it('is true only for the lifetime cadence', () => {
    expect(budgetCapNeverResets('cumulative')).toBe(true);
    expect(budgetCapNeverResets('CUMULATIVE')).toBe(true);
  });

  it('is false for the cadences that do roll over', () => {
    expect(budgetCapNeverResets('monthly')).toBe(false);
    expect(budgetCapNeverResets('weekly')).toBe(false);
  });

  it('is false when the cadence is unknown, matching the server fallback', () => {
    // An unrecognised value resets MONTHLY server-side, so promising a reset is
    // the accurate half here. Guessing "never resets" would be the one answer
    // that leaves the user with nothing to wait for.
    expect(budgetCapNeverResets(null)).toBe(false);
    expect(budgetCapNeverResets(undefined)).toBe(false);
    expect(budgetCapNeverResets('')).toBe(false);
    expect(budgetCapNeverResets('quarterly')).toBe(false);
  });
});

describe('resolveBudgetPeriod', () => {
  /**
   * The single resolution everything downstream reads. It exists because the
   * popover used to resolve the period TWICE - the badge with a fallback, the
   * "never resets" branch without - and the two disagreed on any stored mode
   * nobody implements.
   */
  it.each([
    ['weekly', 'monthly', 'weekly'],
    ['WEEKLY', 'monthly', 'weekly'],
    ['cumulative', 'monthly', 'cumulative'],
    ['monthly', 'cumulative', 'monthly'],
  ])('takes the stored mode %s when we implement it', (mode, fallback, expected) => {
    expect(resolveBudgetPeriod(mode, fallback as any)).toBe(expected);
  });

  it.each([
    [null, 'monthly', 'monthly'],
    [undefined, 'cumulative', 'cumulative'],
    ['', 'weekly', 'weekly'],
  ])('falls back on %s, because an empty mode means the owner never chose', (mode, fallback, expected) => {
    expect(resolveBudgetPeriod(mode as any, fallback as any)).toBe(expected);
  });

  it.each([
    ['quarterly', 'cumulative', 'cumulative'],
    ['quarterly', 'monthly', 'monthly'],
    ['daily', 'weekly', 'weekly'],
  ])('falls back on %s rather than inventing a cadence nobody implements', (mode, fallback, expected) => {
    // The case the two resolutions used to disagree on, and the reason this
    // function exists rather than a second ternary.
    expect(resolveBudgetPeriod(mode, fallback as any)).toBe(expected);
  });

  it('agrees with the label for every input, which is the whole point', () => {
    const EXPECTED = { monthly: 'periodMonth', weekly: 'periodWeek', cumulative: 'periodTotal' } as const;
    for (const mode of ['monthly', 'weekly', 'cumulative', 'quarterly', '', null, undefined]) {
      for (const fallback of ['monthly', 'weekly', 'cumulative'] as const) {
        expect(budgetPeriodLabelName(mode as any, fallback)).toBe(
          EXPECTED[resolveBudgetPeriod(mode as any, fallback)]
        );
      }
    }
  });

  it('says a cap never resets exactly when the resolved period is the lifetime one', () => {
    for (const mode of ['monthly', 'weekly', 'cumulative', 'quarterly', '', null, undefined]) {
      for (const fallback of ['monthly', 'weekly', 'cumulative'] as const) {
        expect(budgetCapNeverResets(resolveBudgetPeriod(mode as any, fallback))).toBe(
          resolveBudgetPeriod(mode as any, fallback) === 'cumulative'
        );
      }
    }
  });
});
