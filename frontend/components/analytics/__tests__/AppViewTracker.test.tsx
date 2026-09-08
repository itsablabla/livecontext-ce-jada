// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const analyticsMock = vi.hoisted(() => ({ setAppView: vi.fn() }));
vi.mock('@/lib/analytics/analytics', () => analyticsMock);

const viewMock = vi.hoisted(() => ({ useCurrentView: vi.fn() }));
vi.mock('@/hooks/useCurrentView', () => viewMock);

import AppViewTracker from '../AppViewTracker';

function setView(view: string, isDetailPage: boolean) {
  viewMock.useCurrentView.mockReturnValue({ view, isDetailPage });
}

describe('AppViewTracker', () => {
  beforeEach(() => {
    analyticsMock.setAppView.mockClear();
  });

  afterEach(cleanup);

  it('registers the current view and detail flag on mount', () => {
    setView('workflow', true);

    render(<AppViewTracker />);

    expect(analyticsMock.setAppView).toHaveBeenCalledTimes(1);
    expect(analyticsMock.setAppView).toHaveBeenCalledWith('workflow', true);
  });

  it('re-registers when the view changes, clearing the previous one first', () => {
    setView('workflow', true);
    const { rerender } = render(<AppViewTracker />);
    analyticsMock.setAppView.mockClear();

    setView('marketplace', false);
    rerender(<AppViewTracker />);

    expect(analyticsMock.setAppView.mock.calls).toEqual([
      [null, false],
      ['marketplace', false],
    ]);
  });

  it('re-registers when only the detail flag changes for the same view', () => {
    setView('chat', false);
    const { rerender } = render(<AppViewTracker />);
    analyticsMock.setAppView.mockClear();

    setView('chat', true);
    rerender(<AppViewTracker />);

    expect(analyticsMock.setAppView).toHaveBeenLastCalledWith('chat', true);
  });

  it('does nothing on a re-render with an unchanged view', () => {
    setView('data', false);
    const { rerender } = render(<AppViewTracker />);
    analyticsMock.setAppView.mockClear();

    rerender(<AppViewTracker />);

    expect(analyticsMock.setAppView).not.toHaveBeenCalled();
  });

  it('unregisters the view on unmount', () => {
    setView('settings', false);
    const { unmount } = render(<AppViewTracker />);
    analyticsMock.setAppView.mockClear();

    unmount();

    expect(analyticsMock.setAppView).toHaveBeenCalledTimes(1);
    expect(analyticsMock.setAppView).toHaveBeenCalledWith(null, false);
  });

  it('renders nothing', () => {
    setView('chat', false);

    const { container } = render(<AppViewTracker />);

    expect(container).toBeEmptyDOMElement();
  });
});
