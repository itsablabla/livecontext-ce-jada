// @vitest-environment jsdom
/**
 * Settings > Information "Platform status" card.
 *
 * This replaced a row in the user menu, so the two things that row was careful
 * about have to survive the move: the status page opens in a NEW tab (checking
 * it must not cost the page the reader was on, which may be a running
 * workflow), and nothing is ever rounded up into "operational" - a state we
 * could not measure says so.
 *
 * The card is cloud only. Its gate lives in the page wrapper, and
 * `useServiceStatus` is disabled in CE as well, which is what the last test
 * here pins: a stray mount renders the loading state, never a claim.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import type { OverallState, ServiceStatus, StatusIncident } from '@/lib/status/types';

const serviceStatus = vi.fn();
vi.mock('@/hooks/useServiceStatus', () => ({ useServiceStatus: () => serviceStatus() }));
vi.mock('next-intl', () => ({
  useLocale: () => 'en',
  useTranslations: () => (key: string) => key,
}));

import PlatformStatusCard from '../PlatformStatusCard';

function status(overrides: Partial<ServiceStatus> = {}): ServiceStatus {
  return {
    overall: 'operational',
    components: [],
    incidents: [],
    windowDays: 90,
    generatedAt: '2026-09-06T10:00:00Z',
    sampleIntervalSeconds: 60,
    dataAvailable: true,
    incidentsSource: 'ok',
    statusPageUrl: null,
    ...overrides,
  };
}

const incident = (): StatusIncident => ({
  id: 'i1',
  kind: 'incident',
  name: 'Elevated errors',
  status: 'investigating',
  components: [],
} as StatusIncident);

const ready = (s: ServiceStatus) => ({ status: s, isLoading: false, isError: false });

beforeEach(() => serviceStatus.mockReset());
afterEach(cleanup);

describe('what the card says about the platform', () => {
  it.each<OverallState>(['operational', 'degraded', 'maintenance', 'major_outage', 'unknown'])(
    'names the %s state rather than colouring a dot and leaving it at that',
    (overall) => {
      // The dot alone is invisible to a screen reader and ambiguous to everyone
      // else; the headline sentence is the answer.
      serviceStatus.mockReturnValue(ready(status({ overall })));

      render(<PlatformStatusCard />);

      expect(screen.getByText(`headline.${overall}`)).toBeInTheDocument();
    },
  );

  it('counts the ongoing incidents when there are any', () => {
    serviceStatus.mockReturnValue(ready(status({ overall: 'degraded', incidents: [incident(), incident()] })));

    render(<PlatformStatusCard />);

    expect(screen.getByText('card.incidents')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('says nothing about incidents when there are none, rather than showing a zero', () => {
    serviceStatus.mockReturnValue(ready(status()));

    render(<PlatformStatusCard />);

    expect(screen.queryByText('card.incidents')).not.toBeInTheDocument();
  });

  it('dates the measurement only when the probe actually timed one', () => {
    // A payload with no timestamp must not be dated: that would put a time on a
    // measurement that did not happen.
    serviceStatus.mockReturnValue(ready(status({ generatedAt: null })));

    render(<PlatformStatusCard />);

    expect(screen.queryByText('card.lastChecked')).not.toBeInTheDocument();
  });

  it('opens the status page in a new tab, safely', () => {
    serviceStatus.mockReturnValue(ready(status()));

    render(<PlatformStatusCard />);

    const link = screen.getByRole('link');
    expect(link).toHaveAttribute('href', '/status');
    expect(link).toHaveAttribute('target', '_blank');
    // Without noopener the opened page gets a handle on this one.
    expect(link.getAttribute('rel')).toContain('noopener');
  });
});

describe('when the status cannot be read', () => {
  it('says so instead of claiming everything is fine', () => {
    // The whole point of a status surface is that it does not fail in the
    // direction that suits us.
    serviceStatus.mockReturnValue({ status: null, isLoading: false, isError: true });

    render(<PlatformStatusCard />);

    expect(screen.getByText('card.loadError')).toBeInTheDocument();
    expect(screen.queryByText('headline.operational')).not.toBeInTheDocument();
  });

  it('shows neither a state nor an error while the first answer is still coming', () => {
    // A grey dot on first paint reads as "unknown" when the truth is "not asked
    // yet", and an error would be a lie about a request still in flight.
    serviceStatus.mockReturnValue({ status: null, isLoading: true, isError: false });

    render(<PlatformStatusCard />);

    expect(screen.queryByText('card.loadError')).not.toBeInTheDocument();
    expect(screen.queryByText('headline.operational')).not.toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });
});
