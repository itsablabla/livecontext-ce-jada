// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { readDraft, writeDraft } from '@/lib/chat/draftStorage';
import {
  FIRST_BUILD_PROMPT_KEY,
  clearFirstBuildPrompt,
  FIRST_BUILD_PROMPT_MAX_AGE_MS,
  buildFirstBuildPrompt,
  consumeFirstBuildPrompt,
  storeFirstBuildPrompt,
} from '../firstBuildPrompt';

/**
 * Stands in for the next-intl translator bound to the `onboarding` namespace.
 * Echoes the key with its interpolations so a test can assert WHICH message was
 * asked for, not just that some string came back.
 */
const t = (key: string, values?: Record<string, string>) => {
  if (key === 'firstBuild.promptSeparator') return ' ';
  if (key === 'firstBuild.toolsClause') return `I mainly work with ${values?.tools}.`;
  if (key.startsWith('tools.')) return key.slice('tools.'.length).toUpperCase();
  return `<${key}>`;
};

afterEach(() => {
  sessionStorage.clear();
  vi.restoreAllMocks();
});

describe('buildFirstBuildPrompt', () => {
  it('builds the prompt for the goal the user picked', () => {
    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'email-follow-ups',
      toolsUsed: [],
      locale: 'en',
      t,
    });

    expect(prompt).toBe('<firstBuild.goalPrompts.emailFollowUps>');
  });

  it('names the tools the user already works with', () => {
    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'lead-generation',
      toolsUsed: ['gmail', 'slack'],
      locale: 'en',
      t,
    });

    expect(prompt).toBe(
      '<firstBuild.goalPrompts.leadGeneration> I mainly work with GMAIL and SLACK.',
    );
  });

  it('names at most three tools, so the sentence stays a request and not a list', () => {
    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'data-sync',
      toolsUsed: ['gmail', 'slack', 'notion', 'stripe', 'github'],
      locale: 'en',
      t,
    });

    expect(prompt).toContain('GMAIL, SLACK, and NOTION');
    expect(prompt).not.toContain('STRIPE');
    expect(prompt).not.toContain('GITHUB');
  });

  it('ignores tool values that name no tool, and drops the clause when none is left', () => {
    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'reporting',
      // 'other' is the "I did not say" answer; 'made-up' guards an onboarding
      // option added later without a matching label key.
      toolsUsed: ['other', 'made-up'],
      locale: 'en',
      t,
    });

    expect(prompt).toBe('<firstBuild.goalPrompts.reporting>');
  });

  it('maps the CE (self-hosted) use-case vocabulary too', () => {
    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'private-assistants',
      toolsUsed: [],
      locale: 'en',
      t,
    });

    expect(prompt).toBe('<firstBuild.goalPrompts.privateAssistants>');
  });

  it('proposes nothing when the goal is "Something else"', () => {
    expect(
      buildFirstBuildPrompt({ primaryGoal: 'other', toolsUsed: ['gmail'], locale: 'en', t }),
    ).toBeNull();
  });

  it('proposes nothing when onboarding was skipped and no goal was given', () => {
    expect(buildFirstBuildPrompt({ primaryGoal: '', toolsUsed: [], locale: 'en', t })).toBeNull();
    expect(
      buildFirstBuildPrompt({ primaryGoal: null, toolsUsed: null, locale: 'en', t }),
    ).toBeNull();
    expect(
      buildFirstBuildPrompt({ primaryGoal: undefined, toolsUsed: undefined, locale: 'en', t }),
    ).toBeNull();
  });

  it('proposes nothing when the goal maps to a message that is blank', () => {
    const blank = (key: string) =>
      key.startsWith('firstBuild.goalPrompts.') ? '   ' : t(key);

    expect(
      buildFirstBuildPrompt({ primaryGoal: 'reporting', toolsUsed: [], locale: 'en', t: blank }),
    ).toBeNull();
  });

  it('joins the two sentences with no space where the locale wants none', () => {
    const zh = (key: string, values?: Record<string, string>) => {
      if (key === 'firstBuild.promptSeparator') return '';
      if (key === 'firstBuild.toolsClause') return `我主要使用 ${values?.tools}。`;
      if (key.startsWith('tools.')) return key.slice('tools.'.length).toUpperCase();
      return '帮我搭建一个工作流。';
    };

    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'reporting',
      toolsUsed: ['slack'],
      locale: 'zh',
      t: zh,
    });

    expect(prompt).toBe('帮我搭建一个工作流。我主要使用 SLACK。');
  });

  it('falls back to a space rather than splicing a key name into the sentence', () => {
    // What a missing message looks like: the key echoed back.
    const echoing = (key: string, values?: Record<string, string>) =>
      key === 'firstBuild.promptSeparator' ? 'firstBuild.promptSeparator' : t(key, values);

    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'reporting',
      toolsUsed: ['slack'],
      locale: 'en',
      t: echoing,
    });

    expect(prompt).toBe('<firstBuild.goalPrompts.reporting> I mainly work with SLACK.');
    expect(prompt).not.toContain('promptSeparator');
  });
});

