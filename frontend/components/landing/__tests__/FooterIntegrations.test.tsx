/**
 * @vitest-environment jsdom
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';
import FooterIntegrations from '../FooterIntegrations';

function renderColumn(props: { siteBaseUrl?: string } = {}) {
  render(<FooterIntegrations {...props} />);
}

function hrefOf(name: RegExp | string) {
  return screen.getByRole('link', { name }).getAttribute('href');
}

describe('footer Integrations column', () => {
  it('lists the chosen integrations, each linking to its own page', () => {
    renderColumn();

    expect(hrefOf('Instagram')).toBe('/integrations/instagram');
    expect(hrefOf('Gmail')).toBe('/integrations/gmail');
    expect(hrefOf('Google Sheets')).toBe('/integrations/google-sheets');
    expect(hrefOf('All integrations')).toBe('/integrations');
  });

  it('shows all eight and the directory, and nothing else', () => {
    renderColumn();

    for (const { name } of WELL_KNOWN_INTEGRATIONS) {
      expect(screen.getByRole('link', { name })).toBeTruthy();
    }
    expect(screen.getAllByRole('link')).toHaveLength(WELL_KNOWN_INTEGRATIONS.length + 1);
  });

  it('leads with Instagram and then the professional set, in that order', () => {
    renderColumn();

    // The order is the reading order of the column and is the whole content of the change
    // that replaced the usage ranking: that ranking put Seedance and Apify in front of
    // Gmail, which answers no question a visitor has.
    const labels = screen.getAllByRole('link').map((a) => a.textContent);
    expect(labels).toEqual([
      'Instagram', 'Gmail', 'Slack', 'Notion', 'GitHub', 'Google Sheets', 'HubSpot',
      'Stripe', 'All integrations',
    ]);
  });

  it('invents no integration link it cannot verify', () => {
    renderColumn();

    // A hand-written slug is a URL nobody checked, and the failure mode is eight 404s in
    // the footer of every page. What makes the list safe is wellKnownIntegrations.test.ts,
    // which resolves each slug against the seed corpus; what this pins is that the column
    // renders nothing outside that verified set.
    const allowed = new Set([
      '/integrations',
      ...WELL_KNOWN_INTEGRATIONS.map((i) => `/integrations/${i.slug}`),
    ]);
    const links = screen.getAllByRole('link').map((a) => a.getAttribute('href'));
    expect(links.filter((href) => !allowed.has(href ?? ''))).toHaveLength(0);
  });

  it('prefixes every link with the site origin on the docs sub-host', () => {
    renderColumn({ siteBaseUrl: 'https://livecontext.ai' });

    // The chrome renders on docs.livecontext.ai too, where a bare /integrations path
    // points at a page that does not exist there.
    expect(hrefOf('Gmail')).toBe('https://livecontext.ai/integrations/gmail');
    expect(hrefOf('All integrations')).toBe('https://livecontext.ai/integrations');
  });

  it('renders without reaching the gateway at all', () => {
    // The point of the rewrite, and not a detail: the previous version read the catalogue
    // on every render of every page carrying the footer. It could not do that at build
    // time, and a render that landed mid-rollout timed out and froze an empty column into
    // production for over an hour. A component that never fetches cannot repeat either.
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    try {
      renderColumn();
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
