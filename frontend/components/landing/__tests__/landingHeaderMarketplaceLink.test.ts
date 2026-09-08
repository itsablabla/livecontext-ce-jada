import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The header's Marketplace entry.
 *
 * Source-level, like its sibling chrome tests: the header renders on public
 * pages that have no intl context, so it is not mounted here.
 *
 * What this pins is the difference between a word and a door. As an in-page
 * anchor, "Marketplace" scrolled to a client-fetched carousel on the landing
 * and bounced back to the landing from every other public page, so the site's
 * most repeated navigation item linked to no listing at all and the crawlable
 * index at `/marketplace` had no inbound link from the chrome.
 */
const shellSrc = readFileSync(path.resolve(__dirname, '../LandingShell.tsx'), 'utf8');

/** Just the header nav, so a match cannot come from the footer columns. */
const headerNav = (() => {
  const start = shellSrc.indexOf('export function LandingHeader');
  expect(start).toBeGreaterThan(-1);
  const end = shellSrc.indexOf('export function LandingFooter', start);
  expect(end).toBeGreaterThan(start);
  return shellSrc.slice(start, end);
})();

describe('landing header Marketplace entry', () => {
  it('links to the public /marketplace index', () => {
    expect(headerNav).toContain("withBase(siteBaseUrl, '/marketplace')");
  });

  it('is no longer an in-page anchor', () => {
    expect(headerNav).not.toContain('targetId="marketplace"');
  });

  it('goes through withBase, so it still resolves on the docs subdomain', () => {
    // A bare href="/marketplace" would point at docs.livecontext.ai/marketplace,
    // which does not exist. Every in-app chrome link goes through the helper.
    expect(headerNav).not.toMatch(/href="\/marketplace"/);
  });

  it('keeps Pricing as an anchor, which has no public page of its own', () => {
    expect(headerNav).toContain('targetId="pricing"');
  });
});
