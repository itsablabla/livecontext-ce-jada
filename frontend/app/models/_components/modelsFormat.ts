/**
 * Formatters shared by the /models page and its catalogue component.
 *
 * They live here, in one place, because the page's stat tiles and the list rows
 * print the SAME values: when the two had their own formatters, the hero tile
 * said the cheapest input was `$0.0375` while that model's own row said `$0.04`.
 *
 * None of them go through `Intl`. A bare `toLocaleString()` / `toLocaleDateString()`
 * follows the BROWSER language, and hardcoding a locale is banned; this page is
 * outside the [locale] tree and its copy is English either way.
 */

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

export function monthLabel(month: number): string {
  return MONTHS[month - 1] ?? '';
}

/** '2026-08-13' -> 'Aug 13, 2026'; '2026-03' -> 'Mar 2026' (month-only announcements). */
export function formatReleased(iso: string): string {
  const [year, month, day] = iso.split('-');
  const name = monthLabel(Number(month));
  return day ? `${name} ${Number(day)}, ${year}` : `${name} ${year}`;
}

/** '2026-08' -> "Aug '26", the timeline's column label. */
export function formatMonthKey(key: string): string {
  const [year, month] = key.split('-');
  return `${monthLabel(Number(month))} '${year.slice(2)}`;
}

/** 1048576 -> '1M', 991808 -> '992K', 200000 -> '200K'. Exact value stays in the title. */
export function formatTokens(tokens: number): string {
  if (tokens >= 1_000_000) {
    const millions = Math.round(tokens / 100_000) / 10;
    return `${millions % 1 === 0 ? millions.toFixed(0) : millions.toFixed(1)}M`;
  }
  if (tokens >= 1_000) return `${Math.round(tokens / 1_000)}K`;
  return String(tokens);
}

/** '2023-12-11' -> '2023'. A helper, not a split() on a display string. */
export function formatYear(iso: string): string {
  return iso.slice(0, 4);
}

/** 1048576 -> '1,048,576'. Grouped by hand: `toLocaleString()` would follow the browser. */
export function groupDigits(value: number): string {
  return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/**
 * 2 -> '$2', 0.2 -> '$0.20', 4.4 -> '$4.40', 0.0375 -> '$0.0375'.
 *
 * Sub-cent prices per 1M tokens are real in this catalogue (Cohere's smallest
 * model is $0.0375), and rounding them to two decimals would print a price we
 * do not charge, so below a cent the extra digits are kept.
 */
export function formatPrice(usd: number): string {
  if (usd % 1 === 0) return `$${usd.toFixed(0)}`;
  // Round only when rounding loses nothing. `0.0375` must not print as `$0.04`:
  // that is a price we do not charge, and the stat tile and the row would then
  // disagree about the same model. `0.2` and `4.4` still print as `$0.20`/`$4.40`.
  if (Number(usd.toFixed(2)) !== usd) return `$${String(usd)}`;
  return `$${usd.toFixed(2)}`;
}
