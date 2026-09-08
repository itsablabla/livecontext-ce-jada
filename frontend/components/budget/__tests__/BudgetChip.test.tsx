// @vitest-environment jsdom
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BudgetChip } from '../BudgetChip';

// next-intl is not wired in unit tests: return the key plus its params so the
// assertions read the wording decisions, not a translated string.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}));

describe('BudgetChip', () => {
  it('renders nothing when there is neither a cap nor a spend', () => {
    // A workflow that has never run in production must not carry a "0" that
    // means nothing - that is noise on every card in the list.
    const { container } = render(<BudgetChip spent={null} cap={null} />);
    expect(container.innerHTML).toBe('');
  });

  it('renders nothing when the spend is zero and no cap is set', () => {
    const { container } = render(<BudgetChip spent={0} cap={null} />);
    expect(container.innerHTML).toBe('');
  });

  it('shows the spend alone when no cap is set, which is the default', () => {
    render(<BudgetChip spent={1200} cap={null} />);
    // No slash: an uncapped workflow is reporting, not being measured.
    expect(screen.queryByText('/')).toBeNull();
  });

  it('shows spend over cap when a cap is set', () => {
    render(<BudgetChip spent={400} cap={1000} />);
    expect(screen.getByText('/')).toBeTruthy();
  });

  it('carries NO native title: the detail belongs to the popover, not a second copy of it', () => {
    // A `title` on top of the popover doubles every hover with a worse version
    // of the same sentence, and the browser draws it over the card.
    const { container } = render(<BudgetChip spent={400} cap={1000} />);
    expect(container.querySelector('[title]')).toBeNull();
  });

  it('keeps the full sentence as visually-hidden TEXT, because the popover needs a mouse', () => {
    // Not aria-label: this is a plain <span>, whose implicit role is `generic`,
    // and aria-label on a generic element is prohibited by ARIA-in-HTML and
    // dropped from the accessibility tree. Text is not. Without it, dropping
    // the old title would have taken the figure's meaning away from exactly the
    // people who cannot see the colour it is painted in, and from every phone.
    const { container } = render(<BudgetChip spent={400} cap={1000} periodMode="monthly" />);
    const hidden = container.querySelector('.sr-only')?.textContent ?? '';
    expect(hidden).toContain('chipTitleCapped');
    expect(hidden).toContain('periodMonth');
  });

  it('hides the DECORATIVE copy of the figures, so they are not read out twice', () => {
    // The sr-only sentence already says "400 / 1000 this month". Leaving the
    // visible spans exposed makes a screen reader say the numbers again, out of
    // any sentence.
    //
    // Asserting "some aria-hidden span exists" would pass while the FIGURES
    // were still exposed and only the "/" was hidden, so name the elements:
    // every child of the chip except the sr-only sentence must be hidden.
    const { container } = render(<BudgetChip spent={400} cap={1000} periodMode="monthly" />);
    const chip = container.firstElementChild as HTMLElement;
    const exposed = Array.from(chip.children).filter(
      (el) => !el.classList.contains('sr-only') && el.getAttribute('aria-hidden') !== 'true',
    );
    expect(exposed).toHaveLength(0);
  });

  it('the uncapped chip says so out loud too, rather than reading as a bare number', () => {
    const { container } = render(<BudgetChip spent={400} cap={null} />);
    expect(container.querySelector('.sr-only')?.textContent).toContain('chipTitleUncapped');
  });

  it('renders a cap with no spend yet, so the card shows the cap exists', () => {
    const { container } = render(<BudgetChip spent={null} cap={1000} />);
    expect(container.innerHTML).not.toBe('');
  });

  /**
   * The period is still RESOLVED here, and the resolution still matters: the
   * two owners disagree about what an empty mode means, and getting it wrong
   * promises a reset that never happens. What changed is where it is SAID. It
   * used to be a visible word glued to the figure ("10 / 100 total"), the
   * longest and least surprising part of a chip that lives in a card's meta
   * row; it is now carried in the chip's own sentence for assistive tech, and
   * shown to everyone as the popover's header badge.
   *
   * So these assertions read the sr-only sentence. They pin exactly what they
   * pinned before - which period this chip believes it is reporting - and they
   * would still fail on a chip that resolved the wrong one.
   */
  const period = (container: HTMLElement) => container.querySelector('.sr-only')?.textContent ?? '';

  it('names the period it is reporting, so a figure is never ambiguous', () => {
    const { container, rerender } = render(<BudgetChip spent={10} cap={100} periodMode="monthly" />);
    expect(period(container)).toContain('periodMonth');
    rerender(<BudgetChip spent={10} cap={100} periodMode="weekly" />);
    expect(period(container)).toContain('periodWeek');
    rerender(<BudgetChip spent={10} cap={100} periodMode="cumulative" />);
    expect(period(container)).toContain('periodTotal');
  });

  it('never spends the row on the period word, which is what used to make it wrap', () => {
    // The regression this guards: the chip printed "10 / 100 total". In a card
    // meta row, beside a date and a run count, that word is what pushed the
    // line onto a second row - and "total" tells a reader nothing they had not
    // already assumed.
    const { container } = render(<BudgetChip spent={10} cap={100} periodMode="cumulative" />);
    const visible = Array.from(container.querySelectorAll('span'))
      .filter((el) => !el.classList.contains('sr-only'))
      .map((el) => el.textContent)
      .join(' ');
    expect(visible).not.toContain('periodTotal');
    expect(visible).not.toContain('periodMonth');
  });

  it('says what the number IS with a glyph rather than a word', () => {
    // One 12px box, the same width in every locale, where a translated unit
    // would not be. Matched as "the chip's one decorative icon", not by lucide's
    // own class name: a lucide upgrade that renames it would turn this red with
    // nothing wrong.
    const { container } = render(<BudgetChip spent={10} cap={100} />);
    const icons = container.querySelectorAll('svg');
    expect(icons).toHaveLength(1);
    // Decorative: the sr-only sentence already names the unit, and an icon left
    // exposed would have it read out a second time with no sentence around it.
    expect(icons[0].getAttribute('aria-hidden')).toBe('true');
  });

  it('drops the coin where the row already opens with one', () => {
    // The run panel and the board card print a run's own cost first, with its
    // own coin. A second one between the two figures reads as a second unit.
    //
    // Counted, not matched on lucide's own class: a selector that stops
    // matching passes this by rendering nothing, which is the failure mode it
    // is supposed to catch.
    const { container } = render(<BudgetChip spent={10} cap={100} showIcon={false} />);
    expect(container.querySelectorAll('svg')).toHaveLength(0);
    // The figure itself is untouched by the icon decision.
    expect(container.textContent).toContain('10');
  });

  it('declares the two classes that keep it on its row', () => {
    // A CLASS contract, named as one: jsdom computes no layout, so this cannot
    // and does not claim the chip survives any parent. What it pins is that the
    // chip brings its own nowrap and refuses to shrink, which is the half of
    // the one-line guarantee that belongs to the chip rather than to the row.
    // The row's half is pinned in WorkflowTable.metaRow.test.tsx.
    const { container } = render(<BudgetChip spent={10} cap={100} />);
    const classes = (container.querySelector('button')?.className ?? '').split(/\s+/);
    expect(classes).toContain('whitespace-nowrap');
    expect(classes).toContain('shrink-0');
  });

  it('defaults an unknown or missing period to the monthly wording', () => {
    const { container } = render(<BudgetChip spent={10} cap={100} periodMode={null} />);
    expect(period(container)).toContain('periodMonth');
  });

  it('lets an agent say its empty period means "never resets", not "this month"', () => {
    // The two owners disagree on what an empty mode means: a workflow with no
    // mode resets monthly, an agent with no mode never resets. Guessing one
    // default for both would promise a reset that never happens.
    const { container, rerender } = render(
      <BudgetChip spent={10} cap={100} periodMode={null} fallbackPeriod="cumulative" />,
    );
    expect(period(container)).toContain('periodTotal');

    // An explicit mode always wins over the fallback.
    rerender(<BudgetChip spent={10} cap={100} periodMode="weekly" fallbackPeriod="cumulative" />);
    expect(period(container)).toContain('periodWeek');
  });

  it('falls back rather than inventing a month when the mode is a value nobody defined', () => {
    const { container } = render(
      <BudgetChip spent={10} cap={100} periodMode="quarterly" fallbackPeriod="cumulative" />,
    );
    expect(period(container)).toContain('periodTotal');
  });

  it('warns in amber from 80% of the cap and in red at the cap', () => {
    const { container, rerender } = render(<BudgetChip spent={10} cap={100} />);
    expect(container.firstElementChild?.className).toContain('text-theme-muted');

    rerender(<BudgetChip spent={80} cap={100} />);
    expect(container.firstElementChild?.className).toContain('amber');

    rerender(<BudgetChip spent={100} cap={100} />);
    expect(container.firstElementChild?.className).toContain('red');
  });

  it('never paints an uncapped chip as over budget, whatever it has spent', () => {
    // Without a cap there is no threshold to cross: colouring it red would
    // invent a limit the user never set.
    const { container } = render(<BudgetChip spent={999999} cap={null} />);
    expect(container.firstElementChild?.className).toContain('text-theme-muted');
    expect(container.firstElementChild?.className).not.toContain('red');
  });
});
