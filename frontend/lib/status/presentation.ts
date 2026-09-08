import { formatDayLabel, formatUtcInstant } from './format';
import { formatUptime } from './probeData';
import type { ComponentState, ServiceStatus, StatusIncident, UptimeDay } from './types';

/**
 * Presentation logic for the public /status page.
 *
 * It lives here rather than inside `app/status/page.tsx` so it can be tested
 * directly: every function below encodes a claim the page makes to a reader
 * (this day was fine, this many days are covered, this is when it started), and
 * those are exactly the statements that must not drift.
 *
 * English, like the page (outside the `[locale]` tree there is no intl context).
 */

/** How many days the narrow layout shows; the CSS hides the rest (`.is-old`). */
export const MOBILE_WINDOW_DAYS = 30;

export const SEVERITY_COLOR: Record<'green' | 'amber' | 'red' | 'grey', string> = {
  green: 'var(--st-green)',
  amber: 'var(--st-amber)',
  red: 'var(--st-red)',
  grey: 'var(--st-grey)',
};

export function componentColor(state: ComponentState): string {
  if (state === 'operational') return SEVERITY_COLOR.green;
  if (state === 'degraded') return SEVERITY_COLOR.amber;
  if (state === 'down') return SEVERITY_COLOR.red;
  return SEVERITY_COLOR.grey;
}

/**
 * Bar tone. A day is green only when it was available all day: partial
 * availability gets amber even at 99.9%, because painting a bad day green is
 * worse than showing no bar at all. An unmeasured day gets no tone, so the
 * strip shows a hole rather than implying uptime.
 */
export function barTone(day: UptimeDay): 'up' | 'part' | 'down' | 'none' {
  if (day.ratio === null) return 'none';
  if (day.ratio >= 1) return 'up';
  return day.ratio > 0 ? 'part' : 'down';
}

export function barClass(day: UptimeDay, isOld: boolean): string {
  const tone = barTone(day);
  return `st-bar${tone === 'none' ? '' : ` is-${tone}`}${isOld ? ' is-old' : ''}`;
}

/** Hover text for one bar. Says when a day was reconstructed rather than watched. */
export function barTitle(day: UptimeDay): string {
  const label = formatDayLabel(day.date);
  if (day.ratio === null) return `${label}: not measured`;
  const suffix = day.reconstructed ? ' (reconstructed from monitoring history)' : '';
  return `${label}: ${formatUptime(day.ratio)} available${suffix}`;
}

export function incidentClass(kind: StatusIncident['kind']): string {
  if (kind === 'maintenance') return 'st-incident is-maintenance';
  if (kind === 'scheduled_maintenance') return 'st-incident is-scheduled';
  return 'st-incident is-incident';
}

/** When an entry happened, or is going to. Null when it carries no usable date. */
export function incidentWhen(incident: StatusIncident): string | null {
  const from = formatUtcInstant(incident.startedAt);
  const to = formatUtcInstant(incident.endsAt);
  if (from && to) return `${from} to ${to}`;
  if (from) {
    return incident.kind === 'scheduled_maintenance' ? `Scheduled for ${from}` : `Started ${from}`;
  }
  return to ? `Until ${to}` : null;
}

/** The dated / affected-components line under an entry. Empty when there is none. */
export function incidentMeta(incident: StatusIncident): string {
  return [
    incidentWhen(incident),
    incident.components.length > 0 ? `Affected: ${incident.components.join(', ')}` : null,
  ]
    .filter(Boolean)
    .join(' - ');
}

/**
 * True when at least one day, on at least one component, was actually measured.
 *
 * Counting components is not enough: the collector always publishes all six, and
 * on its first tick (or while Prometheus is unreachable) it publishes them with
 * `state: "unknown"` and an EMPTY history under a declared 90-day window. Reading
 * only those two numbers made the page promise "the last 90 days" above six
 * "Not measured" rows.
 */
export function hasMeasuredHistory(status: ServiceStatus): boolean {
  return (
    status.dataAvailable &&
    status.windowDays > 0 &&
    status.components.some((component) => component.history.some((day) => day.ratio !== null))
  );
}

/**
 * The lead paragraph. It only promises an uptime window when there is uptime to
 * show: claiming "the last 90 days" three lines above "no data" was the sort of
 * boilerplate that makes a reader stop trusting the rest of the page.
 */
export function leadCopy(status: ServiceStatus): string {
  if (!hasMeasuredHistory(status)) {
    return 'Availability of the LiveContext cloud, measured from outside the platform.';
  }
  return `Availability of the LiveContext cloud, measured from outside the platform. Uptime covers the last ${status.windowDays} days.`;
}

/** "every minute" / "every 30 seconds", from what the collector reports. */
function cadenceCopy(seconds: number): string {
  if (seconds === 60) return 'every minute';
  if (seconds % 60 === 0) return `every ${seconds / 60} minutes`;
  return `every ${seconds} seconds`;
}

/**
 * The method footnote. The cadence comes from the collector's own payload, so it
 * cannot drift from the unit file the way a hardcoded "every 30 seconds" did.
 */
export function methodCopy(status: ServiceStatus): string {
  const cadence = status.sampleIntervalSeconds ? ` ${cadenceCopy(status.sampleIntervalSeconds)}` : '';
  return (
    `Components are measured${cadence} from a host outside the application cluster, so the numbers ` +
    'keep being recorded during an outage. A day is green only when the component was available for ' +
    'the whole day; grey means it was not measured.'
  );
}

/**
 * Extra sentence when the incident feed is configured but did not answer. Silent
 * when there is no feed at all: that is a property of the deployment, not a gap
 * a reader needs warning about.
 */
export function incidentFeedNote(status: ServiceStatus): string | null {
  return status.incidentsSource === 'unreachable'
    ? 'Incident data could not be loaded, so this page cannot confirm that there is no ongoing incident.'
    : null;
}

/** Index from which bars are visible on the narrow layout. */
export function oldestVisibleIndex(dayCount: number): number {
  return Math.max(0, dayCount - MOBILE_WINDOW_DAYS);
}

/**
 * Start-of-axis labels: one for the full window, one for the narrow layout.
 *
 * Two labels rather than one because the CSS hides the older bars under 640px:
 * printing the 90-day start date next to a 30-bar strip put the axis two months
 * off. Each label is rendered in a span the same media query toggles.
 */
export function axisStartLabels(days: UptimeDay[]): { wide: string; narrow: string } {
  if (days.length === 0) return { wide: '', narrow: '' };
  const narrowStart = days[oldestVisibleIndex(days.length)] ?? days[0];
  return { wide: formatDayLabel(days[0].date), narrow: formatDayLabel(narrowStart.date) };
}
