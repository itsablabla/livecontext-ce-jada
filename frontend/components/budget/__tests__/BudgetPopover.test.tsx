// @vitest-environment jsdom
/**
 * The popover behind a spending figure.
 *
 * Two things are tested here and they are different in kind. One is REACH: the
 * detail must be obtainable without a mouse, which is the whole reason this went
 * onto the repo's Radix tooltip instead of a hand-rolled hover - the hand-rolled
 * one opened on `mouseenter` only, above 1024px only, with no ARIA relationship,
 * so its most important sentence ("your automation stops until October") was
 * available to a desktop mouse and nothing else.
 *
 * The other is CONTENT: the sentence a blocked owner reads must be true of THEM.
 * That is not a given here, because the same component serves workflows and
 * agents, whose budgets are two different subsystems with two different reset
 * rules.
 */
import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import { BudgetChip } from '../BudgetChip';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}));

const popover = () => document.querySelector('[data-testid="budget-popover"]');
const trigger = () => screen.getByTestId('budget-chip');

/** Radix opens instantly on focus, which is also the reach we care about. */
async function open() {
  fireEvent.focus(trigger());
  await waitFor(() => expect(popover()).not.toBeNull());
}

afterEach(() => cleanup());

describe('BudgetPopover - reach', () => {
  it('the figure is a real button, so a keyboard can get to it at all', () => {
    // A <span> cannot be focused, which is what made the old implementation
    // mouse-only no matter what it rendered.
    render(<BudgetChip spent={800} cap={1000} periodMode="monthly" />);
    expect(trigger().tagName).toBe('BUTTON');
  });

  it('opens on FOCUS, not only on hover', async () => {
    render(<BudgetChip spent={800} cap={1000} periodMode="monthly" />);
    expect(popover()).toBeNull();
    await open();
  });

  it('is announced to a screen reader through a real aria-describedby relationship', async () => {
    // The previous version set role="tooltip" on an element nothing referenced,
    // which asserts an accessibility relationship that does not exist.
    render(<BudgetChip spent={800} cap={1000} periodMode="monthly" />);
    await open();

    const describedBy = trigger().getAttribute('aria-describedby');
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy as string)).not.toBeNull();
  });

  it('closes on Escape, so a keyboard is never trapped behind it', async () => {
    render(<BudgetChip spent={800} cap={1000} periodMode="monthly" />);
    await open();

    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(popover()).toBeNull());
  });

  it('keeps the FIGURE readable without opening anything at all', () => {
    // The popover is the detail; the figure itself must never depend on it. The
    // visible numbers are aria-hidden, so the sentence carries them.
    render(<BudgetChip spent={400} cap={1000} periodMode="monthly" />);
    expect(trigger().querySelector('.sr-only')?.textContent).toContain('chipTitleCapped');
  });
});

