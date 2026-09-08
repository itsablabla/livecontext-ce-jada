// @vitest-environment node
/**
 * What the collapsed rail lists.
 *
 * The rail is the sidebar most people spend their time in, and it used to keep
 * its own hand-written copy of the pages - so it could list a page the expanded
 * panel did not, with nothing red. Its content is computed here, next to the
 * list itself, so it can be asserted without mounting the whole app shell.
 */
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_HIDDEN_NAV_IDS,
  MARKETPLACE_NAV_ITEM,
  SIDEBAR_NAV_ITEMS,
  railNavItems,
} from '@/lib/sidebar/navItems';

const defaultVisible = SIDEBAR_NAV_ITEMS.filter((item) => !DEFAULT_HIDDEN_NAV_IDS.includes(item.id));

describe('collapsed rail contents', () => {
  it('opens with Marketplace, which no one can hide', () => {
    expect(railNavItems(defaultVisible)[0]).toBe(MARKETPLACE_NAV_ITEM);
  });

  it('keeps Marketplace even when every hideable page is hidden', () => {
    expect(railNavItems([]).map((item) => item.path)).toEqual([MARKETPLACE_NAV_ITEM.path]);
  });

  it('lists the kept pages after it, in the panel’s order', () => {
    expect(railNavItems(defaultVisible).map((item) => item.titleKey)).toEqual([
      'marketplace',
      'agenda',
      'agents',
      'workflows',
      'tables',
      'files',
    ]);
  });

  it('leaves the three overflow pages off the rail, as it does off the panel', () => {
    const titleKeys = railNavItems(defaultVisible).map((item) => item.titleKey);

    expect(titleKeys).not.toContain('board');
    expect(titleKeys).not.toContain('applications');
    expect(titleKeys).not.toContain('interfaces');
  });

  it('never repeats an entry, so the rail cannot draw the same icon twice', () => {
    const paths = railNavItems(SIDEBAR_NAV_ITEMS).map((item) => item.path);

    expect(new Set(paths).size).toBe(paths.length);
  });
});
