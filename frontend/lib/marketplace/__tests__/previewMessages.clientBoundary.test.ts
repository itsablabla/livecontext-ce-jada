import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { PREVIEW_MESSAGES } from '../previewMessages';

/**
 * `previewMessages` pulls in the whole 400 KB `messages/en.json` to re-export
 * two namespaces of it. On the server that is free. Imported from a client
 * component it is not: JSON member access does not tree-shake reliably, so the
 * whole catalogue can land in the bundle of every public marketplace page for
 * the sake of about two kilobytes of it.
 *
 * The card therefore passes these down as PROPS from the server component. That
 * is invisible in the diff of whichever file breaks it later, which is what
 * this test is for. It walks every `'use client'` file under the marketplace
 * trees rather than naming them: the file that breaks this is most likely one
 * that does not exist yet, which is exactly what an allow-list cannot cover.
 * Source-level, because the failure is an import graph, not a behaviour.
 */
const ROOTS = [
  path.resolve(__dirname, '../../../app/marketplace'),
  path.resolve(__dirname, '../../../components/marketplace'),
];

function collectTsx(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry.startsWith('.')) continue;
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) collectTsx(full, out);
    else if (entry.endsWith('.tsx') || entry.endsWith('.ts')) out.push(full);
  }
  return out;
}

/**
 * Tolerates a leading licence or doc comment and either quote style. A plain
 * `startsWith("'use client'")` would drop such a file out of the glob SILENTLY,
 * and a skipped file is invisible to the non-empty guard below: it can only see
 * an empty result, never a short one.
 */
const USE_CLIENT = /^\s*(?:\/\*[\s\S]*?\*\/\s*|\/\/[^\r\n]*\s*)*['"]use client['"]/;

const CLIENT_COMPONENTS = ROOTS.flatMap((root) => collectTsx(root))
  .filter((file) => !file.includes('__tests__'))
  .filter((file) => USE_CLIENT.test(readFileSync(file, 'utf8')));

describe('previewMessages stays out of client bundles', () => {
  it('finds the client components it is meant to be checking', () => {
    // A glob that silently matches nothing is a test that passes forever.
    expect(CLIENT_COMPONENTS.length).toBeGreaterThan(0);
    expect(CLIENT_COMPONENTS.some((file) => file.endsWith('MarketplaceCardPreview.tsx'))).toBe(true);
  });

  it.each(CLIENT_COMPONENTS)('%s does not import the messages', (file) => {
    // Import statements only: both names are legitimately DISCUSSED in comments,
    // and a test that reads prose is a test that fails on a rewording.
    const imports = [...readFileSync(file, 'utf8').matchAll(/^\s*import[^;]*?from\s+'([^']+)';/gm)]
      .map((match) => match[1]);

    expect(imports).not.toContain('@/lib/marketplace/previewMessages');
    expect(imports).not.toContain('@/messages/en.json');
  });

  it('the card preview takes them as a prop instead', () => {
    const source = readFileSync(
      path.resolve(__dirname, '../../../app/marketplace/_components/MarketplaceCardPreview.tsx'),
      'utf8',
    );
    expect(source).toContain('messages: AbstractIntlMessages');
  });

  it('carries exactly the namespaces the interface frame reads', () => {
    // `interfaceLinkGate` and `common` are what OpenLinkConfirmModal asks for.
    // A namespace added to that subtree and forgotten here is a page that
    // renders on the server and blanks in the browser.
    expect(Object.keys(PREVIEW_MESSAGES).sort()).toEqual(['common', 'interfaceLinkGate']);
    expect(PREVIEW_MESSAGES.interfaceLinkGate).toBeTruthy();
  });
});
