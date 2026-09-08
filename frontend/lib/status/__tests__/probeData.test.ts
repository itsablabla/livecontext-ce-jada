/**
 * Tests for the probe-file mapper.
 *
 * The property that matters most here is the difference between "no data" and
 * "fine": a malformed or missing file must produce null (the page then says it
 * cannot report), never an empty-but-valid snapshot that would read as an
 * all-clear. The day window has the same shape of bug: a day the probe missed
 * must stay a visible hole, not close up and look like uptime.
 */
import { describe, it, expect } from 'vitest';
import { buildDayWindow, formatUptime, mapProbePayload } from '../probeData';

function payload(overrides: Record<string, unknown> = {}) {
  return {
    generatedAt: '2026-09-05T10:00:00Z',
    windowDays: 90,
    components: [
      {
        id: 'api',
        name: 'API',
        state: 'operational',
        history: [
          { date: '2026-09-03', ok: 1440, total: 1440 },
          { date: '2026-09-04', ok: 1430, total: 1440 },
        ],
      },
    ],
    ...overrides,
  };
}

describe('mapProbePayload', () => {
  it('maps a well-formed snapshot, uptime averaged over the whole window', () => {
    const snapshot = mapProbePayload(payload());

    expect(snapshot).not.toBeNull();
    expect(snapshot!.windowDays).toBe(90);
    expect(snapshot!.generatedAt).toBe('2026-09-05T10:00:00Z');
    expect(snapshot!.components).toHaveLength(1);

    const api = snapshot!.components[0];
    expect(api.id).toBe('api');
    expect(api.state).toBe('operational');
    // (1440 + 1430) / (1440 + 1440), i.e. the ratio of totals, not of ratios.
    expect(api.uptimeRatio).toBeCloseTo(2870 / 2880, 6);
    expect(api.history.map((day) => day.date)).toEqual(['2026-09-03', '2026-09-04']);
  });

  it('returns null for a payload that is not a snapshot at all', () => {
    expect(mapProbePayload(null)).toBeNull();
    expect(mapProbePayload('down')).toBeNull();
    expect(mapProbePayload([])).toBeNull();
    expect(mapProbePayload({})).toBeNull();
    expect(mapProbePayload({ components: 'nope' })).toBeNull();
  });

  it('reports an empty component list rather than failing, when the file says so', () => {
    // Distinct from null: the probe answered, it just has nothing to report yet.
    const snapshot = mapProbePayload({ components: [] });
    expect(snapshot).not.toBeNull();
    expect(snapshot!.components).toEqual([]);
  });

  it('drops entries with no id or no name instead of rendering a blank row', () => {
    const snapshot = mapProbePayload(
      payload({
        components: [
          { id: '', name: 'API', history: [] },
          { id: 'api', name: '   ', history: [] },
          'not-an-object',
          { id: 'ok', name: 'Fine', state: 'operational', history: [] },
        ],
      }),
    );
    expect(snapshot!.components.map((component) => component.id)).toEqual(['ok']);
  });

  it('treats an unrecognised state as unknown, never as operational', () => {
    const snapshot = mapProbePayload(
      payload({ components: [{ id: 'a', name: 'A', state: 'mostly-fine', history: [] }] }),
    );
    expect(snapshot!.components[0].state).toBe('unknown');
  });

  it('keeps a day with unusable counters as an explicit gap', () => {
    const snapshot = mapProbePayload(
      payload({
        components: [
          {
            id: 'a',
            name: 'A',
            state: 'operational',
            history: [
              { date: '2026-09-01', ok: 10, total: 0 },
              { date: '2026-09-02', ok: 'x', total: 10 },
              { date: '2026-09-03', ok: -1, total: 10 },
              { date: 'not-a-date', ok: 1, total: 1 },
            ],
          },
        ],
      }),
    );
    const component = snapshot!.components[0];
    expect(component.history).toEqual([
      { date: '2026-09-01', ratio: null },
      { date: '2026-09-02', ratio: null },
      { date: '2026-09-03', ratio: null },
    ]);
    // Nothing measurable: no uptime figure may be shown.
    expect(component.uptimeRatio).toBeNull();
  });

  it('clamps a drifted counter instead of showing more than 100% uptime', () => {
    const snapshot = mapProbePayload(
      payload({
        components: [
          { id: 'a', name: 'A', state: 'operational', history: [{ date: '2026-09-01', ok: 15, total: 10 }] },
        ],
      }),
    );
    expect(snapshot!.components[0].history[0].ratio).toBe(1);
    expect(snapshot!.components[0].uptimeRatio).toBe(1);
  });

  it('sorts days oldest-first and collapses a duplicated day', () => {
    const snapshot = mapProbePayload(
      payload({
        components: [
          {
            id: 'a',
            name: 'A',
            state: 'operational',
            history: [
              { date: '2026-09-04', ok: 1, total: 1 },
              { date: '2026-09-02', ok: 1, total: 1 },
              { date: '2026-09-04', ok: 0, total: 1 },
            ],
          },
        ],
      }),
    );
    expect(snapshot!.components[0].history).toEqual([
      { date: '2026-09-02', ratio: 1 },
      { date: '2026-09-04', ratio: 0 },
    ]);
  });

  it('falls back to the widest history when windowDays is missing or absurd', () => {
    for (const windowDays of [undefined, 0, -5, 2.5, 'ninety']) {
      const snapshot = mapProbePayload(payload({ windowDays }));
      expect(snapshot!.windowDays).toBe(2);
    }
  });
});

