/**
 * Tests for the public page's English display helpers.
 *
 * Dates are formatted from an explicit UTC table rather than `Intl`, so these
 * also guard the reason for that: no browser-locale leakage, and identical
 * output on the server and in the browser.
 *
 * `overallCopy` gets the most attention because its `unknown` branch has two
 * genuinely different meanings, and collapsing them would either overstate an
 * outage or understate a blind spot.
 */
import { describe, it, expect } from 'vitest';
import {
  componentStateLabel,
  formatDayLabel,
  formatUtcInstant,
  incidentKindLabel,
  overallCopy,
  statusTokenLabel,
} from '../format';
import type { ComponentState, IncidentsSource, OverallState, ServiceStatus } from '../types';

function status(
  overall: OverallState,
  componentStates: ComponentState[] = [],
  incidentsSource: IncidentsSource = 'ok',
): ServiceStatus {
  return {
    overall,
    components: componentStates.map((state, index) => ({
      id: `c${index}`,
      name: `C${index}`,
      state,
      uptimeRatio: null,
      history: [],
    })),
    incidents: [],
    windowDays: 90,
    generatedAt: null,
    sampleIntervalSeconds: 60,
    dataAvailable: true,
    incidentsSource,
    statusPageUrl: null,
  };
}

describe('formatDayLabel', () => {
  it('renders a short English day, without a leading zero', () => {
    expect(formatDayLabel('2026-09-05')).toBe('Sep 5');
    expect(formatDayLabel('2026-01-31')).toBe('Jan 31');
  });

  it('returns the input untouched when it is not a day', () => {
    expect(formatDayLabel('yesterday')).toBe('yesterday');
  });
});

describe('formatUtcInstant', () => {
  it('renders the instant in UTC, whatever the machine timezone is', () => {
    expect(formatUtcInstant('2026-09-05T14:03:09Z')).toBe('Sep 5, 2026, 14:03 UTC');
    // Same instant, expressed with an offset: the output must not move.
    expect(formatUtcInstant('2026-09-05T16:03:09+02:00')).toBe('Sep 5, 2026, 14:03 UTC');
  });

  it('returns null rather than "Invalid Date" for junk or absence', () => {
    expect(formatUtcInstant(null)).toBeNull();
    expect(formatUtcInstant('soon')).toBeNull();
  });
});

describe('componentStateLabel', () => {
  it('names each state, and never calls an unmeasured component fine', () => {
    expect(componentStateLabel('operational')).toBe('Operational');
    expect(componentStateLabel('degraded')).toBe('Degraded');
    expect(componentStateLabel('down')).toBe('Outage');
    expect(componentStateLabel('unknown')).toBe('Not measured');
  });
});

describe('incidentKindLabel', () => {
  it('distinguishes a running maintenance from a scheduled one', () => {
    expect(incidentKindLabel('incident')).toBe('Incident');
    expect(incidentKindLabel('maintenance')).toBe('Maintenance in progress');
    expect(incidentKindLabel('scheduled_maintenance')).toBe('Scheduled maintenance');
  });
});

describe('statusTokenLabel', () => {
  it('shows the provider token readably instead of mapping it to our vocabulary', () => {
    expect(statusTokenLabel('investigating')).toBe('Investigating');
    expect(statusTokenLabel('in_progress')).toBe('In progress');
    expect(statusTokenLabel('some-new-token')).toBe('Some new token');
    expect(statusTokenLabel('')).toBe('');
  });
});

describe('overallCopy', () => {
  it('claims an all-clear only for the operational state', () => {
    expect(overallCopy(status('operational')).title).toBe('All systems operational');
    for (const state of ['degraded', 'major_outage', 'maintenance', 'unknown'] as const) {
      expect(overallCopy(status(state)).title).not.toContain('All systems operational');
    }
  });

  it('separates "we measured nothing" from "we measured part of it"', () => {
    expect(overallCopy(status('unknown', ['unknown', 'unknown'])).title).toBe(
      'Live status unavailable',
    );
    expect(overallCopy(status('unknown', ['operational', 'unknown'])).title).toBe(
      'Status partially unavailable',
    );
  });

  it('does not blame the components when it is the incident feed that is down', () => {
    // All six measured and green; only the feed is unreachable. Telling the
    // reader that components could not be measured would send them hunting for
    // a problem that is not there.
    const copy = overallCopy(status('unknown', ['operational', 'operational'], 'unreachable'));
    expect(copy.title).toBe('Cannot confirm an all-clear');
    expect(copy.sub).toContain('incident feed');
    expect(copy.sub).not.toContain('components could not be measured');
  });

  it('still blames the components when they are the unmeasured part', () => {
    const copy = overallCopy(status('unknown', ['operational', 'unknown'], 'unreachable'));
    expect(copy.title).toBe('Status partially unavailable');
  });
});
