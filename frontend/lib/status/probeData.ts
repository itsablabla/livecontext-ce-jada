import type { ComponentState, StatusComponent, UptimeDay } from './types';

/**
 * Parsing of the file written by the `lc-status-probe` unit on ops-host.
 *
 * Defensive on purpose, in the same spirit as `lib/changelog/githubReleases.ts`:
 * the status page must never crash, and must never invent a value, because of a
 * half-written or older-format file. A malformed entry is dropped; a malformed
 * payload yields null, which the caller renders as "live data unavailable".
 *
 * File shape (see deploy/exporters/status-probe.py, single source of truth):
 *   { generatedAt, windowDays, sampleIntervalSeconds, components: [
 *       { id, name, state,
 *         history: [{ date: "YYYY-MM-DD", ok, total, backfilled? }] } ] }
 * `ok` is a SUM OF PER-TICK AVAILABILITY (0..1 each), so it can be fractional;
 * `total` is the number of ticks recorded that day. `backfilled` marks a day
 * reconstructed at startup rather than watched tick by tick, and
 * `sampleIntervalSeconds` is the cadence the page quotes instead of hardcoding.
 */

const COMPONENT_STATES: readonly ComponentState[] = ['operational', 'degraded', 'down', 'unknown'];
const DAY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export interface ProbeSnapshot {
  components: StatusComponent[];
  windowDays: number;
  generatedAt: string | null;
  /** Sampling cadence the collector reports, or null when it did not say. */
  sampleIntervalSeconds: number | null;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function asState(value: unknown): ComponentState {
  return typeof value === 'string' && (COMPONENT_STATES as readonly string[]).includes(value)
    ? (value as ComponentState)
    : 'unknown';
}

/** Finite number >= 0, else null. Rejects NaN/Infinity/strings. */
function asCount(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
}

/**
 * One day per entry, oldest first, duplicates collapsed (last wins). Days whose
 * counters are unusable keep their slot with a null ratio rather than vanishing:
 * a gap in the bar chart is the honest rendering of an unmeasured day.
 */
function mapHistory(value: unknown): { days: UptimeDay[]; ok: number; total: number } {
  if (!Array.isArray(value)) return { days: [], ok: 0, total: 0 };

  const byDate = new Map<string, UptimeDay>();
  let okSum = 0;
  let totalSum = 0;

  for (const entry of value) {
    const raw = asRecord(entry);
    if (!raw) continue;
    const date = typeof raw.date === 'string' && DAY_PATTERN.test(raw.date) ? raw.date : '';
    if (!date) continue;

    const total = asCount(raw.total);
    const ok = asCount(raw.ok);
    if (total === null || ok === null || total === 0) {
      byDate.set(date, { date, ratio: null });
      continue;
    }
    // Clamp: a counter drift must not produce 103% uptime.
    const ratio = Math.min(1, ok / total);
    // `backfilled` marks a day rebuilt from monitoring history at startup rather
    // than watched tick by tick. Carried through so the UI can say so.
    byDate.set(date, raw.backfilled === true ? { date, ratio, reconstructed: true } : { date, ratio });
    okSum += Math.min(ok, total);
    totalSum += total;
  }

  const days = Array.from(byDate.values()).sort((a, b) => a.date.localeCompare(b.date));
  return { days, ok: okSum, total: totalSum };
}

/**
 * Map the probe file into component rows. Returns null when the payload is not a
 * usable snapshot at all (not an object, or no `components` array), so the caller
 * can distinguish "no data" from "everything is fine".
 */
export function mapProbePayload(payload: unknown): ProbeSnapshot | null {
  const root = asRecord(payload);
  if (!root || !Array.isArray(root.components)) return null;

  const components: StatusComponent[] = [];
  let widestHistory = 0;

  for (const entry of root.components) {
    const raw = asRecord(entry);
    if (!raw) continue;
    const id = typeof raw.id === 'string' ? raw.id.trim() : '';
    const name = typeof raw.name === 'string' ? raw.name.trim() : '';
    if (!id || !name) continue;

    const { days, ok, total } = mapHistory(raw.history);
    widestHistory = Math.max(widestHistory, days.length);
    components.push({
      id,
      name,
      state: asState(raw.state),
      uptimeRatio: total > 0 ? ok / total : null,
      history: days,
    });
  }

  const declaredWindow =
    typeof root.windowDays === 'number' && Number.isInteger(root.windowDays) && root.windowDays > 0
      ? root.windowDays
      : 0;

  const interval = root.sampleIntervalSeconds;
  return {
    components,
    windowDays: declaredWindow || widestHistory,
    generatedAt: typeof root.generatedAt === 'string' ? root.generatedAt : null,
    sampleIntervalSeconds:
      typeof interval === 'number' && Number.isFinite(interval) && interval > 0 ? interval : null,
  };
}

/**
 * Uptime as a display string, e.g. "99.92%". Null ratio has no number to show.
 *
 * Two decimals round a short outage away, so a ratio that is not exactly 1 never
 * prints as "100.00%": five minutes of downtime over 90 days would otherwise
 * read as a perfect score sitting right next to an amber bar, and the number is
 * the one thing a reader quotes back at you.
 */
export function formatUptime(ratio: number | null): string | null {
  if (ratio === null || !Number.isFinite(ratio)) return null;
  const clamped = Math.min(1, Math.max(0, ratio));
  const percent = (clamped * 100).toFixed(2);
  if (clamped < 1 && percent === '100.00') return '99.99%';
  return `${percent}%`;
}

/** `YYYY-MM-DD` for a UTC instant. */
function utcDay(date: Date): string {
  return date.toISOString().slice(0, 10);
}

/**
 * Expand a component's history into a CONTINUOUS, oldest-first window ending
 * today (UTC), one entry per day, `ratio: null` where nothing was recorded.
 *
 * Rendering the raw history would silently misalign the bars: a day the probe
 * missed would close the gap instead of showing it, so an outage-shaped hole in
 * the data would read as uninterrupted uptime. The window makes gaps visible and
 * keeps every component's strip the same length.
 */
export function buildDayWindow(
  history: UptimeDay[],
  windowDays: number,
  now: Date = new Date(),
): UptimeDay[] {
  if (!Number.isInteger(windowDays) || windowDays <= 0) return [];

  const byDate = new Map<string, UptimeDay>();
  for (const day of history) byDate.set(day.date, day);

  const days: UptimeDay[] = [];
  const cursor = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  cursor.setUTCDate(cursor.getUTCDate() - (windowDays - 1));

  for (let i = 0; i < windowDays; i += 1) {
    const date = utcDay(cursor);
    days.push(byDate.get(date) ?? { date, ratio: null });
    cursor.setUTCDate(cursor.getUTCDate() + 1);
  }
  return days;
}
