/**
 * @vitest-environment jsdom
 */
import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Capture what the thumbnail is ASKED to render. The frame's own rendering has
// its own suite; every assertion here is about the contract the card depends on.
const thumbnailProps: Record<string, unknown>[] = [];
vi.mock('@/app/workflows/builder/components/interface/InterfaceThumbnail', () => ({
  InterfaceThumbnail: (props: Record<string, unknown>) => {
    thumbnailProps.push(props);
    return <div data-testid="thumbnail" />;
  },
}));

import MarketplaceCardPreview from '../MarketplaceCardPreview';

/** The two namespaces the real card hands down, as the frame reads them. */
const MESSAGES = {
  common: { cancel: 'Cancel' },
  interfaceLinkGate: { title: 'Open link', message: 'Open {url}?', confirm: 'Open' },
};

/** Hand-driven IntersectionObserver: jsdom has none, and the point of this
 *  component is precisely WHEN it decides to fetch. */
let intersect: (() => void) | null = null;
let observed = 0;
let disconnected = 0;

class FakeIntersectionObserver {
  constructor(private readonly callback: IntersectionObserverCallback) {
    intersect = () => this.callback(
      [{ isIntersecting: true } as IntersectionObserverEntry],
      this as unknown as IntersectionObserver,
    );
  }
  observe() { observed += 1; }
  disconnect() { disconnected += 1; }
  unobserve() {}
  takeRecords() { return []; }
  root = null;
  rootMargin = '';
  thresholds = [];
}

const SHOWCASE = {
  htmlTemplate: '<div id="app">hello</div>',
  cssTemplate: 'body{margin:0}',
  jsTemplate: 'render()',
  format: null,
  items: [],
};

