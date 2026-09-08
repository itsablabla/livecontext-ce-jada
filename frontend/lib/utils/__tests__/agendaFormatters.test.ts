import { describe, expect, it, vi, beforeEach } from 'vitest';
import {
  formatDayInZone,
  formatDayNarrowInZone,
  formatDayShortInZone,
  formatFullDate,
  formatMonthTitle,
  formatTimeInZone,
  weekdayHeaders,
} from '../agendaTime';

/**
 * The display half of the calendar's date handling.
 *
 * Every one of these formatters had no test, which mattered for a specific reason rather
 * than as a coverage number: the project's hard rule is that dates follow the APP locale,
 * never the browser's, and a regression to a bare `toLocaleTimeString()` would keep every
 * existing suite green while quietly showing a French-browser user French dates in the
 * English app. Both halves are asserted here - the locale is honoured, and the zone is the
 * one asked for rather than the machine's.
 */

vi.mock('@/lib/utils/locale', () => ({
  getClientLocale: () => 'en',
}));

/** Noon UTC, so no formatter can land on an adjacent day by accident. */
const NOON_UTC = new Date('2026-09-03T12:00:00Z');

describe('display formatters', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('honour the requested ZONE, not the machine one', () => {
    it('formatTimeInZone shifts with the zone', () => {
      // The same instant, three clocks. Reading the machine zone instead would make the
      // calendar disagree with the column its chip sits in.
      expect(formatTimeInZone(NOON_UTC, 'UTC')).toBe('12:00');
      expect(formatTimeInZone(NOON_UTC, 'Europe/Paris')).toBe('14:00');
      expect(formatTimeInZone(NOON_UTC, 'Asia/Kolkata')).toBe('17:30');
    });

    it('formatDayInZone can name a different day than UTC does', () => {
      // 23:30 UTC is already tomorrow in Tokyo. A day header that ignored the zone would
      // file the occurrence under the wrong cell.
      const lateEvening = new Date('2026-09-03T23:30:00Z');

      expect(formatDayInZone(lateEvening, 'UTC')).toContain('3');
      expect(formatDayInZone(lateEvening, 'Asia/Tokyo')).toContain('4');
    });

    it('the narrow and short day headers resolve the SAME day as the full one', () => {
      // The three tiers are one header at three widths; if they disagreed about which day
      // they name, a column would say "Thu" at one size and "Fri" at another - and a
      // narrower screen is exactly where nobody would think to check. 23:30 UTC is already
      // tomorrow in Tokyo, which is the case that separates a zone-aware formatter from one
      // that quietly reads the host clock.
      const lateEvening = new Date('2026-09-03T23:30:00Z');

      for (const format of [formatDayInZone, formatDayShortInZone, formatDayNarrowInZone]) {
        expect(format(lateEvening, 'UTC', 'en')).toContain('3');
        expect(format(lateEvening, 'Asia/Tokyo', 'en')).toContain('4');
      }
    });

    it('each tier is shorter than the one it replaces, which is the only reason it exists', () => {
      const noon = new Date('2026-09-03T12:00:00Z');
      const full = formatDayInZone(noon, 'UTC', 'en');
      const short = formatDayShortInZone(noon, 'UTC', 'en');
      const narrow = formatDayNarrowInZone(noon, 'UTC', 'en');

      expect(narrow.length).toBeLessThan(short.length);
      expect(short.length).toBeLessThan(full.length);
      // And each still says which weekday it is, which is the fact a column header carries.
      expect(narrow).toMatch(/T/);
      expect(short).toContain('Thu');
    });

    it('formatMonthTitle can name a different month than UTC does', () => {
      // The last instant of a month in UTC is the first of the next one further east.
      const monthEdge = new Date('2026-08-31T23:00:00Z');

      expect(formatMonthTitle(monthEdge, 'UTC')).toBe('August 2026');
      expect(formatMonthTitle(monthEdge, 'Asia/Tokyo')).toBe('September 2026');
    });

    it('formatFullDate spells the day out in the requested zone', () => {
      expect(formatFullDate(NOON_UTC, 'UTC')).toContain('Thursday');
      expect(formatFullDate(NOON_UTC, 'UTC')).toContain('2026');
    });
  });

  describe('honour the APP locale, never the browser one', () => {
    it('formats in the locale passed explicitly', () => {
      // A French-BROWSER user reading the /en app must see English, and vice versa. The
      // formatters take the locale rather than reading it from the environment.
      expect(formatMonthTitle(NOON_UTC, 'UTC', 'fr')).toBe('septembre 2026');
      expect(formatMonthTitle(NOON_UTC, 'UTC', 'de')).toBe('September 2026');
      expect(formatFullDate(NOON_UTC, 'UTC', 'fr')).toContain('jeudi');
    });

    it('falls back to the app locale, not to the browser default', () => {
      // With no explicit locale the value comes from getClientLocale (URL prefix, then the
      // NEXT_LOCALE cookie, then `en`) - mocked to `en` here. A bare toLocale*() would read
      // the browser instead, which is the regression this pins.
      expect(formatMonthTitle(NOON_UTC, 'UTC')).toBe('September 2026');
    });

    it('weekdayHeaders are localised and ordered by the chosen week start', () => {
      const mondayFirst = weekdayHeaders('UTC', 1, 'en');
      const sundayFirst = weekdayHeaders('UTC', 0, 'en');

      expect(mondayFirst).toHaveLength(7);
      expect(mondayFirst[0]).toMatch(/^Mon/);
      expect(sundayFirst[0]).toMatch(/^Sun/);
      expect(weekdayHeaders('UTC', 1, 'fr')[0]).toMatch(/^lun/);
    });
  });

  describe('caching does not leak between callers', () => {
    it('two zones asked in sequence keep their own answers', () => {
      // The formatters are cached by (locale, zone, options) because a month view rebuilt
      // three hundred of them on every pointer move during a drag. A key missing any of
      // those would hand the second caller the first one's formatter.
      expect(formatTimeInZone(NOON_UTC, 'UTC')).toBe('12:00');
      expect(formatTimeInZone(NOON_UTC, 'Europe/Paris')).toBe('14:00');
      expect(formatTimeInZone(NOON_UTC, 'UTC')).toBe('12:00');
    });

    it('two locales asked in sequence keep their own answers', () => {
      expect(formatMonthTitle(NOON_UTC, 'UTC', 'en')).toBe('September 2026');
      expect(formatMonthTitle(NOON_UTC, 'UTC', 'fr')).toBe('septembre 2026');
      expect(formatMonthTitle(NOON_UTC, 'UTC', 'en')).toBe('September 2026');
    });

    it('two shapes in the same zone and locale keep their own answers', () => {
      // The options are part of the key: without them, whichever formatter was built first
      // would answer for both and one of the two outputs would silently change shape.
      expect(formatTimeInZone(NOON_UTC, 'UTC', 'en')).toBe('12:00');
      expect(formatMonthTitle(NOON_UTC, 'UTC', 'en')).toBe('September 2026');
      expect(formatTimeInZone(NOON_UTC, 'UTC', 'en')).toBe('12:00');
    });
  });
});
