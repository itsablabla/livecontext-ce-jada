/**
 * The two panel GEOMETRY gates, and why neither may use the tabbed-layout flag.
 *
 * `shouldUseTabbedLayout` folds in "the PANEL measured narrow". That signal is
 * correct for deciding what goes INSIDE the panel, and wrong for deciding the
 * panel's own shape: one of these gates produced a user-visible no-op because of
 * it, and the other would feed its own condition.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import {
  shouldConstrainPanelToContainer,
  shouldRenderMinimizedPill,
  shouldUseTabbedLayout,
} from '../useInspectorLayout';

describe('shouldRenderMinimizedPill', () => {
  it('shows the pill for a desktop user who asked to minimize', () => {
    expect(
      shouldRenderMinimizedPill({ isMinimized: true, isWindowMobile: false, isDocked: false }),
    ).toBe(true);
  });

  it('still shows the pill when the PANEL is narrow, which is the bug this fixes', () => {
    // Regression: the gate used to read the tabbed-layout flag, which is true for
    // an advanced-mode user whose panel measured narrow. Clicking minimize then
    // fell through to the full panel and nothing happened.
    const narrowAdvancedPanel = shouldUseTabbedLayout({
      isWindowMobile: false,
      isNarrowPanel: true,
      isAdvanced: true,
      isFullscreen: false,
    });
    expect(narrowAdvancedPanel, 'the flag that used to gate this branch').toBe(true);

    expect(
      shouldRenderMinimizedPill({ isMinimized: true, isWindowMobile: false, isDocked: false }),
      'minimize must work regardless of how wide the panel happened to measure',
    ).toBe(true);
  });

  it('does not show the pill on a mobile window, where the panel is full-screen', () => {
    expect(
      shouldRenderMinimizedPill({ isMinimized: true, isWindowMobile: true, isDocked: false }),
    ).toBe(false);
  });

  it('does not show the pill when docked: the dock owns the panel size', () => {
    expect(
      shouldRenderMinimizedPill({ isMinimized: true, isWindowMobile: false, isDocked: true }),
    ).toBe(false);
  });

  it('shows nothing when the user has not minimized', () => {
    expect(
      shouldRenderMinimizedPill({ isMinimized: false, isWindowMobile: false, isDocked: false }),
    ).toBe(false);
  });
});

describe('shouldConstrainPanelToContainer', () => {
  it('caps a floating desktop panel to its container', () => {
    expect(
      shouldConstrainPanelToContainer({ isFullscreen: false, isDocked: false, isWindowMobile: false }),
    ).toBe(true);
  });

  it.each([
    ['fullscreen', { isFullscreen: true, isDocked: false, isWindowMobile: false }],
    ['docked', { isFullscreen: false, isDocked: true, isWindowMobile: false }],
    ['a mobile window', { isFullscreen: false, isDocked: false, isWindowMobile: true }],
  ])('applies no cap in %s, where something else owns the size', (_case, input) => {
    expect(shouldConstrainPanelToContainer(input)).toBe(false);
  });

});

/**
 * The predicates above are only half the guarantee: the bug lived at the CALL
 * SITE, which passed the tabbed-layout flag where the window flag belongs. Both
 * predicates would still be correct with a wrong argument, and no unit test of a
 * pure function can see that - so the invariant is asserted against the source,
 * the way `JsonbWritesCallsiteInvariantTest` guards its own call sites.
 */
describe('InspectorPanel gate call sites', () => {
  const source = readFileSync(
    join(__dirname, '..', '..', 'InspectorPanel.tsx'),
    'utf8',
  );

  it('passes the WINDOW flag to both geometry gates, never the tabbed-layout flag', () => {
    // `isMobile` in this component is `shouldUseTabbedLayout(...)`, which folds in
    // "the PANEL measured narrow". Feeding it to either gate is the regression:
    // minimize became a no-op for an advanced-mode user with a narrow panel, and
    // the maxWidth cap would feed its own condition.
    const pillCall = source.match(/shouldRenderMinimizedPill\(\{[^}]*\}\)/)?.[0];
    const constrainCall = source.match(/shouldConstrainPanelToContainer\(\{[^}]*\}\)/)?.[0];

    expect(pillCall, 'the minimized-pill gate must be called from InspectorPanel').toBeTruthy();
    expect(constrainCall, 'the panel-size gate must be called from InspectorPanel').toBeTruthy();

    for (const [name, call] of [
      ['shouldRenderMinimizedPill', pillCall!],
      ['shouldConstrainPanelToContainer', constrainCall!],
    ] as const) {
      expect(call, `${name} must be given isWindowMobile`).toContain('isWindowMobile');
      expect(
        /\bisMobile\b/.test(call),
        `${name} must NOT be given isMobile: that is shouldUseTabbedLayout, which knows about the panel width`,
      ).toBe(false);
    }
  });
});
