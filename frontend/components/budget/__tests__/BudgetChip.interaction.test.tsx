// @vitest-environment jsdom
/**
 * How the chip BEHAVES, as opposed to what it says.
 *
 * Three pieces of this feature are deliberate and were, until this file,
 * uncovered. Each of them is invisible when it breaks, which is exactly why
 * they need a guard rather than a comment.
 *
 * <p>1. TOUCH. Radix's open-on-hover returns early on `pointerType === 'touch'`
 * by design, so the chip adds its own tap handler, and that handler's
 * `preventDefault()` is load-bearing: Radix's trigger also closes on `onClick`,
 * and a real tap emits a compatibility `click` immediately after `pointerdown`.
 * Preventing the default is what suppresses that click; without it the popover
 * closes in the same gesture that opened it and a tap visibly does nothing.
 *
 * <p>{@link tap} therefore fires the click only when the `pointerdown` was not
 * prevented, which is what a browser does; firing both unconditionally models
 * no real device. The guard that actually catches a deleted `preventDefault()`
 * is the direct assertion on `defaultPrevented`, and it is there for that
 * reason: in jsdom the close that follows is swallowed by React's batching of
 * the two state updates, so the behavioural test alone stays green while a real
 * phone shows nothing. When an effect cannot be observed in this environment,
 * assert the mechanism that produces it rather than trusting the effect.
 *
 * <p>2. The chip lives inside cards whose whole surface navigates. A tap or a
 * click that reaches the card sends the reader to another page instead of
 * showing them the figure's detail.
 *
 * <p>3. STALENESS. An agent's spend counter is reset lazily, only when the agent
 * next runs, so an agent that hit its cap in September and has not run since
 * still reads at the cap today. Claiming "no new run starts" about that figure
 * tells an owner their automation is dead when its next run will reset the
 * counter and proceed.
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

/**
 * One tap, the way a browser delivers it: `pointerdown`, then the compatibility
 * `click` - and that click ONLY when the pointerdown was not prevented, which is
 * the browser rule this component depends on.
 */
function tap(el: Element, pointerType: 'touch' | 'mouse' = 'touch') {
  const down = new Event('pointerdown', { bubbles: true, cancelable: true });
  Object.defineProperty(down, 'pointerType', { value: pointerType });
  el.dispatchEvent(down);
  if (!down.defaultPrevented) fireEvent.click(el);
  return down;
}

afterEach(() => cleanup());

describe('BudgetChip - touch', () => {
  it('opens on a TAP and STAYS open through the click that follows', async () => {
    // The whole gesture, not just its first half. Radix closes on click, so a
    // tap that does not suppress its compatibility click opens and closes the
    // popover in one gesture: to the reader, tapping does nothing at all.
    render(<BudgetChip spent={800} cap={1000} periodMode="monthly" />);
    expect(popover()).toBeNull();

    tap(trigger());

    await waitFor(() => expect(popover()).not.toBeNull());
  });

  it('prevents the default on a tap, which is what suppresses that click', () => {
    // Asserted directly as well as through its effect, so the reason survives
    // even if Radix later stops closing on click.
    render(<BudgetChip spent={800} cap={1000} periodMode="monthly" />);

    expect(tap(trigger()).defaultPrevented).toBe(true);
  });

  it('closes on a SECOND tap, so a phone is never stuck with it open', async () => {
    // A tooltip has no dismiss affordance on a phone, which is the reason Radix
    // skips touch in the first place. Toggling is what replaces it.
    render(<BudgetChip spent={800} cap={1000} periodMode="monthly" />);

    tap(trigger());
    await waitFor(() => expect(popover()).not.toBeNull());

    tap(trigger());
    await waitFor(() => expect(popover()).toBeNull());
  });

  it('leaves a MOUSE press to Radix instead of toggling under it', () => {
    // The handler must be touch-only, and must not prevent a mouse default:
    // toggling on a mouse press would fight the hover that is already opening
    // and closing the same popover.
    render(<BudgetChip spent={800} cap={1000} periodMode="monthly" />);

    const down = tap(trigger(), 'mouse');

    expect(down.defaultPrevented).toBe(false);
    expect(popover()).toBeNull();
  });
});

