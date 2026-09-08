// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: api }));

import { publicationService } from '../publication.service';

/**
 * The studio axis as it actually reaches the server, on all FOUR marketplace reads.
 *
 * <p><b>Why the component tests are not enough.</b> They assert the ARGUMENT handed to a mocked
 * service, so they pin the caller and stop one layer above the thing that matters. Deleting the
 * four lines that turn `studioOnly` into a query param left every one of them green: the marketplace
 * grid, the search box, and both of their self-hosted proxies would ask the server for the whole
 * catalogue while the page still said "Studio". HTTP 200, the chip still active, nothing logged.
 *
 * <p>Four sites and not one, because they are four separate expressions written twice over in two
 * shapes: the two list endpoints spread the param into an object literal, the two search endpoints
 * assign it into a mutable map. A refactor that fixes one shape leaves the other.
 *
 * <p>Both directions are asserted every time. A serializer that always sends `studio` would hide
 * every ordinary application from the main marketplace, which is the same defect wearing the other
 * hat and is invisible to a test that only checks the true case.
 */
beforeEach(() => {
  vi.clearAllMocks();
  api.get.mockResolvedValue({ publications: [], count: 0 });
});

/** The params of the single GET the call under test made. */
function sentParams(): Record<string, unknown> {
  expect(api.get).toHaveBeenCalledTimes(1);
  return api.get.mock.calls[0][1].params as Record<string, unknown>;
}

/** The path of that GET, so a param cannot be asserted against the wrong endpoint. */
function sentPath(): string {
  return api.get.mock.calls[0][0] as string;
}

describe('the cloud marketplace grid', () => {
  it('asks the server for studio applications only', async () => {
    await publicationService.getMarketplacePublications(0, 24, undefined, undefined, true);

    expect(sentPath()).toBe('/publications/marketplace');
    expect(sentParams()).toMatchObject({ studio: true });
  });

  it('says nothing about the axis on an ordinary browse', async () => {
    await publicationService.getMarketplacePublications(0, 24, undefined, undefined, false);

    expect(sentParams()).not.toHaveProperty('studio');
  });

  it('keeps the category alongside the axis, because they compose', async () => {
    // A second axis, not a replacement: a studio application still says what it is about, and a
    // serializer that dropped one for the other would answer a different question than was asked.
    await publicationService.getMarketplacePublications(0, 24, 'content', undefined, true);

    expect(sentParams()).toMatchObject({ studio: true, category: 'content' });
  });
});

describe('search inside the cloud marketplace', () => {
  it('carries the axis, so typing narrows the shelf instead of leaving it', async () => {
    await publicationService.searchPublications('clip', undefined, undefined, true);

    expect(sentPath()).toBe('/publications/search');
    expect(sentParams()).toMatchObject({ q: 'clip', studio: 'true' });
  });

  it('omits it when the search is not inside the shelf', async () => {
    await publicationService.searchPublications('clip', undefined, undefined, false);

    expect(sentParams()).not.toHaveProperty('studio');
  });
});

describe('the self-hosted proxies, which ask a cloud of possibly another version', () => {
  it('carries the axis on the remote grid', async () => {
    await publicationService.getRemoteMarketplacePublications(0, 50, undefined, undefined, true);

    expect(sentPath()).toBe('/publications/remote/marketplace');
    expect(sentParams()).toMatchObject({ studio: true });
  });

  it('carries the axis on the remote search', async () => {
    await publicationService.searchRemotePublications('clip', undefined, undefined, true);

    expect(sentPath()).toBe('/publications/remote/search');
    expect(sentParams()).toMatchObject({ q: 'clip', studio: 'true' });
  });

  it('omits it on an ordinary remote browse', async () => {
    await publicationService.getRemoteMarketplacePublications(0, 50, undefined, undefined, false);

    expect(sentParams()).not.toHaveProperty('studio');
  });
});
