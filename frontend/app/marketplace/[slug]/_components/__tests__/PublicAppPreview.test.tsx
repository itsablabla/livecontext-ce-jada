/**
 * @vitest-environment jsdom
 */
import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// Capture what the preview ASKS FOR. The frame's own rendering has its own
// suite; every assertion here is about the contract this page depends on.
const frameProps: Record<string, unknown>[] = [];
vi.mock('@/app/workflows/builder/components/interface/InterfaceShadowPreview', () => ({
  InterfaceShadowPreview: (props: Record<string, unknown>) => {
    frameProps.push(props);
    return <div data-testid="frame" />;
  },
}));

// jsdom measures every box at 0, which would make the component render nothing
// at all. Give it a real page width so the scaling path is exercised.
vi.mock('@/lib/interfaces/useFitScale', () => ({
  useMeasuredBox: () => [{ current: null }, { width: 640, height: 0 }],
}));

import PublicAppPreview from '../PublicAppPreview';
import type { PublicShowcaseRender } from '@/lib/marketplace/publicPublications';

function showcase(overrides: Partial<PublicShowcaseRender> = {}): PublicShowcaseRender {
  return {
    htmlTemplate: '<div id="app">{{title}}</div>',
    cssTemplate: 'body{margin:0}',
    jsTemplate: 'renderList()',
    format: null,
    items: [{ data: { title: 'Volcano' } }],
    ...overrides,
  };
}

function lastProps() {
  return frameProps[frameProps.length - 1];
}

describe('PublicAppPreview', () => {
  it('never hands the frame a height, so a long app is not cropped', () => {
    render(<PublicAppPreview render={showcase()} />);

    // THE invariant of this component. `InterfaceShadowPreview` auto-sizes to
    // the height the iframe reports back, but ONLY while no explicit height is
    // given: pass one and the app is silently cut off at that line, which is
    // exactly what the card thumbnail does and what this page must not do.
    const style = lastProps().style as Record<string, unknown>;
    expect(style.width).toBe(1280);
    expect(style).not.toHaveProperty('height');
    expect(lastProps().onSizeChange).toBeTypeOf('function');
  });

  it('does not stack a second scaler inside the frame', () => {
    render(<PublicAppPreview render={showcase()} />);

    // The wrapper already applies a CSS transform; `autoFit` would scale again
    // inside the iframe, shrinking the app twice.
    expect(lastProps().autoFit).toBe(false);
  });

  it('keeps the publisher JS, because it is what draws the app content', () => {
    render(<PublicAppPreview render={showcase()} />);

    // Dropping it renders a correct-looking but EMPTY application: an
    // interface's jsTemplate is what expands its lists and conditionals.
    // Containment is the iframe sandbox's job, not script removal's.
    expect(lastProps().jsTemplate).toBe('renderList()');
    expect(lastProps().removeScripts).toBeUndefined();
  });

  it('renders the app non-interactive', () => {
    const { container } = render(<PublicAppPreview render={showcase()} />);

    // Mouse never reaches the frame. Combined with the sandbox (allow-scripts
    // only, so no forms and no navigation) and with no action mapping being
    // forwarded, there is nothing a visitor can drive.
    expect((container.firstChild as HTMLElement).style.pointerEvents).toBe('none');
    expect(lastProps().actionMapping).toBeUndefined();
  });

  it('renders resolved item data in run mode', () => {
    render(<PublicAppPreview render={showcase()} />);

    expect(lastProps().mode).toBe('run');
    expect(lastProps().resolvedData).toMatchObject({ title: 'Volcano' });
  });

  it('prefers HTML the backend already resolved', () => {
    render(<PublicAppPreview render={showcase({ items: [{ data: { _resolvedHtml: '<p>done</p>' } }] })} />);

    expect(lastProps().htmlTemplate).toBe('<p>done</p>');
    expect(lastProps().mode).toBe('run');
  });

  it('falls back to the raw template in edit mode when the showcase has no data', () => {
    render(<PublicAppPreview render={showcase({ items: [] })} />);

    expect(lastProps().htmlTemplate).toBe('<div id="app">{{title}}</div>');
    expect(lastProps().mode).toBe('edit');
  });

  it('lays a vertical app out at its authored width, not the page width', () => {
    render(<PublicAppPreview render={showcase({ format: 'vertical' })} />);

    // Rendering a 1080-wide page at the container's 640px would reflow it into
    // a layout its author never saw.
    const style = lastProps().style as Record<string, unknown>;
    expect(style.width).toBeGreaterThan(0);
    expect(style.width).not.toBe(640);
  });

  it('mutes the app, since the visitor has no control to mute with', () => {
    render(<PublicAppPreview render={showcase()} />);

    expect(lastProps().mediaMuted).toBe(true);
  });
});
