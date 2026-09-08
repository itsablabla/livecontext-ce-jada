/**
 * The one place that maps a budget period mode to its wording key.
 *
 * <p>Three surfaces read the same figure and must name its period identically:
 * the {@link BudgetChip} on workflow / application / agent cards, the spend
 * gauge in the run panel, and the workflow board card.
 *
 * <p><b>The fallback is the caller's, not this helper's, and the two owners
 * disagree.</b> A workflow with no mode set resets MONTHLY (the column defaults
 * to 'monthly' and the server's rollover rule falls back to monthly for an
 * unknown value). An agent with no mode set NEVER resets ('cumulative' is its
 * column default, and its resolver treats every unknown value as "never
 * reset"). Guessing one default here would label the other owner's chip with a
 * reset that never happens, so the caller passes the fallback it actually
 * implements.
 */
export type BudgetPeriodMode = 'monthly' | 'weekly' | 'cumulative';

/**
 * The period this figure is ACTUALLY reported over: the stored mode when it is
 * one we implement, the caller's own default when it is not.
 *
 * <p>THE one resolution, because everything downstream has to agree on it. The
 * popover used to resolve it twice: the badge through `budgetPeriodLabelName`
 * (which falls back) and `neverResets` straight off the raw `periodMode` (which
 * does not). On a stored mode nobody implements, say `quarterly`, with an agent
 * fallback of `cumulative`, the badge read "total" while the prose beside it
 * offered a reset date and told a blocked owner to wait for an allowance that
 * never comes back.
 */
export function resolveBudgetPeriod(
  mode?: string | null,
  fallback: BudgetPeriodMode = 'monthly',
): BudgetPeriodMode {
  switch ((mode || fallback).toLowerCase()) {
    case 'weekly':
      return 'weekly';
    case 'cumulative':
      return 'cumulative';
    case 'monthly':
      return 'monthly';
    default:
      // An unrecognised value is not a period: fall back to what the caller
      // says its own default is rather than inventing a monthly reset.
      return fallback;
  }
}

export function budgetPeriodLabelName(
  mode?: string | null,
  fallback: BudgetPeriodMode = 'monthly',
): string {
  switch (resolveBudgetPeriod(mode, fallback)) {
    case 'weekly':
      return 'periodWeek';
    case 'cumulative':
      return 'periodTotal';
    default:
      return 'periodMonth';
  }
}

/** Same label, prefixed for a root translator (`useTranslations()` with no namespace). */
export function budgetPeriodLabelKey(
  mode?: string | null,
  fallback: BudgetPeriodMode = 'monthly',
): string {
  return `budget.${budgetPeriodLabelName(mode, fallback)}`;
}

/**
 * Will a {@link BudgetChip} with these figures render anything at all?
 *
 * <p>Call sites MUST gate their separator / wrapper row on this, not on a null
 * check. The server sends a rolled-over period spend that is `0`, never null,
 * for a workflow that has spent nothing this period, and an agent's
 * `credits_consumed` is `NOT NULL DEFAULT 0`. So `spent != null` is true for
 * essentially every row, and a wrapper gated on it renders a lone separator
 * followed by nothing, on the default state of the busiest list pages.
 */
export function budgetChipHasContent(spent?: number | null, cap?: number | null): boolean {
  return (spent ?? 0) > 0 || (cap ?? 0) > 0;
}

/**
 * The reset cadence to SHOW for an agent, which is only its stored mode when
 * that mode is true of it.
 *
 * <p>An agent's counter is reset lazily, by the resolver that enforces its
 * budget, and that resolver returns early when there is no budget. So an
 * uncapped agent carrying `budgetResetMode: 'monthly'` holds a LIFETIME total
 * that nothing has ever reset, and labelling it "this month" states a reset
 * that has never happened. Reporting it as a total is the only reading that is
 * true of every uncapped agent.
 *
 * <p>Workflows do not need this: their figure is rolled over server-side before
 * it is sent, whether or not a cap is set.
 */
export function agentDisplayPeriodMode(
  cap?: number | null,
  storedMode?: string | null,
): string | undefined {
  return (cap ?? 0) > 0 ? (storedMode ?? undefined) : undefined;
}

/**
 * Should the run bar draw the period gauge next to the run's cost?
 *
 * <p>It also returns {@code overBudget}, which the bar no longer reads: the
 * chip paints its own tone from the same figures, so the caller only needs the
 * WHETHER. Kept because the two answers come from one comparison and splitting
 * them invites a second, drifting copy of it.
 *
 * <p>The two figures are NOT interchangeable and the distinction is the whole
 * point of the period: `costCredits` is what THIS run has spent across every
 * epoch, and a pinned workflow keeps one run for months, so its lifetime total
 * passes the cap long before the period spend does. Painting the total red
 * against the cap announces a stop that has not happened.
 *
 * <p>`viewingEpoch` suppresses the gauge because the figure beside it is then
 * one epoch's cost, and a period total sitting next to a single epoch's cost
 * invites the reader to compare two things that do not belong together.
 *
 * <p>A `null` period spend means "this run does not count against the cap" (a
 * builder test fire): it shows its cost with no gauge.
 */
export function runCostGaugeState(input: {
  budgetCredits?: number | null;
  periodSpentCredits?: number | null;
  viewingEpoch: boolean;
}): { showGauge: boolean; overBudget: boolean } {
  const cap = input.budgetCredits ?? 0;
  const spent = input.periodSpentCredits;
  const showGauge = cap > 0 && !input.viewingEpoch && spent != null;
  return { showGauge, overBudget: showGauge && (spent as number) >= cap };
}

/**
 * Does this cadence never roll over?
 *
 * <p>Exists because "your workflow resumes next period" is the natural thing to
 * tell a blocked user, and it is FALSE for a lifetime cap: there is no next
 * period, and the workflow stays stopped until its owner changes the cap or the
 * cadence. Sending someone away to wait for a reset that will never arrive is
 * the worst thing a blocked-run message can do, so the two cases get two
 * sentences and this predicate picks between them.
 */
export function budgetCapNeverResets(mode?: string | null): boolean {
  return (mode ?? '').toLowerCase() === 'cumulative';
}
