// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ChangelogMediaView from '../ChangelogMediaView';

/** Installs a matchMedia that answers `reduced` for the reduce-motion query. */
function mockReducedMotion(reduced: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: reduced && query.includes('prefers-reduced-motion'),
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
    })),
  });
}

const IMAGE = { type: 'image' as const, src: '/changelog/x.svg', width: 1200, height: 630 };
const VIDEO = { type: 'video' as const, src: '/changelog/x.mp4', poster: '/changelog/x.svg', width: 1200, height: 630 };

describe('ChangelogMediaView', () => {
  beforeEach(() => mockReducedMotion(false));
  afterEach(() => cleanup());

  it('renders an image with its intrinsic size, so the panel never reflows around it', () => {
    render(<ChangelogMediaView media={IMAGE} alt="An illustration" />);

    const img = screen.getByAltText('An illustration');
    expect(img).toHaveAttribute('src', '/changelog/x.svg');
    expect(img).toHaveAttribute('width', '1200');
    expect(img).toHaveAttribute('height', '630');
  });

  it('plays a video like an animated image: muted, looping, inline, no controls', () => {
    const { container } = render(<ChangelogMediaView media={VIDEO} alt="A clip" />);

    const video = container.querySelector('video')!;
    expect(video).toBeInTheDocument();
    // Muted is not a preference: autoplay is only honoured when muted, and only inline on iOS.
    expect(video).toHaveProperty('muted', true);
    expect(video).toHaveAttribute('loop');
    expect(video).toHaveAttribute('playsinline');
    expect(video).toHaveAttribute('autoplay');
    expect(video).not.toHaveAttribute('controls');
    expect(video).toHaveAttribute('poster', '/changelog/x.svg');
  });

  it('shows the poster instead of the animation when the viewer asked for reduced motion', () => {
    mockReducedMotion(true);

    const { container } = render(<ChangelogMediaView media={VIDEO} alt="A clip" />);

    expect(container.querySelector('video')).not.toBeInTheDocument();
    expect(screen.getByAltText('A clip')).toHaveAttribute('src', '/changelog/x.svg');
  });

  it('falls back to a paused, controllable video when reduced motion meets a posterless clip', () => {
    mockReducedMotion(true);

    const { container } = render(
      <ChangelogMediaView media={{ ...VIDEO, poster: undefined }} alt="A clip" />,
    );

    const video = container.querySelector('video')!;
    // Nothing moves on its own, but the viewer can still watch it if they want to.
    expect(video).not.toHaveAttribute('autoplay');
    expect(video).toHaveAttribute('controls');
  });

  it('renders an image unchanged under reduced motion', () => {
    mockReducedMotion(true);

    render(<ChangelogMediaView media={IMAGE} alt="An illustration" />);

    expect(screen.getByAltText('An illustration')).toHaveAttribute('src', '/changelog/x.svg');
  });

  it('survives a browser without matchMedia instead of crashing the panel', () => {
    // jsdom-era browsers and some embedded webviews: the panel must still render.
    Object.defineProperty(window, 'matchMedia', { writable: true, configurable: true, value: undefined });

    const { container } = render(<ChangelogMediaView media={VIDEO} alt="A clip" />);

    expect(container.querySelector('video')).toBeInTheDocument();
  });
});
