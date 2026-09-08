// @vitest-environment jsdom
/**
 * The board card's spending gauge.
 *
 * The gauge used to be nested inside a "this run cost something" guard, which
 * hid it in exactly the situation it matters most: re-pinning a workflow mints a
 * fresh production run whose cost is 0, so a workflow sitting blocked at its cap
 * showed no cost and no gauge, while the /app/workflow list showed both. Two
 * surfaces, same workflow, different story.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, cleanup, fireEvent, waitFor } from '@testing-library/react';

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
// Interpolating translator: the tooltip's VALUES have to be observable, or a
// test that only checks the title attribute exists proves nothing about what it
// says.
vi.mock('next-intl', () => ({
  useTranslations: () => (k: string, vars?: Record<string, unknown>) =>
    vars ? `${k}|${Object.entries(vars).map(([n, v]) => `${n}=${v}`).join(',')}` : k,
}));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => null }));

import { WorkflowBoardCard } from '../WorkflowBoardCard';
import type { WorkflowBoardCard as CardType } from '@/lib/api/orchestrator/types';

function card(overrides: Partial<CardType>): CardType {
  return { workflowId: 'wf-1', name: 'X', runCount: 0, column: 'production', ...overrides } as CardType;
}

// The board card renders the same BudgetChip as every other surface now, so it
// is found by that test id rather than by a native title it no longer carries.
const gauge = (c: HTMLElement) => c.querySelector('[data-testid="budget-chip"]');

afterEach(() => cleanup());

describe('WorkflowBoardCard - spending gauge', () => {
  it('shows the gauge for a freshly re-pinned workflow whose run has cost nothing yet', () => {
    // The regression case: costCredits is 0 because the production run is new,
    // but the workflow is at 8 of its 10 for the period.
    const { container } = render(
      <WorkflowBoardCard
        card={card({ costCredits: 0, budgetCredits: 10, budgetPeriodSpent: 8, budgetPeriodMode: 'monthly' })}
        onDragStart={() => {}}
      />,
    );
    expect(gauge(container)).not.toBeNull();
  });

  it('colours the gauge red on the PERIOD spend, not on the run total', () => {
    // A pinned workflow keeps one run for months, so its lifetime cost crosses
    // the cap long before the period does. Colouring off the total would
    // announce a stop that has not happened.
    const { container } = render(
      <WorkflowBoardCard
        card={card({ costCredits: 999, budgetCredits: 10, budgetPeriodSpent: 2, budgetPeriodMode: 'monthly' })}
        onDragStart={() => {}}
      />,
    );
    expect(gauge(container)!.className).not.toMatch(/text-red/);
  });

  it('goes red once the period spend reaches the cap', () => {
    const { container } = render(
      <WorkflowBoardCard
        card={card({ costCredits: 1, budgetCredits: 10, budgetPeriodSpent: 10, budgetPeriodMode: 'monthly' })}
        onDragStart={() => {}}
      />,
    );
    expect(gauge(container)!.className).toMatch(/text-red/);
  });

  it('draws no gauge when there is no cap, however much the run has cost', () => {
    // Without a ceiling there is nothing to gauge against, and a bar with no
    // upper bound would invent one.
    const { container } = render(
      <WorkflowBoardCard
        card={card({ costCredits: 42, budgetPeriodSpent: 42, budgetPeriodMode: 'monthly' })}
        onDragStart={() => {}}
      />,
    );
    expect(gauge(container)).toBeNull();
    expect(container.querySelector('[title^="card.costTitle"]')).not.toBeNull();
  });

  it('draws nothing at all for a workflow that has neither spent nor been capped', () => {
    // The default state of most cards. The server sends 0, never null, so a
    // null check here would have rendered a lone separator followed by nothing.
    const { container } = render(
      <WorkflowBoardCard card={card({ costCredits: 0, budgetPeriodSpent: 0 })} onDragStart={() => {}} />,
    );
    expect(gauge(container)).toBeNull();
    expect(container.querySelector('[title^="card.costTitle"]')).toBeNull();
  });

  it('shows both figures on the card, and leaves the rest to the popover', () => {
    // The card is a glance: two numbers. Over what period, when it resets and
    // what is excluded are a paragraph, and a paragraph does not belong on a
    // kanban card - it belongs behind the hover.
    const { container } = render(
      <WorkflowBoardCard
        card={card({ costCredits: 0, budgetCredits: 10, budgetPeriodSpent: 8, budgetPeriodMode: 'weekly' })}
        onDragStart={() => {}}
      />,
    );
    expect(gauge(container)!.textContent).toContain('/');
    expect(container.querySelector('[data-testid="budget-popover"]')).toBeNull();
  });

  it('carries the cadence to the chip, so the popover can say over what period', () => {
    // Without it, dropping `periodMode` from the card leaves the suite green
    // while every board chip silently falls back to the monthly wording.
    //
    // It is no longer VISIBLE on the card: the period word was the longest and
    // least surprising part of the figure, and it is now the popover's header
    // badge. So this reads the chip's own sentence for assistive tech, which is
    // where the resolved cadence still lives.
    const { container } = render(
      <WorkflowBoardCard
        card={card({ costCredits: 0, budgetCredits: 10, budgetPeriodSpent: 8, budgetPeriodMode: 'weekly' })}
        onDragStart={() => {}}
      />,
    );
    const sentence = container.querySelector('[data-testid="budget-chip"] .sr-only')?.textContent ?? '';
    expect(sentence).toContain('periodWeek');
    expect(sentence).not.toContain('periodMonth');

    // And it does NOT spend the meta row's width on it.
    const visible = Array.from(container.querySelectorAll('[data-testid="budget-chip"] span'))
      .filter((el) => !el.classList.contains('sr-only'))
      .map((el) => el.textContent)
      .join(' ');
    expect(visible).not.toContain('periodWeek');
  });

  it('shows exactly ONE coin on the row, not one per figure', () => {
    // The row prints the run's own cost with a coin, then the period spend. The
    // chip draws its own coin everywhere else, so this row passes
    // `showIcon={false}`: two coins side by side read as two different units.
    const { container } = render(
      <WorkflowBoardCard
        card={card({ costCredits: 4, budgetCredits: 10, budgetPeriodSpent: 8, budgetPeriodMode: 'monthly' })}
        onDragStart={() => {}}
      />,
    );
    const row = gauge(container)!.parentElement!;
    expect(row.querySelectorAll('svg.lucide-coins')).toHaveLength(1);
    // The figures themselves are both still there.
    expect(row.textContent).toContain('8');
    expect(row.textContent).toContain('10');
  });

  it('passes the reset date through to the chip, so the popover can name a day', async () => {
    // The prop is a wiring seam: deleting it leaves every test green while the
    // popover quietly degrades from "starts again on Oct 01" to no sentence at
    // all. Asserted through the rendered popover, which is what a user sees.
    const { container } = render(
      <WorkflowBoardCard
        card={card({
          costCredits: 0,
          budgetCredits: 10,
          budgetPeriodSpent: 8,
          budgetPeriodMode: 'monthly',
          budgetPeriodResetsAt: '2026-10-01T00:00:00Z',
        })}
        onDragStart={() => {}}
      />,
    );

    fireEvent.focus(gauge(container) as HTMLElement);
    await waitFor(() => {
      expect(document.querySelector('[data-testid="budget-popover"]')?.textContent)
        .toContain('popoverResetsOn');
    });
  });

  it('carries no native title, so the browser cannot draw a second tooltip over the popover', () => {
    const { container } = render(
      <WorkflowBoardCard
        card={card({ costCredits: 0, budgetCredits: 10, budgetPeriodSpent: 8, budgetPeriodMode: 'monthly' })}
        onDragStart={() => {}}
      />,
    );
    expect(gauge(container)!.getAttribute('title')).toBeNull();
  });
});
