// @vitest-environment jsdom
/**
 * That the sidebar actually MOUNTS the right credit block per edition.
 *
 * The user block carries a two-way ternary in each layout: the new ring block in
 * cloud, the original coin badge in CE (which bills in dollars against no
 * monthly grant, so it has no denominator to draw a ring from). Every other
 * AppSidebar suite stubs `SidebarCreditBalance` away, so deleting either branch,
 * or swapping the edition test, left the whole suite green.
 *
 * Both layouts are covered: the collapsed rail and the expanded panel each own
 * their own copy of the ternary, and only one of them is on screen at a time.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { UserSection } from '../AppSidebar';

vi.mock('@/hooks/useAppVersion', () => ({
  useAppVersion: () => ({ version: null, isLoading: false, isError: false }),
}));

vi.mock('next-intl', () => ({
  useLocale: () => 'en',
  useTranslations: () => {
    const labels: Record<string, string> = { upgrade: 'Upgrade', viewQuota: 'View quota', cost: 'Cost', credits: 'Credits' };
    return (key: string) => labels[key] ?? key;
  },
}));

vi.mock('@/i18n/navigation', () => ({
  usePathname: () => '/en/app/chat',
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));

vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: () => ({ data: [] }),
  useQueryClient: () => ({ invalidateQueries: vi.fn(() => Promise.resolve()), setQueryData: vi.fn() }),
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (selector: (s: { currentOrgId: string | null; currentOrgRole: string | null; setCurrentOrg: () => void }) => unknown) =>
    selector({ currentOrgId: null, currentOrgRole: 'OWNER', setCurrentOrg: vi.fn() }),
}));

vi.mock('@/components/billing/BalanceBreakdown', () => ({
  BalanceBreakdownTooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

// Rendered as a marker, NOT stubbed to null: this file exists to prove the
// mount point is there, so it has to be able to see it.
// Rendered as a marker AND as a wrapper, not stubbed to null: this file exists
// to prove the mount point is there, and the component's contract is that it
// always renders its children (the avatar) whatever it decides about the ring.
// Mirrors the real components' contracts, because both are what this file is
// here to observe: the ring ALWAYS renders its children (the avatar) and adds
// no wrapper in CE, and the menu section renders nothing in CE. A stub that
// ignored the edition would report a ring on an install that cannot have one.
vi.mock('@/components/billing/SidebarCreditBalance', () => ({
  SidebarCreditRing: ({ children }: { children?: React.ReactNode }) =>
    edition.isCe ? <>{children}</> : <span data-testid="sidebar-credit-block">{children}</span>,
  SidebarCreditMenuSection: () => (edition.isCe ? null : <div data-testid="sidebar-credit-menu-section" />),
}));

const edition = vi.hoisted(() => ({ isCe: false }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return edition.isCe;
  },
}));

vi.mock('@/lib/api/cloud-link.service', () => ({
  CLOUD_NO_SUBSCRIPTION: '__NONE__',
  cloudLinkService: { getStatus: () => Promise.resolve({ installLinked: false, cloudPlanCode: null }) },
}));

function renderUserSection(sidebarCollapsed: boolean, over: Record<string, unknown> = {}) {
  render(
    <UserSection
      sidebarCollapsed={sidebarCollapsed}
      user={{ name: 'Owner E2E', email: 'owner@example.com' }}
      avatarUrl={null}
      numericUserId={42}
      planCode="FREE"
      isSubscriptionLoading={false}
      themePreference="auto"
      onThemeChange={vi.fn()}
      onSignOut={vi.fn()}
      onNavigate={vi.fn()}
      displayName="Owner E2E"
      isLoadingProfile={false}
      creditBalance={12.4}
      creditSubBalance={12.4}
      creditPaygBalance={0}
      isCreditBalanceLoading={false}
      {...over}
    />,
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  edition.isCe = false;
});

describe('AppSidebar credit block - cloud', () => {
  it('wraps the avatar in the expanded panel', () => {
    renderUserSection(false);

    const block = screen.getByTestId('sidebar-credit-block');
    // The avatar must be INSIDE it: the ring is an overlay on the avatar, so a
    // mount that renders the block as a sibling would draw the ring on nothing.
    expect(block.querySelector('img, svg, canvas, div')).not.toBeNull();
  });

  it('wraps it in the collapsed rail too', () => {
    renderUserSection(true);

    const block = screen.getByTestId('sidebar-credit-block');
    expect(block.querySelector('img, svg, canvas, div')).not.toBeNull();
  });

  it('mounts it exactly ONCE per layout, not in both slots at the same time', () => {
    // The two layouts are mutually exclusive branches of the same component;
    // rendering both would put two rings on screen.
    renderUserSection(false);
    expect(screen.getAllByTestId('sidebar-credit-block')).toHaveLength(1);
  });

  it('puts NO credit figure on the user row any more', () => {
    // The wallet is the ring around the avatar now. A number beside it would be
    // a second indicator saying the same thing.
    renderUserSection(false);
    expect(screen.queryByText('12.4')).toBeNull();
    expect(screen.queryByText('$12.4')).toBeNull();
  });
});

describe('AppSidebar credit block - CE', () => {
  /*
   * READ THIS BEFORE TRUSTING THE FIRST TWO TESTS.
   *
   * They render UserSection DIRECTLY with a non-null creditBalance, which is a
   * state production never reaches: AppSidebar passes
   * `creditBalance={IS_CE ? null : creditBalance}`, so in CE the badge's own
   * null-guard is never satisfied and a self-hosted sidebar shows no cost
   * indicator at all. So these pin the component's CONTRACT ("given a balance
   * in CE, render the coin badge and not the ring"), not a user-visible
   * behaviour. The suite below pins the wiring that makes it unreachable, so
   * the gap is recorded rather than implied.
   */
  it('renders the dollar cost badge and NOT the ring, expanded, IF fed a balance', () => {
    edition.isCe = true;
    renderUserSection(false);

    expect(screen.queryByTestId('sidebar-credit-block')).toBeNull();
    expect(screen.getByText('$12.4')).toBeInTheDocument();
  });

  it('does the same in the collapsed rail', () => {
    edition.isCe = true;
    renderUserSection(true);

    expect(screen.queryByTestId('sidebar-credit-block')).toBeNull();
    expect(screen.getByText('$12.4')).toBeInTheDocument();
  });

  it('renders NOTHING when fed the null CE actually passes', () => {
    // The state a real self-hosted install is in.
    edition.isCe = true;
    renderUserSection(false, { creditBalance: null, creditSubBalance: null, creditPaygBalance: null });

    expect(screen.queryByTestId('sidebar-credit-block')).toBeNull();
    expect(screen.queryByText(/^\$/)).toBeNull();
  });
});

