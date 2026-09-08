import clsx from 'clsx';

interface InspectorGeometryOptions {
  /** Covering the whole viewport, which overrides both other cases. */
  isFullscreen: boolean;
  /** Portalled into the side panel's Inspector slot rather than floating. */
  isDocked: boolean;
  /** The wide three-column layout (floating only: docked, the panel sets the width). */
  isAdvanced: boolean;
}

/**
 * The inspector panel's box, in one place because the three cases are mutually
 * exclusive and easy to get wrong when they are spelled out inline.
 *
 *  - Fullscreen covers the viewport, docked or not.
 *  - Docked, the side-panel slot owns the box, so the panel fills it: no fixed
 *    positioning (it would escape the panel entirely), no width step (a
 *    `lg:w-[300px]` would fight the panel's own resize handle) and no rounding
 *    against the panel's edges.
 *  - Floating keeps the historical behavior: full-screen below `lg`, a sized
 *    window above it, with `rounded-2xl` - the floating-surface step of the
 *    radius ladder it shares with the toolbar and the palette next to it.
 */
export function inspectorGeometryClass({
  isFullscreen,
  isDocked,
  isAdvanced,
}: InspectorGeometryOptions): string {
  if (isFullscreen) {
    return 'fixed inset-0 w-full h-full max-w-full max-h-full rounded-none';
  }
  if (isDocked) {
    return 'relative inset-auto w-full h-full max-w-none max-h-none rounded-none';
  }
  return clsx(
    'fixed inset-0 w-full h-full max-w-full max-h-full rounded-none',
    'lg:relative lg:inset-auto lg:rounded-2xl',
    isAdvanced ? 'lg:w-[900px]' : 'lg:w-[300px]',
  );
}