beforeEach(() => {
  thumbnailProps.length = 0;
  intersect = null;
  observed = 0;
  disconnected = 0;
  vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(SHOWCASE), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('MarketplaceCardPreview', () => {
  it('fetches nothing until the card reaches the viewport', () => {
    render(<MarketplaceCardPreview publicationId="pub-1" messages={MESSAGES} />);

    // An index page carries the whole catalogue. Fetching every showcase on
    // mount would be dozens of 12-70 KB reads before the visitor has scrolled.
    expect(observed).toBe(1);
    expect(fetch).not.toHaveBeenCalled();
    expect(screen.queryByTestId('thumbnail')).toBeNull();
  });

  it('renders the frozen showcase once the card is in view', async () => {
    render(<MarketplaceCardPreview publicationId="pub-1" messages={MESSAGES} />);
    intersect!();

    await waitFor(() => expect(screen.getByTestId('thumbnail')).toBeTruthy());
    expect(fetch).toHaveBeenCalledWith(
      '/api/proxy/publications/by-id/pub-1/showcase-render',
      expect.objectContaining({ headers: { Accept: 'application/json' } }),
    );
    expect(thumbnailProps[0]).toMatchObject({
      htmlTemplate: '<div id="app">hello</div>',
      customCss: 'body{margin:0}',
      // Publisher JS renders an interface's lists and conditionals: dropping it
      // would show a correct-looking EMPTY application.
      jsTemplate: 'render()',
      fit: 'contain',
      // A grid of previews all talking at once is what this prevents.
      mediaMuted: true,
    });
  });

  it('only ever fetches once, however often it re-enters the viewport', async () => {
    render(<MarketplaceCardPreview publicationId="pub-1" messages={MESSAGES} />);
    intersect!();
    await waitFor(() => expect(screen.getByTestId('thumbnail')).toBeTruthy());

    // A frozen showcase cannot change while the page is open, and the marquee
    // of a long grid crosses the viewport boundary repeatedly.
    expect(disconnected).toBeGreaterThanOrEqual(1);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('keeps the server-rendered cover when the showcase read fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('nope', { status: 503 })));
    render(<MarketplaceCardPreview publicationId="pub-1" messages={MESSAGES} />);
    intersect!();

    await waitFor(() => expect(fetch).toHaveBeenCalled());
    // A card must never fail because of its preview: no thumbnail means the
    // cover underneath stays visible.
    expect(screen.queryByTestId('thumbnail')).toBeNull();
  });

  it('keeps the cover when the showcase carries no markup', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ htmlTemplate: '' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })));
    render(<MarketplaceCardPreview publicationId="pub-1" messages={MESSAGES} />);
    intersect!();

    await waitFor(() => expect(fetch).toHaveBeenCalled());
    // An empty frame reads as a broken application, which is worse than the
    // node-icon cover the card already drew.
    expect(screen.queryByTestId('thumbnail')).toBeNull();
  });

  it('renders a backend-resolved showcase in run mode, not edit', async () => {
    // `_resolvedHtml` arrives with no `data` beside it. Deriving the mode from
    // `data` would pick `edit`, which rewrites the `{{var|default}}`
    // placeholders the snapshot deliberately kept into `[var]` - a corrupted
    // app that still looks like it rendered.
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      ...SHOWCASE,
      items: [{ data: { _resolvedHtml: '<div id="app">resolved</div>' } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })));

    render(<MarketplaceCardPreview publicationId="pub-1" messages={MESSAGES} />);
    intersect!();

    await waitFor(() => expect(screen.getByTestId('thumbnail')).toBeTruthy());
    expect(thumbnailProps[0]).toMatchObject({
      htmlTemplate: '<div id="app">resolved</div>',
      mode: 'run',
    });
  });

  it('renders an unresolved showcase against the data it carries', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      ...SHOWCASE,
      items: [{ data: { title: 'Ada' } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })));

    render(<MarketplaceCardPreview publicationId="pub-1" messages={MESSAGES} />);
    intersect!();

    await waitFor(() => expect(screen.getByTestId('thumbnail')).toBeTruthy());
    expect(thumbnailProps[0]).toMatchObject({ mode: 'run', resolvedData: { title: 'Ada' } });
  });

  it('renders a vertical app at the shape its author declared', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      ...SHOWCASE,
      format: '1080x1920',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })));

    render(<MarketplaceCardPreview publicationId="pub-1" messages={MESSAGES} />);
    intersect!();

    await waitFor(() => expect(screen.getByTestId('thumbnail')).toBeTruthy());
    // Without this the card would reflow a vertical app into a 1280x800 page
    // its author never saw.
    expect(thumbnailProps[0]).toMatchObject({ viewport: { width: 1080, height: 1920 } });
  });

  it('keeps the cover when the network rejects outright', async () => {
    // Distinct from a 503: a refused connection never produces a Response, so
    // it takes the catch, not the `!res.ok` branch.
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));

    render(<MarketplaceCardPreview publicationId="pub-1" messages={MESSAGES} />);
    intersect!();

    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(screen.queryByTestId('thumbnail')).toBeNull();
  });

  it('does not set state on a card that was unmounted mid-flight', async () => {
    let release: ((value: Response) => void) | null = null;
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { release = resolve; })));
    const errors: unknown[] = [];
    const consoleError = vi.spyOn(console, 'error').mockImplementation((...args) => errors.push(args));

    const { unmount } = render(
      <MarketplaceCardPreview publicationId="pub-1" messages={MESSAGES} />,
    );
    intersect!();
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    // Scrolling a long grid unmounts nothing, but a route change mid-fetch does.
    unmount();
    release!(new Response(JSON.stringify(SHOWCASE), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    await Promise.resolve();

    expect(errors).toEqual([]);
    consoleError.mockRestore();
  });

  it('stays out of the way of the click on the card behind it', async () => {
    // This repo has already shipped this bug once: a live preview iframe laid
    // over a tile ate the tile click. This host covers the ENTIRE thumbnail,
    // which is the card largest click target, and `inert` is what makes a click
    // fall through to the anchor wrapping it.
    //
    // jsdom implements neither `inert` nor hit testing, so this asserts the
    // attribute, not the click. The click itself is checked against a real
    // browser in e2e/marketplace/marketplace-public-index.spec.ts.
    const { container } = render(
      <MarketplaceCardPreview publicationId="pub-1" messages={MESSAGES} />,
    );

    const host = container.firstElementChild;
    expect(host?.hasAttribute('inert')).toBe(true);

    // Still inert once the frame has actually loaded, which is when it matters.
    intersect!();
    await waitFor(() => expect(screen.getByTestId('thumbnail')).toBeTruthy());
    expect(host?.hasAttribute('inert')).toBe(true);
  });
});
