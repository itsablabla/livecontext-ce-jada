import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { LATEST_CHANGELOG_ENTRY, currentEntry, isValidEntry, resolveEntry, type ChangelogEntry } from '../latestEntry';

const LOCALES = ['en', 'fr', 'de', 'es', 'pt', 'zh'] as const;
const REPO_FRONTEND = path.resolve(__dirname, '../../..');

/** Only the parts of a locale file this test reads. */
interface LocaleMessages {
  changelog?: {
    whatsNew?: string;
    dismiss?: string;
    learnMore?: string;
    latest?: Record<string, string>;
  };
  sidebar?: Record<string, string>;
}

function messages(locale: string): LocaleMessages {
  return JSON.parse(readFileSync(path.join(REPO_FRONTEND, 'messages', `${locale}.json`), 'utf8'));
}

/**
 * The entry is edited by hand on every release, in three places that must agree: this file, the
 * media directory and six locale files. Every check below is one of those hand edits going wrong
 * in a way nobody sees until the panel is in front of every user.
 */
describe('the shipped changelog entry', () => {
  it('is valid, so this build actually announces something', () => {
    // If this fails, the release ships a silent build: currentEntry() returns null and no user
    // ever sees the note that was written for them.
    expect(isValidEntry(LATEST_CHANGELOG_ENTRY)).toBe(true);
    expect(currentEntry()).not.toBeNull();
  });

  it('points at a media file that exists in the repo', () => {
    const media = LATEST_CHANGELOG_ENTRY?.media;
    if (!media) return;
    // A path typo passes every type check and every unit test, and renders as a broken image in
    // the panel. The file has to be on disk, under public/, or the entry is not shippable.
    expect(existsSync(path.join(REPO_FRONTEND, 'public', media.src))).toBe(true);
    if (media.poster) {
      expect(existsSync(path.join(REPO_FRONTEND, 'public', media.poster))).toBe(true);
    }
  });

  it('has its copy translated in every locale, with no locale left on the English string', () => {
    const en = messages('en').changelog;
    expect(en?.latest?.title).toBeTruthy();
    expect(en?.latest?.body).toBeTruthy();
    expect(en?.latest?.mediaAlt).toBeTruthy();

    for (const locale of LOCALES.filter((l) => l !== 'en')) {
      const other = messages(locale).changelog;
      for (const key of ['title', 'body', 'mediaAlt'] as const) {
        expect(other?.latest?.[key], `${locale}.changelog.latest.${key} is missing`).toBeTruthy();
        // A copied English string is the failure this catches: the key exists, parity tooling is
        // happy, and that language's users read English.
        expect(other.latest[key], `${locale}.changelog.latest.${key} is the English string`)
          .not.toBe(en.latest[key]);
      }
      for (const key of ['whatsNew', 'dismiss', 'learnMore'] as const) {
        expect(other?.[key], `${locale}.changelog.${key} is missing`).toBeTruthy();
      }
      // No sidebar strings: the panel deliberately has no entry point in the app chrome. It opens
      // by itself once and links to the changelog page; there is nothing to label in a menu.
      expect(messages(locale).sidebar?.whatsNew, `${locale}.sidebar.whatsNew should not exist`).toBeUndefined();
    }
  });

  it('carries no em-dash or en-dash in any locale, per the writing rule', () => {
    for (const locale of LOCALES) {
      const changelog = JSON.stringify(messages(locale).changelog);
      expect(changelog, `${locale} changelog copy contains a dash that reads as AI-generated`)
        .not.toMatch(/[--]/);
    }
  });
});

describe('isValidEntry', () => {
  const valid: ChangelogEntry = {
    key: '2026-09-whats-new',
    publishedAt: '2026-09-07',
    media: { type: 'image', src: '/changelog/x.svg', width: 1200, height: 630 },
    learnMoreUrl: '/changelog',
  };

  it('accepts a well-formed entry, with or without media', () => {
    expect(isValidEntry(valid)).toBe(true);
    expect(isValidEntry({ ...valid, media: null })).toBe(true);
    expect(isValidEntry({ ...valid, learnMoreUrl: null })).toBe(true);
  });

  it('rejects nothing to announce', () => {
    expect(isValidEntry(null)).toBe(false);
  });

  it('rejects a key the server would refuse to store', () => {
    // The server validates the same alphabet. A key rejected there would leave the panel
    // reopening forever, because the acknowledgement never lands.
    expect(isValidEntry({ ...valid, key: 'has space' })).toBe(false);
    expect(isValidEntry({ ...valid, key: '-leading' })).toBe(false);
    expect(isValidEntry({ ...valid, key: 'a'.repeat(121) })).toBe(false);
  });

  it('rejects a malformed publication date, which drives the new-account rule', () => {
    expect(isValidEntry({ ...valid, publishedAt: '2026-9-7' })).toBe(false);
    expect(isValidEntry({ ...valid, publishedAt: 'yesterday' })).toBe(false);
  });

  it('rejects remote media, which would break an offline install and leak the render', () => {
    expect(isValidEntry({ ...valid, media: { ...valid.media!, src: 'https://cdn.example.com/x.png' } })).toBe(false);
    expect(isValidEntry({ ...valid, media: { ...valid.media!, src: '/landing/x.png' } })).toBe(false);
    expect(isValidEntry({
      ...valid,
      media: { ...valid.media!, type: 'video', src: '/changelog/x.mp4', poster: 'https://evil.example/p.png' },
    })).toBe(false);
  });

  it('rejects media without usable dimensions, which would make the panel jump', () => {
    expect(isValidEntry({ ...valid, media: { ...valid.media!, width: 0 } })).toBe(false);
    expect(isValidEntry({ ...valid, media: { ...valid.media!, height: Number.NaN } })).toBe(false);
  });

  it('rejects an unknown media type', () => {
    expect(isValidEntry({ ...valid, media: { ...valid.media!, type: 'gif' as never } })).toBe(false);
  });

  it('rejects an external learn-more link', () => {
    expect(isValidEntry({ ...valid, learnMoreUrl: 'https://example.com' })).toBe(false);
  });

  it('rejects a protocol-relative learn-more link, which leaves the deployment entirely', () => {
    expect(isValidEntry({ ...valid, learnMoreUrl: '//evil.example.com' })).toBe(false);
  });
});

describe('resolveEntry', () => {
  const valid: ChangelogEntry = {
    key: '2026-09-whats-new',
    publishedAt: '2026-09-07',
    media: null,
    learnMoreUrl: '/changelog',
  };

  it('announces a valid entry unchanged', () => {
    expect(resolveEntry(valid)).toBe(valid);
  });

  it('falls back to SILENCE on a malformed entry rather than announcing it', () => {
    // This branch is the safety property of hand-editing the entry on every release: a bad edit
    // ships a build that announces nothing, never one that puts a broken panel in front of
    // every user. It is reachable only through resolveEntry, which is why it exists.
    expect(resolveEntry({ ...valid, key: '' })).toBeNull();
    expect(resolveEntry({ ...valid, publishedAt: 'yesterday' })).toBeNull();
    expect(resolveEntry(null)).toBeNull();
  });

  it('is what currentEntry answers with, so the shipped entry goes through the same gate', () => {
    expect(currentEntry()).toEqual(resolveEntry(LATEST_CHANGELOG_ENTRY));
  });
});
