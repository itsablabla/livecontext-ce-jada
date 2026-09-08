/**
 * Tests for the headline-state derivation.
 *
 * This is the file where a status page either tells the truth or does not, so the
 * cases below are mostly about the ONE forbidden outcome: reporting "operational"
 * for something we did not, or could not, verify. Unknown must beat operational,
 * a live incident must beat a green probe, and losing both sources must be said
 * out loud.
 */
import { describe, it, expect } from 'vitest';
import { deriveOverall, severityOf } from '../overall';
import type { ComponentState, IncidentsSource, StatusComponent, StatusIncident } from '../types';

function component(state: ComponentState, id: string = state): StatusComponent {
  return { id, name: id, state, uptimeRatio: 1, history: [] };
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

function derive(
  components: StatusComponent[],
  incidents: StatusIncident[] = [],
  availability: { dataAvailable: boolean; incidentsSource: IncidentsSource } = {
    dataAvailable: true,
    incidentsSource: 'ok',
  },
) {
  return deriveOverall({ components, incidents, ...availability });
}

describe('deriveOverall', () => {
  it('is operational only when every component is measured and fine', () => {
    expect(derive([component('operational'), component('operational', 'b')])).toBe('operational');
  });

  it('reports unknown, not operational, when a component was not measured', () => {
    expect(derive([component('operational'), component('unknown')])).toBe('unknown');
  });

  it('reports unknown when the probe answered with no component at all', () => {
    expect(derive([])).toBe('unknown');
  });

  it('reports unknown when neither source could be reached', () => {
    expect(derive([], [], { dataAvailable: false, incidentsSource: 'unreachable' })).toBe('unknown');
  });

  it('refuses an all-clear while a CONFIGURED incident feed is unreachable', () => {
    // Green probes are not enough: with no view of declared incidents we could
    // not see one, so claiming "all systems operational" would be unbacked.
    expect(
      derive([component('operational')], [], {
        dataAvailable: true,
        incidentsSource: 'unreachable',
      }),
    ).toBe('unknown');
  });

  it('keeps the all-clear when there is NO incident feed configured at all', () => {
    // Nothing failed to answer here: the deployment simply has no feed, so the
    // probes' verdict stands on its own. This is the launch configuration, and
    // treating it like an outage would make the page cry wolf from day one.
    expect(
      derive([component('operational')], [], {
        dataAvailable: true,
        incidentsSource: 'not_configured',
      }),
    ).toBe('operational');
  });

  it('still reports an incident when the probe is unreachable', () => {
    expect(derive([], [incident()], { dataAvailable: false, incidentsSource: 'ok' })).toBe(
      'degraded',
    );
  });

  it('lets a real outage outrank an unreachable feed', () => {
    expect(
      derive([component('down')], [], { dataAvailable: true, incidentsSource: 'unreachable' }),
    ).toBe('major_outage');
  });

  it('takes the worst component state', () => {
    expect(derive([component('operational'), component('degraded')])).toBe('degraded');
    expect(derive([component('degraded'), component('down')])).toBe('major_outage');
    expect(derive([component('unknown'), component('down')])).toBe('major_outage');
  });

  it('floors the state at degraded while an incident is ongoing, green probes or not', () => {
    expect(derive([component('operational')], [incident()])).toBe('degraded');
  });

  it('reports maintenance for a running maintenance window', () => {
    expect(derive([component('operational')], [incident({ kind: 'maintenance' })])).toBe(
      'maintenance',
    );
  });

  it('lets a real outage outrank a concurrent maintenance', () => {
    expect(derive([component('down')], [incident({ kind: 'maintenance' })])).toBe('major_outage');
  });

  it('ignores a maintenance that is only scheduled', () => {
    expect(
      derive([component('operational')], [incident({ kind: 'scheduled_maintenance' })]),
    ).toBe('operational');
  });
});

describe('severityOf', () => {
  it('maps each state to its traffic-light colour', () => {
    expect(severityOf('operational')).toBe('green');
    expect(severityOf('degraded')).toBe('amber');
    expect(severityOf('maintenance')).toBe('amber');
    expect(severityOf('major_outage')).toBe('red');
    expect(severityOf('unknown')).toBe('grey');
  });
});
