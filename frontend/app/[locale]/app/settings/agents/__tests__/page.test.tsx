// @vitest-environment jsdom
/**
 * Settings > Agents & Chat guards its three states like every sibling settings page:
 * a skeleton while auth is still resolving, a sign-in CTA when unauthenticated, and the
 * editor only once authenticated.
 *
 * The e2e smoke for this route only asserts that the page renders SOMETHING without a
 * client error, which the sign-in CTA also satisfies - so the "authenticated user gets
 * the editor, not the CTA" case has to be pinned here.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';

let authState = { isAuthenticated: true, isAuthChecking: false };
const loginWithRedirect = vi.fn();

vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => authState }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ loginWithRedirect }) }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/components/skeletons', () => ({
  SettingsPageSkeleton: () => <div data-testid="settings-page-skeleton" />,
}));
vi.mock('@/components/settings/AgentChatDefaults', () => ({
  AgentChatDefaults: () => <div data-testid="agent-chat-defaults" />,
}));

import AgentSettingsPage from '../page';

beforeEach(() => {
  authState = { isAuthenticated: true, isAuthChecking: false };
  loginWithRedirect.mockClear();
});
afterEach(() => cleanup());

describe('Settings > Agents & Chat page', () => {
  it('shows the defaults editor to an authenticated user', () => {
    render(<AgentSettingsPage />);
    expect(screen.getByTestId('agent-chat-defaults')).toBeTruthy();
    expect(screen.queryByText('unauthorized')).toBeNull();
  });

  // Rendering the editor while auth is still resolving would fire its GET /v3/chat/defaults
  // without a token and flash an empty form over the user real settings. It must be the
  // SHARED settings skeleton: a local one promises a shape the page never lands in.
  it('shows the shared settings skeleton, not the editor, while auth is still resolving', () => {
    authState = { isAuthenticated: false, isAuthChecking: true };
    render(<AgentSettingsPage />);
    expect(screen.getByTestId('settings-page-skeleton')).toBeTruthy();
    expect(screen.queryByTestId('agent-chat-defaults')).toBeNull();
    expect(screen.queryByText('unauthorized')).toBeNull();
  });

  it('shows the sign-in CTA once auth resolved to signed-out', () => {
    authState = { isAuthenticated: false, isAuthChecking: false };
    render(<AgentSettingsPage />);
    expect(screen.getByText('unauthorized')).toBeTruthy();
    expect(screen.queryByTestId('agent-chat-defaults')).toBeNull();

    screen.getByRole('button').click();
    expect(loginWithRedirect).toHaveBeenCalled();
  });
});