describe('AppSidebar - the wiring that decides which branch can run', () => {
  // No AppSidebar suite renders AppSidebar itself (they all import UserSection),
  // so the prop wiring above them is invisible to every test. Deleting the CE
  // guard, or adding one for cloud, changed nothing anywhere. Read as source
  // because rendering the whole sidebar is a different and much heavier fixture.
  const source = readFileSync(join(__dirname, '..', 'AppSidebar.tsx'), 'utf-8');

  it('still nulls the credit props in CE, which is why the CE badge is unreachable', () => {
    expect(source).toMatch(/creditBalance=\{IS_CE \? null : creditBalance\}/);
    expect(source).toMatch(/creditSubBalance=\{IS_CE \? null : creditSubBalance\}/);
    expect(source).toMatch(/creditPaygBalance=\{IS_CE \? null : creditPaygBalance\}/);
  });

  it('does NOT null them in cloud, where the CE badge needs them', () => {
    // Written as "the cloud arm must be the live value", not as "one exact wrong
    // spelling is absent": a `not.toMatch(/creditBalance=\{null\}/)` passed on a
    // swapped ternary, on `{IS_CE ? null : null}`, and on the prop being dropped.
    const props = ['creditBalance', 'creditSubBalance', 'creditPaygBalance'];
    for (const prop of props) {
      const m = source.match(new RegExp(`${prop}=\{([^}]*)\}`));
      expect(m, `${prop} must still be passed to UserSection`).not.toBeNull();
      expect(m![1].replace(/\s+/g, ' ')).toBe(`IS_CE ? null : ${prop}`);
    }
  });

  it('gives the credits menu section an Upgrade route, since the top bar has none', () => {
    // The upsell lives in that section now. If this prop were dropped, a cloud
    // account would have no Upgrade CTA in the chrome at all.
    expect(source).toMatch(/onUpgrade=\{\(\) => \{ onNavigate\('\/app\/settings\/pricing'\); setShowMenu\(false\); \}\}/);
  });

  it('no longer carries a Credits ROW in cloud, which the section replaced', () => {
    // The row only navigated to the usage page; the section shows the figures
    // and links there itself. Keeping both says the same thing twice.
    expect(source).toContain("...(IS_CE");
    expect(source).not.toMatch(/icon: Coins, label: IS_CE \? t\('cost'\) : t\('credits'\)/);
  });

  it("points the credits readout at the usage page, in the reader's locale", () => {
    // Same reasoning as the Upgrade prop above: this readout is the only route
    // to the usage page from this menu, and its two halves are written two
    // lines apart, so a href leading somewhere else would be silent. The
    // locale prefix is the panel's job (it renders the app's Link), and that
    // half is asserted on the rendered markup in CreditBalance.test.
    expect(source).toMatch(/const QUOTA_PATH = '\/app\/settings\/quota';/);
    expect(source).toMatch(/href: QUOTA_PATH/);
    expect(source).toMatch(/onNavigate: \(\) => \{ onNavigate\(QUOTA_PATH\); setShowMenu\(false\); \}/);
  });

  it('no longer builds a Pricing ROW into the menu groups', () => {
    // Read as source only because this suite already has the file: the rendered
    // proof, in both editions, is in AppSidebar.themeMenu and AppSidebar.ceMenu.
    // The icon import is NOT asserted here - reintroducing CreditCard for an
    // unrelated row would fail this test for a reason it does not mean.
    expect(source).not.toMatch(/label: t\('pricing'\)/);
  });

  it('no longer carries an About row', () => {
    expect(source).not.toContain('isAbout');
    expect(source).not.toContain('AboutMenuVersion');
  });
});
