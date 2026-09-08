/**
 * Tests for what the /status page SAYS.
 *
 * Every function here turns data into a claim a reader will believe, so each
 * case below is a claim that must not drift: which day counts as green, how many
 * days are covered, how often we measure, when an incident started, and which
 * date the axis is labelled with once the narrow layout hides two thirds of the
 * bars.
 */
import { describe, it, expect } from 'vitest';
import {
  axisStartLabels,
  hasMeasuredHistory,
  barClass,
  barTitle,
  barTone,
  componentColor,
  incidentClass,
  incidentFeedNote,
  incidentMeta,
  incidentWhen,
  leadCopy,
  methodCopy,
  oldestVisibleIndex,
} from '../presentation';
import type { IncidentsSource, ServiceStatus, StatusIncident, UptimeDay } from '../types';

function status(overrides: Partial<ServiceStatus> = {}): ServiceStatus {
  return {
    overall: 'operational',
    // One MEASURED day by default: the base fixture stands for a healthy page,
    // and an empty history is now the "nothing measured yet" case with its own
    // cases below.
    components: [
      {
        id: 'api',
        name: 'API',
        state: 'operational',
        uptimeRatio: 1,
        history: [{ date: '2026-09-04', ratio: 1 }],
      },
    ],
    incidents: [],
    windowDays: 90,
    generatedAt: null,
    sampleIntervalSeconds: 60,
    dataAvailable: true,
    incidentsSource: 'ok',
    statusPageUrl: null,
    ...overrides,
  };
}

function incident(overrides: Partial<StatusIncident> = {}): StatusIncident {
  return {
    id: 'i1',
    kind: 'incident',
    name: 'Something',
    status: 'investigating',
    message: null,
    url: null,
    components: [],
    startedAt: null,
    endsAt: null,
    ...overrides,
  };
}

function days(...ratios: Array<number | null>): UptimeDay[] {
  return ratios.map((ratio, index) => ({
    date: `2026-06-${String(index + 1).padStart(2, '0')}`,
    ratio,
  }));
}

describe('barTone', () => {
  it('is green only for a day that was available the whole day', () => {
    expect(barTone({ date: 'd', ratio: 1 })).toBe('up');
    // 99.9% is NOT green: painting a bad day green is the failure mode here.
    expect(barTone({ date: 'd', ratio: 0.999 })).toBe('part');
    expect(barTone({ date: 'd', ratio: 0 })).toBe('down');
    expect(barTone({ date: 'd', ratio: null })).toBe('none');
  });
});

describe('barClass', () => {
  it('leaves an unmeasured day untoned, so it reads as a hole', () => {
    expect(barClass({ date: 'd', ratio: null }, false)).toBe('st-bar');
  });

  it('marks the days the narrow layout hides', () => {
    expect(barClass({ date: 'd', ratio: 1 }, true)).toBe('st-bar is-up is-old');
    expect(barClass({ date: 'd', ratio: 0 }, false)).toBe('st-bar is-down');
  });
});

describe('barTitle', () => {
  it('states the figure, and says when a day was reconstructed', () => {
    expect(barTitle({ date: '2026-09-05', ratio: 1 })).toBe('Sep 5: 100.00% available');
    expect(barTitle({ date: '2026-09-05', ratio: null })).toBe('Sep 5: not measured');
    expect(barTitle({ date: '2026-09-05', ratio: 0.5, reconstructed: true })).toBe(
      'Sep 5: 50.00% available (reconstructed from monitoring history)',
    );
  });
});

describe('componentColor', () => {
  it('gives each state its own colour, unknown included', () => {
    expect(componentColor('operational')).toBe('var(--st-green)');
    expect(componentColor('degraded')).toBe('var(--st-amber)');
    expect(componentColor('down')).toBe('var(--st-red)');
    expect(componentColor('unknown')).toBe('var(--st-grey)');
  });
});

describe('incidentClass', () => {
  it('tones an incident, a running maintenance and a future one differently', () => {
    expect(incidentClass('incident')).toContain('is-incident');
    expect(incidentClass('maintenance')).toContain('is-maintenance');
    expect(incidentClass('scheduled_maintenance')).toContain('is-scheduled');
  });
});

describe('incidentWhen', () => {
  it('reads a window, a start, an end, or nothing', () => {
    expect(
      incidentWhen(
        incident({ startedAt: '2026-09-08T01:00:00Z', endsAt: '2026-09-08T03:00:00Z' }),
      ),
    ).toBe('Sep 8, 2026, 01:00 UTC to Sep 8, 2026, 03:00 UTC');
    expect(incidentWhen(incident({ startedAt: '2026-09-05T18:40:00Z' }))).toBe(
      'Started Sep 5, 2026, 18:40 UTC',
    );
    expect(
      incidentWhen(
        incident({ kind: 'scheduled_maintenance', startedAt: '2026-09-08T01:00:00Z' }),
      ),
    ).toBe('Scheduled for Sep 8, 2026, 01:00 UTC');
    expect(incidentWhen(incident({ endsAt: '2026-09-08T03:00:00Z' }))).toBe(
      'Until Sep 8, 2026, 03:00 UTC',
    );
    expect(incidentWhen(incident())).toBeNull();
  });
});

