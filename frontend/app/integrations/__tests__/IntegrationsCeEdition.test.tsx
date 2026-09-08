/**
 * @vitest-environment jsdom
 */
import { render } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PublicIntegration } from '@/lib/integrations/integrations';

// A self-hosted build must advertise nothing: robots.ts already disallows the whole
// site there, and structured data pointing at livecontext.ai from someone else's
// domain is a claim about a site they do not run. Neither page suite covered this,
// because IS_CE is a module constant nobody was mocking.
vi.mock('@/lib/edition', () => ({ IS_CE: true }));

const fetchAllIntegrations = vi.fn();
const fetchIntegration = vi.fn();
const fetchTopIntegrations = vi.fn();
vi.mock('@/lib/integrations/publicIntegrations', () => ({
  fetchAllIntegrations: (...args: unknown[]) => fetchAllIntegrations(...args),
  fetchIntegration: (...args: unknown[]) => fetchIntegration(...args),
  fetchTopIntegrations: (...args: unknown[]) => fetchTopIntegrations(...args),
  fetchIntegrations: vi.fn(),
  PUBLIC_INTEGRATIONS_REVALIDATE_SECONDS: 3600,
  LANDING_INTEGRATIONS_REVALIDATE_SECONDS: 600,
}));

vi.mock('@/components/landing/LandingShell', () => ({
  LandingShell: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/app/[locale]/_landing/SignInButton', () => ({
  default: ({ children }: { children: React.ReactNode }) => <button type="button">{children}</button>,
}));

const jsonLd: unknown[] = [];
vi.mock('@/components/seo/JsonLd', () => ({
  default: ({ data }: { data: Record<string, unknown> }) => {
    jsonLd.push(data);
    return null;
  },
}));

vi.mock('next/navigation', () => ({ notFound: () => { throw new Error('NEXT_NOT_FOUND'); } }));

import IntegrationsDirectoryPage, { metadata } from '../page';
import IntegrationPage, { generateMetadata } from '../[slug]/page';

function integration(): PublicIntegration {
  return {
    slug: 'slack',
    name: 'Slack',
    description: 'Post messages, manage channels and read conversations from your workflows.',
    iconSlug: 'slack',
    iconUrl: null,
    toolCount: 45,
    authType: 'bearer_token',
  };
}

beforeEach(() => {
  jsonLd.length = 0;
  fetchAllIntegrations.mockResolvedValue({
    integrations: [integration()],
    totalElements: 1,
    truncated: false,
  });
  fetchTopIntegrations.mockResolvedValue({ integrations: [], totalElements: 0, truncated: false });
  fetchIntegration.mockResolvedValue({
    integration: integration(),
    documentation: null,
    tools: [{ name: 'send_message', description: 'Post a message', method: 'POST' }],
    toolsTruncated: false,
  });
});

describe('the integration pages on a self-hosted edition', () => {
  it('tells crawlers not to index the directory', () => {
    expect(metadata.robots).toEqual({ index: false, follow: true });
  });

  it('tells crawlers not to index an integration page, however substantial it is', async () => {
    const meta = await generateMetadata({ params: Promise.resolve({ slug: 'slack' }) });

    // 45 endpoints and a real description: indexable on the cloud, never here.
    expect(meta.robots).toEqual({ index: false, follow: true });
  });

  it('emits no structured data from either page', async () => {
    render(await IntegrationsDirectoryPage());
    render(await IntegrationPage({ params: Promise.resolve({ slug: 'slack' }) }));

    expect(jsonLd).toEqual([]);
  });

  it('still renders both pages, because they are reachable in the app', async () => {
    const { container: directory } = render(await IntegrationsDirectoryPage());
    const { container: detail } = render(
      await IntegrationPage({ params: Promise.resolve({ slug: 'slack' }) }),
    );

    expect(directory.textContent).toContain('Slack');
    expect(detail.textContent).toContain('Slack integration');
  });
});
