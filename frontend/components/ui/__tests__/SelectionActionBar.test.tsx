// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import { SelectionActionBar, BulkBarButton, bulkBarButtonClass } from '../SelectionActionBar';

afterEach(cleanup);

function renderBar(ui: React.ReactNode) {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      {ui}
    </NextIntlClientProvider>,
  );
}

describe('SelectionActionBar', () => {
  it('renders the "{count} selected" label from the common namespace', () => {
    renderBar(
      <SelectionActionBar count={3} onClear={() => {}}>
        <BulkBarButton>Act</BulkBarButton>
      </SelectionActionBar>,
    );
    // Real en.json → common.selectedCount = "{count} selected".
    expect(screen.getByText('3 selected')).toBeInTheDocument();
  });

  it('renders the provided action buttons as children', () => {
    renderBar(
      <SelectionActionBar count={1} onClear={() => {}}>
        <BulkBarButton>Clone</BulkBarButton>
        <BulkBarButton variant="danger">Delete</BulkBarButton>
      </SelectionActionBar>,
    );
    expect(screen.getByRole('button', { name: 'Clone' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
  });

  it('floats bottom-center (absolute), not pushed inline - regression for the inline→floating move', () => {
    const { getByTestId } = renderBar(
      <SelectionActionBar count={1} onClear={() => {}}>
        <BulkBarButton>Act</BulkBarButton>
      </SelectionActionBar>,
    );
    const bar = getByTestId('selection-action-bar');
    // Anchored to <main> at the bottom-center, like the task board's bulk bar.
    expect(bar.className).toContain('absolute');
    expect(bar.className).toContain('bottom-6');
    expect(bar.className).toContain('left-1/2');
  });

  it('is a high-contrast neutral surface (black in light / white in dark) so it stands out from the page', () => {
    const { getByTestId } = renderBar(
      <SelectionActionBar count={1} onClear={() => {}}>
        <BulkBarButton>Act</BulkBarButton>
      </SelectionActionBar>,
    );
    const bar = getByTestId('selection-action-bar');
    // bg flips with the theme: black in light, white in dark ...
    expect(bar.className).toContain('bg-black');
    expect(bar.className).toContain('dark:bg-white');
    // ... and contents use the inverse colour so they stay legible on it.
    expect(bar.className).toContain('text-white');
    expect(bar.className).toContain('dark:text-black');
    // The old same-as-page surface (low contrast) is gone.
    expect(bar.className).not.toContain('bg-[var(--bg-primary)]');
  });

  it('clicking the trailing × calls onClear exactly once', () => {
    const onClear = vi.fn();
    renderBar(
      <SelectionActionBar count={2} onClear={onClear}>
        <BulkBarButton>Act</BulkBarButton>
      </SelectionActionBar>,
    );
    fireEvent.click(screen.getByTestId('selection-action-bar-clear'));
    expect(onClear).toHaveBeenCalledTimes(1);
  });

  it('the clear button is labelled for a11y (common.clearSelection)', () => {
    renderBar(
      <SelectionActionBar count={1} onClear={() => {}}>
        <BulkBarButton>Act</BulkBarButton>
      </SelectionActionBar>,
    );
    expect(screen.getByRole('button', { name: 'Clear selection' })).toBeInTheDocument();
  });

  it('folds onto a second line instead of hanging off both edges of a phone', () => {
    // The bar is centred with `-translate-x-1/2` and holds five to seven
    // labelled buttons: on a 410px screen it used to overflow BOTH sides at
    // once, so the actions at each end could not be reached at all.
    const { getByTestId } = renderBar(
      <SelectionActionBar count={3} onClear={() => {}}>
        <BulkBarButton>Duplicate</BulkBarButton>
        <BulkBarButton>Move to folder</BulkBarButton>
        <BulkBarButton>Delete</BulkBarButton>
      </SelectionActionBar>,
    );

    const bar = getByTestId('selection-action-bar');
    expect(bar.className).toContain('flex-wrap');
    // Against its CONTAINER, not the window: the bar is absolutely positioned
    // inside the content area, which is narrower than the viewport whenever the
    // sidebar is open, so a 100vw cap would not have stopped it overflowing.
    expect(bar.className).toContain('max-w-[calc(100%-1rem)]');
  });

  it('honours a custom testId', () => {
    const { getByTestId } = renderBar(
      <SelectionActionBar count={1} onClear={() => {}} testId="my-bar">
        <BulkBarButton>Act</BulkBarButton>
      </SelectionActionBar>,
    );
    expect(getByTestId('my-bar')).toBeInTheDocument();
  });
});

describe('BulkBarButton', () => {
  it('applies the danger class for variant="danger" and the neutral class by default', () => {
    render(
      <>
        <BulkBarButton>Neutral</BulkBarButton>
        <BulkBarButton variant="danger">Danger</BulkBarButton>
      </>,
    );
    // Inverted-surface bar: neutral buttons take the inverse colour (white in light / black in dark), danger stays red.
    expect(screen.getByRole('button', { name: 'Neutral' }).className).toContain('text-white/80');
    expect(screen.getByRole('button', { name: 'Danger' }).className).toContain('text-red-400');
  });

  it('forwards onClick and disabled', () => {
    const onClick = vi.fn();
    render(<BulkBarButton disabled onClick={onClick}>X</BulkBarButton>);
    const btn = screen.getByRole('button', { name: 'X' });
    expect(btn).toBeDisabled();
    fireEvent.click(btn);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('is type="button" so it never submits an enclosing form', () => {
    render(<BulkBarButton>X</BulkBarButton>);
    expect(screen.getByRole('button', { name: 'X' })).toHaveAttribute('type', 'button');
  });
});

describe('bulkBarButtonClass', () => {
  it('returns neutral styling by default and danger styling on request', () => {
    expect(bulkBarButtonClass()).toContain('text-white/80');
    expect(bulkBarButtonClass('danger')).toContain('text-red-400');
    // Both share the pill base (height + rounded + text-xs) from the task board bar.
    expect(bulkBarButtonClass()).toContain('h-7');
    expect(bulkBarButtonClass('danger')).toContain('rounded-md');
  });
});
