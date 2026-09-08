/**
 * The frequency catalogue every schedule picker in the app composes cron from.
 *
 * <p>These entries, and the little cron builders and parsers under them, were written for
 * the workflow builder's schedule-trigger inspector and lived inside it. The agenda now
 * offers the same choice when a workflow is created from an empty slot, and the one thing
 * that must not happen is two catalogues: a preset added in one place and missing in the
 * other reads as a bug in whichever surface the user tried second, and a cron shape parsed
 * differently by the two makes the SAME schedule show a different frequency depending on
 * where it is opened.
 *
 * <p>Cron strings are 5-field Unix style. The backend validator and the orchestrator's
 * `validate-cron` endpoint remain the single source of truth for validity and for the
 * human description; nothing here re-parses an expression to decide whether it is legal.
 * The parsing that IS here answers one narrow question - "which entry should the dropdown
 * show for this cron?" - and falls back to `advanced` whenever it cannot tell.
 *
 * <p>The i18n keys stay where they already are (`workflowBuilder.inspector.scheduleTrigger`,
 * `freq_<value>` and `group_<group>`), so both surfaces name a frequency identically and no
 * locale file grows a second copy of thirty strings.
 */

export type FrequencyKind = 'preset' | 'daily-custom' | 'weekly-custom' | 'monthly-custom' | 'advanced';
export type FrequencyGroup = 'minutes' | 'hours' | 'daily' | 'weekly' | 'monthly' | 'advanced';

export interface FrequencyOption {
  /** i18n key suffix and stable identifier. */
  value: string;
  group: FrequencyGroup;
  kind: FrequencyKind;
  /** Fixed cron, for preset entries only. */
  cron?: string;
}

export const FREQUENCIES: FrequencyOption[] = [
  // Minutes
  { value: 'every_minute',     group: 'minutes', kind: 'preset', cron: '* * * * *' },
  { value: 'every_5_minutes',  group: 'minutes', kind: 'preset', cron: '*/5 * * * *' },
  { value: 'every_15_minutes', group: 'minutes', kind: 'preset', cron: '*/15 * * * *' },
  { value: 'every_30_minutes', group: 'minutes', kind: 'preset', cron: '*/30 * * * *' },
  // Hours
  { value: 'every_hour',       group: 'hours',   kind: 'preset', cron: '0 * * * *' },
  { value: 'every_2_hours',    group: 'hours',   kind: 'preset', cron: '0 */2 * * *' },
  { value: 'every_3_hours',    group: 'hours',   kind: 'preset', cron: '0 */3 * * *' },
  { value: 'every_6_hours',    group: 'hours',   kind: 'preset', cron: '0 */6 * * *' },
  { value: 'every_12_hours',   group: 'hours',   kind: 'preset', cron: '0 */12 * * *' },
  // Daily
  { value: 'every_day_midnight', group: 'daily', kind: 'preset', cron: '0 0 * * *' },
  { value: 'every_day_9am',      group: 'daily', kind: 'preset', cron: '0 9 * * *' },
  { value: 'every_day_noon',     group: 'daily', kind: 'preset', cron: '0 12 * * *' },
  { value: 'every_day_6pm',      group: 'daily', kind: 'preset', cron: '0 18 * * *' },
  { value: 'daily_custom',       group: 'daily', kind: 'daily-custom' },
  // Weekly
  { value: 'every_monday',  group: 'weekly', kind: 'preset', cron: '0 9 * * 1' },
  { value: 'every_weekday', group: 'weekly', kind: 'preset', cron: '0 9 * * 1-5' },
  { value: 'every_weekend', group: 'weekly', kind: 'preset', cron: '0 10 * * 6' },
  { value: 'weekly_custom', group: 'weekly', kind: 'weekly-custom' },
  // Monthly
  { value: 'first_of_month',  group: 'monthly', kind: 'preset', cron: '0 9 1 * *' },
  { value: 'monthly_custom',  group: 'monthly', kind: 'monthly-custom' },
  // Advanced (free-text)
  { value: 'advanced', group: 'advanced', kind: 'advanced', cron: '0 0 * * *' },
];

export const FREQUENCY_BY_VALUE: Record<string, FrequencyOption> = Object.fromEntries(
  FREQUENCIES.map((f) => [f.value, f]),
);

export const GROUP_ORDER: FrequencyGroup[] = ['minutes', 'hours', 'daily', 'weekly', 'monthly', 'advanced'];

/**
 * Safe default applied when switching INTO a configurator entry from one whose current
 * cron does not match the new shape.
 */
