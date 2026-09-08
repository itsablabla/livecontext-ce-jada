/**
 * That the agenda actually MOUNTS the drag preview, and clears it on every exit.
 *
 * <p>This reads source rather than rendering, and that is a deliberate trade. AgendaView is
 * the page: auth, router, search params, the org store, toasts, preferences and the agenda
 * fetch all have to be stood up before a single chip exists, and simulating a dnd-kit drag
 * in jsdom needs hand-fed element rects on top of that. What the cost buys is small, so the
 * cheap version is what gets written and kept.
 *
 * <p>What justifies keeping it at all is the shape of the regression. Delete the
 * `DragOverlay` and nothing fails: OccurrenceDragPreview's own tests still pass, the chip
 * still drags, the drop still opens the dialog - and the user is back to a gesture with no
 * feedback, which is the defect this work was opened for. Same for `onDragCancel`: without
 * it a drag abandoned with Escape leaves the card stranded under the cursor with nothing
 * left to move it, and no other test can see that either.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const SOURCE = readFileSync(
  join(process.cwd(), 'components', 'views', 'AgendaView.tsx'),
  'utf8',
);

describe('AgendaView drag wiring', () => {
  it('renders the preview inside a DragOverlay, which is what tracks the pointer', () => {
    // Nested, not merely both present: a preview mounted outside the overlay renders in the
    // page flow and never moves, which is the state this work started from.
    const overlay = SOURCE.slice(SOURCE.indexOf('<DragOverlay'));
    expect(SOURCE).toContain('<DragOverlay');
    expect(overlay.slice(0, overlay.indexOf('</DragOverlay>'))).toContain('<OccurrenceDragPreview');
  });

  it('feeds the preview a proposed start, so it can say where the drop would land', () => {
    // Without onDragOver the card follows the pointer saying only what the chip already
    // says. The time is the one thing a move changes and the only reason to look at it.
    expect(SOURCE).toContain('onDragOver=');
    expect(SOURCE).toMatch(/proposedStart=\{/);
  });

  it('resolves that proposal with the SAME rule the drop uses', () => {
    // resolveDropStart is what the drop calls. A second rule here would let the preview
    // promise one time and the dialog open on another, which is worse than no preview: the
    // user would confirm a pre-filled value they had been shown differently a moment before.
    const dragOver = SOURCE.slice(SOURCE.indexOf('const handleDragOver'));
    expect(dragOver.slice(0, dragOver.indexOf('const handleDragEnd'))).toContain(
      'resolveDropStart(',
    );
  });

  it('clears the preview on cancel as well as on drop', () => {
    // A drag abandoned with Escape, or cancelled by the browser claiming the pointer, never
    // reaches onDragEnd.
    //
    // This reads the handler's body because the file writes it inline, and a source test can
    // do no better: it cannot follow an identifier. So if the handler is ever extracted to a
    // named callback, this expectation has to be pointed at the new name - it is not
    // refactor-proof and there is no way to make it so. That cost is the reason the rest of
    // the suite asserts props rather than bodies.
    const cancel = SOURCE.slice(SOURCE.indexOf('onDragCancel='));
    expect(SOURCE).toContain('onDragCancel=');
    expect(cancel.slice(0, 120)).toContain('setActiveDrag(null)');
  });

  it('clears it before the early returns in the drop handler, not after them', () => {
    // handleDragEnd returns early for a drop outside any cell. Clearing at the bottom would
    // leave the card on screen for exactly the gesture a user makes to change their mind.
    const dragEnd = SOURCE.slice(SOURCE.indexOf('const handleDragEnd'));
    const body = dragEnd.slice(0, dragEnd.indexOf('setMoveTarget'));
    expect(body.indexOf('setActiveDrag(null)')).toBeLessThan(body.indexOf('return;'));
  });
});
