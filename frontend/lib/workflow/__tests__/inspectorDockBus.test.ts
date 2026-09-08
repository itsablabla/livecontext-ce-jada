/**
 * @vitest-environment jsdom
 *
 * The bus that lets the docked inspector cross React trees: the panel offers a
 * host element, the canvas publishes whether it wants the dock, and the page is
 * asked to open the panel.
 *
 * Everything is keyed by workflow id AND surface. The workflow id alone is not
 * unique - the same workflow can be open on its own page and inside a panel tab
 * at once - and the surface is what stops the two canvases from overwriting each
 * other. Several canvases of DIFFERENT workflows are also mounted at once, so
 * both halves of the key get their own leak test here.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  OPEN_INSPECTOR_PANEL_EVENT,
  getInspectorDockHost,
  getInspectorDockState,
  isInspectorOpenRequestFor,
  makeEmptyInspectorDockState,
  openInspectorPanel,
  publishInspectorDockState,
  setInspectorDockHost,
  subscribeInspectorDockHost,
  subscribeInspectorDockState,
} from '../inspectorDockBus';

function reset() {
  for (const wf of ['wf-1', 'wf-2']) {
    for (const surface of ['page', 'embedded'] as const) {
      setInspectorDockHost(wf, surface, null);
      publishInspectorDockState({ workflowId: wf, surface, docked: false });
    }
  }
}

beforeEach(reset);
afterEach(reset);

describe('inspectorDockBus - host registry', () => {
  it('has no host until a panel offers one', () => {
    expect(getInspectorDockHost('wf-1', 'page')).toBeNull();
    expect(getInspectorDockHost(undefined, 'page')).toBeNull();
  });

  it('hands the registered element back and notifies subscribers', () => {
    const seen: (HTMLElement | null)[] = [];
    const unsubscribe = subscribeInspectorDockHost('wf-1', 'page', h => seen.push(h));
    const el = document.createElement('div');

    setInspectorDockHost('wf-1', 'page', el);

    expect(getInspectorDockHost('wf-1', 'page')).toBe(el);
    expect(seen).toEqual([el]);
    unsubscribe();
  });

  it('withdraws the host on null, so the portal never targets a detached node', () => {
    const el = document.createElement('div');
    setInspectorDockHost('wf-1', 'page', el);
    const seen: (HTMLElement | null)[] = [];
    subscribeInspectorDockHost('wf-1', 'page', h => seen.push(h));

    setInspectorDockHost('wf-1', 'page', null);

    expect(getInspectorDockHost('wf-1', 'page')).toBeNull();
    expect(seen).toEqual([null]);
  });

  it('does not notify when the same element is offered twice', () => {
    const el = document.createElement('div');
    const listener = vi.fn();
    subscribeInspectorDockHost('wf-1', 'page', listener);

    setInspectorDockHost('wf-1', 'page', el);
    setInspectorDockHost('wf-1', 'page', el);

    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('keeps one workflow host out of another', () => {
    const el = document.createElement('div');
    const otherListener = vi.fn();
    subscribeInspectorDockHost('wf-2', 'page', otherListener);

    setInspectorDockHost('wf-1', 'page', el);

    expect(getInspectorDockHost('wf-2', 'page')).toBeNull();
    expect(otherListener).not.toHaveBeenCalled();
  });

  it('keeps the two surfaces of ONE workflow apart', () => {
    // A workflow open on its page and in a panel tab at the same time: the page
    // canvas must not portal itself into the embedded panel's slot.
    const pageSlot = document.createElement('div');
    const embeddedSlot = document.createElement('div');
    const embeddedListener = vi.fn();
    subscribeInspectorDockHost('wf-1', 'embedded', embeddedListener);

    setInspectorDockHost('wf-1', 'page', pageSlot);
    expect(embeddedListener).not.toHaveBeenCalled();

    setInspectorDockHost('wf-1', 'embedded', embeddedSlot);

    expect(getInspectorDockHost('wf-1', 'page')).toBe(pageSlot);
    expect(getInspectorDockHost('wf-1', 'embedded')).toBe(embeddedSlot);
  });

  it('stops notifying after unsubscribe', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeInspectorDockHost('wf-1', 'page', listener);
    unsubscribe();

    setInspectorDockHost('wf-1', 'page', document.createElement('div'));

    expect(listener).not.toHaveBeenCalled();
  });
});

describe('inspectorDockBus - dock state', () => {
  it('reports an undocked empty state for a workflow nobody published for', () => {
    expect(getInspectorDockState('wf-unknown', 'page'))
      .toEqual(makeEmptyInspectorDockState('wf-unknown', 'page'));
    expect(getInspectorDockState('wf-unknown', 'page').docked).toBe(false);
  });

  it('caches the last publish so a panel mounting LATE still sees the selection', () => {
    // The panel body is unmounted while the side panel is closed: without the
    // cache, reopening it on a canvas that already has a node selected would
    // show no Inspector tab until the user clicked the node again.
    publishInspectorDockState({ workflowId: 'wf-1', surface: 'page', docked: true, label: 'Send Email' });

    const state = getInspectorDockState('wf-1', 'page');
    expect(state.docked).toBe(true);
    expect(state.label).toBe('Send Email');
  });

  it('delivers publishes to subscribers of that workflow only', () => {
    const mine: string[] = [];
    const theirs = vi.fn();
    const unsubscribe = subscribeInspectorDockState('wf-1', 'page', s => mine.push(s.label ?? ''));
    subscribeInspectorDockState('wf-2', 'page', theirs);

    publishInspectorDockState({ workflowId: 'wf-1', surface: 'page', docked: true, label: 'Fetch Rows' });

    expect(mine).toEqual(['Fetch Rows']);
    expect(theirs).not.toHaveBeenCalled();
    unsubscribe();
  });

  it('does not let one surface clear the other for the same workflow', () => {
    // The failure this key exists to prevent: the embedded canvas publishing
    // "nothing selected" and taking away the tab the page canvas just asked for.
    publishInspectorDockState({ workflowId: 'wf-1', surface: 'page', docked: true, label: 'Send Email' });
    const pageListener = vi.fn();
    subscribeInspectorDockState('wf-1', 'page', pageListener);

    publishInspectorDockState({ workflowId: 'wf-1', surface: 'embedded', docked: false });

    expect(getInspectorDockState('wf-1', 'page').docked).toBe(true);
    expect(getInspectorDockState('wf-1', 'embedded').docked).toBe(false);
    expect(pageListener).not.toHaveBeenCalled();
  });

  it('bumps seq on every publish, so an identical state still notifies', () => {
    // Re-clicking the node that is already selected changes nothing about the
    // state, yet it is how a user comes back to the Inspector tab.
    const seqs: number[] = [];
    subscribeInspectorDockState('wf-1', 'page', s => seqs.push(s.seq));

    publishInspectorDockState({ workflowId: 'wf-1', surface: 'page', docked: true, label: 'Same' });
    publishInspectorDockState({ workflowId: 'wf-1', surface: 'page', docked: true, label: 'Same' });

    expect(seqs).toHaveLength(2);
    expect(seqs[1]).toBeGreaterThan(seqs[0]);
  });

  it('ignores a publish with no workflow id rather than caching it under nothing', () => {
    const listener = vi.fn();
    subscribeInspectorDockState('wf-1', 'page', listener);

    publishInspectorDockState({ workflowId: '', surface: 'page', docked: true });

    expect(listener).not.toHaveBeenCalled();
  });
});

describe('inspectorDockBus - open request', () => {
  it('dispatches the open event addressed to the asking workflow and surface', () => {
    const handler = vi.fn();
    window.addEventListener(OPEN_INSPECTOR_PANEL_EVENT, handler);

    openInspectorPanel({ workflowId: 'wf-1', surface: 'embedded' });

    expect(handler).toHaveBeenCalledTimes(1);
    expect((handler.mock.calls[0][0] as CustomEvent).detail)
      .toEqual({ workflowId: 'wf-1', surface: 'embedded' });
    window.removeEventListener(OPEN_INSPECTOR_PANEL_EVENT, handler);
  });

  it('routes a request to the matching workflow AND surface only', () => {
    const request = { workflowId: 'wf-1', surface: 'embedded' as const };

    expect(isInspectorOpenRequestFor(request, 'wf-1', 'embedded')).toBe(true);
    // Same workflow, wrong composition: the page panel must not follow it.
    expect(isInspectorOpenRequestFor(request, 'wf-1', 'page')).toBe(false);
    expect(isInspectorOpenRequestFor(request, 'wf-2', 'embedded')).toBe(false);
  });

  it('lets an unaddressed request through, so a caller can stay generic', () => {
    expect(isInspectorOpenRequestFor(undefined, 'wf-1', 'page')).toBe(true);
    expect(isInspectorOpenRequestFor({}, 'wf-1', 'embedded')).toBe(true);
    expect(isInspectorOpenRequestFor({ workflowId: 'wf-1' }, 'wf-1', 'embedded')).toBe(true);
  });
});
