// @vitest-environment jsdom
/**
 * The keycap primitive. Small, but it is what keeps the app's three shortcut
 * hints looking like one thing, so the two properties worth pinning are that it
 * renders a real <kbd> and that a call site can still step its background.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { Kbd } from '../kbd';

afterEach(cleanup);

describe('Kbd', () => {
  it('renders the semantic element, not a styled span', () => {
    render(<Kbd data-testid="cap">Ctrl K</Kbd>);

    expect(screen.getByTestId('cap').tagName).toBe('KBD');
  });

  it('carries the shared keycap styling', () => {
    render(<Kbd data-testid="cap">Ctrl K</Kbd>);

    const classes = screen.getByTestId('cap').className.split(/\s+/);
    expect(classes).toContain('border');
    expect(classes).toContain('bg-[var(--bg-secondary)]');
    // Never a click target: it labels a shortcut, it does not fire one.
    expect(classes).toContain('pointer-events-none');
  });

  it('lets a call site override the background rather than duplicate the rest', () => {
    // The home quick-open button needs this: it IS a --bg-secondary surface, so
    // the default keycap would disappear into it.
    render(<Kbd data-testid="cap" className="bg-[var(--bg-primary)]">Ctrl K</Kbd>);

    const classes = screen.getByTestId('cap').className.split(/\s+/);
    expect(classes).toContain('bg-[var(--bg-primary)]');
    expect(classes).not.toContain('bg-[var(--bg-secondary)]');
    expect(classes).toContain('border');
  });
});
