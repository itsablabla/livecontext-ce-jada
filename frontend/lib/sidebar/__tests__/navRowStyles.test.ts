import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  SIDEBAR_NAV_ROW_ICON_CLASS,
  SIDEBAR_NAV_ROW_LABEL_CLASS,
  sidebarNavRowClass,
} from '@/lib/sidebar/navRowStyles';

/**
 * One class list, read by every row in the sidebar's navigation block.
 *
 * The tests that render the rows can only prove they all use THIS list; what
 * the list itself must contain has to be pinned here, or the properties it was
 * created to guarantee can be deleted with the whole suite still green.
 */

const BUTTON_SOURCE = readFileSync(
  join(__dirname, '../../../components/ui/button.tsx'),
  'utf8'
);

describe('the shared sidebar row', () => {
  it('leaves exactly 8px between the icon and the label', () => {
    // The bug: the overflow row was a <Button>, whose base adds `gap-2` on top
    // of this `mr-2`, so its label sat 16px from the icon while every other
    // label sat at 8px. The row must space itself once, and only once.
    expect(SIDEBAR_NAV_ROW_ICON_CLASS.split(/\s+/)).toContain('mr-2');
    expect(sidebarNavRowClass(false).split(/\s+/)).not.toContain('gap-2');
  });

  it('keeps the icon at its full 16px however long the label is', () => {
    // Without `shrink-0` the icon is a shrinkable flex item, so the locale with
    // the longest labels is exactly the one where the block's icons stop lining
    // up - the opposite of what this module exists for.
    expect(SIDEBAR_NAV_ROW_ICON_CLASS.split(/\s+/)).toContain('shrink-0');
  });

  it('shows keyboard focus, which nothing in the block used to do', () => {
    const classes = sidebarNavRowClass(false).split(/\s+/);
    expect(classes).toContain('focus-visible:ring-2');
    expect(classes).toContain('focus-visible:outline-none');
  });

  it('keeps every row on one line, and ellipsises rather than slicing mid-word', () => {
    // Both halves, on the LABEL, which is where this has to live: the label is
    // the only text in a row, and `truncate` already declares
    // `white-space: nowrap` on the element that matters, so the same class on
    // the ROW would be inert while reading as load-bearing. `truncate` gives
    // the nowrap and the ellipsis; `min-w-0` is what lets the flex item shrink
    // at all, and without it the ellipsis never fires and the sidebar's
    // `overflow-hidden` cuts the word off with nothing to say it did.
    const label = SIDEBAR_NAV_ROW_LABEL_CLASS.split(/\s+/);
    expect(label).toContain('truncate');
    expect(label).toContain('min-w-0');
    expect(
      sidebarNavRowClass(false).split(/\s+/),
      'the row must not restate what the label owns'
    ).not.toContain('whitespace-nowrap');
  });

  it('draws the app\'s own focus ring, so focus reads the same here as anywhere', () => {
    // The RING is read out of button.tsx rather than copied: the overflow row
    // inherited it from <Button> before this module existed, so a row wearing a
    // ring of its own invention is still a regression, just a subtler one.
    const ring = BUTTON_SOURCE.match(/focus-visible:ring-\[[^\]]+\]/)?.[0];
    const width = BUTTON_SOURCE.match(/focus-visible:ring-offset-\d+/)?.[0];
    expect(ring, 'button.tsx no longer declares a ring colour').toBeTruthy();
    expect(width, 'button.tsx no longer declares a ring-offset width').toBeTruthy();

    const classes = sidebarNavRowClass(false).split(/\s+/);
    expect(classes).toContain(ring);
    expect(classes).toContain(width);
  });

  it('offsets that ring in the colour of the SIDEBAR, not the colour a button sits on', () => {
    // `ring-offset` paints a 1px band of its colour between the row and the
    // ring, so it has to be whatever the row sits on. These rows sit on the
    // sidebar (`bg-theme-secondary`). Copying button.tsx's offset wholesale -
    // `--bg-primary`, what a button sits on out in the page - drew a white
    // hairline halo on a grey panel in light theme and a near-black one on dark
    // grey in dark theme. This assertion exists to REJECT that copy, so it is
    // deliberately NOT read out of button.tsx the way the ring above is: a
    // guard that tracked the button file would reject the correct colour.
    const classes = sidebarNavRowClass(false).split(/\s+/);
    expect(classes).toContain('focus-visible:ring-offset-[var(--bg-secondary)]');
    expect(classes).not.toContain('focus-visible:ring-offset-[var(--bg-primary)]');
  });

  it('paints the hover surface at rest and keeps it while active', () => {
    expect(sidebarNavRowClass(false)).toContain('hover:bg-surface-hover');
    expect(sidebarNavRowClass(true).split(/\s+/)).toContain('bg-surface-hover');
    // Not both: an active row that also carried `bg-transparent` would depend
    // on class order to paint at all.
    expect(sidebarNavRowClass(true).split(/\s+/)).not.toContain('bg-transparent');
  });

  it('gives the icon and label the group they hang off', () => {
    // Both react with `group-hover:` / `group-[.bg-surface-hover]:`, which are
    // inert without `group` on the row - and inert silently.
    expect(sidebarNavRowClass(false).split(/\s+/)).toContain('group');
    expect(SIDEBAR_NAV_ROW_ICON_CLASS).toContain('group-hover:');
    expect(SIDEBAR_NAV_ROW_LABEL_CLASS).toContain('group-hover:');
  });
});
