// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ThemePreference } from '@/components/ThemeProvider';
import { UserSection } from '../AppSidebar';

// UserSection reads the build version (CE-only About entry). The hook calls useAuth(),
// which requires AppDataProvider; stub it so these menu tests stay provider-free.
vi.mock('@/hooks/useAppVersion', () => ({
  useAppVersion: () => ({ version: null, isLoading: false, isError: false }),
}));

vi.mock('next-intl', () => ({
  useLocale: () => 'en',
  useTranslations: () => {
    const labels: Record<string, string> = {
      settings: 'Settings',
      pricing: 'Pricing',
      credits: 'Credits',
      about: 'About',
      information: 'Information',
      workspace: 'Workspace',
      createWorkspace: 'Create workspace',
      inviteTeammates: 'Invite teammates',
      autoMode: 'Auto',
      lightMode: 'Light mode',
      darkMode: 'Dark mode',
      signOut: 'Sign out',
      upgrade: 'Upgrade',
      viewQuota: 'View quota',
      cost: 'Cost',
    };
    return (key: string) => labels[key] ?? key;
  },
}));

const mockPush = vi.fn();
const mockRefresh = vi.fn();

vi.mock('@/i18n/navigation', () => ({
  usePathname: () => '/en/app/chat',
  useRouter: () => ({
    push: mockPush,
    refresh: mockRefresh,
  }),
}));

// Configurable workspace list returned by the org-memberships useQuery. Default empty (the
// pre-resolved/initial-load case); individual tests set a workspace to exercise the switcher.
let mockWorkspaces: Array<{ id: string; name: string; isDefault?: boolean; avatarUrl?: string | null; paused?: boolean; pendingDeletion?: boolean }> = [];

vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: () => ({ data: mockWorkspaces }),
  // AppSidebar uses useQueryClient (workspace switch/restore invalidation); the bare UserSection
  // render has no QueryClientProvider, so stub it to avoid "No QueryClient set".
  useQueryClient: () => ({ invalidateQueries: vi.fn(() => Promise.resolve()), setQueryData: vi.fn() }),
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (selector: (state: { currentOrgId: string | null; setCurrentOrg: () => void }) => unknown) =>
    selector({ currentOrgId: null, setCurrentOrg: vi.fn() }),
}));

// Render a marker carrying the variant so tests can assert WHICH upsell the gate opens:
// 'workspace' (→ PRO, extra workspaces) vs 'teammates' (→ TEAM, collaboration).
vi.mock('@/components/organization/WorkspaceUpgradeModal', () => ({
  WorkspaceUpgradeModal: ({ open, variant }: { open: boolean; variant?: string }) =>
    open ? <div data-testid="workspace-upgrade-modal" data-variant={variant ?? 'teammates'} /> : null,
}));

vi.mock('@/components/billing/BalanceBreakdown', () => ({
  BalanceBreakdownTooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock('@/lib/edition', () => ({
  IS_CE: false,
}));


/**
 * The user menu is placed by hand, so it has to be held inside the screen by hand.
 *
 * It hangs UPWARD from the avatar row at the bottom of the sidebar
 * (`translateY(-100%)`), and it is wider than the button it hangs from: the
 * 240px floor exists so the menu keeps its shape when the sidebar is collapsed
 * to a 32px rail. On a phone that made both axes wrong at once - the left edge
 * anchored to a trigger with no room to its right, and a menu taller than the
 * space above it running off the top with nothing to scroll.
 */
function renderUserSection() {
  render(
    <UserSection
      sidebarCollapsed={false}
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
      creditBalance={null}
      creditSubBalance={null}
      creditPaygBalance={null}
      isCreditBalanceLoading={false}
    />,
  );
}

/** Open the menu with the trigger reporting the rect a phone would give it. */
function openMenuAt(rect: { left: number; top: number; width: number }) {
  renderUserSection();
  const trigger = screen.getByRole('button', { name: /Owner E2E/ });
  trigger.getBoundingClientRect = () => ({
    left: rect.left,
    top: rect.top,
    right: rect.left + rect.width,
    bottom: rect.top + 40,
    width: rect.width,
    height: 40,
    x: rect.left,
    y: rect.top,
    toJSON: () => ({}),
  }) as DOMRect;
  fireEvent.click(trigger);
  return screen.getByTestId('sidebar-user-menu');
}

describe('AppSidebar user menu placement', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    Object.defineProperty(document.documentElement, 'clientWidth', { value: 1024, configurable: true, writable: true });
  });

  it('stays inside the screen when the trigger has no room to its right', () => {
    Object.defineProperty(document.documentElement, 'clientWidth', { value: 410, configurable: true, writable: true });

    const menu = openMenuAt({ left: 220, top: 700, width: 216 });

    // 220 + 240 = 460 on a 410px screen. Clamped to 410 - 240 - 8.
    expect(menu.style.left).toBe('162px');
    expect(menu.style.width).toBe('240px');
  });

  it('never grows taller than the space it hangs into, and scrolls instead', () => {
    // The menu's bottom edge sits 8px above a trigger at y=300, so it may use
    // 284px and still leave an 8px gutter at the top of the screen. Its rows
    // plus the credit readout plus an open submenu are taller than that.
    const menu = openMenuAt({ left: 16, top: 300, width: 216 });

    expect(menu.style.maxHeight).toBe('284px');
    expect(menu.style.overflowY).toBe('auto');
  });

  it('keeps a usable menu even when the trigger sits at the very top', () => {
    // A floor, not a formula: 0px of space above would otherwise collapse the
    // menu to nothing rather than give it a scroll.
    const menu = openMenuAt({ left: 16, top: 0, width: 216 });

    expect(menu.style.maxHeight).toBe('200px');
  });
});
