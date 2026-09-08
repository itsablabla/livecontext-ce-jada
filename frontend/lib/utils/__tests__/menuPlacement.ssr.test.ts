// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { clampMenuCenter, clampMenuLeft } from '../menuPlacement';

/**
 * On the server there is no viewport to clamp against.
 *
 * These helpers are imported by components that render during SSR (the sidebar
 * user menu, the chat tool cards). Touching `window` there would throw and take
 * the page down, so the untouched offset is the only right answer - and this is
 * the one branch a jsdom test can never reach.
 */
describe('without a DOM', () => {
  it('returns the preferred left offset untouched', () => {
    expect(clampMenuLeft(1234, 320)).toBe(1234);
  });

  it('returns the preferred midpoint untouched', () => {
    expect(clampMenuCenter(1234, 320)).toBe(1234);
  });
});