export const DEFAULT_CRONS: Record<FrequencyKind, string> = {
  'preset': '0 * * * *',
  'daily-custom': '0 9 * * *',
  'weekly-custom': '0 9 * * 1',
  'monthly-custom': '0 9 1 * *',
  'advanced': '0 0 * * *',
};

/** Cron weekday numbering: 0 is Sunday. */
export const WEEKDAYS = [
  { value: '1', labelKey: 'weekdayMon' },
  { value: '2', labelKey: 'weekdayTue' },
  { value: '3', labelKey: 'weekdayWed' },
  { value: '4', labelKey: 'weekdayThu' },
  { value: '5', labelKey: 'weekdayFri' },
  { value: '6', labelKey: 'weekdaySat' },
  { value: '0', labelKey: 'weekdaySun' },
];

export function clamp(n: number, min: number, max: number): number {
  if (isNaN(n)) return min;
  return Math.min(max, Math.max(min, n));
}

/**
 * Which dropdown entry a cron string should show.
 *
 * <p>The ONLY parsing the frontend does, and it is used to restore UI state, never to
 * decide whether an expression is valid. Anything that does not match a known shape lands
 * on `advanced`, where the free-text field appears with the expression prefilled.
 */
export function cronToFrequencyValue(cron: string): string {
  if (!cron || cron.trim() === '') return 'advanced';
  const trimmed = cron.trim();

  // 1) Exact preset match
  for (const f of FREQUENCIES) {
    if (f.kind === 'preset' && f.cron === trimmed) return f.value;
  }

  const parts = trimmed.split(/\s+/);
  if (parts.length !== 5) return 'advanced';
  const [minute, hour, dom, month, dow] = parts;

  const isNum = (s: string) => /^\d+$/.test(s);

  // 2) Daily: M H * * *
  if (isNum(minute) && isNum(hour) && dom === '*' && month === '*' && dow === '*') {
    return 'daily_custom';
  }
  // 3) Weekly: M H * * D[,D...] (digits + commas)
  if (isNum(minute) && isNum(hour) && dom === '*' && month === '*' && /^[\d,]+$/.test(dow)) {
    return 'weekly_custom';
  }
  // 4) Monthly: M H D * *
  if (isNum(minute) && isNum(hour) && isNum(dom) && month === '*' && dow === '*') {
    return 'monthly_custom';
  }
  return 'advanced';
}

/** Picker state read back out of a cron string, with defaults when the shape differs. */
export function parseDailyCron(cron: string): { hour: number; minute: number } {
  const m = cron.trim().match(/^(\d+)\s+(\d+)\s+\*\s+\*\s+\*$/);
  if (!m) return { hour: 9, minute: 0 };
  return { hour: clamp(parseInt(m[2], 10), 0, 23), minute: clamp(parseInt(m[1], 10), 0, 59) };
}

export function parseWeeklyCron(cron: string): { hour: number; minute: number; days: string[] } {
  const m = cron.trim().match(/^(\d+)\s+(\d+)\s+\*\s+\*\s+([\d,]+)$/);
  if (!m) return { hour: 9, minute: 0, days: ['1'] };
  const days = m[3].split(',').map((d) => d.trim()).filter((d) => WEEKDAYS.some((w) => w.value === d));
  return {
    hour: clamp(parseInt(m[2], 10), 0, 23),
    minute: clamp(parseInt(m[1], 10), 0, 59),
    days: days.length > 0 ? days : ['1'],
  };
}

export function parseMonthlyCron(cron: string): { hour: number; minute: number; dayOfMonth: number } {
  const m = cron.trim().match(/^(\d+)\s+(\d+)\s+(\d+)\s+\*\s+\*$/);
  if (!m) return { hour: 9, minute: 0, dayOfMonth: 1 };
  return {
    hour: clamp(parseInt(m[2], 10), 0, 23),
    minute: clamp(parseInt(m[1], 10), 0, 59),
    dayOfMonth: clamp(parseInt(m[3], 10), 1, 31),
  };
}

export function buildDailyCron(hour: number, minute: number): string {
  return `${minute} ${hour} * * *`;
}

export function buildWeeklyCron(hour: number, minute: number, days: string[]): string {
  const safeDays = days.length > 0 ? days : ['1'];
  return `${minute} ${hour} * * ${safeDays.join(',')}`;
}

export function buildMonthlyCron(hour: number, minute: number, dayOfMonth: number): string {
  return `${minute} ${hour} ${dayOfMonth} * *`;
}
