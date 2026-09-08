// @vitest-environment jsdom
/**
 * The popover rendered with the REAL message files.
 *
 * Every other test in this folder mocks next-intl to echo key names, which is
 * the right default: it keeps assertions about wording decisions rather than
 * about a translation. But it is blind to the failure this feature is most
 * exposed to, because the whole feature IS its sentences.
 *
 * That failure was real on this branch before review caught it, twice. Once a
 * sentence read "The allowance starts again each {period}" and {period} is
 * filled with "this month", so the card said "each this month". Once the scope
 * line interpolated a hardcoded English "credits" into all five other
 * languages. Both times the key-echo test covering the branch passed.
 *
 * So this file interpolates the actual message values and reads the result as a
 * sentence, in English AND in French: an English-only pass cannot see an English
 * word sitting in the middle of a French one.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import en from '../../../messages/en.json';
import fr from '../../../messages/fr.json';

// Which catalogue the mocked translator reads. Set per describe block.
let catalogue: any = en;

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) => {
    const value = key.split('.').reduce<any>((node, part) => node?.[part], catalogue);
    if (typeof value !== 'string') return key;
    return params
      ? value.replace(/\{(\w+)\}/g, (_m, name) => String(params[name] ?? `{${name}}`))
      : value;
  },
}));

import { BudgetChip } from '../BudgetChip';

const popover = () => document.querySelector('[data-testid="budget-popover"]');
const text = () => popover()?.textContent ?? '';

/** Radix opens instantly on focus, which needs no timer control. */
async function open() {
  fireEvent.focus(screen.getByTestId('budget-chip'));
  await waitFor(() => expect(popover()).not.toBeNull());
}

beforeEach(() => {
  catalogue = en;
});

afterEach(() => cleanup());

/** Fragments that only ever appear when two pieces have been glued badly. */
const BROKEN_GLUE = [
  'each this',
  'each every',
  'again this month',
  'again this week',
  ' the the ',
];

function expectReadsLikeASentence(body: string) {
  for (const bad of BROKEN_GLUE) {
    expect(body.toLowerCase()).not.toContain(bad.toLowerCase());
  }
  // A missing interpolation param renders the literal "{spent}" to the user.
  expect(body).not.toMatch(/\{[a-zA-Z]+\}/);
}

describe('BudgetPopover - the real sentences, in English', () => {
  it('reads correctly for a capped workflow with a server-sent reset date', async () => {
    render(
      <BudgetChip spent={800} cap={1000} periodMode="monthly" resetsAt="2026-10-01T00:00:00Z" />,
    );
    await open();
    expect(text()).toContain('The allowance starts again on');
    expectReadsLikeASentence(text());
  });

  it('promises no date when the server sent none, which is the agent-chip case', async () => {
    // An agent's budget sends no reset date because its subsystem resets on a
    // rolling seven days from its own last reset, on whatever weekday that fell.
    // Any calendar sentence here asserts a rule this chip does not know: the
    // previous wording sent a Saturday-blocked owner to wait for a Monday when
    // it resumes on Thursday.
    render(<BudgetChip spent={800} cap={1000} periodMode="weekly" />);
    await open();

    // The period, as the header badge. It used to be a sentence ("Counted this
    // week."), which repeated in prose what the card had just labelled.
    expect(text()).toContain('this week');
    expect(text()).not.toContain('starts again');
    // The figure carries its unit. This is the whole reason the chip outside can
    // get away with a coin and no word: delete `amountWithUnit` from the Spent
    // row and this is the assertion that notices. The scope line's "in credits"
    // does NOT cover it - that sentence predates this change.
    expect(text()).toContain('800.00 credits');
    expect(text()).toContain('1,000.00 credits');
    expectReadsLikeASentence(text());
  });

  it('reads correctly for a cap that never resets, without naming a resource', async () => {
    // This chip serves agents as well as workflows, so the sentence must not say
    // "since this workflow was created" on an agent's row.
    render(<BudgetChip spent={1000} cap={1000} periodMode="cumulative" />);
    await open();

    expect(text()).toContain('never resets');
    expect(text().toLowerCase()).not.toContain('workflow');
    expectReadsLikeASentence(text());
  });

  it('never contradicts itself on an uncapped figure', async () => {
    // Ungated, it says "no cap set" and "this cap never resets" in one card.
    render(<BudgetChip spent={500} cap={null} fallbackPeriod="cumulative" />);
    await open();

    expect(text()).toContain('None set');
    expect(text()).not.toContain('never resets');
    expect(text()).not.toContain('allowance');
    expect(text().toLowerCase()).not.toContain('workflow');
    expectReadsLikeASentence(text());
  });

  it('reads correctly when the cap is reached', async () => {
    render(
      <BudgetChip spent={1000} cap={1000} periodMode="monthly" resetsAt="2026-10-01T00:00:00Z" />,
    );
    await open();
    expect(text()).toContain('The cap is reached');
    expectReadsLikeASentence(text());
  });
});

describe('BudgetPopover - the real sentences, in French', () => {
  // English is the language a hardcoded English word hides in. The scope line
  // interpolated a literal "credits", which reads correctly in en.json and put
  // an English noun in the middle of the French, German, Spanish, Portuguese
  // and Chinese sentence.
  beforeEach(() => {
    catalogue = fr;
  });

  it('never leaves an English word inside a French sentence', async () => {
    render(
      <BudgetChip spent={800} cap={1000} periodMode="monthly" resetsAt="2026-10-01T00:00:00Z" />,
    );
    await open();

    expect(text()).toContain('Dépense des agents');
    expect(text()).toContain('crédits');
    expect(text()).not.toContain('credits');
    expectReadsLikeASentence(text());
  });

  it('reads as French for a cap that never resets', async () => {
    render(<BudgetChip spent={1000} cap={1000} periodMode="cumulative" />);
    await open();

    expect(text()).toContain('ne se remet jamais à zéro');
    expectReadsLikeASentence(text());
  });

  it('promises no date in French either when none was sent', async () => {
    render(<BudgetChip spent={800} cap={1000} periodMode="weekly" />);
    await open();

    expect(text()).not.toContain('repart le');
    expectReadsLikeASentence(text());
  });
});