describe('BudgetChip - never navigates the card underneath', () => {
  it('swallows the click instead of letting the surrounding card handle it', () => {
    // Every consumer (workflow card, agent row, application card) wraps this in
    // a clickable container. Without stopPropagation, reading a figure means
    // being navigated away from it.
    const cardClicked = vi.fn();
    render(
      <div onClick={cardClicked}>
        <BudgetChip spent={800} cap={1000} periodMode="monthly" />
      </div>,
    );

    fireEvent.click(trigger());

    expect(cardClicked).not.toHaveBeenCalled();
  });

  it('never reaches the card on a TAP either', () => {
    // The tap suppresses its own compatibility click, so nothing reaches the
    // card. Should that suppression ever go away, the stopPropagation below is
    // the second layer that still keeps the reader on the page they are on.
    const cardClicked = vi.fn();
    render(
      <div onClick={cardClicked}>
        <BudgetChip spent={800} cap={1000} periodMode="monthly" />
      </div>,
    );

    tap(trigger());
    fireEvent.click(trigger());

    expect(cardClicked).not.toHaveBeenCalled();
  });
});

describe('BudgetChip - a stale figure never claims the automation is stopped', () => {
  async function openWith(props: Partial<React.ComponentProps<typeof BudgetChip>>) {
    render(<BudgetChip spent={1000} cap={1000} periodMode="monthly" {...props} />);
    fireEvent.focus(trigger());
    await waitFor(() => expect(popover()).not.toBeNull());
    return popover()!.textContent ?? '';
  }

  it('withholds the claim when the figure may be stale', async () => {
    // Delete `&& spendIsCurrent` from the gate in BudgetPopover and this is the
    // only test in the suite that turns red.
    const body = await openWith({ spendIsCurrent: false });

    expect(body).not.toContain('budget.popoverStopped');
    expect(body).not.toContain('budget.popoverStoppedForGood');
  });

  it('still reports the figure and the period, so silence is not blankness', async () => {
    // Withholding one sentence must not withhold the card. An agent owner at
    // their cap still needs to read what they spent and against what.
    const body = await openWith({ spendIsCurrent: false });

    expect(body).toContain('budget.popoverSpent');
    expect(body).toContain('budget.popoverCap');
    expect(body).toContain('budget.periodMonth');
  });

  it('makes the claim on the same figure once it is known to be current', async () => {
    // The contrast that gives the two tests above their meaning: the wording is
    // gated on staleness alone, not quietly removed for everyone.
    const body = await openWith({ spendIsCurrent: true });

    expect(body).toContain('budget.popoverStopped');
  });

  it('says nothing about being stopped on a stale figure that is UNDER its cap', async () => {
    render(<BudgetChip spent={400} cap={1000} periodMode="monthly" spendIsCurrent={false} />);
    fireEvent.focus(trigger());
    await waitFor(() => expect(popover()).not.toBeNull());

    expect(popover()!.textContent).not.toContain('budget.popoverStopped');
  });
});

describe('BudgetChip - the reset date follows the APP locale', () => {
  // The one genuinely locale-sensitive value in this feature. `formatUtcDate` is
  // called with no locale argument so it defaults through `getClientLocale()`;
  // hardcoding 'en' there, or reaching for the BROWSER language, would both pass
  // every other test in this folder while showing a French reader an English
  // month.
  afterEach(() => {
    document.cookie = 'NEXT_LOCALE=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  async function openWithLocale(locale: string) {
    document.cookie = `NEXT_LOCALE=${locale}; path=/`;
    render(
      <BudgetChip spent={800} cap={1000} periodMode="monthly" resetsAt="2026-10-01T00:00:00Z" />,
    );
    fireEvent.focus(trigger());
    await waitFor(() => expect(popover()).not.toBeNull());
    return popover()!.textContent ?? '';
  }

  it('renders the month in French for a French reader', async () => {
    expect(await openWithLocale('fr')).toContain('oct.');
  });

  it('renders the month in English for an English reader', async () => {
    expect(await openWithLocale('en')).toContain('Oct');
  });
});
