// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { closeSync, openSync, readSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { WELL_KNOWN_INTEGRATIONS } from '../wellKnownIntegrations';

/**
 * The footer's fallback list must be real integrations, not plausible ones.
 *
 * <p>A hand-written entry here becomes `/integrations/{slug}` in the footer of every public
 * page, so a wrong slug is not a cosmetic issue: it is a 404 repeated site-wide, and it is
 * exactly the objection that kept a fallback out of the footer in the first place. This
 * test is what answers it, so the list may stay.
 *
 * <p>It checks against the API-migration seed corpus, which is what the importer loads into
 * the catalogue, and it reproduces the importer's own slug derivation
 * (`ApiMigrationImporter.slugify`: lowercase, then every run of non-alphanumerics becomes a
 * dash). That derivation is why the seed FILENAME cannot be used as the slug:
 * `google_sheets.json` declares "Google Sheets" and is served as `google-sheets`.
 */

const SEED_DIR = join(process.cwd(), '..', 'scripts', 'api-migrations');

/** Only the head of each seed file: `apiName`/`apiSlug` are top-level and the corpus is ~250 MB. */
const HEAD_BYTES = 4096;

function readHead(path: string): string {
  const fd = openSync(path, 'r');
  try {
    const buffer = Buffer.alloc(HEAD_BYTES);
    const read = readSync(fd, buffer, 0, HEAD_BYTES, 0);
    return buffer.toString('utf8', 0, read);
  } finally {
    closeSync(fd);
  }
}

/** `ApiMigrationImporter.slugify`, with the trailing dash a trailing "(...)" would leave. */
function slugify(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

/** Every slug the catalogue can serve, mapped to the name the seed declares for it. */
function catalogueSlugs(): Map<string, string> {
  const bySlug = new Map<string, string>();

  for (const file of readdirSync(SEED_DIR)) {
    if (!file.endsWith('.json')) continue;
    const head = readHead(join(SEED_DIR, file));
    const name = head.match(/"apiName"\s*:\s*"((?:[^"\\]|\\.)*)"/)?.[1];
    if (!name) continue;
    const explicit = head.match(/"apiSlug"\s*:\s*"([^"]*)"/)?.[1];
    bySlug.set(explicit || slugify(name), name.replace(/\\"/g, '"'));
  }

  return bySlug;
}

describe('well-known integrations fallback', () => {
  const catalogue = catalogueSlugs();

  it('reads the seed corpus at all, so the cases below cannot pass vacuously', () => {
    expect(catalogue.size).toBeGreaterThan(500);
  });

  it('has exactly the eight the footer renders', () => {
    // Fewer leaves a short column; more silently drops entries at the render, where the
    // count is applied. Kept in step with FOOTER_INTEGRATION_COUNT.
    expect(WELL_KNOWN_INTEGRATIONS).toHaveLength(8);
  });

  it.each(WELL_KNOWN_INTEGRATIONS.map((i) => [i.slug, i.name]))(
    '/integrations/%s exists in the catalogue seed',
    (slug) => {
      expect(catalogue.has(slug)).toBe(true);
    },
  );

  it.each(WELL_KNOWN_INTEGRATIONS.map((i) => [i.slug, i.name]))(
    '%s is shown under the name the catalogue gives it (%s)',
    (slug, name) => {
      // A drifted label is not a broken link, but it makes the footer disagree with the
      // page it leads to, which is the whole reason the column is ranked and not curated.
      expect(catalogue.get(slug)).toBe(name);
    },
  );

  it('lists each integration once', () => {
    const slugs = WELL_KNOWN_INTEGRATIONS.map((i) => i.slug);
    expect(new Set(slugs).size).toBe(slugs.length);
  });
});
