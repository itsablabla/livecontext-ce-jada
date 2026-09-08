// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ToggleGroup } from '../toggle-group';

/**
 * The control is a radio group by nature: exactly one option is selected and
 * picking another replaces it. Announcing that needs two roles working together
 * (`radiogroup` around `radio`) plus a name for the group, and a `radio` with
 * no owning group is an ARIA violation an audit tool flags.
 *
 * <p>So the roles are opt-in, keyed on the caller supplying the name. That
 * decision is the subject here: a caller that names the group must get the full
 * semantics, and the roughly thirty callers that do not must render EXACTLY as
 * they did before, since none of them asked for anything.
 */
const OPTIONS = [
  { value: 'user', label: 'My credential' },
  { value: 'platform', label: 'Platform' },
];

afterEach(cleanup);

describe('ToggleGroup', () => {
  /**
   * Hover on the PILL variant.
   *
   * <p>The app's `bg-theme-*` / `text-theme-*` classes are hand-written CSS in
   * `@layer components` (globals.css), so Tailwind v4 does not own them: it emits
   * `.bg-theme-primary`, but never a variant of it and never an opacity modifier. The pill's
   * inactive state asked for `hover:text-theme-primary hover:bg-theme-primary/10`, so NEITHER
   * reached the stylesheet and the control was inert under the pointer - which is how the
   * chat/studio switch above the composer came to have no hover at all. Compiling globals.css
   * with the repo's own Tailwind confirms both readings: `hover:bg-theme-primary/10` is absent
   * from the output, `hover:bg-[var(--bg-secondary)]` is present.
   *
   * <p>The assertions are on class NAMES rather than on computed colours because that is where
   * the defect lives: jsdom applies no stylesheet, so a computed-style assertion would read the
   * same empty value before and after the fix and could never fail. The rule being pinned is
   * "hover is written in a form Tailwind generates", and only the shape of the class carries it.
   */
  const themeVariantClass = /(?:hover|focus|focus-visible|active|group-hover|disabled|dark):(?:bg|text|border|placeholder)-theme-/;

  it('gives the pill variant a hover ground that Tailwind actually generates', () => {
    render(
      <ToggleGroup variant="pill" value="user" onValueChange={() => {}} options={OPTIONS} />,
    );

    const inactive = screen.getByText('Platform').closest('button')!;

    // An arbitrary value: the one form a variant can be prefixed to here.
    expect(inactive.className).toContain('hover:bg-[var(--bg-secondary)]');
    expect(inactive.className).toContain('hover:text-[var(--text-primary)]');
  });

  it('never writes the pill hover as a variant of a hand-written theme class', () => {
    // The regression proper. Rewriting the hover as `hover:bg-theme-secondary` looks tidier and
    // matches the rest of the file, and it silently produces no CSS: the reviewer sees a hover
    // in the source, the reader gets none, and nothing fails.
    render(
      <ToggleGroup variant="pill" value="user" onValueChange={() => {}} options={OPTIONS} />,
    );

    const inactive = screen.getByText('Platform').closest('button')!;

    expect(inactive.className).not.toMatch(themeVariantClass);
  });

  it('leaves the SELECTED pill option without a hover ground', () => {
    // Clicking the option already in force does nothing (onValueChange early-returns on the
    // current value), so a ground lighting up under the pointer would advertise an action that
    // cannot happen. This is a decision, so it is pinned rather than left to the next reader.
    const onValueChange = vi.fn();
    render(
      <ToggleGroup variant="pill" value="user" onValueChange={onValueChange} options={OPTIONS} />,
    );

    const active = screen.getByText('My credential').closest('button')!;

    expect(active.className).not.toContain('hover:bg-');
    fireEvent.click(active);
    expect(onValueChange).toHaveBeenCalledWith('user');
  });

  it('keeps the grid variant hover in the generatable form it already used', () => {
    // The grid variant never had the bug: it wrote its hover as an arbitrary value from the
    // start. Harmonising it onto the `*-theme-*` spelling of its neighbours would break it in
    // exactly the way the pill was broken, with no visible diff in review.
    render(
      <ToggleGroup variant="grid" value="user" onValueChange={() => {}} options={OPTIONS} />,
    );

    const inactive = screen.getByText('Platform').closest('button')!;

    expect(inactive.className).toContain('hover:text-[var(--text-primary)]');
    expect(inactive.className).not.toMatch(themeVariantClass);
  });

  it('announces itself as a named radio group when a caller names it', () => {
    render(
      <ToggleGroup ariaLabel="Credential source" value="user" onValueChange={() => {}} options={OPTIONS} />,
    );

    const group = screen.getByRole('radiogroup', { name: 'Credential source' });
    expect(group).toBeInTheDocument();
    // Which one is in force is the whole meaning of the control on a screen
    // that decides who pays for a purchase.
    expect(screen.getByRole('radio', { name: 'My credential' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: 'Platform' })).toHaveAttribute('aria-checked', 'false');
  });

  it('leaves an unnamed group exactly as it was: no roles, no checked state', () => {
    // The other callers in the app pass no label. Emitting a radiogroup with no
    // name would announce an anonymous group, and emitting radios without the
    // group would be invalid ARIA. Both are worse than plain buttons.
    render(<ToggleGroup value="user" onValueChange={() => {}} options={OPTIONS} />);

    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument();
    expect(screen.queryAllByRole('radio')).toHaveLength(0);
    expect(screen.getByText('Platform').closest('button')).not.toHaveAttribute('aria-checked');
  });

  it('reports the value picked, named or not', () => {
    const onValueChange = vi.fn();
    render(
      <ToggleGroup ariaLabel="Credential source" value="user" onValueChange={onValueChange} options={OPTIONS} />,
    );

    fireEvent.click(screen.getByText('Platform'));

    expect(onValueChange).toHaveBeenCalledWith('platform');
  });

  it('does not report anything while disabled', () => {
    const onValueChange = vi.fn();
    render(
      <ToggleGroup
        ariaLabel="Credential source"
        value="user"
        onValueChange={onValueChange}
        options={OPTIONS}
        disabled
      />,
    );

    fireEvent.click(screen.getByText('Platform'));

    expect(onValueChange).not.toHaveBeenCalled();
  });
});
