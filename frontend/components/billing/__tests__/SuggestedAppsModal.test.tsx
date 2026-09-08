// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const mockPush = vi.hoisted(() => vi.fn());
const mockApiGet = vi.hoisted(() => vi.fn());
const mockGetSuggested = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: mockPush }),
}));

vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ user: { sub: 'u1' }, isLoading: false }),
}));

vi.mock('@/lib/api', () => ({ apiClient: { get: mockApiGet } }));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getSuggestedApplications: mockGetSuggested },
}));

// The card itself is the shared marketplace PublicationCard (tested via the
// marketplace). Stub it so these tests focus on the modal's sequencing/fetch.
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: ({ publication }: { publication: { title: string } }) => (
    <div data-testid="pub-card">{publication.title}</div>
  ),
}));

import SuggestedAppsModal from '../SuggestedAppsModal';

function renderModal() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <SuggestedAppsModal />
    </QueryClientProvider>,
  );
}

const SAMPLE_APP = {
  id: 'pub1',
  title: 'CRM Sync',
  description: 'Keep your CRM in sync',
  category: { id: 'c1', slug: 'sales-crm', name: 'Sales & CRM' },
  nodeIcons: [{ iconSlug: 'salesforce' }],
};

describe('SuggestedAppsModal', () => {
  beforeEach(() => {
    sessionStorage.clear();
    mockPush.mockReset();
    mockApiGet.mockReset();
    mockGetSuggested.mockReset();
    mockApiGet.mockResolvedValue({
      interests: ['sales-crm'],
      useCases: [],
      profession: 'sales',
      primaryGoal: 'lead-generation',
    });
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
  });

  // Also the regression for the removed hand-off: this modal used to wait for
  // a `lc:welcome-gift-done` event from the credit-gift modal that ran before
  // it. That modal is gone, so a modal still waiting would arm and then never
  // open - a silent disappearance, since nothing errors and nothing logs.
  it('arms on the onboarding flag alone, fetches and opens with suggestions', async () => {
    sessionStorage.setItem('lc_show_app_suggestions', '1');
    mockGetSuggested.mockResolvedValue({ count: 1, publications: [SAMPLE_APP] });

    renderModal();

    // The show-flag is consumed on arm so it never re-fires.
    await waitFor(() => expect(sessionStorage.getItem('lc_show_app_suggestions')).toBeNull());
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('CRM Sync')).toBeInTheDocument();
    expect(mockGetSuggested).toHaveBeenCalledWith({
      interests: ['sales-crm'],
      useCases: [],
      profession: 'sales',
      primaryGoal: 'lead-generation',
      limit: 4,
    });
  });

  it('closes the modal when a suggested card is clicked (card Link handles navigation)', async () => {
    sessionStorage.setItem('lc_show_app_suggestions', '1');
    mockGetSuggested.mockResolvedValue({ count: 1, publications: [SAMPLE_APP] });

    renderModal();

    const card = await screen.findByTestId('pub-card');
    fireEvent.click(card);

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('"Browse the marketplace" CTA navigates to the marketplace and closes', async () => {
    sessionStorage.setItem('lc_show_app_suggestions', '1');
    mockGetSuggested.mockResolvedValue({ count: 1, publications: [SAMPLE_APP] });

    renderModal();

    const cta = await screen.findByRole('button', { name: /cta/i });
    fireEvent.click(cta);

    expect(mockPush).toHaveBeenCalledWith('/en/app/marketplace');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('stays hidden when the backend returns no suggestions', async () => {
    sessionStorage.setItem('lc_show_app_suggestions', '1');
    mockGetSuggested.mockResolvedValue({ count: 0, publications: [] });

    renderModal();

    await waitFor(() => expect(mockGetSuggested).toHaveBeenCalled());
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('does not arm or fetch without the onboarding flag', async () => {
    mockGetSuggested.mockResolvedValue({ count: 1, publications: [SAMPLE_APP] });

    renderModal();

    // Give effects a chance to run.
    await Promise.resolve();
    expect(mockGetSuggested).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
