/**
 * Service-status contract, shared by the probe on ops-host, the Next.js status
 * route and every product surface (the public /status page, the user-menu dot,
 * the in-app incident strip).
 *
 * Two independent sources feed it, and NEITHER is allowed to fake the other:
 *  - machine truth: the `lc-status-probe` unit on ops-host rolls up what Prometheus
 *    already scrapes (`up`) and what blackbox_exporter already probes
 *    (`probe_success`) into one JSON file. It keeps measuring while the cluster is
 *    down, because it runs outside the pods.
 *  - human truth: incident.io's status-page Widget API (ongoing incidents and
 *    maintenances, declared by us or by an alert).
 *
 * When a source is missing, its half of the payload is simply absent, and the
 * payload says which (`dataAvailable`, and `incidentsSource` below). "No data"
 * must NEVER render as "operational": that is the one lie a status page cannot
 * afford.
 */

/** Live state of one public component. */
export type ComponentState = 'operational' | 'degraded' | 'down' | 'unknown';

/** Headline state of the platform, worst-of across components and incidents. */
export type OverallState =
  | 'operational'
  | 'maintenance'
  | 'degraded'
  | 'major_outage'
  | 'unknown';

/** One UTC day of availability for a component. `ratio` is null when unmeasured. */
export interface UptimeDay {
  /** UTC calendar day, `YYYY-MM-DD`. */
  date: string;
  /** Availability over that day, 0..1, or null when no sample exists. */
  ratio: number | null;
  /**
   * True for a day the collector did not watch live but reconstructed from
   * monitoring history at startup. Same meaning, coarser resolution, so the day
   * says so on hover rather than passing as a measured one.
   */
  reconstructed?: boolean;
}

/** A public component as shown on the status page (one row). */
export interface StatusComponent {
  /** Stable id, also the component name declared in incident.io. */
  id: string;
  /** Human label, English (the public page is English-only). */
  name: string;
  state: ComponentState;
  /** Availability across the whole window, 0..1, or null when nothing measured. */
  uptimeRatio: number | null;
  /** Oldest-first, one entry per UTC day in the window. */
  history: UptimeDay[];
}

/** What kind of event an incident.io entry is. */
export type IncidentKind = 'incident' | 'maintenance' | 'scheduled_maintenance';

/**
 * One incident.io entry, normalised.
 *
 * The Widget API's full schema is not published; incident.io documents the three
 * arrays and that each entry carries an id, a name, a status, the last update
 * message, affected components and dates. So the mapper reads exactly those, via
 * a few field aliases, and drops anything it cannot recognise instead of guessing.
 */
export interface StatusIncident {
  id: string;
  kind: IncidentKind;
  name: string;
  /** Provider status token, lowercased (e.g. investigating). Empty when absent. */
  status: string;
  /** Latest update text, or null. */
  message: string | null;
  /** Permalink on the status page, or null. */
  url: string | null;
  /** Names of affected components (may be empty). */
  components: string[];
  /** ISO start, or null. */
  startedAt: string | null;
  /** ISO end (scheduled maintenance), or null. */
  endsAt: string | null;
}

/**
 * Where the incident half of the payload stands.
 *
 * The three cases are NOT interchangeable, and conflating two of them is how a
 * status page ends up lying:
 *  - `ok`: incident.io answered. An empty incident list then means something.
 *  - `unreachable`: it is configured and did not answer, so we cannot claim
 *    there is no incident, and the headline must not read as a full all-clear.
 *  - `not_configured`: this deployment has no incident feed at all. Absence of
 *    incident data is then a property of the setup, not a blind spot, so it must
 *    not degrade a headline our own probes did verify.
 */
export type IncidentsSource = 'ok' | 'unreachable' | 'not_configured';

/** The whole payload served by `GET /api/status`. */
export interface ServiceStatus {
  overall: OverallState;
  /** Empty when the probe is unreachable. */
  components: StatusComponent[];
  /** Empty when incident.io has nothing ongoing, or did not answer. */
  incidents: StatusIncident[];
  /** Length of the uptime window in days (0 when there is no history). */
  windowDays: number;
  /** ISO timestamp the probe wrote its file, or null. */
  generatedAt: string | null;
  /**
   * How often the collector samples, in seconds, as reported BY the collector,
   * or null when it did not say. The page describes its own method from this
   * rather than from a number retyped in the copy.
   */
  sampleIntervalSeconds: number | null;
  /** True when the probe answered. False means: no components, no uptime. */
  dataAvailable: boolean;
  /** State of the incident feed. See IncidentsSource. */
  incidentsSource: IncidentsSource;
  /** Canonical externally-hosted status page, or null when unconfigured. */
  statusPageUrl: string | null;
}
