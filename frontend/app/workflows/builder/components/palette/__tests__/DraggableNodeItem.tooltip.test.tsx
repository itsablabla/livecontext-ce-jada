/**
 * @vitest-environment jsdom
 *
 * The "Add node" palette row's hover card.
 *
 * It used to be two grey lines (name, description) in a `max-w-xs` box, and it
 * was rendered ONLY when the row happened to carry a description. It is now the
 * same card the Run tab shows on a step: a bordered header with the name and a
 * right-aligned badge, then separated rows. These pin the parts that are easy to
 * regress by touching one call site: which badge a row gets, what the card says
 * the two gestures do, and that a description-less row still gets a card.
 */
import '@testing-library/jest-dom/vitest';
import * as React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { TooltipProvider } from '@/components/ui/tooltip';
import enMessages from '@/messages/en.json';

// The row's icon reads the theme, which is irrelevant here and would drag a whole
// provider into a test about the hover card.
vi.mock('../../nodes/shared', () => ({ NodeIcon: () => null }));

const { DraggableNodeItem } = await import('../DraggableNodeItem');

const tip = enMessages.workflowBuilder.canvas.paletteTooltip;

/** Render a row and open its hover card, returning the portalled card element. */
async function hoverRow(props: Partial<React.ComponentProps<typeof DraggableNodeItem>> = {}) {
  const { container } = render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      {/* 0ms so the card is up on the next tick rather than after the palette's
          real 150ms delay, which fake timers would have to drive. */}
      <TooltipProvider delayDuration={0}>
        <DraggableNodeItem id="media" label="Media" dragData={{ id: 'media' }} {...props} />
      </TooltipProvider>
    </NextIntlClientProvider>,
  );
  const row = container.querySelector('.group') as HTMLElement;
  fireEvent.pointerEnter(row, { pointerType: 'mouse' });
  fireEvent.mouseEnter(row);
  fireEvent.focus(row);
  return waitFor(() => screen.getByRole('tooltip'));
}

/** The resolved kind, read as data: several row names contain a badge word. */
const badgeOf = (card: HTMLElement) =>
  card.querySelector('[data-palette-badge]')?.getAttribute('data-palette-badge');

