import type { ComponentState, IncidentKind, OverallState, ServiceStatus } from './types';

/**
 * English display helpers for the PUBLIC /status page.
 *
 * The page renders outside the `[locale]` tree, where there is no next-intl
 * context (the LandingShell contract), so its copy is English like /about,
 * /changelog and /legal. Dates are formatted from an explicit UTC month table
 * rather than `Intl`/`toLocaleString`: that keeps the app-locale rule intact (no
 * hidden browser-locale formatting) and keeps server and client output identical,
 * which matters because the page renders on the server and then hydrates: a
 * locale- or timezone-dependent format would mismatch there.
 *
 * The IN-APP surfaces do not use these: they are translated through next-intl.
 */

const MONTHS = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];

/** `2026-09-05` -> `Sep 5`. Returns the input unchanged when unparseable. */
export function formatDayLabel(date: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) return date;
  const [, , month, day] = match;
  return `${MONTHS[Number(month) - 1] ?? month} ${Number(day)}`;
}

/** ISO instant -> `Sep 5, 2026, 14:03 UTC`. Returns null when unparseable. */
export function formatUtcInstant(iso: string | null): string | null {
  if (!iso) return null;
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return null;
  const month = MONTHS[parsed.getUTCMonth()];
  const day = parsed.getUTCDate();
  const year = parsed.getUTCFullYear();
  const hours = String(parsed.getUTCHours()).padStart(2, '0');
  const minutes = String(parsed.getUTCMinutes()).padStart(2, '0');
  return `${month} ${day}, ${year}, ${hours}:${minutes} UTC`;
}

export function componentStateLabel(state: ComponentState): string {
  switch (state) {
    case 'operational':
      return 'Operational';
    case 'degraded':
      return 'Degraded';
    case 'down':
      return 'Outage';
    default:
      return 'Not measured';
  }
}

export function incidentKindLabel(kind: IncidentKind): string {
  switch (kind) {
    case 'maintenance':
      return 'Maintenance in progress';
    case 'scheduled_maintenance':
      return 'Scheduled maintenance';
    default:
      return 'Incident';
  }
}

/** `investigating` -> `Investigating`. Provider tokens are shown, not mapped. */
export function statusTokenLabel(token: string): string {
  if (!token) return '';
  const spaced = token.replace(/[_-]+/g, ' ').trim();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

/**
 * Headline copy. The `unknown` case has two readings and they must not be
 * conflated: some components measured and fine but others not measured is
 * "partially" unavailable, whereas nothing measured at all means we genuinely
 * cannot say. Neither is allowed to read as "all systems operational".
 */
export function overallCopy(status: ServiceStatus): { title: string; sub: string } {
  const state: OverallState = status.overall;
  switch (state) {
    case 'operational':
      return {
        title: 'All systems operational',
        sub: 'We are not aware of any issue affecting the platform.',
      };
    case 'maintenance':
      return {
        title: 'Maintenance in progress',
        sub: 'A planned maintenance is running. Some features may be briefly unavailable.',
      };
    case 'degraded':
      return {
        title: 'Degraded performance',
        sub: 'Part of the platform is affected. Details below.',
      };
    case 'major_outage':
      return {
        title: 'Major outage',
        sub: 'One or more components are down. We are on it.',
      };
    default: {
      // `unknown` has three distinct causes and they read very differently to
      // someone in the middle of an outage. Saying "some components could not be
      // measured" when in fact all six were measured and only the incident feed
      // is down would send a reader looking for a problem that is not there.
      const allComponentsMeasured =
        status.components.length > 0 &&
        status.components.every((component) => component.state !== 'unknown');
      if (status.incidentsSource === 'unreachable' && allComponentsMeasured) {
        return {
          title: 'Cannot confirm an all-clear',
          sub: 'Every component we measure is responding, but our incident feed could not be reached, so a declared incident cannot be ruled out.',
        };
      }
      const someMeasured = status.components.some((component) => component.state !== 'unknown');
      return someMeasured
        ? {
            title: 'Status partially unavailable',
            sub: 'Some components could not be measured, so we cannot confirm a full all-clear.',
          }
        : {
            title: 'Live status unavailable',
            sub: 'Our monitoring could not be reached, so this page cannot confirm the platform state.',
          };
    }
  }
}
