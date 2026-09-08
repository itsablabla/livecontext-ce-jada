import { describe, it, expect } from 'vitest';
import {
  DEFAULT_CRONS,
  FREQUENCIES,
  FREQUENCY_BY_VALUE,
  GROUP_ORDER,
  WEEKDAYS,
  buildDailyCron,
  buildMonthlyCron,
  buildWeeklyCron,
  clamp,
  cronToFrequencyValue,
  parseDailyCron,
  parseMonthlyCron,
  parseWeeklyCron,
} from '../cronFrequencies';

/**
 * The catalogue two surfaces now share.
 *
 * <p>It was moved out of the workflow builder's schedule inspector so the agenda could
 * offer the same choices, and the whole value of that move is that the two cannot drift.
 * Nothing pinned it at its old address, so a "harmless" edit to a cron string or a parsing
 * branch would change BOTH surfaces silently: the inspector and the calendar would agree
 * with each other and disagree with what the user picked last week.
 *
 * <p>The round-trip is the property that matters. What a picker BUILDS must be read back by
 * the parser as the same values and resolve to the same dropdown entry; break either half
 * and the symptom is a schedule that reopens showing something else.
 */
describe('the frequency catalogue', () => {
  it('gives every entry a group the dropdown renders and a kind the form can handle', () => {
    for (const option of FREQUENCIES) {
      expect(GROUP_ORDER, `${option.value} has an unrenderable group`).toContain(option.group);
      expect(DEFAULT_CRONS[option.kind], `${option.value} has no default cron`).toBeTruthy();
    }
  });

  it('gives every PRESET a cron, and every configurator none', () => {
    // A preset with no cron selects nothing; a configurator with one is a fixed schedule
    // wearing a picker.
    for (const option of FREQUENCIES) {
      if (option.kind === 'preset') expect(option.cron, option.value).toBeTruthy();
      else if (option.kind !== 'advanced') expect(option.cron, option.value).toBeUndefined();
    }
  });

  it('indexes every entry by its value, with no duplicates', () => {
    expect(Object.keys(FREQUENCY_BY_VALUE)).toHaveLength(FREQUENCIES.length);
  });

  it('numbers the weekdays the way cron does, Sunday at 0', () => {
    // The agenda seeds the weekly picker from `zonedParts().weekday`, which is 0 = Sunday.
    // A list numbering Monday 0 would schedule everything one day off.
    expect(WEEKDAYS.map((d) => d.value)).toEqual(['1', '2', '3', '4', '5', '6', '0']);
  });
});

describe('reading a cron back into a dropdown entry', () => {
  it('recognises a preset exactly', () => {
    expect(cronToFrequencyValue('*/15 * * * *')).toBe('every_15_minutes');
    expect(cronToFrequencyValue('0 9 * * 1-5')).toBe('every_weekday');
  });

  it('recognises the three custom shapes', () => {
    expect(cronToFrequencyValue('30 14 * * *')).toBe('daily_custom');
    expect(cronToFrequencyValue('30 14 * * 2,4')).toBe('weekly_custom');
    expect(cronToFrequencyValue('30 14 17 * *')).toBe('monthly_custom');
  });

  it('falls back to advanced rather than guessing', () => {
    // Anything it cannot name lands on the free-text field WITH the expression, never on a
    // picker that would rewrite it into a shape it never had.
    expect(cronToFrequencyValue('0 0 1 1 *')).toBe('advanced');
    expect(cronToFrequencyValue('0 9 * *')).toBe('advanced');
    expect(cronToFrequencyValue('')).toBe('advanced');
    expect(cronToFrequencyValue('   ')).toBe('advanced');
  });
});

describe('building and parsing round-trip', () => {
  it('reads back exactly what the daily picker built', () => {
    expect(parseDailyCron(buildDailyCron(14, 30))).toEqual({ hour: 14, minute: 30 });
  });

  it('reads back exactly what the weekly picker built, days included', () => {
    expect(parseWeeklyCron(buildWeeklyCron(8, 5, ['2', '4']))).toEqual({
      hour: 8, minute: 5, days: ['2', '4'],
    });
  });

  it('reads back exactly what the monthly picker built', () => {
    expect(parseMonthlyCron(buildMonthlyCron(23, 59, 17))).toEqual({
      hour: 23, minute: 59, dayOfMonth: 17,
    });
  });

  it('never builds a weekly cron with no day', () => {
    // `0 9 * * ` is not a cron. The picker refuses to deselect the last day, but the builder
    // is the last line: a caller passing [] must still get something that fires.
    expect(buildWeeklyCron(9, 0, [])).toBe('0 9 * * 1');
  });

  it('falls back to a usable time when the cron has another shape', () => {
    // These values are rendered into number inputs; NaN there is an empty control the user
    // cannot fix without retyping everything.
    expect(parseDailyCron('*/5 * * * *')).toEqual({ hour: 9, minute: 0 });
    expect(parseWeeklyCron('nonsense')).toEqual({ hour: 9, minute: 0, days: ['1'] });
    expect(parseMonthlyCron('')).toEqual({ hour: 9, minute: 0, dayOfMonth: 1 });
  });

  it('drops weekday numbers cron does not have', () => {
    expect(parseWeeklyCron('0 9 * * 1,9').days).toEqual(['1']);
  });
});

describe('clamp', () => {
  it('holds a typed number inside the range', () => {
    expect(clamp(99, 0, 23)).toBe(23);
    expect(clamp(-4, 0, 59)).toBe(0);
  });

  it('answers the minimum for a field the user emptied', () => {
    // `parseInt('')` is NaN, which reaches this from every number input on the pickers.
    expect(clamp(NaN, 1, 31)).toBe(1);
  });
});
