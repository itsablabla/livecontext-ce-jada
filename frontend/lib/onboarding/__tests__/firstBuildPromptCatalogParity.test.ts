import { describe, expect, it } from 'vitest';
import { readFileSync } from 'fs';
import { join } from 'path';
import { GOAL_PROMPT_KEYS, TOOL_LABEL_KEYS } from '../firstBuildPrompt';

/**
 * The proposal catalogue must not drift from the onboarding questions or from
 * the message files.
 *
 * <p><strong>Why this needs a test.</strong> Both kinds of drift are SILENT.
 * Add a goal to the onboarding step and forget the prompt: users who pick it
 * get no proposal, `buildFirstBuildPrompt` returns null, nothing errors and no
 * other test fails. Rename a message key: next-intl's fallback returns the key
 * PATH, which is a non-blank string, so the guard in `message()` is the only
 * thing standing between that and a raw `onboarding.firstBuild.goalPrompts.x`
 * landing in the first message a new user ever sees. Every other test in this
 * folder runs against a stub translator, so none of them reads a real message
 * file. This one does.
 *
 * <p>The onboarding option lists are read from the page SOURCE rather than
 * imported: they are module-local constants inside a client page component, and
 * parsing the literal keeps this test from dragging the whole page (and its
 * providers) into the suite. Same approach as `__tests__/ci-vitest-paths.test.ts`.
 */

const FRONTEND_ROOT = join(__dirname, '..', '..', '..');
const ONBOARDING_PAGE = join(FRONTEND_ROOT, 'app', '[locale]', 'onboarding', 'page.tsx');
const LOCALES = ['en', 'fr', 'de', 'es', 'pt', 'zh'] as const;

/** The `value:` entries of a `const <name> = [...]` option list in the page source. */
function optionValues(source: string, constName: string): string[] {
  const start = source.indexOf(`const ${constName} = [`);
  expect(start, `${constName} not found in the onboarding page`).toBeGreaterThan(-1);
  const end = source.indexOf('];', start);
  expect(end, `${constName} literal is not terminated`).toBeGreaterThan(start);

  const block = source.slice(start, end);
  const values = [...block.matchAll(/value:\s*'([^']+)'/g)].map(match => match[1]);
  expect(values.length, `${constName} parsed as empty`).toBeGreaterThan(0);
  return values;
}

function messages(locale: string): Record<string, unknown> {
  return JSON.parse(readFileSync(join(FRONTEND_ROOT, 'messages', `${locale}.json`), 'utf8'));
}

function onboardingSection(locale: string, section: string): Record<string, string> {
  const root = messages(locale) as { onboarding: Record<string, never> };
  const path = section.split('.');
  let node: unknown = root.onboarding;
  for (const segment of path) {
    node = (node as Record<string, unknown>)[segment];
  }
  return node as Record<string, string>;
}

const source = readFileSync(ONBOARDING_PAGE, 'utf8');

describe('first-build prompt catalogue parity', () => {
  it('proposes a prompt for every goal the onboarding offers', () => {
    // 'other' is excluded by design: it is the answer that says nothing, and a
    // default prompt there would put words in the user's mouth.
    const offered = [
      ...optionValues(source, 'PRIMARY_GOALS'),
      ...optionValues(source, 'CE_USE_CASES'),
    ].filter(value => value !== 'other');

    const unmapped = offered.filter(value => !(value in GOAL_PROMPT_KEYS));

    expect(unmapped, 'onboarding goals with no prompt (users picking these get nothing)').toEqual([]);
  });

  it('maps no goal the onboarding no longer offers', () => {
    const offered = new Set([
      ...optionValues(source, 'PRIMARY_GOALS'),
      ...optionValues(source, 'CE_USE_CASES'),
    ]);

    const orphans = Object.keys(GOAL_PROMPT_KEYS).filter(value => !offered.has(value));

    expect(orphans, 'prompts for goals nobody can pick any more').toEqual([]);
  });

  it('names every tool the onboarding offers', () => {
    const offered = optionValues(source, 'TOOLS').filter(value => value !== 'other');

    const unmapped = offered.filter(value => !(value in TOOL_LABEL_KEYS));

    expect(unmapped, 'onboarding tools with no label mapping').toEqual([]);
  });

  it('maps no tool the onboarding no longer offers', () => {
    const offered = new Set(optionValues(source, 'TOOLS'));

    const orphans = Object.keys(TOOL_LABEL_KEYS).filter(value => !offered.has(value));

    expect(orphans, 'tool mappings for options nobody can pick any more').toEqual([]);
  });

  it.each(LOCALES)('has a real goal prompt for every mapped goal in %s', locale => {
    const prompts = onboardingSection(locale, 'firstBuild.goalPrompts');

    const missing = Object.values(GOAL_PROMPT_KEYS).filter(
      key => typeof prompts[key] !== 'string' || !prompts[key].trim(),
    );

    expect(missing, `${locale}.json is missing goal prompts`).toEqual([]);
  });

  it.each(LOCALES)('carries no goal prompt that maps to nothing in %s', locale => {
    const mapped = new Set(Object.values(GOAL_PROMPT_KEYS));
    const prompts = onboardingSection(locale, 'firstBuild.goalPrompts');

    const orphans = Object.keys(prompts).filter(key => !mapped.has(key));

    expect(orphans, `${locale}.json carries prompts no goal points at`).toEqual([]);
  });

  it.each(LOCALES)('has a label for every mapped tool in %s', locale => {
    const labels = onboardingSection(locale, 'tools');

    const missing = Object.values(TOOL_LABEL_KEYS).filter(
      key => typeof labels[key] !== 'string' || !labels[key].trim(),
    );

    expect(missing, `${locale}.json is missing tool labels`).toEqual([]);
  });

  it.each(LOCALES)('has the tools clause and the sentence separator in %s', locale => {
    const firstBuild = onboardingSection(locale, 'firstBuild') as unknown as {
      toolsClause?: string;
      promptSeparator?: string;
    };

    expect(firstBuild.toolsClause, `${locale}.json toolsClause`).toContain('{tools}');
    // The separator is the one message whose correct value can be empty, so it
    // is checked for presence and shape, not for content.
    expect(typeof firstBuild.promptSeparator, `${locale}.json promptSeparator`).toBe('string');
    expect(firstBuild.promptSeparator!.trim()).toBe('');
  });

  it('no longer carries the removed welcome-gift messages in any locale', () => {
    for (const locale of LOCALES) {
      const modals = (messages(locale) as { modals: Record<string, unknown> }).modals;
      expect(modals.welcomeGift, `${locale}.json still has modals.welcomeGift`).toBeUndefined();
    }
  });
});
