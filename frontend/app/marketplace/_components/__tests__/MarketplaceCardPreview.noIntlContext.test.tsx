/**
 * @vitest-environment jsdom
 */
import { render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The card mounts the REAL interface frame here, with NO `NextIntlClientProvider`
 * around it. That is not an artificial setup: it is how both public pages that
 * render this card work. `/marketplace` and the publisher profile `/u/{handle}`
 * live outside the `[locale]` tree and have no intl context at all.
 *
 * Deep inside the frame, `OpenLinkConfirmModal` calls `useTranslations` ABOVE
 * its `if (!url) return null`, so the hook runs on every render even with no
 * link pending. Without a provider that throws while hydrating and the error
 * boundary blanks the whole page. The page still SERVER-renders, so the failure
 * is invisible to a crawler and total for a visitor, which is exactly the kind
 * of break that ships unnoticed.
 *
 * Nothing is mocked below the component under test for that reason: a stubbed
 * `InterfaceThumbnail` would remove the very component that throws.
 */

// jsdom measures every box at 0, and the thumbnail renders nothing at scale 0,
// which would make this test pass for the wrong reason. Only the measurement is
// replaced; the real scaling and the whole frame chain below it stay in place.
vi.mock('@/lib/interfaces/useFitScale', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/interfaces/useFitScale')>()),
  useMeasuredBox: () => [{ current: null }, { width: 640, height: 400 }],
}));

import MarketplaceCardPreview from '../MarketplaceCardPreview';

/** The two namespaces the real card hands down, as the frame reads them. */
const MESSAGES = {
  common: { cancel: 'Cancel' },
  interfaceLinkGate: { title: 'Open link', message: 'Open {url}?', confirm: 'Open' },
};

let intersect: (() => void) | null = null;

class FakeIntersectionObserver {
  constructor(private readonly callback: IntersectionObserverCallback) {
    intersect = () => this.callback(
      [{ isIntersecting: true } as IntersectionObserverEntry],
      this as unknown as IntersectionObserver,
    );
  }
  observe() {}
  disconnect() {}
  unobserve() {}
  takeRecords() { return []; }
  root = null;
  rootMargin = '';
  thresholds = [];
}

const SHOWCASE = {
  htmlTemplate: '<div id="app">hello</div>',
  cssTemplate: null,
  jsTemplate: null,
  format: null,
  items: [],
};

beforeEach(() => {
  intersect = null;
  vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
  // The frame measures itself; jsdom ships neither observer.
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(SHOWCASE), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('MarketplaceCardPreview on a page with no intl context', () => {
  it('renders the frame instead of throwing "No intl context found"', async () => {
    const errors: unknown[] = [];
    const consoleError = vi.spyOn(console, 'error').mockImplementation((...args) => {
      errors.push(args);
    });

    const { container } = render(
      <MarketplaceCardPreview publicationId="pub-1" messages={MESSAGES} />,
    );
    intersect!();

    await waitFor(() => expect(container.querySelector('iframe')).toBeTruthy());

    const intlFailures = errors
      .map((args) => (Array.isArray(args) ? args.map(String).join(' ') : String(args)))
      .filter((line) => /intl context/i.test(line));
    expect(intlFailures).toEqual([]);

    consoleError.mockRestore();
  });
});
