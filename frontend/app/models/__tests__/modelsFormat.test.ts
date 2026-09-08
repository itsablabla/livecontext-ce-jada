import { describe, it, expect } from 'vitest';
import { CATALOG_MODELS } from '../_components/modelsData';
import { formatMonthKey, formatPrice, formatReleased, formatTokens, groupDigits, monthLabel } from '../_components/modelsFormat';

// These formatters are the reason the page's stat tiles and its rows can print the
// same number the same way. They used to be duplicated, and the copies disagreed:
// the hero said the cheapest input was $0.0375 while that model's row said $0.04.

describe('formatPrice', () => {
  it('drops the decimals on a whole dollar amount', () => {
    expect(formatPrice(2)).toBe('$2');
    expect(formatPrice(50)).toBe('$50');
  });

  it('pads to two decimals when two decimals are exact', () => {
    expect(formatPrice(0.2)).toBe('$0.20');
    expect(formatPrice(4.4)).toBe('$4.40');
    expect(formatPrice(1.32)).toBe('$1.32');
    expect(formatPrice(0.75)).toBe('$0.75');
  });

  it('keeps the extra digits rather than round a price we do not charge', () => {
    // Cohere's smallest model really is $0.0375 per 1M input tokens. Rounding it
    // to $0.04 invents a rate, and it is exactly what made the tile and the row
    // contradict each other.
    expect(formatPrice(0.0375)).toBe('$0.0375');
    expect(formatPrice(0.005)).toBe('$0.005');
  });

  it('agrees with itself on every price in the catalogue', () => {
    // The invariant that matters: whatever a row prints, the tile prints too.
    const cheapest = CATALOG_MODELS.reduce((min, m) => Math.min(min, m.priceIn), Number.POSITIVE_INFINITY);
    const row = CATALOG_MODELS.find((m) => m.priceIn === cheapest)!;
    expect(formatPrice(cheapest)).toBe(formatPrice(row.priceIn));
    for (const model of CATALOG_MODELS) {
      expect(formatPrice(model.priceIn), model.id).toMatch(/^\$\d+(\.\d+)?$/);
      expect(formatPrice(model.priceOut), model.id).toMatch(/^\$\d+(\.\d+)?$/);
    }
  });
});

describe('formatTokens', () => {
  it('reads a million-token window as M and everything else as K', () => {
    expect(formatTokens(1_048_576)).toBe('1M');
    expect(formatTokens(2_000_000)).toBe('2M');
    expect(formatTokens(1_050_000)).toBe('1.1M');
    expect(formatTokens(991_808)).toBe('992K');
    expect(formatTokens(500_000)).toBe('500K');
    expect(formatTokens(200_000)).toBe('200K');
    expect(formatTokens(8_192)).toBe('8K');
    expect(formatTokens(512)).toBe('512');
  });
});

describe('formatReleased', () => {
  it('renders a full date, and a month-only announcement without inventing a day', () => {
    expect(formatReleased('2026-08-13')).toBe('Aug 13, 2026');
    expect(formatReleased('2025-10-15')).toBe('Oct 15, 2025');
    expect(formatReleased('2026-03')).toBe('Mar 2026');
    // No leading zero on the day: "Sep 1, 2026", not "Sep 01, 2026".
    expect(formatReleased('2026-09-01')).toBe('Sep 1, 2026');
  });

  it('formats every date in the catalogue into something readable', () => {
    for (const model of CATALOG_MODELS) {
      expect(formatReleased(model.released), model.id).toMatch(/^[A-Z][a-z]{2} (\d{1,2}, )?\d{4}$/);
    }
  });
});

describe('formatMonthKey and monthLabel', () => {
  it('labels a timeline column with its month and short year', () => {
    expect(formatMonthKey('2026-08')).toBe("Aug '26");
    expect(formatMonthKey('2023-12')).toBe("Dec '23");
  });

  it('is 1-indexed, matching the ISO month', () => {
    expect(monthLabel(1)).toBe('Jan');
    expect(monthLabel(12)).toBe('Dec');
    expect(monthLabel(13)).toBe('');
    expect(monthLabel(0)).toBe('');
  });
});

describe('groupDigits', () => {
  it('groups thousands without going through the browser locale', () => {
    // `toLocaleString()` would print 1.048.576 for a German visitor on an English
    // page. This is fixed grouping on purpose.
    expect(groupDigits(1_048_576)).toBe('1,048,576');
    expect(groupDigits(200_000)).toBe('200,000');
    expect(groupDigits(512)).toBe('512');
  });
});
