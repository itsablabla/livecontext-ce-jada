import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

// The blog is withheld while the section is being reworked: the routes still
// render but they are unlinked from the public chrome, absent from the sitemap
// and served `noindex, nofollow`.
//
// The landing chrome is the only place that ever linked to it (header nav and
// the footer Resources column), and it renders on public pages with no intl
// context, so this is checked at source level like its sibling footer tests
// rather than by mounting the shell.
const shellSrc = readFileSync(path.resolve(__dirname, '../LandingShell.tsx'), 'utf8');

const BLOG_ROUTE_FILES = [
  'app/blog/page.tsx',
  'app/blog/[slug]/page.tsx',
  'app/[locale]/blog/page.tsx',
  'app/[locale]/blog/[slug]/page.tsx',
];

describe('blog withheld from the public chrome', () => {
  it('the landing header and footer link to no blog route', () => {
    // Catches the href in any spelling: withBase(...,'/blog'), a bare '/blog',
    // or a locale-prefixed variant.
    expect(shellSrc).not.toMatch(/['"`]\/(?:[a-z]{2}\/)?blog/);
  });

  it('the chrome shows no Blog entry at all', () => {
    expect(shellSrc).not.toContain('>Blog<');
  });

  for (const file of BLOG_ROUTE_FILES) {
    it(`${file} refuses indexing unconditionally, not just on CE`, () => {
      const src = readFileSync(path.resolve(__dirname, '../../..', file), 'utf8');

      // A CE-only guard would leave the cloud pages indexable, which is exactly
      // the state this change exists to end.
      expect(src).toMatch(/robots:\s*\{\s*index:\s*false,\s*follow:\s*false\s*\}/);
      expect(src).not.toMatch(/robots:\s*IS_CE\s*\?/);
    });
  }
});
