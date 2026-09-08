import type { IncidentKind, StatusIncident } from './types';

/**
 * Parsing of incident.io's status-page **Widget API** payload.
 *
 * What incident.io documents publicly: the endpoint is unauthenticated, lives on
 * a URL you read off the status page's "Widget API" settings, is "highly
 * cacheable" (they ask that it be proxied through your own backend with caching,
 * which `app/api/status/route.ts` does), and returns three arrays -
 * `ongoing_incidents`, `in_progress_maintenances`, `scheduled_maintenances` -
 * whose entries carry an id, a name, a status, the last update message, the
 * affected components and dates.
 *
 * The exhaustive field list is NOT published, so this mapper:
 *  - reads only the fields above, each through a few plausible aliases;
 *  - keeps `status` as an opaque lowercased token (never switch on a value we
 *    have not seen; the UI shows it as a label);
 *  - drops an entry that has no id or no name, and returns null for a payload
 *    that is not an object, rather than inventing structure.
 * Widen the aliases only after seeing a real payload in the Widget API preview.
 */

export interface IncidentSnapshot {
  incidents: StatusIncident[];
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

/** First non-blank string among the given keys, else null. */
function pickString(raw: Record<string, unknown>, keys: string[]): string | null {
  for (const key of keys) {
    const value = raw[key];
    if (typeof value === 'string' && value.trim() !== '') return value.trim();
  }
  return null;
}

/**
 * Latest update text. incident.io exposes "the last update message"; depending on
 * the shape that is either a nested object or a list of updates, so both are read
 * and the LAST list entry wins (updates are chronological).
 */
function pickMessage(raw: Record<string, unknown>): string | null {
  const direct = pickString(raw, ['last_update_message', 'message', 'description']);
  if (direct) return direct;

  for (const key of ['last_update', 'latest_update', 'most_recent_update']) {
    const nested = asRecord(raw[key]);
    if (nested) {
      const text = pickString(nested, ['message', 'text', 'body']);
      if (text) return text;
    }
  }

  const updates = raw.updates;
  if (Array.isArray(updates)) {
    for (let i = updates.length - 1; i >= 0; i -= 1) {
      const nested = asRecord(updates[i]);
      const text = nested ? pickString(nested, ['message', 'text', 'body']) : null;
      if (text) return text;
    }
  }
  return null;
}

/** Component names, accepting both `["API"]` and `[{ name: "API" }]`. */
function pickComponents(raw: Record<string, unknown>): string[] {
  const names: string[] = [];
  for (const key of ['affected_components', 'components', 'component_impacts']) {
    const list = raw[key];
    if (!Array.isArray(list)) continue;
    for (const entry of list) {
      if (typeof entry === 'string' && entry.trim() !== '') {
        names.push(entry.trim());
        continue;
      }
      const nested = asRecord(entry);
      if (!nested) continue;
      const name =
        pickString(nested, ['name', 'component_name']) ??
        (() => {
          const inner = asRecord(nested.component);
          return inner ? pickString(inner, ['name']) : null;
        })();
      if (name) names.push(name);
    }
    if (names.length > 0) break;
  }
  return Array.from(new Set(names));
}

function mapEntry(entry: unknown, kind: IncidentKind): StatusIncident | null {
  const raw = asRecord(entry);
  if (!raw) return null;

  const id = pickString(raw, ['id', 'external_id', 'reference']);
  const name = pickString(raw, ['name', 'title', 'summary']);
  if (!id || !name) return null;

  const status = pickString(raw, ['status', 'state', 'incident_status']);
  return {
    id,
    kind,
    name,
    status: status ? status.toLowerCase() : '',
    message: pickMessage(raw),
    url: pickString(raw, ['url', 'permalink', 'link', 'html_url']),
    components: pickComponents(raw),
    startedAt: pickString(raw, [
      'started_at',
      'starts_at',
      'scheduled_start_at',
      'scheduled_for',
      'created_at',
    ]),
    endsAt: pickString(raw, ['ends_at', 'scheduled_end_at', 'scheduled_until', 'resolved_at']),
  };
}

/**
 * Map a Widget API payload. Ordering is deliberate: live incidents first, then
 * maintenance already running, then what is only scheduled - the same order of
 * urgency the surfaces render in.
 */
export function mapWidgetPayload(payload: unknown): IncidentSnapshot | null {
  const root = asRecord(payload);
  if (!root) return null;

  const groups: Array<[string, IncidentKind]> = [
    ['ongoing_incidents', 'incident'],
    ['in_progress_maintenances', 'maintenance'],
    ['scheduled_maintenances', 'scheduled_maintenance'],
  ];

  const incidents: StatusIncident[] = [];
  for (const [key, kind] of groups) {
    const list = root[key];
    if (!Array.isArray(list)) continue;
    for (const entry of list) {
      const mapped = mapEntry(entry, kind);
      if (mapped) incidents.push(mapped);
    }
  }
  return { incidents };
}

/** An entry that is happening now (as opposed to merely scheduled). */
export function isLive(incident: StatusIncident): boolean {
  return incident.kind !== 'scheduled_maintenance';
}
