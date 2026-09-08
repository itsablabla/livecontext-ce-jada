/**
 * The integrations named in the public footer.
 *
 * <p><strong>Chosen, not ranked, and that is the point.</strong> The column used to render
 * the catalogue's most-RUN integrations. What that produced in production on 2026-09-06 was
 * "xAI, Instagram, Telegram, TikTok, YouTube Data API, Seedance, Gmail, Apify": a true
 * ranking of what the platform executes, and a poor answer to the only question a visitor
 * asks here, which is whether it connects to the tools their COMPANY runs. The usage ledger
 * is dominated by whatever the heaviest workflows happen to call, so it will keep surfacing
 * media and model APIs over the office stack. Instagram earns its place on recognition;
 * everything after it is the professional set.
 *
 * <p><strong>Why hardcoding is safe HERE and was rightly refused before.</strong> The
 * objection on `FooterIntegrations` is exact: a hand-written slug is a URL nobody verified,
 * and the failure mode is eight 404s in the footer of every page. That is answered by
 * verification, not by avoidance: `wellKnownIntegrations.test.ts` resolves every slug AND
 * every label against the API-migration seed corpus, so a rename that would break a link
 * fails a test instead of shipping.
 *
 * <p>It also removes a gateway read from every public page. The ranked version could not be
 * read at build time (the CI builder cannot reach the gateway) and timed out when a render
 * landed mid-rollout, which is how production served a column containing nothing but the
 * "All integrations" link for over an hour.
 *
 * <p>Names and slugs were read from the live catalogue
 * (`GET /api/public/integrations?q=...`) rather than guessed from the seed filenames, which
 * do not match: the slug is derived from the API's display name, so `google_sheets.json` is
 * served as `google-sheets`.
 */
export interface WellKnownIntegration {
  /** The catalogue slug, i.e. the last segment of `/integrations/{slug}`. */
  slug: string;
  /** The catalogue's display name, shown as the link text. */
  name: string;
}

/**
 * Eight, matching `FOOTER_INTEGRATION_COUNT`: enough to read as a catalogue, few enough to
 * sit beside the other footer columns.
 *
 * <p>Order is the reading order of the column. Instagram leads on recognition, then the
 * tools a company runs its day on: mail, chat, docs, code, spreadsheet, CRM, payments.
 * The plain `instagram` entry is deliberate over `instagram-instagram-login`, which is the
 * same product under a login-flow label that means nothing in a footer.
 */
export const WELL_KNOWN_INTEGRATIONS: readonly WellKnownIntegration[] = [
  { slug: 'instagram', name: 'Instagram' },
  { slug: 'gmail', name: 'Gmail' },
  { slug: 'slack', name: 'Slack' },
  { slug: 'notion', name: 'Notion' },
  { slug: 'github', name: 'GitHub' },
  { slug: 'google-sheets', name: 'Google Sheets' },
  { slug: 'hubspot', name: 'HubSpot' },
  { slug: 'stripe', name: 'Stripe' },
];
