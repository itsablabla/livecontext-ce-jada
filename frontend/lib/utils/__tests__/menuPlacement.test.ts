// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { clampMenuCenter, clampMenuLeft, MENU_VIEWPORT_MARGIN } from '../menuPlacement';

/**
 * The clamp every hand-positioned menu in the app goes through.
 *
 * Each case here is a menu that actually misbehaved on a 410px phone, named in
 * the comment: they are the reason the helper exists, and they are what should
 * fail if someone loosens it.
 */
const REAL_CLIENT_WIDTH = document.documentElement.clientWidth;
const REAL_INNER_WIDTH = window.innerWidth;

function viewport({ client, inner }: { client?: number; inner?: number }) {
  Object.defineProperty(document.documentElement, 'clientWidth', {
    value: client ?? 0,
    configurable: true,
    writable: true,
  });
  Object.defineProperty(window, 'innerWidth', {
    value: inner ?? 0,
    configurable: true,
    writable: true,
  });
}

afterEach(() => viewport({ client: REAL_CLIENT_WIDTH, inner: REAL_INNER_WIDTH }));

describe('clampMenuLeft', () => {
  it('leaves a menu that already fits exactly where the caller wanted it', () => {
    viewport({ client: 1440 });
    expect(clampMenuLeft(600, 192)).toBe(600);
  });

  it('pulls a menu back when its trigger sits near the right edge', () => {
    // The chat tool-card menu: 192px anchored at rect.left = 370 on a 410px
    // phone spanned 370-562, i.e. mostly off screen.
    viewport({ client: 410 });
    expect(clampMenuLeft(370, 192)).toBe(410 - 192 - MENU_VIEWPORT_MARGIN);
  });

  it('pushes a menu back when right-aligning it would put it off the left edge', () => {
    // The preview action menu right-aligns: rect.right - 176 goes negative for
    // any trigger in the first 176px of the row.
    viewport({ client: 410 });
    expect(clampMenuLeft(-136, 176)).toBe(MENU_VIEWPORT_MARGIN);
  });

  it('pins a menu wider than the screen to the left gutter rather than off both edges', () => {
    viewport({ client: 300 });
    expect(clampMenuLeft(120, 320)).toBe(MENU_VIEWPORT_MARGIN);
  });

  it('treats a screen with exactly no room to spare as the too-wide case', () => {
    // maxLeft === margin: both branches would return the same number here, and
    // that is the point - the boundary must not fall through to a negative one.
    viewport({ client: 320 + 2 * MENU_VIEWPORT_MARGIN });
    expect(clampMenuLeft(999, 320)).toBe(MENU_VIEWPORT_MARGIN);
  });

  it('honours a caller that keeps a wider gutter', () => {
    // The cURL popover asks for 16px, as it always did.
    viewport({ client: 410 });
    expect(clampMenuLeft(400, 320, 16)).toBe(410 - 320 - 16);
  });

  it('measures the layout viewport, not the window, so a scrollbar is not counted as room', () => {
    // A `fixed` menu is laid out against the initial containing block, which
    // EXCLUDES a classic scrollbar; window.innerWidth includes it. Reading the
    // window would spend the whole 8px gutter on the scrollbar and park the menu
    // underneath it.
    viewport({ client: 1000, inner: 1017 });
    expect(clampMenuLeft(900, 200)).toBe(1000 - 200 - MENU_VIEWPORT_MARGIN);
  });

  it('falls back to the window width when the document reports nothing', () => {
    viewport({ client: 0, inner: 410 });
    expect(clampMenuLeft(370, 192)).toBe(410 - 192 - MENU_VIEWPORT_MARGIN);
  });
});

describe('clampMenuCenter', () => {
  it('leaves a centred menu alone when both its edges fit', () => {
    viewport({ client: 1440 });
    expect(clampMenuCenter(700, 320)).toBe(700);
  });

  it('moves the midpoint so a centred menu keeps both edges on screen', () => {
    // The canvas trigger menu is translateX(-50%) on its button: a button near
    // the right edge put half the menu past it.
    viewport({ client: 410 });
    const center = clampMenuCenter(390, 320);
    expect(center - 160).toBeGreaterThanOrEqual(MENU_VIEWPORT_MARGIN);
    expect(center + 160).toBeLessThanOrEqual(410 - MENU_VIEWPORT_MARGIN);
  });

  it('centres a menu too wide for the screen instead of picking an edge', () => {
    // Neither edge can be honoured, so what overflows is split evenly rather
    // than falling off one side; anchoring it to the left gutter would push all
    // of it past the right.
    viewport({ client: 300 });
    expect(clampMenuCenter(50, 320)).toBe(150);
    expect(clampMenuCenter(280, 320)).toBe(150);
  });
});
