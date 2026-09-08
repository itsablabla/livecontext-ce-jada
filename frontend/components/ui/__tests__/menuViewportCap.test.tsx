// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import * as React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { Popover, PopoverContent, PopoverTrigger } from '../popover';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../tooltip';
import { Select, SelectContent, SelectItem, SelectTrigger } from '../select';

/**
 * The three portalled surfaces every menu in the app is built from, and the one
 * promise they now make: never wider than the space the viewport leaves.
 *
 * Regression (2026-09-05): the sidebar "More" menu opened `side="right"` from a
 * 256px drawer on a 410px phone, 288px of content starting at x=240, 118px of it
 * off screen. Radix only flips to the opposite side and shifts along the
 * ALIGNMENT axis, so nothing in the library was going to save it - the width cap
 * is the fix.
 *
 * The cap has to LEAD the class list, and that is the second half of what is
 * asserted here: a call site that states its own `max-w-*` (26 tooltips do, to
 * make their text wrap at a readable measure) must keep deciding its own width.
 * Written as an inline style, the cap would have silently killed all 26.
 */
afterEach(cleanup);

const POPPER_CAP = 'max-w-[var(--radix-popper-available-width,calc(100vw-1rem))]';
const SELECT_CAP = 'max-w-[var(--radix-select-content-available-width,calc(100vw-1rem))]';

function renderPopover(className?: string) {
  render(
    <Popover open>
      <PopoverTrigger>open</PopoverTrigger>
      <PopoverContent data-testid="content" className={className}>body</PopoverContent>
    </Popover>,
  );
  return screen.getByTestId('content');
}

function renderTooltip(className?: string) {
  render(
    <TooltipProvider>
      <Tooltip open>
        <TooltipTrigger>hover</TooltipTrigger>
        <TooltipContent data-testid="tip" className={className}>explanation</TooltipContent>
      </Tooltip>
    </TooltipProvider>,
  );
  return screen.getAllByTestId('tip')[0];
}

function renderSelect(className?: string) {
  render(
    <Select open value="a" onValueChange={() => {}}>
      <SelectTrigger>trigger</SelectTrigger>
      <SelectContent data-testid="list" className={className}>
        <SelectItem value="a">A</SelectItem>
      </SelectContent>
    </Select>,
  );
  return screen.getByTestId('list');
}

describe('PopoverContent viewport cap', () => {
  it('caps its width at what the viewport leaves beside the trigger', () => {
    expect(renderPopover().className).toContain(POPPER_CAP);
  });

  it('caps a call site that only sets a WIDTH, since the two are different properties', () => {
    const el = renderPopover('w-72');
    expect(el.className).toContain('w-72');
    expect(el.className).toContain(POPPER_CAP);
  });

  it('stands aside for a call site that states its own max-width', () => {
    const el = renderPopover('max-w-xs');
    expect(el.className).toContain('max-w-xs');
    expect(el.className).not.toContain(POPPER_CAP);
  });
});

describe('TooltipContent viewport cap', () => {
  it('caps a tooltip that would otherwise run off the edge', () => {
    expect(renderTooltip().className).toContain(POPPER_CAP);
  });

  it('leaves the wrapping measure to the tooltips that chose one', () => {
    // These exist: 23x max-w-xs (agent help), ScopeStatusIndicator max-w-md,
    // FeatureLabel max-w-[16rem]. Overriding them turns a wrapped paragraph into
    // one long strip, because Radix sets min-width:max-content on the wrapper.
    const el = renderTooltip('max-w-xs');
    expect(el.className).toContain('max-w-xs');
    expect(el.className).not.toContain(POPPER_CAP);
  });
});

describe('SelectContent viewport cap', () => {
  it('caps the list in Select\'s own re-namespaced variable, not the popper one', () => {
    // Radix Select re-exports --radix-popper-available-width under its own name;
    // the popper spelling resolves to nothing here and would cap nothing.
    const el = renderSelect();
    expect(el.className).toContain(SELECT_CAP);
    expect(el.className).not.toContain(POPPER_CAP);
  });

  it('stands aside for a call site that states its own max-width', () => {
    expect(renderSelect('max-w-sm').className).not.toContain(SELECT_CAP);
  });
});
