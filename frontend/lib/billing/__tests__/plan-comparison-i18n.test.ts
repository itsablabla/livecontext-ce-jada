import { describe, it, expect } from 'vitest';
import { buildPlanComparison, COMPARISON_PLAN_IDS } from '@/lib/billing/plan-comparison';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * Every label the comparison renders must exist in every locale.
 *
 * Missing keys fall back to English rather than throwing, so this is the only
 * place the omission is visible: without it, a French reader gets an English
 * row label - or, for a value key, a raw `values.storage1gb` - and the suite
 * stays green. The list is derived from the built matrix, so a row added to the
 * table is a row this test starts demanding a translation for.
 */

const LOCALES: Record<string, any> = { en, fr, de, es, pt, zh };

const compareOf = (locale: any) => locale?.pricing?.compare ?? {};

function flatten(obj: any, prefix = ''): string[] {
  return Object.entries(obj).flatMap(([k, v]) =>
    v && typeof v === 'object' && !Array.isArray(v) ? flatten(v, `${prefix}${k}.`) : [`${prefix}${k}`]
  );
}

function read(messages: any, path: string): unknown {
  return path.split('.').reduce<any>((node, part) => (node ? node[part] : undefined), messages);
}

const sections = buildPlanComparison();
const rows = sections.flatMap((s) => s.rows);

/** Every message path the dialog resolves while rendering the table. */
const requiredPaths: string[] = [
  'open',
  'title',
  'subtitle',
  'cycleLabel',
  'featureColumn',
  'unlocksBadge',
  'included',
  'notIncluded',
  'footnote',
  ...sections.map((section) => `sections.${section.id}`),
  ...rows.filter((row) => row.kind === 'scale').map((row) => `dimensions.${row.id}`),
  // A scale cell renders the VALUE of whichever key the plan carries.
  ...Array.from(
    new Set(
      rows.flatMap((row) =>
        row.kind === 'scale'
          ? COMPARISON_PLAN_IDS.map((planId) => row.cells[planId]).filter((key): key is string => !!key)
          : []
      )
    )
  ).map((key) => `values.${key}`),
];

describe('pricing.compare i18n', () => {
  const refKeys = flatten(compareOf(en)).sort();

  it('en.json defines a non-empty compare subtree', () => {
    expect(refKeys.length).toBeGreaterThan(0);
  });

  for (const [locale, messages] of Object.entries(LOCALES)) {
    it(`${locale}.json has exactly the same compare keys as en.json (0 missing / 0 extra)`, () => {
      const keys = flatten(compareOf(messages)).sort();
      const missing = refKeys.filter((k) => !keys.includes(k));
      const extra = keys.filter((k) => !refKeys.includes(k));
      expect(missing, `${locale} missing: ${missing.join(', ')}`).toEqual([]);
      expect(extra, `${locale} extra: ${extra.join(', ')}`).toEqual([]);
    });

    it(`${locale}.json translates every label the table renders`, () => {
      const subtree = compareOf(messages);
      const missing = requiredPaths.filter((path) => {
        const value = read(subtree, path);
        return typeof value !== 'string' || value.length === 0;
      });
      expect(missing, `${locale} missing: ${missing.join(', ')}`).toEqual([]);
    });

    it(`${locale}.json keeps the interpolations the dialog passes`, () => {
      const subtree = compareOf(messages);
      // Both are rendered with a live number; a translation that drops the
      // placeholder silently shows a sentence with a hole in it.
      expect(String(read(subtree, 'subtitle')), `${locale} subtitle`).toContain('{credits}');
      expect(String(read(subtree, 'values.creditsDynamic')), `${locale} creditsDynamic`).toContain('{credits}');
    });

    it(`${locale}.json carries no em-dash in the compare subtree`, () => {
      // Project rule: the em-dash and en-dash are banned from user-facing text.
      const offenders = flatten(compareOf(messages)).filter((path) => {
        const value = read(compareOf(messages), path);
        return typeof value === 'string' && /[--]/.test(value);
      });
      expect(offenders, `${locale} uses a dash in: ${offenders.join(', ')}`).toEqual([]);
    });
  }

  it('keeps node coverage identical from Starter up, in every locale', () => {
    // The only bar in the catalogue is publishing, and it drops at STARTER
    // (migration V458), so Starter and every plan above it have the same node
    // coverage. A translation that words them differently re-creates the false
    // ladder the English copy had, and only a reader in that language sees it.
    for (const [locale, messages] of Object.entries(LOCALES)) {
      const values = compareOf(messages).values ?? {};
      expect(values.nodesPublishing, `${locale}: Starter and above must read the same`).toBe(
        values.nodesAll
      );
      expect(values.nodesCore, `${locale}: Free must differ`).not.toBe(values.nodesAll);
    }
  });

  it('translates every flag row through the plan cards, which already name them', () => {
    // Flag rows deliberately reuse `pricing.planCards.features.*` rather than
    // duplicating a label: the guard is that the reuse actually resolves.
    for (const [locale, messages] of Object.entries(LOCALES)) {
      const features = messages?.pricing?.planCards?.features ?? {};
      const missing = rows
        .filter((row) => row.kind === 'flag')
        .map((row) => row.id)
        .filter((key) => typeof features[key] !== 'string' || features[key].length === 0);
      expect(missing, `${locale} missing feature labels: ${missing.join(', ')}`).toEqual([]);
    }
  });
});