describe('DraggableNodeItem - hover card', () => {
  afterEach(() => cleanup());

  it('shows the name and the full description in the run-tab card shape', async () => {
    // The row truncates the name and clamps the description to two lines; the
    // card is the place both are readable in full.
    const card = await hoverRow({ label: 'Merge', description: 'Wait for every incoming branch' });

    expect(card).toHaveTextContent('Merge');
    expect(card).toHaveTextContent('Wait for every incoming branch');
  });

  it('opens for a row with NO description - the badge and the gestures still say something', async () => {
    // The old tooltip was gated on `description`, so exactly the rows whose name
    // is truncated hardest answered nothing at all on hover.
    const card = await hoverRow({ label: 'Some very long node name that truncates' });

    expect(card).toHaveTextContent('Some very long node name that truncates');
    expect(card).toHaveTextContent(tip.badgeNode);
  });

  it('badges a trigger as a trigger from its FAMILY', async () => {
    const card = await hoverRow({ nodeFamily: 'trigger' });
    expect(card).toHaveTextContent(tip.badgeTrigger);
  });

  it('badges a trigger as a trigger from its KIND, which is what the trigger rows pass', async () => {
    // The two call sites that render real trigger rows pass `nodeKind="entry"`
    // and NO family, so a resolver reading only the family badges every trigger
    // in the palette "Node" while a family-based test stays green.
    const card = await hoverRow({ nodeKind: 'entry' });
    expect(card).toHaveTextContent(tip.badgeTrigger);
  });

  it('badges a single API endpoint as a Node, not as an Integration', async () => {
    // `isMcp` is an icon-source flag: the endpoint rows carry it too. The
    // integration is the row that opens a tool list, so the chevron is the other
    // half of the test - without it every endpoint claimed to be an integration.
    const card = await hoverRow({ isMcp: true });
    expect(card).toHaveTextContent(tip.badgeNode);
    expect(card).not.toHaveTextContent(tip.badgeIntegration);
  });

  it('badges the "Triggers" GROUP tile a category, on the role the palette declares', async () => {
    // Found in a browser, not here. The group tile is `kind: 'entry'` and
    // `family: 'trigger'` (it is the Triggers class), so ranking the type first
    // labelled a folder "Trigger" - a row reading "Triggers / Trigger". Ranking
    // the chevron first fixed that and broke the three drill-down triggers
    // below instead: no rule over these props separates the two, so the branch
    // that renders group tiles declares the role.
    const card = await hoverRow({ label: 'Triggers', nodeKind: 'entry', nodeFamily: 'trigger', showArrow: true, disableDrag: true, paletteRole: 'category' });
    // Read off the badge, not the card: the row's own name contains the word.
    expect(badgeOf(card)).toBe('category');
    expect(card.querySelector('[data-palette-badge]')).toHaveTextContent(tip.badgeCategory);
  });

  it('badges a trigger that DRILLS DOWN a trigger, not a category', async () => {
    // `tables-trigger` / `workflows-trigger` / `error-trigger` sit inside the
    // Triggers list with a chevron: they open a list AND they are triggers. A
    // chevron-first rule called all three "Category".
    const card = await hoverRow({ label: 'Tables trigger', nodeKind: 'entry', showArrow: true, disableDrag: true });
    expect(badgeOf(card)).toBe('trigger');
  });

  it('lets the declared role override even an integration row', async () => {
    // The override is unconditional on purpose: the palette knows more than the
    // props do, and a rule that quietly wins over it is the bug this replaced.
    const card = await hoverRow({ isMcp: true, showArrow: true, paletteRole: 'category' });
    expect(badgeOf(card)).toBe('category');
  });

  it('badges a DRAGGABLE group row as a category, and promises it no drag', async () => {
    // The top-level AI / Flow / Core groups navigate AND are draggable, and
    // `getPaletteItemDataFromId` never returns falsy, so a rule keyed on
    // `disableDrag` badged them "Node" and advertised a drag that drops a
    // nameless node on the canvas.
    const card = await hoverRow({ showArrow: true, dragData: { id: 'flow' } });
    expect(card).toHaveTextContent(tip.badgeCategory);
    expect(card).toHaveTextContent(tip.hintClickBrowse);
    expect(card).not.toHaveTextContent(tip.hintDrag);
  });

  it('badges an integration row as an integration, not as a category', async () => {
    // An integration row opens its tool list AND drags onto the canvas as a whole
    // node, so `showArrow` alone must not decide the badge.
    const card = await hoverRow({ isMcp: true, showArrow: true });
    expect(card).toHaveTextContent(tip.badgeIntegration);
    expect(card).not.toHaveTextContent(tip.badgeCategory);
  });

  it('badges a navigation row as a category', async () => {
    const card = await hoverRow({ showArrow: true, disableDrag: true, dragData: undefined });
    expect(card).toHaveTextContent(tip.badgeCategory);
  });

  it('says "click to add, or drag" for a row that adds a node', async () => {
    const card = await hoverRow();
    expect(card).toHaveTextContent(tip.hintClickAdd);
    expect(card).toHaveTextContent(tip.hintDrag);
  });

  it('says "click to browse" and offers NO drag hint for a row that only opens a list', async () => {
    // The hint is composed from the row's real capabilities: promising a drag a
    // category row refuses is worse than saying nothing.
    const card = await hoverRow({ showArrow: true, disableDrag: true, dragData: undefined });
    expect(card).toHaveTextContent(tip.hintClickBrowse);
    expect(card).not.toHaveTextContent(tip.hintDrag);
  });

  it('offers a drag hint for an integration row, which browses AND drags', async () => {
    const card = await hoverRow({ isMcp: true, showArrow: true });
    expect(card).toHaveTextContent(tip.hintClickBrowse);
    expect(card).toHaveTextContent(tip.hintDrag);
  });

  it('names the missing plan, which the row only shows as a padlock', async () => {
    const card = await hoverRow({ lockedPlan: 'PRO' });
    expect(card).toHaveTextContent('Requires PRO');
  });

  it('shows the row\'s extra fact alongside the description, not instead of it', async () => {
    // The ROW picks one or the other; the card has room for both, and "5 tools"
    // is the fact that decides whether an integration is worth opening.
    const card = await hoverRow({ description: 'Send messages', secondaryInfo: '5 tools' });
    expect(card).toHaveTextContent('Send messages');
    expect(card).toHaveTextContent('5 tools');
  });
});
