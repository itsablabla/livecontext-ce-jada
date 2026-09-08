'use client';

import { useEffect, useRef, useState } from 'react';
import { NextIntlClientProvider } from 'next-intl';
import type { AbstractIntlMessages } from 'next-intl';
import { InterfacePreview } from '@/components/marketplace/InterfacePreview';
import { resolveShowcaseContent } from '@/lib/interfaces/showcaseResolve';

/**
 * Deliberately local rather than `PublicShowcaseRender`, which is the same six
 * fields: that type lives in the `server-only` reader, and while `import type`
 * is erased and would technically be safe, pointing a client component at a
 * server module is the kind of edge a future refactor gets wrong quietly. Six
 * self-documenting lines is the cheaper side of that trade.
 */
interface ShowcaseRenderPayload {
  htmlTemplate?: string | null;
  cssTemplate?: string | null;
  jsTemplate?: string | null;
  format?: string | null;
  items?: Array<{ data?: Record<string, unknown> | null }>;
}

/** Start the fetch this far before the card reaches the viewport. */
const PRELOAD_MARGIN = '400px';

/**
 * The published application itself, running inside a marketplace card.
 *
 * <p>Why the card is not just a picture: a listing's whole claim is "this
 * works", and the platform has no screenshot pipeline, so the only honest
 * thumbnail is the frozen showcase rendering itself. That is what the
 * authenticated grid already shows; this is the anonymous twin of it.
 *
 * <p><b>What this adds over the pieces it stands on.</b> Presentation is
 * `InterfacePreview` verbatim, the same brick the authenticated cards use, so
 * the two grids cannot drift in shape, scaling or sandboxing. The fetch is not
 * `ShowcasePreview`'s: that component calls `useTranslations('showcase')` and
 * `useAuthGuard`, neither of which exists on a public page, and unpicking that
 * from a component the whole app depends on is a bigger change than this one.
 * What is genuinely new here is the pairing: fetch the PUBLIC showcase
 * endpoint, only when the card is scrolled to, and carry the intl context the
 * frame needs.
 *
 * <p><b>It fetches in the browser, and that is deliberate on an SEO page.</b>
 * A showcase render is 12-70 KB of markup EACH: inlining the whole catalogue
 * server-side would put several megabytes of HTML in front of every crawler and
 * every visitor, which is the fastest way to lose the Core Web Vitals this page
 * exists to win. So the card's SEO payload (title, description, author,
 * category, link, node icons) is server-rendered and complete without this
 * component, and the live preview is layered on top of it, only for the cards a
 * visitor actually scrolls to. A crawler that runs no JavaScript loses nothing
 * it could have indexed: iframe content is not indexed either way.
 *
 * <p>The cost that remains is real and worth naming: one gateway read and one
 * script-running (sandboxed, `allow-scripts` only, no same-origin) frame per
 * card a visitor scrolls past. The index renders the whole catalogue, so that
 * ceiling is the catalogue size, in the dozens today. If it reaches the
 * hundreds, this page needs paging before it needs a preview cap: a card
 * without its application is not worth much.
 *
 * <p>Muted by design: a marketplace grid mounts dozens of these at once, and a
 * showcase carrying an `<audio>`/`<video>` would start talking on page load.
 * `InterfacePreview` also makes the frame inert to pointer events, so nothing
 * inside it can be driven, though publisher JS can still open the frame's
 * external-link gate on its own - which is precisely why the intl provider
 * below is not optional.
 */

export default function MarketplaceCardPreview({
  publicationId,
  messages,
}: {
  publicationId: string;
  /**
   * The two message namespaces the frame reads, handed down by the SERVER card.
   *
   * They are a prop rather than an import so `messages/en.json` (400 KB) never
   * has to be reachable from a client bundle: only the two namespaces cross,
   * as serialized props. Once in total, not once per card, because
   * `PREVIEW_MESSAGES` is a module-level constant that React Flight emits by
   * reference. Measured on the built page rather than assumed: 79 cards, and
   * `interfaceLinkGate` appears exactly ONCE in the payload (78 KB gzipped for
   * the page). The provider itself has to live here rather than at
   * the call sites, because the frame's external-link gate calls
   * `useTranslations` unconditionally and every page that renders this card
   * (`/marketplace`, `/u/{handle}`) is outside the `[locale]` tree with no intl
   * context. Without it that throws while hydrating and the error boundary
   * blanks the page: server-rendered, so invisible to a crawler and total for a
   * visitor.
   */
  messages: AbstractIntlMessages;
}) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [render, setRender] = useState<ShowcaseRenderPayload | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    let cancelled = false;

    const load = async () => {
      try {
        const res = await fetch(
          `/api/proxy/publications/by-id/${encodeURIComponent(publicationId)}/showcase-render`,
          { headers: { Accept: 'application/json' } },
        );
        if (!res.ok) return;
        const body = (await res.json()) as ShowcaseRenderPayload;
        // No markup means nothing to draw: leaving `render` null keeps the
        // server-rendered cover, which is a real thumbnail, not an empty box.
        if (!cancelled && typeof body?.htmlTemplate === 'string' && body.htmlTemplate.length > 0) {
          setRender(body);
        }
      } catch {
        /* The cover underneath stays: a card must never fail because of its preview. */
      }
    };

    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        // One shot: the frozen showcase cannot change while the page is open.
        observer.disconnect();
        void load();
      },
      { rootMargin: PRELOAD_MARGIN },
    );
    observer.observe(host);

    return () => {
      cancelled = true;
      observer.disconnect();
    };
  }, [publicationId]);

  const content = resolveShowcaseContent(render?.items);

  return (
    // `inert` rather than `aria-hidden`: the frame is a focusable <iframe>, and
    // hiding a focusable element from assistive tech while leaving it in the tab
    // order is the one combination that is worse than doing neither.
    <div ref={hostRef} className="absolute inset-0" inert>
      {render && (
        <NextIntlClientProvider locale="en" messages={messages}>
          <div className="absolute inset-0 bg-white dark:bg-slate-900">
            <InterfacePreview
              snapshot={{
                // Publisher JS is what renders an interface's lists and
                // conditionals; dropping it would show a correct-looking EMPTY
                // app. The `allow-scripts`-only sandbox is what contains it,
                // exactly the trade the authenticated cards already make.
                htmlTemplate: content.effectiveHtml || render.htmlTemplate || '',
                cssTemplate: render.cssTemplate,
                jsTemplate: render.jsTemplate,
                format: render.format,
                data: content.resolvedData,
              }}
              // Not derived from `data`: a backend-resolved showcase carries no
              // data and still must render in `run`.
              mode={content.mode}
              mediaMuted
            />
          </div>
        </NextIntlClientProvider>
      )}
    </div>
  );
}
