/**
 * @vitest-environment jsdom
 *
 * The single stop / cancel / reactivate control.
 *
 * It was extracted out of `RunSummaryBar` so that every surface showing a live
 * run can carry the SAME affordance; these tests pin the contract the surfaces
 * rely on - which status offers which action, that a hard cancel still asks
 * first, and that a click in flight cannot be fired twice.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

// The REAL terminal set on purpose. A hand-written stand-in would let this suite
// certify the author's guess about which statuses are terminal - the exact way a
// derived value gets tested against fiction.
import { TERMINAL_STATUSES } from '@/contexts/workflow-run/RunStateStore';
import { RunActionButton, resolveRunAction } from '../RunActionButton';

afterEach(cleanup);

describe('resolveRunAction', () => {
  it('offers a graceful stop while the run is executing', () => {
    expect(resolveRunAction('RUNNING')).toBe('stop');
    expect(resolveRunAction('PAUSED')).toBe('stop');
    // Lowercase reaches this from the run manager's own state, not only the API.
    expect(resolveRunAction('running')).toBe('stop');
  });

  it('offers the hard cancel only to a run parked on its triggers', () => {
    expect(resolveRunAction('WAITING_TRIGGER')).toBe('cancel');
  });

  it('offers reactivation to EVERY terminal run, which no trigger can wake', () => {
    // Driven off the real set, so a status added to it later is covered here the
    // day it is added rather than the day someone remembers this file.
    expect(TERMINAL_STATUSES.size).toBeGreaterThan(0);
    for (const status of TERMINAL_STATUSES) {
      expect(resolveRunAction(status.toUpperCase())).toBe('reactivate');
    }
  });

  it('offers nothing without a status, so a surface paints no chrome around it', () => {
    expect(resolveRunAction(undefined)).toBeNull();
    expect(resolveRunAction(null)).toBeNull();
    expect(resolveRunAction('')).toBeNull();
  });
});

describe('RunActionButton', () => {
  it('stops the run in one click - the running case has no confirmation', () => {
    const onStop = vi.fn();
    render(<RunActionButton status="RUNNING" onStop={onStop} />);

    fireEvent.click(screen.getByRole('button'));

    expect(onStop).toHaveBeenCalledTimes(1);
    expect(document.querySelector('[data-run-cancel-backdrop]')).toBeNull();
  });

  it('asks before the hard cancel, and only cancels once confirmed', () => {
    const onCancel = vi.fn();
    render(<RunActionButton status="WAITING_TRIGGER" onCancel={onCancel} />);

    fireEvent.click(screen.getByRole('button'));
    expect(onCancel).not.toHaveBeenCalled();
    expect(document.querySelector('[data-run-cancel-backdrop]')).not.toBeNull();

    fireEvent.click(screen.getByText('workflow.cancelRun.confirm'));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('warns that cancelling takes the PINNED version down with the run', () => {
    render(<RunActionButton status="WAITING_TRIGGER" onCancel={vi.fn()} pinnedVersion={4} />);
    fireEvent.click(screen.getByRole('button'));
    expect(screen.queryByText('workflow.cancelRun.warning')).not.toBeNull();
  });

  it('does not warn about a pin when the run carries none', () => {
    render(<RunActionButton status="WAITING_TRIGGER" onCancel={vi.fn()} />);
    fireEvent.click(screen.getByRole('button'));
    expect(screen.queryByText('workflow.cancelRun.warning')).toBeNull();
  });

  it('renders nothing when the surface offers no handler for the status', () => {
    // A read-only surface passes no handlers: the control must disappear rather
    // than render a button that cannot act.
    const { container } = render(<RunActionButton status="RUNNING" />);
    expect(container.querySelector('button')).toBeNull();
  });

  it('renders nothing for a status that offers no action at all', () => {
    const { container } = render(<RunActionButton status="" onStop={vi.fn()} />);
    expect(container.querySelector('button')).toBeNull();
  });

  it('refuses a second click while the first action is still in flight', () => {
    const onStop = vi.fn();
    render(<RunActionButton status="RUNNING" onStop={onStop} pendingAction="stop" />);

    const button = screen.getByRole('button');
    fireEvent.click(button);

    expect(onStop).not.toHaveBeenCalled();
    expect(button.getAttribute('aria-busy')).toBe('true');
  });

  it('is DISABLED while in flight, which is the ONE thing stopping a second click', () => {
    // There is no handler guard, deliberately: React reads `props.disabled`, so a
    // guard inside onClick would be unreachable code pretending to be a
    // safeguard. This attribute is therefore load-bearing on its own.
    render(<RunActionButton status="RUNNING" onStop={vi.fn()} pendingAction="stop" />);
    expect((screen.getByRole('button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('spins for the action ON SCREEN, never for other work in flight', () => {
    // A stop flips the run's status, so by the time it settles this control may
    // already offer `cancel`. A boolean `pending` left that new control spinning
    // for work nobody asked of it.
    render(<RunActionButton status="WAITING_TRIGGER" onCancel={vi.fn()} pendingAction="stop" />);
    const button = screen.getByRole('button') as HTMLButtonElement;

    expect(button.getAttribute('data-run-action')).toBe('cancel');
    expect(button.getAttribute('aria-busy')).toBeNull();
    expect(button.disabled).toBe(false);
  });

  it('sizes itself for the surface it is on', () => {
    const { rerender } = render(<RunActionButton status="RUNNING" onStop={vi.fn()} size="compact" />);
    const compact = screen.getByRole('button').className;

    rerender(<RunActionButton status="RUNNING" onStop={vi.fn()} size="panel" />);

    expect(screen.getByRole('button').className).not.toBe(compact);
  });

  it('says on the control that the attempt failed, without needing a mouse', () => {
    render(<RunActionButton status="RUNNING" onStop={vi.fn()} failed />);
    const button = screen.getByRole('button');
    // A tooltip alone leaves a touch user and a screen-reader user with nothing,
    // so the accessible NAME carries it too - through next-intl, never a raw API
    // message, because this string is user-facing.
    expect(button.getAttribute('title')).toContain('workflow.runAction.failed');
    expect(button.getAttribute('aria-label')).toContain('workflow.runAction.failed');
    expect(button.getAttribute('data-run-action-failed')).toBe('true');
  });

  it('swaps the glyph on failure, so the signal is not colour alone', () => {
    const { container, rerender } = render(<RunActionButton status="RUNNING" onStop={vi.fn()} />);
    const resting = container.querySelector('button svg')?.getAttribute('class') ?? '';

    rerender(<RunActionButton status="RUNNING" onStop={vi.fn()} failed />);

    const failed = container.querySelector('button svg')?.getAttribute('class') ?? '';
    expect(failed).not.toBe(resting);
  });

  it('puts focus INSIDE the confirmation, not on the page behind it', () => {
    render(<RunActionButton status="WAITING_TRIGGER" onCancel={vi.fn()} />);
    fireEvent.click(screen.getByRole('button'));

    const dialog = document.querySelector('[role="alertdialog"]');
    expect(document.activeElement).toBe(dialog);
  });

  it('returns focus to the control it was opened from', () => {
    render(<RunActionButton status="WAITING_TRIGGER" onCancel={vi.fn()} />);
    const opener = screen.getByRole('button');
    opener.focus();
    fireEvent.click(opener);

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(document.activeElement).toBe(opener);
  });

  it('cycles Tab within the confirmation instead of letting it escape', () => {
    render(<RunActionButton status="WAITING_TRIGGER" onCancel={vi.fn()} />);
    fireEvent.click(screen.getByRole('button'));
    const dialog = document.querySelector('[role="alertdialog"]') as HTMLElement;
    const focusable = dialog.querySelectorAll('button');
    const last = focusable[focusable.length - 1] as HTMLElement;
    last.focus();

    fireEvent.keyDown(document, { key: 'Tab' });

    expect(document.activeElement).toBe(focusable[0]);
  });

  it('looks different when it failed, not merely on hover', () => {
    const { rerender } = render(<RunActionButton status="RUNNING" onStop={vi.fn()} />);
    const restingClass = screen.getByRole('button').className;

    rerender(<RunActionButton status="RUNNING" onStop={vi.fn()} failed />);

    const failedButton = screen.getByRole('button');
    expect(failedButton.className).not.toBe(restingClass);
    expect(failedButton.className).toContain('ring-2');
  });

  it('carries no failure marker while nothing has failed', () => {
    render(<RunActionButton status="RUNNING" onStop={vi.fn()} />);
    const button = screen.getByRole('button');
    expect(button.getAttribute('data-run-action-failed')).toBeNull();
    expect(button.getAttribute('title')).not.toContain('workflow.runAction.failed');
    expect(button.getAttribute('aria-label')).not.toContain('workflow.runAction.failed');
  });

  it('renders the separator its host hands down, and only alongside a real action', () => {
    const separator = <span data-testid="sep" />;
    const { rerender } = render(
      <RunActionButton status="RUNNING" onStop={vi.fn()} separator={separator} />,
    );
    expect(screen.queryByTestId('sep')).not.toBeNull();

    rerender(<RunActionButton status="" onStop={vi.fn()} separator={separator} />);
    expect(screen.queryByTestId('sep')).toBeNull();
  });

  it('reactivates in one click, like the stop and unlike the hard cancel', () => {
    const onReactivate = vi.fn();
    render(<RunActionButton status="COMPLETED" onReactivate={onReactivate} />);

    fireEvent.click(screen.getByRole('button'));

    expect(onReactivate).toHaveBeenCalledTimes(1);
    expect(document.querySelector('[data-run-cancel-backdrop]')).toBeNull();
  });

  it('does not even open the confirmation while an action is in flight', () => {
    const onCancel = vi.fn();
    render(<RunActionButton status="WAITING_TRIGGER" onCancel={onCancel} pendingAction="cancel" />);

    fireEvent.click(screen.getByRole('button'));

    expect(document.querySelector('[data-run-cancel-backdrop]')).toBeNull();
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('lets the user back out of the hard cancel from the dialog', () => {
    const onCancel = vi.fn();
    render(<RunActionButton status="WAITING_TRIGGER" onCancel={onCancel} />);
    fireEvent.click(screen.getByRole('button'));

    fireEvent.click(screen.getByText('workflow.cancelRun.keep'));

    expect(onCancel).not.toHaveBeenCalled();
    expect(document.querySelector('[data-run-cancel-backdrop]')).toBeNull();
  });

  it('closes the dialog on Escape without cancelling the run', () => {
    const onCancel = vi.fn();
    render(<RunActionButton status="WAITING_TRIGGER" onCancel={onCancel} />);
    fireEvent.click(screen.getByRole('button'));

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(document.querySelector('[data-run-cancel-backdrop]')).toBeNull();
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('consumes the Escape, so nothing behind the dialog also reacts to it', () => {
    // Other document-level Escape handlers exist (a fullscreen application close,
    // for one). Dismissing a confirmation must not fire them too.
    const behind = vi.fn();
    document.addEventListener('keydown', behind);
    try {
      render(<RunActionButton status="WAITING_TRIGGER" onCancel={vi.fn()} />);
      fireEvent.click(screen.getByRole('button'));
      behind.mockClear();

      const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
      document.dispatchEvent(event);

      expect(event.defaultPrevented).toBe(true);
    } finally {
      document.removeEventListener('keydown', behind);
    }
  });

  it('gives each mounted copy its OWN dialog ids', () => {
    // The control now mounts up to four times at once. Duplicate ids in one
    // document make aria-labelledby resolve to whichever came first, so a screen
    // reader can announce another control's dialog.
    render(<RunActionButton status="WAITING_TRIGGER" onCancel={vi.fn()} />);
    render(<RunActionButton status="WAITING_TRIGGER" onCancel={vi.fn()} />);
    const [first, second] = screen.getAllByRole('button');
    fireEvent.click(first);
    fireEvent.click(second);

    const dialogs = document.querySelectorAll('[role="alertdialog"]');
    expect(dialogs).toHaveLength(2);
    const ids = Array.from(dialogs).map((d) => d.getAttribute('aria-labelledby'));
    expect(ids[0]).not.toBe(ids[1]);
    expect(ids.every(Boolean)).toBe(true);
  });

  it('dismisses the dialog on the backdrop without cancelling the run', () => {
    const onCancel = vi.fn();
    render(<RunActionButton status="WAITING_TRIGGER" onCancel={onCancel} />);
    fireEvent.click(screen.getByRole('button'));

    fireEvent.click(document.querySelector('[data-run-cancel-backdrop]')!);

    expect(onCancel).not.toHaveBeenCalled();
    expect(document.querySelector('[data-run-cancel-backdrop]')).toBeNull();
  });

  it('keeps the dismissing click off the bar behind it', () => {
    // The dialog is a portal, but React bubbles SYNTHETIC events through the
    // React tree: without stopPropagation the dismissing click also reached the
    // run bar hosting this control and opened the run panel behind the modal.
    const onCancel = vi.fn();
    const hostClick = vi.fn();
    render(
      <div onClick={hostClick}>
        <RunActionButton status="WAITING_TRIGGER" onCancel={onCancel} />
      </div>,
    );
    fireEvent.click(screen.getByRole('button'));
    hostClick.mockClear();

    fireEvent.click(document.querySelector('[data-run-cancel-backdrop]')!);

    expect(hostClick).not.toHaveBeenCalled();
  });

  it('keeps the opening click off the bar behind it too', () => {
    const hostClick = vi.fn();
    render(
      <div onClick={hostClick}>
        <RunActionButton status="RUNNING" onStop={vi.fn()} />
      </div>,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(hostClick).not.toHaveBeenCalled();
  });

  it('tags itself with the action it performs, for the surfaces that key on it', () => {
    render(<RunActionButton status="RUNNING" onStop={vi.fn()} />);
    expect(screen.getByRole('button').getAttribute('data-run-action')).toBe('stop');
  });
});