describe('mapProbePayload optional fields', () => {
  it('carries the reconstructed marker through, so the UI can say so', () => {
    const snapshot = mapProbePayload(
      payload({
        components: [
          {
            id: 'a',
            name: 'A',
            state: 'operational',
            history: [
              { date: '2026-09-01', ok: 720, total: 1440, backfilled: true },
              { date: '2026-09-02', ok: 1440, total: 1440 },
            ],
          },
        ],
      }),
    );

    const [reconstructed, watched] = snapshot!.components[0].history;
    expect(reconstructed.reconstructed).toBe(true);
    // Absent, not false: only a day that WAS reconstructed carries the marker.
    expect(watched.reconstructed).toBeUndefined();
  });

  it('survives the window expansion with the marker intact', () => {
    // buildDayWindow rebuilds the list; a day must not lose its provenance there.
    const days = buildDayWindow(
      [{ date: '2026-09-04', ratio: 0.5, reconstructed: true }],
      2,
      new Date('2026-09-05T12:00:00Z'),
    );
    expect(days[0]).toEqual({ date: '2026-09-04', ratio: 0.5, reconstructed: true });
  });

  it('reads the sampling cadence, and refuses an unusable one', () => {
    expect(mapProbePayload(payload({ sampleIntervalSeconds: 60 }))!.sampleIntervalSeconds).toBe(60);
    for (const bad of [undefined, 0, -30, 'sixty', Number.NaN, null]) {
      expect(
        mapProbePayload(payload({ sampleIntervalSeconds: bad }))!.sampleIntervalSeconds,
        String(bad),
      ).toBeNull();
    }
  });
});

describe('formatUptime', () => {
  it('formats two decimals and refuses to invent one', () => {
    expect(formatUptime(1)).toBe('100.00%');
    expect(formatUptime(0.9992)).toBe('99.92%');
    expect(formatUptime(0)).toBe('0.00%');
    expect(formatUptime(null)).toBeNull();
    expect(formatUptime(Number.NaN)).toBeNull();
  });

  it('never rounds an imperfect day up to a perfect score', () => {
    // 5 minutes down over 90 days rounds to 100.00%, which would sit next to an
    // amber bar and contradict it. The number a reader quotes must not lie.
    expect(formatUptime(0.9999999)).toBe('99.99%');
    expect(formatUptime(1 - 1e-12)).toBe('99.99%');
  });
});

describe('buildDayWindow', () => {
  const now = new Date('2026-09-05T12:00:00Z');

  it('returns a continuous window ending today, in UTC', () => {
    const days = buildDayWindow([], 3, now);
    expect(days.map((day) => day.date)).toEqual(['2026-09-03', '2026-09-04', '2026-09-05']);
  });

  it('keeps an unrecorded day as a hole instead of shifting the bars', () => {
    const days = buildDayWindow(
      [
        { date: '2026-09-03', ratio: 1 },
        { date: '2026-09-05', ratio: 0.5 },
      ],
      3,
      now,
    );
    expect(days).toEqual([
      { date: '2026-09-03', ratio: 1 },
      { date: '2026-09-04', ratio: null },
      { date: '2026-09-05', ratio: 0.5 },
    ]);
  });

  it('ignores history outside the window', () => {
    const days = buildDayWindow([{ date: '2026-01-01', ratio: 0 }], 2, now);
    expect(days.map((day) => day.ratio)).toEqual([null, null]);
  });

  it('crosses a month boundary correctly', () => {
    const days = buildDayWindow([], 3, new Date('2026-03-01T00:30:00Z'));
    expect(days.map((day) => day.date)).toEqual(['2026-02-27', '2026-02-28', '2026-03-01']);
  });

  it('returns nothing for a non-positive or non-integer window', () => {
    expect(buildDayWindow([], 0, now)).toEqual([]);
    expect(buildDayWindow([], -1, now)).toEqual([]);
    expect(buildDayWindow([], 1.5, now)).toEqual([]);
  });
});