describe('incidentMeta', () => {
  it('joins what is known and stays empty when nothing is', () => {
    expect(
      incidentMeta(incident({ startedAt: '2026-09-05T18:40:00Z', components: ['API', 'Workflows'] })),
    ).toBe('Started Sep 5, 2026, 18:40 UTC - Affected: API, Workflows');
    expect(incidentMeta(incident({ components: ['API'] }))).toBe('Affected: API');
    expect(incidentMeta(incident())).toBe('');
  });
});

describe('leadCopy', () => {
  it('promises an uptime window only when there is uptime to show', () => {
    expect(leadCopy(status())).toContain('Uptime covers the last 90 days');

    // No data: the page must not advertise 90 days of history three lines above
    // the sentence saying it has none.
    for (const broken of [
      status({ dataAvailable: false, components: [], windowDays: 0 }),
      status({ components: [], windowDays: 0 }),
      status({ windowDays: 0 }),
      status({ components: [{ id: 'api', name: 'API', state: 'unknown', uptimeRatio: null, history: [] }] }),
    ]) {
      expect(leadCopy(broken)).not.toContain('Uptime covers');
    }
  });
});

describe('hasMeasuredHistory', () => {
  it('is false for the collector\'s day-one payload', () => {
    // The real shape on a first tick, and while Prometheus is unreachable: all
    // components published, every one unknown, no history, window still 90.
    // Counting components would call this "measured" and promise 90 days of
    // uptime above six "Not measured" rows.
    const dayOne = status({
      components: ['website', 'api', 'workflows'].map((id) => ({
        id,
        name: id,
        state: 'unknown' as const,
        uptimeRatio: null,
        history: [],
      })),
    });
    expect(hasMeasuredHistory(dayOne)).toBe(false);
    expect(leadCopy(dayOne)).not.toContain('Uptime covers');
  });

  it('is false when every recorded day is a gap', () => {
    const gaps = status({
      components: [
        {
          id: 'api',
          name: 'API',
          state: 'unknown',
          uptimeRatio: null,
          history: [
            { date: '2026-09-03', ratio: null },
            { date: '2026-09-04', ratio: null },
          ],
        },
      ],
    });
    expect(hasMeasuredHistory(gaps)).toBe(false);
  });

  it('is true as soon as one day on one component was measured', () => {
    const measured = status({
      components: [
        {
          id: 'api',
          name: 'API',
          state: 'operational',
          uptimeRatio: 1,
          history: [
            { date: '2026-09-03', ratio: null },
            { date: '2026-09-04', ratio: 1 },
          ],
        },
      ],
    });
    expect(hasMeasuredHistory(measured)).toBe(true);
    expect(leadCopy(measured)).toContain('Uptime covers the last 90 days');
  });

  it('is false when the probe did not answer at all', () => {
    expect(hasMeasuredHistory(status({ dataAvailable: false, components: [] }))).toBe(false);
  });
});

describe('methodCopy', () => {
  it('takes the cadence from the collector, not from the copy', () => {
    expect(methodCopy(status({ sampleIntervalSeconds: 60 }))).toContain('measured every minute');
    expect(methodCopy(status({ sampleIntervalSeconds: 30 }))).toContain('measured every 30 seconds');
    expect(methodCopy(status({ sampleIntervalSeconds: 300 }))).toContain('measured every 5 minutes');
  });

  it('says nothing about cadence when the collector did not report one', () => {
    const copy = methodCopy(status({ sampleIntervalSeconds: null }));
    expect(copy).toContain('Components are measured from a host outside');
    expect(copy).not.toMatch(/every\s/);
  });
});

describe('incidentFeedNote', () => {
  it('warns only when a configured feed failed to answer', () => {
    const cases: Array<[IncidentsSource, boolean]> = [
      ['unreachable', true],
      ['ok', false],
      ['not_configured', false],
    ];
    for (const [source, expected] of cases) {
      expect(incidentFeedNote(status({ incidentsSource: source })) !== null, source).toBe(expected);
    }
  });
});

describe('axisStartLabels', () => {
  it('labels each layout with the first day it actually shows', () => {
    const window = days(...Array.from({ length: 40 }, () => 1));
    const labels = axisStartLabels(window);

    expect(labels.wide).toBe('Jun 1');
    // The narrow layout hides all but the last 30 bars, so its axis starts on
    // the 11th day of a 40-day window, not on the first.
    expect(labels.narrow).toBe('Jun 11');
    expect(oldestVisibleIndex(40)).toBe(10);
  });

  it('uses the same label for both layouts when everything fits', () => {
    const labels = axisStartLabels(days(1, 1, 1));
    expect(labels).toEqual({ wide: 'Jun 1', narrow: 'Jun 1' });
    expect(oldestVisibleIndex(3)).toBe(0);
  });

  it('has nothing to label with no days', () => {
    expect(axisStartLabels([])).toEqual({ wide: '', narrow: '' });
  });
});
