// @vitest-environment jsdom
/**
 * One application row, two jobs, and the second must not change the first.
 *
 * <p>This component draws the Home page's application row for every reader. The studio narrows it on
 * a second AXIS rather than growing a second gallery, which is the right call only if the unscoped
 * path is byte-for-byte what it was: the curated-first fetch, and the reader's stored
 * Favourites/Highlights pick under the key it has always used.
 *
 * <p>So half of this suite is about the new behaviour and half is about the old one being untouched.
 * The second half is the one that matters: it is a regression surface on the app's landing screen.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getHighlights = vi.fn();
const getMarketplacePublications = vi.fn();
const getFavorites = vi.fn();
const getFavoriteIds = vi.fn();
const getAcquiredApplicationsPage = vi.fn();

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('next/link', () => ({ default: ({ children }: { children?: React.ReactNode }) => <a>{children}</a> }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isReady: true, numericUserId: 1 }),
}));
vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ isLoading: false, isInstallCloudLinked: false }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (selector: (s: { currentOrgId: string }) => unknown) => selector({ currentOrgId: 'org-1' }),
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: { getMarketplacePublications: (...a: unknown[]) => getMarketplacePublications(...a) },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getHighlights: (...a: unknown[]) => getHighlights(...a),
    getRemoteHighlights: vi.fn(),
    getRemoteMarketplacePublications: vi.fn(),
    getFavorites: (...a: unknown[]) => getFavorites(...a),
    getAcquiredApplicationsPage: (...a: unknown[]) => getAcquiredApplicationsPage(...a),
    getLandingSnapshot: vi.fn(async () => ({ landing: null })),
    getPublicationByIdPublic: vi.fn(),
  },
}));
vi.mock('@/lib/api/orchestrator/favorite.service', () => ({
  favoriteService: { getFavoriteIds: (...a: unknown[]) => getFavoriteIds(...a) },
}));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => null }));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => null }));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));

import { HighlightedApps } from '../HighlightedApps';

function pub(id: string, title: string, category?: unknown) {
  return {
    id,
    title,
    description: '',
    displayMode: 'APPLICATION',
    creditsPerUse: 0,
    publisherId: 'p1',
    publisherName: 'Publisher',
    ...(category as Record<string, unknown>),
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  window.localStorage.clear();
  getHighlights.mockResolvedValue({ highlights: [{ publication: pub('h1', 'A curated app') }] });
  getMarketplacePublications.mockResolvedValue({ publications: [pub('m1', 'A studio app')] });
  getFavorites.mockResolvedValue({ favorites: [] });
  getFavoriteIds.mockResolvedValue([]);
  getAcquiredApplicationsPage.mockResolvedValue({ items: [] });
});
afterEach(cleanup);

describe('HighlightedApps - the Home row is untouched', () => {
  it('still asks for the curated highlights first, and never for a category', async () => {
    render(<HighlightedApps />);

    await waitFor(() => expect(getHighlights).toHaveBeenCalledWith('APPLICATION'));
    // The curated list is the Home row's whole point; a category listing here would replace an
    // editorial choice with an arbitrary slice of the catalogue.
    expect(getMarketplacePublications).not.toHaveBeenCalled();
  });

  it('still reads the stored pick under the key it has always used', async () => {
    // A reader's existing preference lives at this exact key. Scoping it per row is only safe if the
    // unscoped one keeps working, and that is what this pins.
    window.localStorage.setItem('lc.home.highlightMode', 'FAVORITES');
    render(<HighlightedApps />);

    await waitFor(() => expect(getHighlights).toHaveBeenCalled());
    expect(window.localStorage.getItem('lc.home.highlightMode')).toBe('FAVORITES');
  });
});

describe('HighlightedApps - a studio-scoped row', () => {
  it('asks the SERVER for the studio axis, and skips curation', async () => {
    render(<HighlightedApps studioOnly heading="Studio apps" />);

    // The axis, not a category: the third argument stays undefined so the row composes with whatever
    // category each application already carries.
    await waitFor(() => expect(getMarketplacePublications)
      .toHaveBeenCalledWith(0, 24, undefined, undefined, true));
    // Curation is an editorial choice about the whole catalogue. Filtering it down here usually
    // leaves nothing, and never leaves the right thing.
    expect(getHighlights).not.toHaveBeenCalled();
  });

  it('narrows the favourites to the same axis, and keeps their own category out of it', async () => {
    // The axis and the category are independent: a studio application is still a Content or a
    // Marketing app, and filtering on the category would drop it from its own shelf.
    getFavorites.mockResolvedValue({
      favorites: [
        pub('f1', 'A studio favourite', { studio: true, categorySlug: 'content' }),
        pub('f2', 'Another studio favourite', { studio: true, category: { id: 'c', slug: 'marketing', name: 'Marketing' } }),
        pub('f3', 'A marketing favourite', { studio: false, categorySlug: 'marketing' }),
      ],
    });

    const { findByText, queryByText } = render(
      <HighlightedApps studioOnly heading="Studio apps" favoritesHeading="My studio apps" />,
    );

    expect(await findByText('A studio favourite')).toBeInTheDocument();
    expect(await findByText('Another studio favourite')).toBeInTheDocument();
    // A row headed "My studio apps" listing every favourite the reader has is two shelves under one
    // heading.
    await waitFor(() => expect(queryByText('A marketing favourite')).toBeNull());
  });

  it('leaves the HOME favourites row alone: without the axis, every favourite still shows', async () => {
    // The other half of the narrowing, and the expensive one. This component serves two rows, and
    // the Home row passes no axis at all: a filter written as "keep the studio ones" instead of
    // "keep everything unless an axis was asked for" empties the Home favourites row for every user
    // on the platform, including everyone who has never opened the studio. The studio-side
    // assertions above cannot see that, because they all pass studioOnly.
    getFavorites.mockResolvedValue({
      favorites: [
        pub('f1', 'A studio favourite', { studio: true }),
        pub('f3', 'A marketing favourite', { studio: false, categorySlug: 'marketing' }),
      ],
    });

    const { findByText } = render(<HighlightedApps heading="Apps" favoritesHeading="My apps" />);

    expect(await findByText('A marketing favourite')).toBeInTheDocument();
    expect(await findByText('A studio favourite')).toBeInTheDocument();
  });

  it('writes its own stored pick, and leaves the Home row key alone', async () => {
    // Asserting only that the Home key is unchanged would pass against a row that writes NOWHERE.
    // So the toggle is actually pressed, and both keys are read back.
    window.localStorage.setItem('lc.home.highlightMode', 'HIGHLIGHTS');
    getFavorites.mockResolvedValue({ favorites: [pub('f1', 'A studio favourite', { studio: true })] });

    const { findByText } = render(<HighlightedApps studioOnly heading="Studio apps" />);

    await waitFor(() => expect(getMarketplacePublications).toHaveBeenCalled());
    // The translator stub is bound to the 'chat.highlights' namespace, so the toggle's two buttons
    // read 'favorites' and 'title'. The row leads with Favourites once there are some, so pressing
    // 'title' is the toggle. The heading is overridden, so 'title' names only the button.
    fireEvent.click(await findByText('title'));

    expect(window.localStorage.getItem('lc.home.highlightMode.studio')).toBe('HIGHLIGHTS');
    // The two rows show different catalogues. Sharing one key means a toggle on one silently moves
    // the other.
    expect(window.localStorage.getItem('lc.home.highlightMode')).toBe('HIGHLIGHTS');
  });
});
