// @vitest-environment jsdom
import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { TooltipProvider } from '@/components/ui/tooltip';

// The row's icon reads the theme, which is irrelevant to the lock and would drag a
// whole provider into a test about drag and click.
vi.mock('../../nodes/shared', () => ({ NodeIcon: () => null }));

const { DraggableNodeItem } = await import('../DraggableNodeItem');

function renderItem(props: Partial<React.ComponentProps<typeof DraggableNodeItem>> = {}) {
  return render(
    <TooltipProvider>
      <DraggableNodeItem id="media" label="Media" dragData={{ id: 'media' }} {...props} />
    </TooltipProvider>,
  );
}

describe('DraggableNodeItem - plan marker', () => {
  it('renders no marker and stays draggable when the plan includes the node', () => {
    const { container } = renderItem();
    const row = container.querySelector('[draggable]') as HTMLElement;

    expect(row.getAttribute('draggable')).toBe('true');
    expect(container.querySelector('[aria-label="PRO"]')).toBeNull();
  });

  it('marks the row with the padlock alone, named for a screen reader', () => {
    // A pill competed with the row's own text on a list where most rows are
    // unmarked. The plan name belongs in the sentence on the node; here the
    // padlock carries it as its accessible name and nothing is drawn twice.
    const { container } = renderItem({ lockedPlan: 'PRO' });

    expect(screen.queryByText('PRO')).toBeNull();
    expect(container.querySelector('[aria-label="PRO"]')).toBeTruthy();
  });

  it('STAYS draggable while marked - the marker informs, it does not refuse', () => {
    // Refusing here was a false floor: dragging the integration and then picking a
    // restricted endpoint in the inspector reached the same place unmarked. The
    // boundary is the backend; the canvas node carries the warning.
    const { container } = renderItem({ lockedPlan: 'PRO' });
    const row = container.querySelector('.group') as HTMLElement;

    expect(row.getAttribute('draggable')).toBe('true');
  });

  it('carries its drag payload like any other row', () => {
    const { container } = renderItem({ lockedPlan: 'PRO' });
    const row = container.querySelector('.group') as HTMLElement;
    const setData = vi.fn();

    fireEvent.dragStart(row, { dataTransfer: { setData, setDragImage: vi.fn() } });

    expect(setData).toHaveBeenCalled();
  });

  it('reports the click like any other row', () => {
    const onClick = vi.fn();
    const { container } = renderItem({ lockedPlan: 'PRO', onClick });

    fireEvent.click(container.querySelector('.group') as HTMLElement);

    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