/**
 * next-intl does not return a blank string for a missing message: its default
 * fallback returns the FULL key path. Every guard below exists because that
 * value is non-blank and would otherwise be written straight into the composer
 * as the first sentence a new user reads.
 */
describe('buildFirstBuildPrompt when a message does not resolve', () => {
  /** How next-intl reports a missing message: the namespace-qualified key. */
  const missing = (keys: string[]) => (key: string, values?: Record<string, string>) =>
    keys.includes(key) ? `onboarding.${key}` : t(key, values);

  it('proposes nothing rather than a raw key path when the goal prompt is missing', () => {
    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'reporting',
      toolsUsed: ['slack'],
      locale: 'en',
      t: missing(['firstBuild.goalPrompts.reporting']),
    });

    expect(prompt).toBeNull();
  });

  it('keeps the goal sentence and drops the clause when the clause is missing', () => {
    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'reporting',
      toolsUsed: ['slack'],
      locale: 'en',
      t: missing(['firstBuild.toolsClause']),
    });

    expect(prompt).toBe('<firstBuild.goalPrompts.reporting>');
  });

  it('drops a tool whose label is missing instead of naming it by its key', () => {
    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'reporting',
      toolsUsed: ['slack', 'notion'],
      locale: 'en',
      t: missing(['tools.slack']),
    });

    expect(prompt).toBe('<firstBuild.goalPrompts.reporting> I mainly work with NOTION.');
    expect(prompt).not.toContain('tools.slack');
  });

  it('still names three tools when an earlier one has no label', () => {
    // Slicing to three BEFORE resolving would cut this list to
    // [gmail, slack, notion], lose slack to the missing label, and name two
    // tools while stripe sat unused. Four mapped tools with one unresolvable
    // among the first three is the only shape that tells the two orders apart.
    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'reporting',
      toolsUsed: ['gmail', 'slack', 'notion', 'stripe'],
      locale: 'en',
      t: missing(['tools.slack']),
    });

    expect(prompt).toBe(
      '<firstBuild.goalPrompts.reporting> I mainly work with GMAIL, NOTION, and STRIPE.',
    );
  });

  it('proposes nothing when the translator throws instead of returning a fallback', () => {
    const throwing = (key: string) => {
      if (key.startsWith('firstBuild.goalPrompts.')) throw new Error('MISSING_MESSAGE');
      return t(key);
    };

    expect(
      buildFirstBuildPrompt({ primaryGoal: 'reporting', toolsUsed: [], locale: 'en', t: throwing }),
    ).toBeNull();
  });

  it.each([
    ['undefined', undefined],
    ['null', null],
    ['a number', 42],
    ['an object', {}],
  ])('falls back to a space for a non-string separator (%s)', (_label, value) => {
    // A wrapping or stubbed translator can hand back a non-string. Two ways to
    // get this wrong: returning the raw value splices the literal text
    // "undefined" into the user's first message, and normalising it to '' glues
    // the two sentences together. An unusable message takes the same fallback
    // as a throw.
    const nonString = (key: string, values?: Record<string, string>) =>
      key === 'firstBuild.promptSeparator'
        ? (value as unknown as string)
        : t(key, values);

    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'reporting',
      toolsUsed: ['slack'],
      locale: 'en',
      t: nonString,
    });

    expect(prompt).toBe('<firstBuild.goalPrompts.reporting> I mainly work with SLACK.');
  });

  it('falls back to a space when the separator message throws', () => {
    const throwing = (key: string, values?: Record<string, string>) => {
      if (key === 'firstBuild.promptSeparator') throw new Error('MISSING_MESSAGE');
      return t(key, values);
    };

    const prompt = buildFirstBuildPrompt({
      primaryGoal: 'reporting',
      toolsUsed: ['slack'],
      locale: 'en',
      t: throwing,
    });

    expect(prompt).toBe('<firstBuild.goalPrompts.reporting> I mainly work with SLACK.');
  });
});

describe('buildFirstBuildPrompt tool-list joining', () => {
  /** `Intl` seen as a plain bag, so a test can remove or replace a member. */
  const intl = Intl as unknown as { ListFormat?: unknown };

  it('joins with commas where the runtime has no Intl.ListFormat', () => {
    // Older Safari and some test environments; the fallback must still produce
    // a readable sentence rather than throwing on the way to the composer.
    const original = intl.ListFormat;
    delete intl.ListFormat;
    try {
      const prompt = buildFirstBuildPrompt({
        primaryGoal: 'reporting',
        toolsUsed: ['gmail', 'slack'],
        locale: 'en',
        t,
      });

      expect(prompt).toBe('<firstBuild.goalPrompts.reporting> I mainly work with GMAIL, SLACK.');
    } finally {
      intl.ListFormat = original;
    }
  });

  it('joins with commas where Intl.ListFormat rejects the locale', () => {
    const original = intl.ListFormat;
    intl.ListFormat = function ThrowingListFormat() {
      throw new RangeError('unsupported locale');
    };
    try {
      const prompt = buildFirstBuildPrompt({
        primaryGoal: 'reporting',
        toolsUsed: ['gmail', 'slack'],
        locale: 'not-a-locale',
        t,
      });

      expect(prompt).toBe('<firstBuild.goalPrompts.reporting> I mainly work with GMAIL, SLACK.');
    } finally {
      intl.ListFormat = original;
    }
  });
});

