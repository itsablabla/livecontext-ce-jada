// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { renderHook } from '@testing-library/react';
import { NextIntlClientProvider, useTranslations } from 'next-intl';
import React from 'react';
import { readFileSync } from 'fs';
import { join } from 'path';
import { buildFirstBuildPrompt } from '../firstBuildPrompt';

/**
 * The proposal built through the REAL translator and the REAL message files.
 *
 * <p><strong>Why this needs a test.</strong> Every other test in this folder
 * drives a stub translator, so all of them would stay green against a message
 * file that does not have these keys, or a next-intl whose fallback shape is
 * not what the module assumes. Two things in particular can only be proven
 * here: that `promptSeparator: ""` in Chinese survives the deep-merge fallback
 * onto English instead of resolving to the English space, and that a genuinely
 * missing key really does come back as the key PATH, which is the sole reason
 * the `message()` guard exists.
 */

const MESSAGES_DIR = join(__dirname, '..', '..', '..', 'messages');

function messagesFor(locale: string) {
  return JSON.parse(readFileSync(join(MESSAGES_DIR, `${locale}.json`), 'utf8'));
}

/**
 * The app's own fallback: `i18n/request.ts` deep-merges English under the
 * active locale, so a locale is never asked to resolve a key alone.
 */
function withEnglishFallback(locale: string) {
  const en = messagesFor('en');
  const target = messagesFor(locale);
  return {
    ...en,
    ...target,
    onboarding: {
      ...en.onboarding,
      ...target.onboarding,
      firstBuild: { ...en.onboarding.firstBuild, ...target.onboarding.firstBuild },
      tools: { ...en.onboarding.tools, ...target.onboarding.tools },
    },
  };
}

function translatorFor(locale: string, messages: Record<string, unknown>) {
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <NextIntlClientProvider locale={locale} messages={messages}>
      {children}
    </NextIntlClientProvider>
  );
  const { result } = renderHook(() => useTranslations('onboarding'), { wrapper });
  return result.current as unknown as (key: string, values?: Record<string, string>) => string;
}

describe('buildFirstBuildPrompt against the real translator and message files', () => {
  it.each(['en', 'fr', 'de', 'es', 'pt', 'zh'])(
    'builds a real sentence naming the real tools in %s',
    locale => {
      const t = translatorFor(locale, withEnglishFallback(locale));

      const prompt = buildFirstBuildPrompt({
        primaryGoal: 'email-follow-ups',
        toolsUsed: ['gmail', 'slack'],
        locale,
        t,
      });

      expect(prompt).toBeTruthy();
      // Real prose, not a key path and not an untranslated placeholder.
      expect(prompt).not.toContain('firstBuild.');
      expect(prompt).not.toContain('{tools}');
      expect(prompt).toContain('Gmail');
      expect(prompt).toContain('Slack');
      expect(prompt!.length).toBeGreaterThan(40);
    },
  );

  it.each(['en', 'fr', 'de', 'es', 'pt'])(
    'separates the two sentences with exactly one space in %s',
    locale => {
      // The parity test only checks that the separator TRIMS to empty, which is
      // true of both " " and "". Only a rendered sentence can tell a locale
      // whose space survived from one a translation pass emptied, and the
      // symptom would be two sentences glued together in production.
      const t = translatorFor(locale, withEnglishFallback(locale));

      // Build the same prompt with and without tools: the difference between
      // the two IS the separator plus the clause, which is exact and cannot be
      // confused by a full stop inside the sentence itself.
      const base = buildFirstBuildPrompt({
        primaryGoal: 'reporting',
        toolsUsed: [],
        locale,
        t,
      })!;
      const withTools = buildFirstBuildPrompt({
        primaryGoal: 'reporting',
        toolsUsed: ['slack'],
        locale,
        t,
      })!;

      expect(withTools.startsWith(base)).toBe(true);
      expect(withTools.slice(base.length, base.length + 1)).toBe(' ');
      expect(withTools.slice(base.length + 1, base.length + 2)).not.toBe(' ');
    },
  );

  it('leaves no space before the Chinese clause, where a space would be wrong', () => {
    const t = translatorFor('zh', withEnglishFallback('zh'));

    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'reporting',
      toolsUsed: ['slack'],
      locale: 'zh',
      t,
    });

    // The separator is an empty message in zh. If the English " " won the
    // deep-merge fallback, or an empty message resolved to its key, this is
    // where it would show.
    expect(prompt).toContain('。我主要使用');
    expect(prompt).not.toContain('。 我主要使用');
  });

  it('puts a space between the two sentences in the Latin locales', () => {
    const t = translatorFor('fr', withEnglishFallback('fr'));

    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'reporting',
      toolsUsed: ['slack'],
      locale: 'fr',
      t,
    });

    expect(prompt).toContain('. Je travaille principalement avec Slack.');
  });

  it('confirms the assumption the missing-message guard rests on', () => {
    // The module treats "the value still ends with the key I asked for" as
    // absent. That is only correct while next-intl's fallback returns the key
    // path; if that ever changes, this fails and the guard needs revisiting.
    const t = translatorFor('en', withEnglishFallback('en'));

    expect(t('firstBuild.goalPrompts.noSuchGoal')).toContain(
      'firstBuild.goalPrompts.noSuchGoal',
    );
  });
});
