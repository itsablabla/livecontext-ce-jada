/**
 * @vitest-environment jsdom
 *
 * Tests for the in-app incident strip.
 *
 * The behaviours worth pinning: it stays invisible unless something is happening
 * NOW (a scheduled window must not shout at anyone), it disappears when dismissed
 * and stays dismissed across a remount, and it comes BACK when the same incident
 * moves to a new stage - which is the difference between "read once" and "silent
 * until it is over".
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';
import type { ServiceStatus, StatusIncident } from '@/lib/status/types';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

const serviceStatus = vi.fn();
vi.mock('@/hooks/useServiceStatus', () => ({
  useServiceStatus: () => serviceStatus(),
}));

const IncidentStrip = (await import('../IncidentStrip')).default;

function incident(overrides: Partial<StatusIncident> = {}): StatusIncident {
  return {
    id: 'i1',
    kind: 'incident',
    name: 'Chat is slow',
    status: 'investigating',
    message: 'We are looking into elevated latency.',
    url: 'https://status.example.com/incidents/i1',
    components: ['Agents & chat'],
    startedAt: null,
    endsAt: null,
    ...overrides,
  };
}

function withIncidents(incidents: StatusIncident[]): ServiceStatus {
  return {
    overall: incidents.length > 0 ? 'degraded' : 'operational',
    components: [],
    incidents,
    windowDays: 90,
    generatedAt: null,
    sampleIntervalSeconds: 60,
    dataAvailable: true,
    incidentsSource: 'ok',
    statusPageUrl: 'https://status.example.com',
  };
}

beforeEach(() => {
  window.localStorage.clear();
  serviceStatus.mockReset();
});

describe('IncidentStrip', () => {
  it('renders nothing before the status is known', () => {
    serviceStatus.mockReturnValue({ status: null, isLoading: true, isError: false });
    const { container } = render(<IncidentStrip />);
    expect(container.innerHTML).toBe('');
  });

  it('renders nothing when there is no ongoing entry', () => {
    serviceStatus.mockReturnValue({ status: withIncidents([]), isLoading: false, isError: false });
    const { container } = render(<IncidentStrip />);
    expect(container.innerHTML).toBe('');
  });

  it('stays quiet for a maintenance that is only scheduled', () => {
    serviceStatus.mockReturnValue({
      status: withIncidents([incident({ kind: 'scheduled_maintenance', name: 'Planned reboot' })]),
      isLoading: false,
      isError: false,
    });
    const { container } = render(<IncidentStrip />);
    expect(container.innerHTML).toBe('');
  });

  it('announces an ongoing incident, with its latest update and a link out', () => {
    serviceStatus.mockReturnValue({
      status: withIncidents([incident()]),
      isLoading: false,
      isError: false,
    });

    render(<IncidentStrip />);

    expect(screen.getByRole('status')).toBeTruthy();
    expect(screen.getByText(/Chat is slow/)).toBeTruthy();
    expect(screen.getByText('We are looking into elevated latency.')).toBeTruthy();
    expect(screen.getByRole('link').getAttribute('href')).toBe(
      'https://status.example.com/incidents/i1',
    );
  });

  it('falls back to the status page when the entry carries no permalink', () => {
    serviceStatus.mockReturnValue({
      status: withIncidents([incident({ url: null })]),
      isLoading: false,
      isError: false,
    });

    render(<IncidentStrip />);

    expect(screen.getByRole('link').getAttribute('href')).toBe('https://status.example.com');
  });

  it('stays dismissed across a remount', () => {
    serviceStatus.mockReturnValue({
      status: withIncidents([incident()]),
      isLoading: false,
      isError: false,
    });

    const first = render(<IncidentStrip />);
    fireEvent.click(screen.getByRole('button'));
    expect(first.container.innerHTML).toBe('');

    first.unmount();
    const second = render(<IncidentStrip />);
    expect(second.container.innerHTML).toBe('');
  });

  it('comes back when the same incident reaches a new stage', () => {
    serviceStatus.mockReturnValue({
      status: withIncidents([incident()]),
      isLoading: false,
      isError: false,
    });
    const first = render(<IncidentStrip />);
    fireEvent.click(screen.getByRole('button'));
    first.unmount();

    serviceStatus.mockReturnValue({
      status: withIncidents([incident({ status: 'fixing' })]),
      isLoading: false,
      isError: false,
    });
    render(<IncidentStrip />);

    expect(screen.getByRole('status')).toBeTruthy();
  });

  it('shows the next live entry once the first is dismissed', () => {
    serviceStatus.mockReturnValue({
      status: withIncidents([
        incident(),
        incident({ id: 'm1', kind: 'maintenance', name: 'DB upgrade', url: null }),
      ]),
      isLoading: false,
      isError: false,
    });

    render(<IncidentStrip />);
    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText(/DB upgrade/)).toBeTruthy();
  });

  it('survives a browser that refuses local storage', () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    serviceStatus.mockReturnValue({
      status: withIncidents([incident()]),
      isLoading: false,
      isError: false,
    });

    render(<IncidentStrip />);
    fireEvent.click(screen.getByRole('button'));

    // Dismissal still works for this session; it just cannot be remembered.
    expect(screen.queryByRole('status')).toBeNull();
    getItem.mockRestore();
    setItem.mockRestore();
  });
});
