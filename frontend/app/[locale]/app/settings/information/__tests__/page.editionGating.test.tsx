// @vitest-environment jsdom
/**
 * Which cards the Information page shows, per edition.
 *
 * The three edition-gated cards answer opposite questions and must not cross
 * over. Version and Optional components describe THIS install, so they are
 * self-hosted only. Platform status describes OUR CLOUD, which a self-hosted
 * install neither runs nor depends on, so it is cloud only: showing it there
 * would report on a platform the reader is not using, and an "all systems
 * operational" about someone else's cloud is worse than no card at all.
 *
 * The gate lives in this page wrapper, which is the only place it exists - the
 * cards themselves do not check the edition - so it is the thing to pin.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

const edition = { IS_CE: false };
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return edition.IS_CE;
  },
}));

vi.mock('@/components/about/AboutInformationContent', () => ({
  default: () => <div data-testid="about" />,
}));
vi.mock('@/components/settings/VersionCard', () => ({
  default: () => <div data-testid="version-card" />,
}));
vi.mock('@/components/settings/OptionalComponentsCard', () => ({
  default: () => <div data-testid="optional-components-card" />,
}));
vi.mock('@/components/settings/PlatformStatusCard', () => ({
  default: () => <div data-testid="platform-status-card" />,
}));

import InformationPage from '../page';

beforeEach(() => {
  edition.IS_CE = false;
});
afterEach(cleanup);

describe('Settings > Information, per edition', () => {
  it('shows the platform status in cloud, and neither self-hosted card', () => {
    render(<InformationPage />);

    expect(screen.getByTestId('platform-status-card')).toBeInTheDocument();
    expect(screen.queryByTestId('version-card')).not.toBeInTheDocument();
    expect(screen.queryByTestId('optional-components-card')).not.toBeInTheDocument();
  });

  it('shows the self-hosted cards in CE, and NOT the platform status', () => {
    edition.IS_CE = true;

    render(<InformationPage />);

    expect(screen.getByTestId('version-card')).toBeInTheDocument();
    expect(screen.getByTestId('optional-components-card')).toBeInTheDocument();
    expect(screen.queryByTestId('platform-status-card')).not.toBeInTheDocument();
  });

  it('shows the shared About content in both editions', () => {
    render(<InformationPage />);
    expect(screen.getByTestId('about')).toBeInTheDocument();
    cleanup();

    edition.IS_CE = true;
    render(<InformationPage />);
    expect(screen.getByTestId('about')).toBeInTheDocument();
  });
});
