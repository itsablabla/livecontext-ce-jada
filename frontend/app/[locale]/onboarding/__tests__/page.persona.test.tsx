// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  FIRST_BUILD_PROMPT_KEY,
  consumeFirstBuildPrompt,
  storeFirstBuildPrompt,
} from '@/lib/onboarding/firstBuildPrompt';
/**
 * The builder is SPIED, not stubbed: it delegates to the real implementation so
 * every other test here still exercises the real mapping, and only the "it
 * throws" case overrides it. The real function is captured inside the factory,
 * because importing it normally in this file would resolve to the spy and
 * recurse.
 */
const real = vi.hoisted(() => ({
  buildFirstBuildPrompt: null as
    | typeof import('@/lib/onboarding/firstBuildPrompt')['buildFirstBuildPrompt']
    | null,
}));

vi.mock('@/lib/onboarding/firstBuildPrompt', async () => {
  const actual = await vi.importActual<typeof import('@/lib/onboarding/firstBuildPrompt')>(
    '@/lib/onboarding/firstBuildPrompt',
  );
  real.buildFirstBuildPrompt = actual.buildFirstBuildPrompt;
  return {
    ...actual,
    buildFirstBuildPrompt: (...args: Parameters<typeof actual.buildFirstBuildPrompt>) =>
      mocks.buildFirstBuildPrompt(...args),
  };
});

/**
 * Persona questionnaire (steps 2 and 3) of the onboarding page.
 *
 * Pins the new questions (primary goal + tools, previous tool + referral
 * source), the per-step completion rules, the bounded save payload (no
 * `interests` / `useCases` / `experienceLevel` any more), the restore of
 * saved answers, and the `onboarding_completed` analytics props.
 */
const mocks = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  track: vi.fn(),
  buildFirstBuildPrompt: vi.fn(),
}));

vi.mock('next-intl', () => ({
  // Keys echo, so queries can find controls by their key text. `firstBuild.*`
  // and `tools.*` are the exception and must resolve to something that is NOT
  // their own key: `buildFirstBuildPrompt` reads a value equal to its key as a
  // MISSING message (next-intl's fallback returns the key path). While tool
  // labels echoed, the tools clause could never be built, and this file could
  // not tell whether the page passes `toolsUsed` at all.
  useTranslations: () => (key: string, values?: Record<string, string>) => {
    if (key === 'firstBuild.toolsClause') return `tools clause: ${values?.tools}`;
    if (key.startsWith('firstBuild.') || key.startsWith('tools.')) return `${key} (resolved)`;
    return key;
  },
  useLocale: () => 'en',
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({
    user: { sub: 'u1', name: 'Jane', email: 'jane@example.com' },
    isLoading: false,
    isAuthenticated: true,
    loginWithRedirect: vi.fn(),
  }),
}));

vi.mock('@/lib/api', () => ({
  apiClient: { get: mocks.apiGet, post: mocks.apiPost },
}));

vi.mock('@/lib/edition', () => ({ IS_CE: false }));

vi.mock('@/lib/analytics/analytics', () => ({ track: mocks.track }));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <div data-testid="spinner" />,
}));

import OnboardingPage from '../page';

type StatusOverrides = Record<string, unknown>;

function mockStatus(overrides: StatusOverrides) {
  mocks.apiGet.mockImplementation(async (path: string) => {
    if (path === '/auth/email/status') return { verified: true };
    if (path === '/auth-service/api/onboarding/status') {
      return {
        needsOnboarding: true,
        completed: false,
        skipped: false,
        currentStep: 2,
        displayName: 'Jane',
        profession: 'sales',
        companySize: 'solo',
        ...overrides,
      };
    }
    if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
      return { available: true, message: '' };
    }
    throw new Error(`unexpected GET ${path}`);
  });
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <OnboardingPage />
    </QueryClientProvider>,
  );
}

const pressed = (name: string | RegExp) =>
  screen.getByRole('button', { name }).getAttribute('aria-pressed');

