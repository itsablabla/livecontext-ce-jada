import enMessages from '@/messages/en.json';

/**
 * The ONLY translated strings the public marketplace pages carry, and they are
 * not those pages' own copy.
 *
 * <p>Everything under `app/marketplace/**` renders OUTSIDE the `[locale]` tree,
 * so there is no `NextIntlClientProvider` (see the LandingShell contract) and
 * the copy is hardcoded English like /about and /compare. But both the listing
 * page and the index reuse the real interface-preview components, and one of
 * them (the iframe's external-link gate, `OpenLinkConfirmModal`) calls
 * `useTranslations` UNCONDITIONALLY - its `if (!url) return null` sits after the
 * hooks, so the hook runs even when no link is pending. With no provider that
 * throws during hydration and the error boundary blanks the whole page: it
 * still server-renders, so the failure is invisible to a crawler and total for
 * a visitor.
 *
 * <p>Hence a provider around the preview subtrees only, carrying the two
 * namespaces that subtree actually reads rather than the whole catalogue (which
 * would ship the entire message file to every marketplace visitor).
 *
 * <p>Shared between the card and the listing page so the two cannot drift: a
 * namespace added to one preview surface and forgotten on the other is the same
 * blank-page failure, on whichever page was missed.
 *
 * <p><b>Server modules only.</b> The card's provider lives in a CLIENT
 * component, and letting IT import this would make the whole 400 KB `en.json`
 * reachable from that bundle for the sake of two kilobytes of it. The server
 * card passes these down as props instead, so only the two namespaces cross the
 * boundary. `previewMessages.clientBoundary.test.ts` is what keeps that true;
 * an `import 'server-only'` here would say it more loudly but cannot be
 * resolved under the jsdom test environment the card suites need.
 */
export const PREVIEW_MESSAGES = {
  common: enMessages.common,
  interfaceLinkGate: enMessages.interfaceLinkGate,
};
