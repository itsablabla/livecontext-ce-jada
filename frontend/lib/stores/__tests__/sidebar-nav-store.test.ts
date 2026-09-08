// @vitest-environment jsdom
/**
 * The sidebar's navigation preferences: which pages are drawn, where the home
 * page's quick-open shortcut points, and the floor that keeps the sidebar
 * navigable.
 *
 * The floor is the part worth pinning hardest: emptying the block would take
 * away the customize menu itself, which lives in it - the user would have no
 * way back short of clearing their site data.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import {
  DEFAULT_HIDDEN_NAV_IDS,
  MIN_VISIBLE_NAV_ITEMS,
  SIDEBAR_NAV_ITEMS,
  type SidebarNavId,
} from '@/lib/sidebar/navItems';
import { useSidebarNavStore } from '@/lib/stores/sidebar-nav-store';

const STORAGE_KEY = 'lc.sidebar.nav.v1';

function visibleIds(): SidebarNavId[] {
  const hidden = useSidebarNavStore.getState().hiddenNavIds;
  return SIDEBAR_NAV_ITEMS.filter((item) => !hidden.includes(item.id)).map((item) => item.id);
}

function seedStorage(payload: unknown): void {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ state: payload, version: 0 }));
}

beforeEach(() => {
  window.localStorage.clear();
  useSidebarNavStore.setState({
    hiddenNavIds: [...DEFAULT_HIDDEN_NAV_IDS],
    quickOpenNavId: 'agenda',
  });
});

describe('sidebar nav preferences - defaults', () => {
  it('keeps Board, Applications and Interfaces out of the sidebar until asked for', () => {
    // Out of the sidebar, not out of reach: these three are the overflow
    // menu's opening list.
    expect(useSidebarNavStore.getState().hiddenNavIds).toEqual(['board', 'applications', 'interfaces']);
    expect(visibleIds()).not.toContain('board');
    expect(visibleIds()).not.toContain('applications');
    expect(visibleIds()).not.toContain('interfaces');
  });

  it('leaves every other page in the sidebar', () => {
    expect(visibleIds()).toEqual(['agenda', 'agents', 'workflows', 'tables', 'files']);
  });

  it('points the home quick-open shortcut at the agenda', () => {
    expect(useSidebarNavStore.getState().quickOpenNavId).toBe('agenda');
  });
});

describe('sidebar nav preferences - toggling', () => {
  it('shows a page that was hidden', () => {
    useSidebarNavStore.getState().toggleNavItem('board');

    expect(visibleIds()).toContain('board');
  });

  it('hides a page that was shown', () => {
    useSidebarNavStore.getState().toggleNavItem('files');

    expect(visibleIds()).not.toContain('files');
  });

  it('refuses to hide the last page above the floor', () => {
    // Six visible by default; hide down to exactly the floor.
    const toHide: SidebarNavId[] = ['agenda', 'agents'];
    toHide.forEach((id) => useSidebarNavStore.getState().toggleNavItem(id));
    expect(visibleIds()).toHaveLength(MIN_VISIBLE_NAV_ITEMS);

    useSidebarNavStore.getState().toggleNavItem('files');

    expect(visibleIds()).toHaveLength(MIN_VISIBLE_NAV_ITEMS);
    expect(visibleIds()).toContain('files');
  });

  it('lets a page be shown again once at the floor, so the choice is never a dead end', () => {
    (['agenda', 'agents'] as SidebarNavId[]).forEach((id) => useSidebarNavStore.getState().toggleNavItem(id));
    expect(visibleIds()).toHaveLength(MIN_VISIBLE_NAV_ITEMS);

    useSidebarNavStore.getState().toggleNavItem('agenda');

    expect(visibleIds()).toContain('agenda');
    expect(visibleIds().length).toBeGreaterThan(MIN_VISIBLE_NAV_ITEMS);
  });

  it('keeps the quick-open target when its page is hidden - the shortcut is a second way in', () => {
    useSidebarNavStore.getState().toggleNavItem('agenda');

    expect(visibleIds()).not.toContain('agenda');
    expect(useSidebarNavStore.getState().quickOpenNavId).toBe('agenda');
  });
});

describe('sidebar nav preferences - quick open', () => {
  it('remembers the page the user picked', () => {
    useSidebarNavStore.getState().setQuickOpenNavId('workflows');

    expect(useSidebarNavStore.getState().quickOpenNavId).toBe('workflows');
  });
});

describe('sidebar nav preferences - persistence', () => {
  it('writes the choice so the next visit keeps it', () => {
    useSidebarNavStore.getState().toggleNavItem('board');
    useSidebarNavStore.getState().setQuickOpenNavId('files');

    const stored = JSON.parse(window.localStorage.getItem(STORAGE_KEY) as string);
    expect(stored.state.hiddenNavIds).toEqual(['applications', 'interfaces']);
    expect(stored.state.quickOpenNavId).toBe('files');
  });

  it('reads a stored choice back on rehydration', async () => {
    seedStorage({ hiddenNavIds: ['files'], quickOpenNavId: 'workflows' });

    await useSidebarNavStore.persist.rehydrate();

    expect(useSidebarNavStore.getState().hiddenNavIds).toEqual(['files']);
    expect(useSidebarNavStore.getState().quickOpenNavId).toBe('workflows');
  });

  it('drops an id this build no longer knows instead of hiding nothing under its name', async () => {
    seedStorage({ hiddenNavIds: ['files', 'a-page-that-was-removed'], quickOpenNavId: 'agenda' });

    await useSidebarNavStore.persist.rehydrate();

    expect(useSidebarNavStore.getState().hiddenNavIds).toEqual(['files']);
  });

  it('clamps a payload that would hide the sidebar past its floor', async () => {
    seedStorage({ hiddenNavIds: SIDEBAR_NAV_ITEMS.map((item) => item.id), quickOpenNavId: 'agenda' });

    await useSidebarNavStore.persist.rehydrate();

    expect(visibleIds().length).toBeGreaterThanOrEqual(MIN_VISIBLE_NAV_ITEMS);
  });

  it('falls back to the default quick-open target when the stored one is not a page', async () => {
    seedStorage({ hiddenNavIds: [], quickOpenNavId: 'not-a-page' });

    await useSidebarNavStore.persist.rehydrate();

    expect(useSidebarNavStore.getState().quickOpenNavId).toBe('agenda');
  });

  it('survives a corrupt payload rather than rendering an empty sidebar', async () => {
    seedStorage({ hiddenNavIds: 'everything', quickOpenNavId: 42 });

    await useSidebarNavStore.persist.rehydrate();

    expect(useSidebarNavStore.getState().hiddenNavIds).toEqual([...DEFAULT_HIDDEN_NAV_IDS]);
    expect(useSidebarNavStore.getState().quickOpenNavId).toBe('agenda');
  });
});

describe('sidebar nav preferences - reset', () => {
  it('puts back the defaults', () => {
    useSidebarNavStore.getState().toggleNavItem('board');
    useSidebarNavStore.getState().setQuickOpenNavId('files');

    useSidebarNavStore.getState().resetNavPreferences();

    expect(useSidebarNavStore.getState().hiddenNavIds).toEqual([...DEFAULT_HIDDEN_NAV_IDS]);
    expect(useSidebarNavStore.getState().quickOpenNavId).toBe('agenda');
  });
});
