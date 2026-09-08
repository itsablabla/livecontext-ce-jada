// @vitest-environment jsdom
//
// The user menu has NO platform-status row.
//
// It had one, above Language, which put a live-polling traffic light in the menu
// people open to sign out or switch workspace. The status moved to Settings >
// Information, beside the other answers to "what is this install"
// (components/settings/PlatformStatusCard, which has its own suite).
//
// This file is kept, pointed the other way: the label is still in the translator
// mock below, so these fail the moment the row comes back rather than passing
// because the string went missing. Cloud AND CE, because the row was cloud-only
// and a partial revert would otherwise look green in CE.
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const edition = { IS_CE: false };
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return edition.IS_CE;
  },
}));

vi.mock('@/hooks/useAppVersion', () => ({
  useAppVersion: () => ({ version: null, isLoading: false, isError: false }),
}));

vi.mock('next-intl', () => ({
  useLocale: () => 'en',
  useTranslations: () => {
    const labels: Record<string, string> = {
      settings: 'Settings',
      status: 'Status',
      cost: 'Cost',
      credits: 'Credits',
      workspace: 'Workspace',
      createWorkspace: 'Create workspace',
      referAndEarn: 'Refer & earn',
      autoMode: 'Auto',
      lightMode: 'Light mode',
      darkMode: 'Dark mode',
      signOut: 'Sign out',
    };
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
  useQueryClient: () => ({
    invalidateQueries: vi.fn(() => Promise.resolve()),
    setQueryData: vi.fn(),
  }),
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (
    selector: (state: {
      currentOrgId: string | null;
      currentOrgRole: string | null;
      setCurrentOrg: () => void;
    }) => unknown,
  ) => selector({ currentOrgId: null, currentOrgRole: 'OWNER', setCurrentOrg: vi.fn() }),
}));

vi.mock('@/components/billing/BalanceBreakdown', () => ({
  BalanceBreakdownTooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

const { UserSection } = await import('../AppSidebar');

function renderUserSection() {
  render(
    <UserSection
      sidebarCollapsed={false}
      user={{ name: 'Ada Byron', email: 'ada@example.com' }}
      avatarUrl={null}
      numericUserId={1}
      planCode="PRO"
      isSubscriptionLoading={false}
      themePreference="auto"
      onThemeChange={vi.fn()}
      onSignOut={vi.fn()}
      onNavigate={vi.fn()}
      displayName="Ada Byron"
      isLoadingProfile={false}
      creditBalance={null}
      creditSubBalance={null}
      creditPaygBalance={null}
      isCreditBalanceLoading={false}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: /Ada Byron/ }));
}

beforeEach(() => {
  edition.IS_CE = false;
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('user-menu status row', () => {
  it('is not in the cloud menu', async () => {
    renderUserSection();

    // Wait for the menu itself, so this cannot pass by asserting against a menu
    // that has not rendered yet.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: /Status/ })).not.toBeInTheDocument();
  });

  it('is not in the CE menu either', async () => {
    edition.IS_CE = true;
    renderUserSection();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: /Status/ })).not.toBeInTheDocument();
  });

  it('still opens the menu on Language and Theme, so the group survived the removal', async () => {
    // The row was the first entry of its group; removing it must not have taken
    // the group with it.
    renderUserSection();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /Auto/ })).toBeInTheDocument();
  });
});
