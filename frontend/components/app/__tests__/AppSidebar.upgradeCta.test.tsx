// @vitest-environment jsdom
/**
 * Where the sidebar's Upgrade CTA is allowed to exist.
 *
 * The cloud upsell moved into the header credit dial, so the sidebar keeps it in
 * CE ONLY, where there is no dial and the upsell means "link this install to the
 * cloud". That is one boolean - `showUpgrade = IS_CE && !isInstallCloudLinked` -
 * and nothing asserted it: reverting it to the old expression, or fat-fingering
 * it to `IS_CE || !hasActiveSubscription`, left the whole suite green while
 * either duplicating the upsell or resurrecting it on every cloud page.
 *
 * The plan NAME must survive in both editions - it is how a reader knows what
 * they are on - and must stay a LABEL: it used to open the plan comparison from
 * every page in the app, which is exactly the reach that overlay lost.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { UserSection } from '../AppSidebar';

vi.mock('@/hooks/useAppVersion', () => ({
  useAppVersion: () => ({ version: null, isLoading: false, isError: false }),
}));

vi.mock('next-intl', () => ({
  useLocale: () => 'en',
  useTranslations: () => {
    const labels: Record<string, string> = { upgrade: 'Upgrade', viewQuota: 'View quota', cost: 'Cost' };
    return (key: string) => labels[key] ?? key;
  },
}));

const cloudLink = vi.hoisted(() => ({ status: { installLinked: false, cloudPlanCode: null as string | null } }));

vi.mock('@/i18n/navigation', () => ({
  usePathname: () => '/en/app/chat',
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));

// Two queries live in this component: the org memberships and, in CE, the
// cloud-link status. Route by query key - returning the workspace list for both
// would leave `installLinked` undefined and quietly make every CE case read
// "unlinked", which is the answer one of these tests is trying to disprove.
vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: (opts: { queryKey?: unknown[] }) =>
    JSON.stringify(opts.queryKey ?? []).includes('cloud-link')
      ? { data: cloudLink.status }
      : { data: [] },
  useQueryClient: () => ({ invalidateQueries: vi.fn(() => Promise.resolve()), setQueryData: vi.fn() }),
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (selector: (s: { currentOrgId: string | null; currentOrgRole: string | null; setCurrentOrg: () => void }) => unknown) =>
    selector({ currentOrgId: null, currentOrgRole: 'OWNER', setCurrentOrg: vi.fn() }),
}));

vi.mock('@/components/billing/BalanceBreakdown', () => ({
  BalanceBreakdownTooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

// The credit block owns its own tests; here it only has to not require a
// QueryClientProvider, so the whole component is stubbed out.
// The ring MUST pass its children through even when stubbed: it wraps the
// avatar, so a stub returning null would take the avatar out of the sidebar and
// make every assertion here read a tree the app never renders.
vi.mock('@/components/billing/SidebarCreditBalance', () => ({
  SidebarCreditRing: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  SidebarCreditMenuSection: () => null,
}));

const edition = vi.hoisted(() => ({ isCe: false }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return edition.isCe;
  },
}));

vi.mock('@/lib/api/cloud-link.service', () => ({
  CLOUD_NO_SUBSCRIPTION: '__NONE__',
  cloudLinkService: { getStatus: () => Promise.resolve(cloudLink.status) },
}));

function renderUserSection(planCode: string | null) {
  render(
    <UserSection
      sidebarCollapsed={false}
      user={{ name: 'Owner E2E', email: 'owner@example.com' }}
      avatarUrl={null}
      numericUserId={42}
      planCode={planCode}
      isSubscriptionLoading={false}
      themePreference="auto"
      onThemeChange={vi.fn()}
      onSignOut={vi.fn()}
      onNavigate={vi.fn()}
      displayName="Owner E2E"
      isLoadingProfile={false}
      creditBalance={null}
      creditSubBalance={null}
      creditPaygBalance={null}
      isCreditBalanceLoading={false}
    />,
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  edition.isCe = false;
  cloudLink.status = { installLinked: false, cloudPlanCode: null };
});

describe('AppSidebar Upgrade CTA - cloud', () => {
  it('shows NO Upgrade CTA for a FREE cloud account: the header dial owns it now', () => {
    edition.isCe = false;
    renderUserSection('FREE');

    expect(screen.queryByText('Upgrade')).toBeNull();
  });

  it('shows NO Upgrade CTA for a subscribed cloud account either', () => {
    edition.isCe = false;
    renderUserSection('PRO');

    expect(screen.queryByText('Upgrade')).toBeNull();
  });

  it('still names the plan, as a label rather than a control', () => {
    // The name stays: it is how a reader knows what they are on. It no longer
    // opens the plan comparison - that overlay is reachable from the pricing
    // page only, so a surface the reader passes through constantly does not
    // carry it.
    edition.isCe = false;
    renderUserSection('PRO');

    const name = screen.getByTestId('sidebar-plan-name');
    expect(name).toHaveTextContent('Pro');
    expect(name.getAttribute('role')).toBeNull();
    expect(name.getAttribute('tabindex')).toBeNull();
  });
});

describe('AppSidebar Upgrade CTA - CE', () => {
  it('KEEPS the Upgrade CTA on an unlinked install, which has no header dial', () => {
    edition.isCe = true;
    cloudLink.status = { installLinked: false, cloudPlanCode: null };
    renderUserSection('FREE');

    return waitFor(() => {
      expect(screen.getByText('Community')).toBeInTheDocument();
      expect(screen.getByText('Upgrade')).toBeInTheDocument();
    });
  });

  it('drops it once the install is cloud-linked, since billing lives on the cloud account', () => {
    edition.isCe = true;
    cloudLink.status = { installLinked: true, cloudPlanCode: 'TEAM' };
    renderUserSection('FREE');

    return waitFor(() => {
      expect(screen.getByText('CE Team')).toBeInTheDocument();
      expect(screen.queryByText('Upgrade')).toBeNull();
    });
  });
});
