// @vitest-environment jsdom
/**
 * The store must NOT read localStorage while it is being created.
 *
 * That is the whole reason it is configured with `skipHydration` and rehydrated
 * from an effect: the sidebar renders on the server, and a store that picked up
 * the stored choices during module evaluation would make the first client render
 * disagree with the server HTML - a hydration mismatch that only ever shows in
 * production.
 *
 * Nothing else pins that. Dropping `skipHydration` leaves every other test green
 * (they all run client-side, where reading storage early looks like an
 * improvement), so this file imports the module FRESH with storage already
 * populated and asserts the defaults survive until rehydration is asked for.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_HIDDEN_NAV_IDS } from '@/lib/sidebar/navItems';

const STORAGE_KEY = 'lc.sidebar.nav.v1';

async function freshStore() {
  vi.resetModules();
  return (await import('@/lib/stores/sidebar-nav-store')).useSidebarNavStore;
}

beforeEach(() => {
  window.localStorage.clear();
  window.localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({ state: { hiddenNavIds: ['files'], quickOpenNavId: 'workflows' }, version: 0 }),
  );
});

describe('sidebar nav store - hydration is deferred', () => {
  it('starts from the defaults even when storage already holds a choice', async () => {
    const store = await freshStore();

    // The stored payload says files/workflows; the first render must not know it.
    expect(store.getState().hiddenNavIds).toEqual([...DEFAULT_HIDDEN_NAV_IDS]);
    expect(store.getState().quickOpenNavId).toBe('agenda');
  });

  it('reports itself as not yet hydrated, so the effect knows to rehydrate', async () => {
    const store = await freshStore();

    expect(store.persist.hasHydrated()).toBe(false);
  });

  it('picks the stored choice up only once rehydration is asked for', async () => {
    const store = await freshStore();

    await store.persist.rehydrate();

    expect(store.getState().hiddenNavIds).toEqual(['files']);
    expect(store.getState().quickOpenNavId).toBe('workflows');
    expect(store.persist.hasHydrated()).toBe(true);
  });
});