describe('Onboarding persona questionnaire', () => {
  beforeEach(() => {
    sessionStorage.clear();
    mocks.apiGet.mockReset();
    mocks.apiPost.mockReset();
    mocks.track.mockReset();
    mocks.apiPost.mockResolvedValue({});
    // Default: delegate to the real builder, so only the throw case overrides it.
    mocks.buildFirstBuildPrompt.mockReset();
    mocks.buildFirstBuildPrompt.mockImplementation((...args: unknown[]) =>
      real.buildFirstBuildPrompt!(
        ...(args as Parameters<NonNullable<typeof real.buildFirstBuildPrompt>>),
      ),
    );
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
  });

  it('step 2 asks for ONE primary goal and the tools in use, and requires the goal to continue', async () => {
    mockStatus({ currentStep: 2 });
    renderPage();

    expect(await screen.findByText('step2.title')).toBeInTheDocument();
    // Old questions are gone.
    expect(screen.queryByText('interests.automation')).not.toBeInTheDocument();
    expect(screen.queryByText('useCasesLabel')).not.toBeInTheDocument();
    expect(screen.queryByText('experienceLevel')).not.toBeInTheDocument();
    // No free-text input on this step: the only inputs would be the display name (step 1).
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();

    const next = screen.getByRole('button', { name: /^next$/ });
    expect(next).toBeDisabled();

    // Primary goal is single-choice: picking a second one replaces the first.
    fireEvent.click(screen.getByRole('button', { name: 'primaryGoals.reporting' }));
    fireEvent.click(screen.getByRole('button', { name: 'primaryGoals.dataSync' }));
    expect(pressed('primaryGoals.reporting')).toBe('false');
    expect(pressed('primaryGoals.dataSync')).toBe('true');
    expect(next).toBeEnabled();

    // Tools are multi-choice and toggle.
    fireEvent.click(screen.getByRole('button', { name: 'tools.gmail (resolved)' }));
    fireEvent.click(screen.getByRole('button', { name: 'tools.slack (resolved)' }));
    fireEvent.click(screen.getByRole('button', { name: 'tools.gmail (resolved)' }));
    expect(pressed('tools.gmail (resolved)')).toBe('false');
    expect(pressed('tools.slack (resolved)')).toBe('true');

    fireEvent.click(next);

    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledTimes(1));
    const [endpoint, payload] = mocks.apiPost.mock.calls[0];
    expect(endpoint).toBe('/auth-service/api/onboarding/save');
    expect(payload).toEqual({
      displayName: 'Jane',
      profession: 'sales',
      companySize: 'solo',
      primaryGoal: 'data-sync',
      toolsUsed: ['slack'],
      previousTool: null,
      referralSource: null,
      currentStep: 2,
    });
    expect(payload).not.toHaveProperty('interests');
    expect(payload).not.toHaveProperty('useCases');
    expect(payload).not.toHaveProperty('experienceLevel');

    expect(await screen.findByText('step3.title')).toBeInTheDocument();
  });

  it('step 3 requires both answers, completes with bounded values, and tracks the persona props', async () => {
    mockStatus({ currentStep: 3, primaryGoal: 'reporting', toolsUsed: ['gmail', 'slack'] });
    renderPage();

    expect(await screen.findByText('step3.title')).toBeInTheDocument();
    expect(screen.getByText('previousToolLabel')).toBeInTheDocument();
    expect(screen.getByText('referralSourceLabel')).toBeInTheDocument();

    const complete = screen.getByRole('button', { name: /complete/ });
    expect(complete).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'previousTools.zapierMake' }));
    expect(complete).toBeDisabled(); // referral source still missing

    fireEvent.click(screen.getByRole('button', { name: 'referralSources.wordOfMouth' }));
    expect(complete).toBeEnabled();
    fireEvent.click(complete);

    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledTimes(1));
    const [endpoint, payload] = mocks.apiPost.mock.calls[0];
    expect(endpoint).toBe('/auth-service/api/onboarding/complete');
    expect(payload).toMatchObject({
      primaryGoal: 'reporting',
      toolsUsed: ['gmail', 'slack'],
      previousTool: 'zapier-make',
      referralSource: 'word-of-mouth',
      currentStep: 3,
    });

    await waitFor(() => expect(mocks.track).toHaveBeenCalledWith('onboarding_completed', {
      first_build_prompt_proposed: true,
      profession: 'sales',
      primary_goal: 'reporting',
      tools_count: 2,
      previous_tool: 'zapier-make',
      referral_source: 'word-of-mouth',
    }));
    expect(sessionStorage.getItem('lc_show_app_suggestions')).toBe('1');
    // The chat home reads this on the next screen and fills the composer with it.
    const proposal = consumeFirstBuildPrompt();
    expect(proposal).toContain('firstBuild.goalPrompts.reporting');
    // ...and the tools the user ticked reached it. Without this the page could
    // stop passing `toolsUsed` and every other assertion here would stay green.
    expect(proposal).toContain('tools.gmail (resolved)');
    expect(proposal).toContain('tools.slack (resolved)');
  });

  it('completes normally when building the proposal throws', async () => {
    // The completion has ALREADY succeeded, server-side and on screen, by the
    // time the prompt is built. So the cost of a throw is not an error the user
    // sees: it is the three lines AFTER it being skipped in silence, losing the
    // completion event and the suggested-apps hand-off on an account that
    // finished correctly. That is what this pins.
    mocks.buildFirstBuildPrompt.mockImplementationOnce(() => {
      throw new Error('translator exploded');
    });
    mockStatus({ currentStep: 3, primaryGoal: 'reporting', toolsUsed: ['gmail'] });
    renderPage();

    expect(await screen.findByText('step3.title')).toBeInTheDocument();
    fireEvent.click(screen.getByText('previousTools.n8n'));
    fireEvent.click(screen.getByText('referralSources.search'));
    const complete = screen.getByRole('button', { name: 'complete' });
    await waitFor(() => expect(complete).toBeEnabled());
    fireEvent.click(complete);

    // The flow still finishes: analytics fires and the hand-off flag is set.
    await waitFor(() => expect(mocks.track).toHaveBeenCalledWith(
      'onboarding_completed',
      expect.objectContaining({ first_build_prompt_proposed: false }),
    ));
    // The hand-off that a throw would have swallowed.
    expect(sessionStorage.getItem('lc_show_app_suggestions')).toBe('1');
    // ...and the flow really did reach its end state: the questionnaire is
    // gone, replaced by the completed spinner that redirects to chat.
    expect(screen.queryByText('step3.title')).not.toBeInTheDocument();
    expect(screen.getByTestId('spinner')).toBeInTheDocument();
    // The spinner IS the redirect state: a separate effect navigates to chat on
    // `pageState === 'completed'`, so reaching it means the user is not stranded
    // (jsdom cannot follow the navigation itself).
  });

  it('proposes no first message when the goal is "Something else", and clears any parked one', async () => {
    // Parked first: without it this asserts nothing, because `beforeEach`
    // already emptied the slot. Completing twice in a tab is possible, so a
    // second pass that says nothing must overwrite the first pass's sentence.
    storeFirstBuildPrompt('a proposal from an earlier pass');
    mockStatus({ currentStep: 3, primaryGoal: 'other', toolsUsed: ['gmail'] });
    renderPage();

    expect(await screen.findByText('step3.title')).toBeInTheDocument();
    fireEvent.click(screen.getByText('previousTools.n8n'));
    fireEvent.click(screen.getByText('referralSources.search'));
    const complete = screen.getByRole('button', { name: 'complete' });
    await waitFor(() => expect(complete).toBeEnabled());
    fireEvent.click(complete);

    await waitFor(() => expect(mocks.track).toHaveBeenCalledWith(
      'onboarding_completed',
      expect.objectContaining({ first_build_prompt_proposed: false }),
    ));
    // "Something else" says nothing specific, so the composer is left alone
    // rather than filled with a generic sentence.
    expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toBeNull();
  });

  it('restores only answers that are options in this edition (CE goal and unknown tool are dropped)', async () => {
    mockStatus({
      currentStep: 2,
      primaryGoal: 'internal-automation', // CE-only goal, not offered in cloud
      toolsUsed: ['gmail', 'bogus-tool', 'gmail'],
      previousTool: 'n8n',
      referralSource: 'not-a-source',
    });
    renderPage();

    expect(await screen.findByText('step2.title')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'ce.useCases.internalAutomation' })).not.toBeInTheDocument();
    // No goal restored, so the step cannot advance yet.
    expect(screen.getByRole('button', { name: /^next$/ })).toBeDisabled();
    expect(pressed('tools.gmail (resolved)')).toBe('true');
    expect(pressed('tools.slack (resolved)')).toBe('false');

    fireEvent.click(screen.getByRole('button', { name: 'primaryGoals.aiAssistant' }));
    fireEvent.click(screen.getByRole('button', { name: /^next$/ }));

    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledTimes(1));
    expect(mocks.apiPost.mock.calls[0][1]).toMatchObject({
      primaryGoal: 'ai-assistant',
      toolsUsed: ['gmail'],
      previousTool: 'n8n',
      referralSource: null,
    });

    // Step 3 shows the restored previous tool and nothing for the unknown source.
    expect(await screen.findByText('step3.title')).toBeInTheDocument();
    expect(pressed('previousTools.n8n')).toBe('true');
    expect(screen.getByRole('button', { name: /complete/ })).toBeDisabled();
  });

  it('skip stays available on the persona steps and tracks the step it was skipped at', async () => {
    mockStatus({ currentStep: 2 });
    renderPage();

    expect(await screen.findByText('step2.title')).toBeInTheDocument();
    // The skip button waits for the display-name availability check (debounced).
    const skip = screen.getByRole('button', { name: 'skipForNow' });
    await waitFor(() => expect(skip).toBeEnabled());
    fireEvent.click(skip);

    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledWith(
      '/auth-service/api/onboarding/skip',
      { displayName: 'Jane' },
    ));
    await waitFor(() => expect(mocks.track).toHaveBeenCalledWith('onboarding_skipped', { skipped_at_step: 2 }));
    // Skipping tells us nothing beyond a display name, so there is nothing to propose.
    expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toBeNull();
  });

  it('skipping CLEARS a proposal parked by an earlier pass through onboarding', async () => {
    // Reaching onboarding twice in one tab is possible (a completion whose
    // status has not settled bounces back). Not writing a new proposal is not
    // enough: the stale one would still be waiting in the composer, greeting a
    // user who just declined to answer.
    storeFirstBuildPrompt('a proposal from an earlier pass');
    mockStatus({ currentStep: 2 });
    renderPage();

    expect(await screen.findByText('step2.title')).toBeInTheDocument();
    const skip = screen.getByRole('button', { name: 'skipForNow' });
    await waitFor(() => expect(skip).toBeEnabled());
    fireEvent.click(skip);

    await waitFor(() => expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toBeNull());
  });
});
