/**
 * The inspector's box for its three mutually exclusive placements.
 *
 * The one that matters most is DOCKED: the panel is portalled into the side
 * panel, so any leftover `fixed inset-0` would take it out of the panel and lay
 * it over the whole viewport, and any `lg:w-[300px]` would fight the panel's own
 * resize handle. Both are invisible in the floating case, which is why they get
 * asserted here rather than left to a screenshot.
 */
import { describe, expect, it } from 'vitest';

import { inspectorGeometryClass } from '../inspectorGeometry';

describe('inspectorGeometryClass', () => {
  it('fills its slot when docked, without escaping it', () => {
    const cls = inspectorGeometryClass({ isFullscreen: false, isDocked: true, isAdvanced: false });

    expect(cls).toContain('relative');
    expect(cls).toContain('w-full');
    expect(cls).toContain('h-full');
    // Would break out of the side panel and cover the page.
    expect(cls).not.toContain('fixed');
    expect(cls).not.toContain('inset-0');
    // Would fight the panel's own width.
    expect(cls).not.toContain('lg:w-[');
    // The panel already has its own edges.
    expect(cls).not.toContain('rounded-2xl');
  });

  it('takes no width step when docked, whatever the advanced layout says', () => {
    const basic = inspectorGeometryClass({ isFullscreen: false, isDocked: true, isAdvanced: false });
    const advanced = inspectorGeometryClass({ isFullscreen: false, isDocked: true, isAdvanced: true });

    expect(advanced).toBe(basic);
  });

  it('keeps the historical floating box: full-screen below lg, sized window above', () => {
    const cls = inspectorGeometryClass({ isFullscreen: false, isDocked: false, isAdvanced: false });

    expect(cls).toContain('fixed inset-0');
    expect(cls).toContain('lg:relative');
    expect(cls).toContain('lg:inset-auto');
    expect(cls).toContain('lg:rounded-2xl');
    expect(cls).toContain('lg:w-[300px]');
  });

  it('widens the floating window in the advanced three-column layout', () => {
    const cls = inspectorGeometryClass({ isFullscreen: false, isDocked: false, isAdvanced: true });

    expect(cls).toContain('lg:w-[900px]');
    expect(cls).not.toContain('lg:w-[300px]');
  });

  it('covers the viewport in fullscreen, docked or not', () => {
    const floating = inspectorGeometryClass({ isFullscreen: true, isDocked: false, isAdvanced: true });
    const docked = inspectorGeometryClass({ isFullscreen: true, isDocked: true, isAdvanced: false });

    expect(floating).toBe(docked);
    expect(docked).toContain('fixed inset-0');
    expect(docked).toContain('rounded-none');
    expect(docked).not.toContain('lg:w-[');
  });
});
