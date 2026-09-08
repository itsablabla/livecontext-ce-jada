/**
 * @vitest-environment jsdom
 *
 * The status surfaces rendered with the REAL translator and the REAL en.json.
 *
 * Surfaces: the Settings > Information card (PlatformStatusCard) and the
 * incident strip. The card replaced a user-menu row that carried the same
 * headline strings.
 *
 * Why this exists next to the other two suites: those mock next-intl as
 * `useTranslations: () => (key) => key`, so they assert on message IDs. That
 * makes them blind to the one mistake most likely to reach a user here - a key
 * that does not exist. With the real provider a typo renders the raw key (and
 * next-intl reports an error), so this suite fails instead of shipping
 * "headline.major_outage" into the UI.
 *
 * The other locales are covered by the repo-wide key-parity discipline; this
 * pins that the keys these components ask for are the keys that exist.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as React from 'react';
import { NextIntlClientProvider } from 'next-intl';
import messages from '@/messages/en.json';
import type { OverallState, ServiceStatus, StatusIncident } from '@/lib/status/types';

const serviceStatus = vi.fn();
vi.mock('@/hooks/useServiceStatus', () => ({
  useServiceStatus: () => serviceStatus(),
}));

const PlatformStatusCard = (await import('@/components/settings/PlatformStatusCard')).default;
const IncidentStrip = (await import('../IncidentStrip')).default;

function status(overall: OverallState, incidents: StatusIncident[] = []): ServiceStatus {
  return {
    overall,
    components: [],
    incidents,
    windowDays: 0,
    generatedAt: null,
    sampleIntervalSeconds: 60,
    dataAvailable: true,
    incidentsSource: 'ok',
    statusPageUrl: 'https://status.example.com',
  };
}

function incident(overrides: Partial<StatusIncident> = {}): StatusIncident {
  return {
    id: 'i1',
    kind: 'incident',
    name: 'Elevated errors',
    status: 'investigating',
    message: null,
    url: null,
    components: [],
    startedAt: null,
    endsAt: null,
    ...overrides,
  };
}

function withIntl(node: React.ReactNode) {
  return render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      {node}
    </NextIntlClientProvider>,
  );
}

describe('status surfaces with real messages', () => {
  it('renders a real sentence for every headline state, never a raw key', () => {
    const states: OverallState[] = [
      'operational',
      'degraded',
      'maintenance',
      'major_outage',
      'unknown',
    ];

    for (const state of states) {
      serviceStatus.mockReturnValue({ status: status(state), isLoading: false, isError: false });
      const { container, unmount } = withIntl(<PlatformStatusCard />);
      const label = container.textContent ?? '';
      expect(label.length, state).toBeGreaterThan(0);
      expect(label, state).not.toContain('headline.');
      // The card's own labels come from the same namespace and are just as able
      // to be a typo.
      expect(label, state).not.toContain('card.');
      unmount();
    }
  });

  it('labels the incident strip and its controls from real messages', () => {
    serviceStatus.mockReturnValue({
      status: status('degraded', [incident()]),
      isLoading: false,
      isError: false,
    });

    const { container } = withIntl(<IncidentStrip />);

    expect(container.textContent).not.toContain('strip.');
    expect(screen.getByText(/Ongoing incident/)).toBeTruthy();
    expect(screen.getByRole('link').textContent).toContain('View status');
    expect(screen.getByRole('button').getAttribute('aria-label')).toBe('Dismiss');
  });

  it('uses the maintenance wording for a running maintenance', () => {
    serviceStatus.mockReturnValue({
      status: status('maintenance', [incident({ kind: 'maintenance', name: 'DB upgrade' })]),
      isLoading: false,
      isError: false,
    });

    const { container } = withIntl(<IncidentStrip />);

    expect(container.textContent).toContain('Maintenance in progress');
    expect(container.textContent).not.toContain('strip.');
  });
});
