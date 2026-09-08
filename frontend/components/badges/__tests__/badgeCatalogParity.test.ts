import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { join } from 'path';
import enMessages from '@/messages/en.json';
import { FAMILY_VISUALS, TIER_VISUALS, FAMILY_ORDER, shapePath } from '../badgeVisuals';

/**
 * The badge catalog is defined ONCE, in Java, and consumed in three other
 * places that cannot see it: the artwork maps, the English message file, and
 * the five other locales. Every one of those drifts silently - a badge with no
 * translation renders its key path, a family with no artwork crashes the medal,
 * and neither shows up until a real user unlocks the badge.
 *
 * <p>This test reads the Java source as the source of truth and fails the
 * moment any of the three falls behind.
 */

const CATALOG_JAVA = join(
  __dirname, '..', '..', '..', '..',
  'backend', 'orchestrator-service', 'src', 'main', 'java', 'com', 'apimarketplace',
  'orchestrator', 'services', 'badge', 'BadgeCatalog.java',
);

interface CatalogEntry {
  code: string;
  family: string;
  tier: string;
}

/** Parses the `def("code", BadgeFamily.X, BadgeTier.Y, …)` rows out of the catalog. */
function readJavaCatalog(): CatalogEntry[] {
  const source = readFileSync(CATALOG_JAVA, 'utf8');
  const pattern = /def\(\s*"([a-z0-9_]+)"\s*,\s*BadgeFamily\.([A-Z_]+)\s*,\s*BadgeTier\.([A-Z]+)/g;
  const entries: CatalogEntry[] = [];
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(source)) !== null) {
    entries.push({ code: match[1], family: match[2], tier: match[3] });
  }
  return entries;
}

const catalog = readJavaCatalog();
const LOCALES = ['en', 'fr', 'de', 'es', 'pt', 'zh'] as const;

const MESSAGES_DIR = join(__dirname, '..', '..', '..', 'messages');

/**
 * Read a locale bundle from disk rather than importing it. The `@` alias is a
 * bundler concern; reading the file is also what makes the loop over six
 * locales possible without six static imports.
 */
function localeMessages(locale: string): Record<string, any> {
  return JSON.parse(readFileSync(join(MESSAGES_DIR, `${locale}.json`), 'utf8'));
}

describe('badge catalog parity', () => {
  it('finds the Java catalog - the whole test is vacuous if the parse breaks', () => {
    // A regex that silently matches nothing would turn every assertion below
    // into "for each of zero badges", which passes and proves nothing.
    expect(catalog.length).toBeGreaterThan(40);
  });

  it('gives every badge an English name, so none can render as a raw key path', () => {
    const names = (enMessages as { badges: { item: Record<string, { name?: string }> } }).badges.item;
    const missing = catalog.filter((entry) => !names[entry.code]?.name).map((e) => e.code);
    expect(missing).toEqual([]);
  });

  it('translates every badge name in all six locales', () => {
    const missing: string[] = [];
    for (const locale of LOCALES) {
      const messages = localeMessages(locale);
      for (const entry of catalog) {
        const name = messages?.badges?.item?.[entry.code]?.name;
        if (!name) missing.push(`${locale}:${entry.code}`);
      }
    }
    expect(missing).toEqual([]);
  });

  it('gives every family artwork and a slot in the display order', () => {
    const families = [...new Set(catalog.map((entry) => entry.family))];
    for (const family of families) {
      expect(FAMILY_VISUALS, `no artwork for family ${family}`).toHaveProperty(family);
      expect(FAMILY_ORDER, `family ${family} missing from FAMILY_ORDER`).toContain(family as never);
    }
    // The reverse direction too: an entry left in the order after its badges
    // were removed would render an empty section header.
    for (const family of FAMILY_ORDER) {
      expect(families, `FAMILY_ORDER lists ${family} but no badge uses it`).toContain(family);
    }
  });

  it('gives every family a translated label and every tier a translated name', () => {
    const badges = (enMessages as {
      badges: { family: Record<string, string>; tier: Record<string, string> };
    }).badges;
    for (const family of FAMILY_ORDER) {
      expect(badges.family[family], `no label for family ${family}`).toBeTruthy();
    }
    for (const tier of Object.keys(TIER_VISUALS)) {
      expect(badges.tier[tier], `no label for tier ${tier}`).toBeTruthy();
    }
  });

  it('gives the origin family a silhouette of its own, used by nothing else', () => {
    // Its whole point is that it cannot be worked toward, so it must not look
    // like a rung on one of the ladders next to it.
    const originShape = FAMILY_VISUALS.FOUNDER.shape;
    const sharers = FAMILY_ORDER.filter(
      (family) => family !== 'FOUNDER' && FAMILY_VISUALS[family].shape === originShape,
    );
    expect(sharers).toEqual([]);
  });

  it('gives every tier used by a badge its own metal and rim', () => {
    const tiers = [...new Set(catalog.map((entry) => entry.tier))];
    const metals = new Set<string>();
    for (const tier of tiers) {
      const visual = TIER_VISUALS[tier as keyof typeof TIER_VISUALS];
      expect(visual, `no artwork for tier ${tier}`).toBeTruthy();
      expect(visual.metal).toHaveLength(3);
      expect(visual.rim).toMatch(/^#/);
      metals.add(visual.metal.join('|'));
    }
    // Two tiers sharing a metal would now be indistinguishable on the medal:
    // it is the only rank signal the artwork still carries.
    expect(metals.size).toBe(tiers.length);
  });

  it('draws a closed path for every silhouette', () => {
    for (const shape of ['shield', 'hexagon', 'rosette', 'gem'] as const) {
      const path = shapePath(shape);
      expect(path.startsWith('M')).toBe(true);
      expect(path.trim().endsWith('Z')).toBe(true);
      // NaN in a path attribute makes the browser drop the whole shape, which
      // renders as an invisible medal rather than an error.
      expect(path).not.toMatch(/NaN/);

      // Every coordinate has to stay inside the 0-100 viewBox. A generated
      // silhouette that spills outside is not an error either - it is silently
      // clipped, and the medal comes out with a flat side.
      const coordinates = path.match(/-?\d+(\.\d+)?/g)?.map(Number) ?? [];
      expect(coordinates.length).toBeGreaterThan(6);
      expect(Math.min(...coordinates)).toBeGreaterThanOrEqual(0);
      expect(Math.max(...coordinates)).toBeLessThanOrEqual(100);
    }
  });

  it('gives every metric a requirement sentence in every locale', () => {
    const metrics = Object.keys(
      (enMessages as { badges: { requirement: Record<string, string> } }).badges.requirement,
    );
    const missing: string[] = [];
    for (const locale of LOCALES) {
      const messages = localeMessages(locale);
      for (const metric of metrics) {
        if (!messages?.badges?.requirement?.[metric]) missing.push(`${locale}:${metric}`);
      }
    }
    expect(missing).toEqual([]);
    // Every metric the Java enum declares must have a sentence: a badge whose
    // metric has none shows an empty rule line.
    const javaMetrics = readFileSync(
      CATALOG_JAVA.replace('BadgeCatalog.java', 'BadgeMetric.java'), 'utf8',
    ).match(/^\s{4}([A-Z_]+),?$/gm)?.map((line) => line.trim().replace(',', '')) ?? [];
    expect(javaMetrics.length).toBeGreaterThan(5);
    expect(javaMetrics.filter((metric) => !metrics.includes(metric))).toEqual([]);
  });
});