describe('BudgetPopover - what it says', () => {
  it('gives the date the allowance starts again, when the server sent one', async () => {
    render(
      <BudgetChip spent={1000} cap={1000} periodMode="monthly" resetsAt="2026-10-01T00:00:00Z" />,
    );
    await open();
    expect(popover()!.textContent).toContain('budget.popoverResetsOn');
  });

  it('says NOTHING about when, if the server sent no date', async () => {
    // The failure this guards is specific and was real: the fallback used to
    // read "starts again every Monday", which is the WORKFLOW rule. An agent's
    // weekly budget rolls seven days from its own last reset, on whatever
    // weekday that fell, so a Saturday-blocked owner was sent to wait for a
    // Monday when it resumes on Thursday. Silence is the only true option.
    render(<BudgetChip spent={1000} cap={1000} periodMode="weekly" />);
    await open();

    // The period is still SAID - it is the header badge now, not a sentence -
    // so silence about the date is not silence about the figure.
    expect(popover()!.textContent).toContain('budget.periodWeek');
    expect(popover()!.textContent).not.toContain('budget.popoverResetsOn');
    expect(popover()!.textContent).not.toMatch(/popoverResets(Weekly|Monthly)/);
  });

  it('says nothing about resets on an UNCAPPED figure, which has no allowance', async () => {
    render(<BudgetChip spent={500} cap={null} periodMode={null} fallbackPeriod="cumulative" />);
    await open();

    expect(popover()!.textContent).not.toContain('budget.popoverNeverResets');
    expect(popover()!.textContent).not.toContain('budget.popoverResetsOn');
  });

  it('tells a NEVER-RESETS cap the truth instead of promising a reset', async () => {
    render(<BudgetChip spent={1000} cap={1000} periodMode="cumulative" />);
    await open();

    expect(popover()!.textContent).toContain('budget.popoverNeverResets');
    expect(popover()!.textContent).toContain('budget.popoverStoppedForGood');
  });

  it('describes the never-resets period without naming a resource it may not be', async () => {
    // This chip serves agents too. The sentence used to say "since this workflow
    // was created", on the default fallback for every UNCAPPED agent.
    render(<BudgetChip spent={500} cap={null} fallbackPeriod="cumulative" />);
    await open();
    expect(popover()!.textContent).toContain('budget.periodTotal');
  });

  it('says the automation is stopped only once the cap is actually reached', async () => {
    const { rerender } = render(<BudgetChip spent={999} cap={1000} periodMode="monthly" />);
    await open();
    expect(popover()!.textContent).not.toContain('budget.popoverStopped');

    rerender(<BudgetChip spent={1000} cap={1000} periodMode="monthly" />);
    await waitFor(() => expect(popover()!.textContent).toContain('budget.popoverStopped'));
    // Both assertions are needed: `popoverStopped` is a SUBSTRING of
    // `popoverStoppedForGood`, and the two say opposite things about whether the
    // automation comes back on its own.
    expect(popover()!.textContent).not.toContain('budget.popoverStoppedForGood');
  });

  it('never claims an UNCAPPED figure is stopped', async () => {
    render(<BudgetChip spent={999999} cap={null} periodMode="monthly" />);
    await open();

    // Still reporting: the Cap row says there is none, rather than the card
    // going quiet on a figure somebody is looking at.
    expect(popover()!.textContent).toContain('budget.popoverNoCap');
    expect(popover()!.textContent).not.toContain('budget.popoverStopped');
  });

  it('is drawn as the same object as the "Add node" palette card', async () => {
    // The point of the restyle: two hover cards that explain a thing should not
    // be two different objects. This one was a wider, rounder, translucent
    // panel of its own; the palette card and the run panel's step card already
    // agreed with each other, and this was the outlier.
    //
    // Read off the rendered wrapper rather than the source, so it also fails if
    // the shared Tooltip base changes underneath it.
    render(<BudgetChip spent={800} cap={1000} periodMode="monthly" />);
    await open();

    const wrapper = popover()!.parentElement!;
    // `py-2.5` is the palette card's own override; `px-3` comes from the shared
    // Tooltip base and is asserted as part of that base still being there, not
    // as evidence of this change.
    expect(wrapper.className).toContain('py-2.5');
    for (const base of ['rounded-xl', 'border', 'bg-white']) {
      expect(wrapper.className, `the shared Tooltip card lost ${base}`).toContain(base);
    }
    // The panel it stopped being.
    expect(wrapper.className).not.toContain('rounded-[24px]');
    expect(wrapper.className).not.toContain('backdrop-blur');
    expect(wrapper.className).not.toContain('w-80');

    // And the body sets the palette card's own type scale and width, rather
    // than inheriting the Tooltip base's `text-sm`.
    const body = popover()!.className;
    expect(body).toContain('text-xs');
    expect(body).toContain('min-w-[260px]');
    expect(body).toContain('max-w-[320px]');
  });

  it('annotates the period badge with the RESOLVED cadence, not the raw one', async () => {
    // `quarterly` is a value nobody defined: the label falls back, and the
    // attribute has to fall back with it or it annotates a badge reading
    // "total" with `quarterly` - wrong in exactly the case a test needs it.
    render(<BudgetChip spent={10} cap={100} periodMode="quarterly" fallbackPeriod="cumulative" />);
    await open();

    const badge = popover()!.querySelector('[data-budget-period]')!;
    expect(badge.getAttribute('data-budget-period')).toBe('periodTotal');
    expect(badge.textContent).toBe('budget.periodTotal');
  });

  it('does not promise a reset on a period it decided never resets', async () => {
    // The two halves used to be resolved separately: the badge fell back to the
    // caller's default on a cadence nobody implements, `neverResets` read the
    // raw mode and did not. On `quarterly` with an agent's `cumulative`
    // fallback, the card then said "total" in the badge and offered a reset
    // DATE beside it - and told a blocked owner to wait for an allowance that
    // never comes back.
    //
    // A `resetsAt` is what makes this visible, which is why the earlier
    // `quarterly` test could not see it: it passed none.
    render(
      <BudgetChip
        spent={100}
        cap={100}
        periodMode="quarterly"
        fallbackPeriod="cumulative"
        resetsAt="2026-10-01T00:00:00Z"
      />,
    );
    await open();

    const body = popover()!.textContent ?? '';
    expect(body).toContain('budget.popoverNeverResets');
    expect(body).not.toContain('budget.popoverResetsOn');
    // And the cap being reached is stated as permanent, not as "until it resets".
    expect(body).toContain('budget.popoverStoppedForGood');
  });

  it('still promises the date on a cadence that really does reset', async () => {
    // The contrast: resolving once must not turn every card into a lifetime cap.
    render(
      <BudgetChip spent={100} cap={100} periodMode="monthly" resetsAt="2026-10-01T00:00:00Z" />,
    );
    await open();

    const body = popover()!.textContent ?? '';
    expect(body).toContain('budget.popoverResetsOn');
    expect(body).not.toContain('budget.popoverNeverResets');
  });

  it('states what the figure leaves out, so nobody reads it as their bill', async () => {
    render(<BudgetChip spent={800} cap={1000} periodMode="monthly" />);
    await open();
    expect(popover()!.textContent).toContain('budget.popoverScope');
  });
});
