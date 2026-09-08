// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { CopyButton } from '../shared/CopyButton';

function stubClipboard(writeText: () => Promise<void>) {
  Object.assign(navigator, { clipboard: { writeText: vi.fn(writeText) } });
  return navigator.clipboard.writeText as ReturnType<typeof vi.fn>;
}

describe('CopyButton', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('copies a string verbatim and an object as pretty JSON', async () => {
    const writeText = stubClipboard(async () => {});
    const { rerender } = render(<CopyButton value="https://example.com" />);
    fireEvent.click(screen.getByRole('button'));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('https://example.com'));

    rerender(<CopyButton value={{ a: 1 }} />);
    fireEvent.click(screen.getByRole('button'));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('{\n  "a": 1\n}'));
  });

  it('confirms the copy, then goes back to offering one', async () => {
    stubClipboard(async () => {});
    render(<CopyButton value="x" />);
    const button = screen.getByRole('button');
    expect(button.getAttribute('title')).toBe('copyValue');

    fireEvent.click(button);
    await waitFor(() => expect(button.getAttribute('title')).toBe('copied'));

    act(() => {
      vi.advanceTimersByTime(1600);
    });
    await waitFor(() => expect(button.getAttribute('title')).toBe('copyValue'));
  });

  it('stays silent when the clipboard refuses, instead of throwing into the tree', async () => {
    stubClipboard(async () => {
      throw new Error('NotAllowedError');
    });
    render(<CopyButton value="x" />);
    const button = screen.getByRole('button');

    expect(() => fireEvent.click(button)).not.toThrow();
    // No confirmation: nothing was copied.
    await waitFor(() => expect(button.getAttribute('title')).toBe('copyValue'));
  });

  it('does not let the click reach the row it sits on', () => {
    // The tree rows toggle expansion on click; copying must not also open the node.
    stubClipboard(async () => {});
    const onRowClick = vi.fn();
    render(
      <div onClick={onRowClick}>
        <CopyButton value="x" />
      </div>,
    );
    fireEvent.click(screen.getByRole('button'));
    expect(onRowClick).not.toHaveBeenCalled();
  });

  it('clears its timer on unmount rather than setting state on a dead component', async () => {
    stubClipboard(async () => {});
    const { unmount } = render(<CopyButton value="x" />);
    fireEvent.click(screen.getByRole('button'));
    await waitFor(() => expect(screen.getByRole('button').getAttribute('title')).toBe('copied'));

    const errors: unknown[] = [];
    const originalError = console.error;
    console.error = (...args: unknown[]) => errors.push(args);
    unmount();
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    console.error = originalError;
    expect(errors).toEqual([]);
  });

  it('uses the caller\'s label when one is given, for a copy that is not "this value"', () => {
    stubClipboard(async () => {});
    render(<CopyButton value="x" title="copyAll" />);
    expect(screen.getByRole('button').getAttribute('title')).toBe('copyAll');
    expect(screen.getByRole('button').getAttribute('aria-label')).toBe('copyAll');
  });
});