describe('storeFirstBuildPrompt / consumeFirstBuildPrompt', () => {
  it('hands the proposal from onboarding to the chat', () => {
    storeFirstBuildPrompt('Build me a workflow.');

    expect(consumeFirstBuildPrompt()).toBe('Build me a workflow.');
  });

  it('is read once: a second reader gets nothing', () => {
    storeFirstBuildPrompt('Build me a workflow.');

    expect(consumeFirstBuildPrompt()).toBe('Build me a workflow.');
    expect(consumeFirstBuildPrompt()).toBeNull();
    expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toBeNull();
  });

  it('leaves no key behind when there is no proposal', () => {
    storeFirstBuildPrompt(null);
    storeFirstBuildPrompt('');
    storeFirstBuildPrompt('   ');

    expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toBeNull();
    expect(consumeFirstBuildPrompt()).toBeNull();
  });

  it.each([
    ['null', null],
    ['an empty string', ''],
    ['whitespace', '   '],
  ])('CLEARS a parked proposal when given %s', (_label, value) => {
    // Setting, not merely writing: "no proposal" has to be able to overwrite an
    // earlier one, or a second pass through onboarding that says nothing leaves
    // the first pass's sentence waiting in the composer.
    storeFirstBuildPrompt('a proposal from an earlier pass');

    storeFirstBuildPrompt(value);

    expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toBeNull();
    expect(consumeFirstBuildPrompt()).toBeNull();
  });

  it('clearFirstBuildPrompt does not throw when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });

    expect(() => clearFirstBuildPrompt()).not.toThrow();
  });

  it('discards, and purges, a slot that is not the shape this module writes', () => {
    // A hand-edited value, or one written by an older build.
    sessionStorage.setItem(FIRST_BUILD_PROMPT_KEY, '   ');

    expect(consumeFirstBuildPrompt()).toBeNull();
    expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toBeNull();
  });

  it.each([
    ['no timestamp', JSON.stringify({ v: 'Build me a workflow.' })],
    ['a non-numeric timestamp', JSON.stringify({ v: 'Build me a workflow.', t: 'yesterday' })],
    ['a JSON null', 'null'],
    ['a JSON array', '["Build me a workflow."]'],
  ])('discards a slot holding %s, because its age cannot be judged', (_label, stored) => {
    sessionStorage.setItem(FIRST_BUILD_PROMPT_KEY, stored);

    expect(consumeFirstBuildPrompt()).toBeNull();
    expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toBeNull();
  });

  it('discards a stored proposal with no text', () => {
    storeFirstBuildPrompt('Build me a workflow.');
    sessionStorage.setItem(FIRST_BUILD_PROMPT_KEY, JSON.stringify({ v: '  ', t: Date.now() }));

    expect(consumeFirstBuildPrompt()).toBeNull();
  });

  it('restores a proposal that is still fresh', () => {
    const written = 1_000_000;
    storeFirstBuildPrompt('Build me a workflow.', written);

    expect(consumeFirstBuildPrompt(written + FIRST_BUILD_PROMPT_MAX_AGE_MS - 1)).toBe(
      'Build me a workflow.',
    );
  });

  it('expires on the same boundary as the draft it defers to', () => {
    // `readDraft` treats exactly MAX as stale. The proposal has to agree: it
    // steps aside for a draft, so a proposal that outlived that draft by even a
    // millisecond would fill a composer whose draft had just been purged.
    const written = 1_000_000;
    storeFirstBuildPrompt('Build me a workflow.', written);
    writeDraft(null, 'a draft written at the same instant', written);

    const atBoundary = written + FIRST_BUILD_PROMPT_MAX_AGE_MS;
    expect(readDraft(null, atBoundary)).toBeNull();
    expect(consumeFirstBuildPrompt(atBoundary)).toBeNull();
  });

  it('drops a proposal the user came back to hours later', () => {
    // The tab outlives the moment: sessionStorage survives navigation and
    // reloads for the whole life of the tab, so without the age guard a
    // forgotten onboarding fills the composer on a much later visit.
    const written = 1_000_000;
    storeFirstBuildPrompt('Build me a workflow.', written);

    expect(consumeFirstBuildPrompt(written + FIRST_BUILD_PROMPT_MAX_AGE_MS + 1)).toBeNull();
    expect(sessionStorage.getItem(FIRST_BUILD_PROMPT_KEY)).toBeNull();
  });

  it('never lets unavailable storage break onboarding or the chat', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });

    expect(() => storeFirstBuildPrompt('Build me a workflow.')).not.toThrow();
    expect(consumeFirstBuildPrompt()).toBeNull();
  });
});
