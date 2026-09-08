// @vitest-environment node
/**
 * The sidebar's navigation is drawn from ONE list, in ONE component.
 *
 * There used to be two hand-written copies of the list - `chatNavItems` in the
 * shell and eight copy-pasted row blocks in the conversation sidebar - so adding
 * a page to one and not the other drifted silently, with nothing red. Then there
 * was one list read by two COMPONENTS, which stopped the pages drifting but not
 * the answers: each decided for itself which entry was active. Now there is one
 * component, {@link ../SidebarNavigation}, and these are the structural guards
 * around that:
 *
 *  - no surface may name a customizable page's path itself, and
 *  - no surface but that component may draw a navigation entry.
 *
 * What each SHAPE actually renders is asserted against rendered components in
 * SidebarNavigation.test.tsx, and against the live app in
 * e2e/ce/ce-sidebar-one-navigation.spec.ts. Asserting it here would only pin
 * file contents.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { SIDEBAR_NAV_ITEMS } from '@/lib/sidebar/navItems';

/** The surfaces that hold the sidebar's two shapes but must not draw its entries. */
const SHELLS = {
  'AppSidebar.tsx (the shell holding the collapsed rail)': join(__dirname, '../AppSidebar.tsx'),
  'ConversationSidebar.tsx (the panel holding the expanded rows)': join(__dirname, '../../chat/ConversationSidebar.tsx'),
};

/** The one component allowed to draw them. */
const NAVIGATION = join(__dirname, '../SidebarNavigation.tsx');

describe('sidebar navigation - one list, one component', () => {
  Object.entries(SHELLS).forEach(([label, path]) => {
    it(`${label} does not hardcode a customizable page's path`, () => {
      const source = readFileSync(path, 'utf8');

      const hardcoded = SIDEBAR_NAV_ITEMS.filter((item) => source.includes(`'${item.path}'`)).map((i) => i.path);

      expect(hardcoded).toEqual([]);
    });

    it(`${label} does not draw a navigation entry of its own`, () => {
      // The row and icon markup is defined once, in SidebarNavigation. A shell
      // that starts spelling `sidebarNavRowClass` or mounting `NavIconButton`
      // again is the beginning of the second implementation this removed - and
      // it would pass every rendered test, because it would look identical on
      // the day it was written.
      const source = readFileSync(path, 'utf8');

      expect(source).not.toContain('sidebarNavRowClass');
      expect(source).not.toContain('<NavIconButton');
    });
  });

  it('the navigation component is the one that renders the shared row styles', () => {
    // The guard above is only worth something while SOMETHING renders them; a
    // rename that left every file clean would otherwise pass silently.
    const source = readFileSync(NAVIGATION, 'utf8');

    expect(source).toContain('sidebarNavRowClass(');
    expect(source).toContain('SIDEBAR_NAV_ROW_ICON_CLASS');
    expect(source).toContain('NavIconButton');
  });

  it('every entry has a distinct id, path and view - the rail keys on the path', () => {
    const ids = SIDEBAR_NAV_ITEMS.map((item) => item.id);
    const paths = SIDEBAR_NAV_ITEMS.map((item) => item.path);
    const views = SIDEBAR_NAV_ITEMS.map((item) => item.view);

    expect(new Set(ids).size).toBe(ids.length);
    expect(new Set(paths).size).toBe(paths.length);
    expect(new Set(views).size).toBe(views.length);
  });
});
