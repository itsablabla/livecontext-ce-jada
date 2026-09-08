// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The REAL consent module is used on purpose: the provider and the banner share
// one localStorage key, and this test pins that contract from the provider side.
import { CONSENT_CHANGE_EVENT, CONSENT_VERSION } from '@/lib/analytics/consent';

const analyticsMock = vi.hoisted(() => ({
  initAnalytics: vi.fn(),
  disableAnalytics: vi.fn(),
  identifyUser: vi.fn(),
  setAnalyticsOrganization: vi.fn(),
  track: vi.fn(),
  isAnalyticsConfigured: vi.fn(() => true),
}));
vi.mock('@/lib/analytics/analytics', () => analyticsMock);

const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));
vi.mock('@/lib/providers/smart-providers', () => authMock);

const orgState = vi.hoisted(() => ({ currentOrgId: null as string | null }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (selector: (s: typeof orgState) => unknown) => selector(orgState),
}));

import AnalyticsProvider from '../AnalyticsProvider';

const CONSENT_KEY = 'lc.cookieConsent';

function storeConsent(status: 'accepted' | 'rejected') {
  localStorage.setItem(CONSENT_KEY, JSON.stringify({ status, version: CONSENT_VERSION, ts: 1 }));
}

/** What the banner does: persist the choice, then notify live listeners. */
function bannerDecides(status: 'accepted' | 'rejected') {
  storeConsent(status);
  act(() => {
    window.dispatchEvent(new CustomEvent(CONSENT_CHANGE_EVENT, { detail: status }));
  });
}

function setAuth(overrides: Partial<{ isAuthenticated: boolean; isReady: boolean; numericUserId: number | null }> = {}) {
  authMock.useAuth.mockReturnValue({
    isAuthenticated: false,
    isReady: false,
    numericUserId: null,
    ...overrides,
  });
}

describe('AnalyticsProvider', () => {
  beforeEach(() => {
    localStorage.clear();
    Object.values(analyticsMock).forEach((fn) => fn.mockClear());
    analyticsMock.isAnalyticsConfigured.mockReturnValue(true);
    orgState.currentOrgId = null;
    setAuth();
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  it('initializes analytics when consent was already accepted in storage, without re-counting that decision', () => {
    storeConsent('accepted');

    render(<AnalyticsProvider />);

    expect(analyticsMock.initAnalytics).toHaveBeenCalledTimes(1);
    expect(analyticsMock.disableAnalytics).not.toHaveBeenCalled();
    // The accept happened in an earlier session: it was (or could not be)
    // counted then, so counting it again here would inflate the accept rate.
    expect(analyticsMock.track).not.toHaveBeenCalled();
  });

  it('stays off when no consent choice is stored at all', () => {
    render(<AnalyticsProvider />);

    expect(analyticsMock.initAnalytics).not.toHaveBeenCalled();
    expect(analyticsMock.identifyUser).not.toHaveBeenCalled();
    expect(analyticsMock.track).not.toHaveBeenCalled();
  });

  it('starts analytics and counts consent_accepted exactly once when the banner grants consent live', () => {
    const { rerender } = render(<AnalyticsProvider />);
    expect(analyticsMock.initAnalytics).not.toHaveBeenCalled();

    bannerDecides('accepted');

    expect(analyticsMock.initAnalytics).toHaveBeenCalledTimes(1);
    expect(analyticsMock.track).toHaveBeenCalledTimes(1);
    expect(analyticsMock.track).toHaveBeenCalledWith('consent_accepted', { consent_version: CONSENT_VERSION });

    // A repeated grant signal and a re-render of the provider must not count
    // the same decision a second time.
    bannerDecides('accepted');
    rerender(<AnalyticsProvider />);

    expect(analyticsMock.track).toHaveBeenCalledTimes(1);
  });

  it('stops analytics and emits nothing when the banner rejects consent', () => {
    storeConsent('accepted');
    render(<AnalyticsProvider />);
    analyticsMock.initAnalytics.mockClear();

    bannerDecides('rejected');

    expect(analyticsMock.disableAnalytics).toHaveBeenCalledTimes(1);
    expect(analyticsMock.initAnalytics).not.toHaveBeenCalled();
    expect(analyticsMock.track).not.toHaveBeenCalled();
  });

  it('identifies the user by the numeric backend id (as a string) and the current org once consent and auth are ready', () => {
    storeConsent('accepted');
    orgState.currentOrgId = 'org-uuid-1';
    setAuth({ isAuthenticated: true, isReady: true, numericUserId: 42 });

    render(<AnalyticsProvider />);

    expect(analyticsMock.identifyUser).toHaveBeenCalledTimes(1);
    expect(analyticsMock.identifyUser).toHaveBeenCalledWith('42', 'org-uuid-1');
    expect(analyticsMock.setAnalyticsOrganization).toHaveBeenCalledWith('org-uuid-1');
  });

  it('does not identify before auth is ready, then identifies once it is', () => {
    storeConsent('accepted');
    orgState.currentOrgId = 'org-uuid-1';
    setAuth({ isAuthenticated: true, isReady: false, numericUserId: 42 });

    const { rerender } = render(<AnalyticsProvider />);
    expect(analyticsMock.identifyUser).not.toHaveBeenCalled();

    setAuth({ isAuthenticated: true, isReady: true, numericUserId: 42 });
    rerender(<AnalyticsProvider />);

    expect(analyticsMock.identifyUser).toHaveBeenCalledWith('42', 'org-uuid-1');
  });

  it('does not identify while consent is withheld even if auth is ready', () => {
    setAuth({ isAuthenticated: true, isReady: true, numericUserId: 42 });

    render(<AnalyticsProvider />);

    expect(analyticsMock.identifyUser).not.toHaveBeenCalled();
    expect(analyticsMock.setAnalyticsOrganization).not.toHaveBeenCalled();
  });

  it('is fully inert when no PostHog key is configured', () => {
    analyticsMock.isAnalyticsConfigured.mockReturnValue(false);
    storeConsent('accepted');
    orgState.currentOrgId = 'org-uuid-1';
    setAuth({ isAuthenticated: true, isReady: true, numericUserId: 42 });

    render(<AnalyticsProvider />);
    bannerDecides('accepted');
    bannerDecides('rejected');

    expect(analyticsMock.initAnalytics).not.toHaveBeenCalled();
    expect(analyticsMock.disableAnalytics).not.toHaveBeenCalled();
    expect(analyticsMock.identifyUser).not.toHaveBeenCalled();
    expect(analyticsMock.setAnalyticsOrganization).not.toHaveBeenCalled();
    expect(analyticsMock.track).not.toHaveBeenCalled();
  });
});
