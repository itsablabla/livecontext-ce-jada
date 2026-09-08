import { isLive } from './incidentIo';
import type {
  ComponentState,
  IncidentsSource,
  OverallState,
  StatusComponent,
  StatusIncident,
} from './types';

/**
 * Headline state, as the WORST of what the sources say.
 *
 * Ranking (worst first): major_outage > degraded > maintenance > unknown >
 * operational. `unknown` deliberately ranks WORSE than `operational`, so a
 * component we failed to measure can never be rounded up into "all systems
 * operational" - the whole point of a status page is that it does not lie in the
 * direction that suits us.
 *
 * A live incident.io entry floors the state at `degraded`: the Widget API's
 * severity vocabulary is not something we can rely on, but a declared, ongoing
 * incident is by itself enough to stop claiming everything is fine.
 *
 * The incident feed being CONFIGURED AND UNREACHABLE also floors it at `unknown`:
 * we would have no way to see a declared incident, so an all-clear would be a
 * claim we cannot back. A feed that is simply NOT CONFIGURED is different - there
 * is nothing to fail to reach, so it leaves a probe-verified all-clear standing.
 */
const RANK: Record<OverallState, number> = {
  major_outage: 4,
  degraded: 3,
  maintenance: 2,
  unknown: 1,
  operational: 0,
};

const FROM_COMPONENT: Record<ComponentState, OverallState> = {
  down: 'major_outage',
  degraded: 'degraded',
  unknown: 'unknown',
  operational: 'operational',
};

function worse(a: OverallState, b: OverallState): OverallState {
  return RANK[b] > RANK[a] ? b : a;
}

export interface OverallInput {
  components: StatusComponent[];
  incidents: StatusIncident[];
  dataAvailable: boolean;
  incidentsSource: IncidentsSource;
}

export function deriveOverall(input: OverallInput): OverallState {
  const { components, incidents, dataAvailable, incidentsSource } = input;

  // Nothing usable at all: say so. Never "operational".
  if (!dataAvailable && incidentsSource !== 'ok') return 'unknown';

  // The probe answered but reported no component: nothing to claim from it.
  let state: OverallState = dataAvailable && components.length > 0 ? 'operational' : 'unknown';

  for (const component of components) {
    state = worse(state, FROM_COMPONENT[component.state] ?? 'unknown');
  }

  for (const incident of incidents) {
    if (!isLive(incident)) continue; // merely scheduled: no impact yet
    state = worse(state, incident.kind === 'maintenance' ? 'maintenance' : 'degraded');
  }

  if (incidentsSource === 'unreachable') state = worse(state, 'unknown');

  return state;
}

/** Traffic-light bucket, for the dot / pill colour. */
export function severityOf(state: OverallState): 'green' | 'amber' | 'red' | 'grey' {
  switch (state) {
    case 'operational':
      return 'green';
    case 'degraded':
    case 'maintenance':
      return 'amber';
    case 'major_outage':
      return 'red';
    default:
      return 'grey';
  }
}
