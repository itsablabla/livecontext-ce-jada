/**
 * Tests for the incident.io Widget API mapper.
 *
 * incident.io publishes the three arrays and the fields each entry carries, but
 * not an exhaustive schema, so the mapper is tolerant by design. These tests pin
 * that tolerance in both directions: known aliases are read, and anything the
 * mapper cannot identify is DROPPED rather than half-rendered - an incident card
 * with no title would be worse than no card.
 *
 * The other pinned property is `null` for a non-payload: the caller uses it to
 * decide whether it may claim "no ongoing incident" at all.
 */
import { describe, it, expect } from 'vitest';
import { isLive, mapWidgetPayload } from '../incidentIo';

describe('mapWidgetPayload', () => {
  it('maps the three arrays, live entries before merely scheduled ones', () => {
    const snapshot = mapWidgetPayload({
      ongoing_incidents: [{ id: 'i1', name: 'Chat is slow', status: 'investigating' }],
      in_progress_maintenances: [{ id: 'm1', name: 'DB upgrade', status: 'in_progress' }],
      scheduled_maintenances: [{ id: 's1', name: 'Planned reboot', status: 'scheduled' }],
    });

    expect(snapshot!.incidents.map((entry) => [entry.id, entry.kind])).toEqual([
      ['i1', 'incident'],
      ['m1', 'maintenance'],
      ['s1', 'scheduled_maintenance'],
    ]);
  });

  it('returns null when the payload is not an object', () => {
    expect(mapWidgetPayload(null)).toBeNull();
    expect(mapWidgetPayload('outage')).toBeNull();
    expect(mapWidgetPayload([])).toBeNull();
  });

  it('reports an empty list for a payload with no arrays we know', () => {
    // The endpoint answered, there is simply nothing ongoing: that IS information.
    expect(mapWidgetPayload({})).toEqual({ incidents: [] });
    expect(mapWidgetPayload({ ongoing_incidents: 'nope' })).toEqual({ incidents: [] });
  });

  it('drops an entry with no id or no name', () => {
    const snapshot = mapWidgetPayload({
      ongoing_incidents: [
        { name: 'No id here' },
        { id: 'i2' },
        'not-an-object',
        { id: 'i3', name: 'Kept' },
      ],
    });
    expect(snapshot!.incidents.map((entry) => entry.id)).toEqual(['i3']);
  });

  it('reads the title and status through their aliases, lowercasing the token', () => {
    const snapshot = mapWidgetPayload({
      ongoing_incidents: [{ reference: 'INC-4', title: 'Aliased', state: 'IDENTIFIED' }],
    });
    expect(snapshot!.incidents[0]).toMatchObject({
      id: 'INC-4',
      name: 'Aliased',
      status: 'identified',
    });
  });

  it('takes the latest update message, from whichever shape carries it', () => {
    const nested = mapWidgetPayload({
      ongoing_incidents: [{ id: 'a', name: 'A', last_update: { message: 'From nested' } }],
    });
    expect(nested!.incidents[0].message).toBe('From nested');

    const fromList = mapWidgetPayload({
      ongoing_incidents: [
        { id: 'b', name: 'B', updates: [{ message: 'older' }, { message: 'newest' }] },
      ],
    });
    // Updates are chronological, so the LAST one is the current word on it.
    expect(fromList!.incidents[0].message).toBe('newest');

    const none = mapWidgetPayload({ ongoing_incidents: [{ id: 'c', name: 'C' }] });
    expect(none!.incidents[0].message).toBeNull();
  });

  it('reads affected components as bare strings or as objects, deduplicated', () => {
    const asStrings = mapWidgetPayload({
      ongoing_incidents: [{ id: 'a', name: 'A', affected_components: ['API', 'API', 'Workflows'] }],
    });
    expect(asStrings!.incidents[0].components).toEqual(['API', 'Workflows']);

    const asObjects = mapWidgetPayload({
      ongoing_incidents: [
        {
          id: 'b',
          name: 'B',
          component_impacts: [{ component: { name: 'Sign-in' } }, { name: 'API' }, {}],
        },
      ],
    });
    expect(asObjects!.incidents[0].components).toEqual(['Sign-in', 'API']);
  });

  it('reads the permalink through its aliases', () => {
    const aliases = ['url', 'permalink', 'link', 'html_url'];
    for (const alias of aliases) {
      const snapshot = mapWidgetPayload({
        ongoing_incidents: [{ id: 'a', name: 'A', [alias]: 'https://status.example.com/x' }],
      });
      expect(snapshot!.incidents[0].url, alias).toBe('https://status.example.com/x');
    }
    expect(mapWidgetPayload({ ongoing_incidents: [{ id: 'a', name: 'A' }] })!.incidents[0].url).toBeNull();
  });

  it('falls back to created_at when no explicit start is given', () => {
    const snapshot = mapWidgetPayload({
      ongoing_incidents: [{ id: 'a', name: 'A', created_at: '2026-09-05T18:00:00Z' }],
    });
    expect(snapshot!.incidents[0].startedAt).toBe('2026-09-05T18:00:00Z');
  });

  it('prefers the explicit start over created_at', () => {
    const snapshot = mapWidgetPayload({
      ongoing_incidents: [
        { id: 'a', name: 'A', started_at: '2026-09-05T18:40:00Z', created_at: '2026-09-05T18:00:00Z' },
      ],
    });
    expect(snapshot!.incidents[0].startedAt).toBe('2026-09-05T18:40:00Z');
  });

  it('stops at the first component key that yields names', () => {
    // `affected_components` wins over the fallbacks rather than being merged with
    // them: a payload carrying both must not list the same component twice under
    // two spellings.
    const snapshot = mapWidgetPayload({
      ongoing_incidents: [
        {
          id: 'a',
          name: 'A',
          affected_components: [{ name: 'API' }],
          components: [{ name: 'Workflows' }],
        },
      ],
    });
    expect(snapshot!.incidents[0].components).toEqual(['API']);
  });

  it('moves on to the next key when the first carries no usable name', () => {
    const snapshot = mapWidgetPayload({
      ongoing_incidents: [{ id: 'a', name: 'A', affected_components: [{}], components: ['API'] }],
    });
    expect(snapshot!.incidents[0].components).toEqual(['API']);
  });

  it('reads start and end through their aliases, or leaves them null', () => {
    const snapshot = mapWidgetPayload({
      scheduled_maintenances: [
        {
          id: 's1',
          name: 'Window',
          scheduled_start_at: '2026-09-06T01:00:00Z',
          scheduled_end_at: '2026-09-06T03:00:00Z',
        },
      ],
      ongoing_incidents: [{ id: 'i1', name: 'Now' }],
    });
    const [incident, maintenance] = snapshot!.incidents;
    expect(incident.startedAt).toBeNull();
    expect(incident.endsAt).toBeNull();
    expect(maintenance.startedAt).toBe('2026-09-06T01:00:00Z');
    expect(maintenance.endsAt).toBe('2026-09-06T03:00:00Z');
  });
});

describe('isLive', () => {
  it('counts incidents and running maintenance, not a future window', () => {
    const entries = mapWidgetPayload({
      ongoing_incidents: [{ id: 'i', name: 'i' }],
      in_progress_maintenances: [{ id: 'm', name: 'm' }],
      scheduled_maintenances: [{ id: 's', name: 's' }],
    })!.incidents;

    expect(entries.filter(isLive).map((entry) => entry.id)).toEqual(['i', 'm']);
  });
});
