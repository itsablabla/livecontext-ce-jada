import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The landing page's marketplace section, at the wiring level.
 *
 * The section shows the live marquee of applications and nothing else: the
 * "everything published so far" text catalogue that used to sit under it was
 * removed on 2026-09-02 (it read as a wall of links on a page meant to sell
 * the product). The crawlable listing of every publication is /marketplace,
 * which is also what the sitemap points at, so the landing page must not grow
 * a second copy of it, and must not block its render on a gateway read.
 *
 * Source-level, like `app/__tests__/seoContent.test.ts`: the landing page is a
 * 3,000-line server component whose module pulls in the whole marketing tree,
 * and rendering it in jsdom would test Framer motion, not this.
 */
const landingSource = readFileSync(
  path.resolve(__dirname, '../../page.tsx'),
  'utf8',
);

describe('landing marketplace section', () => {
  it('renders the live marquee only, with no text catalogue and no server read behind it', () => {
    expect(landingSource).toContain('<MarketplacePreview />');
    expect(landingSource).not.toContain('MarketplaceCatalogue');
    expect(landingSource).not.toContain('fetchAllPublicPublications');
    expect(landingSource).not.toContain('Everything published so far');
  });

  it('stays statically rendered with a revalidate window', () => {
    expect(landingSource).toMatch(/export const revalidate = \d+;/);
    expect(landingSource).not.toContain("export const dynamic = 'force-dynamic'");
  });
});
