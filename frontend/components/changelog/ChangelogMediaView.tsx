'use client';

import { useSyncExternalStore } from 'react';
import type { ChangelogMedia } from '@/lib/changelog/latestEntry';

/**
 * The illustration of a changelog entry: an image, or a short muted video that behaves like one.
 *
 * Three rules the panel depends on:
 *  - the box is reserved from the declared intrinsic size, so opening the panel never reflows
 *    around a late-loading asset;
 *  - a video is decoration, not a player: muted, looping, inline, no audio expected. It carries no
 *    controls, because there is nothing to control;
 *  - a viewer who asked for reduced motion gets the poster instead of the animation, and when
 *    there is no poster the video is shown paused with controls rather than moving on its own.
 *
 * Plain `<img>` rather than `next/image`: the asset is a local file of known intrinsic size served
 * from `public/`, the entry ships one image at a time, and CE installs run offline where the
 * optimizer round trip buys nothing. SVG would need `dangerouslyAllowSVG` on top of that.
 */
export default function ChangelogMediaView({ media, alt }: { media: ChangelogMedia; alt: string }) {
  const prefersReducedMotion = usePrefersReducedMotion();
  const ratio = `${media.width} / ${media.height}`;

  if (media.type === 'video' && prefersReducedMotion && media.poster) {
    return (
      // eslint-disable-next-line @next/next/no-img-element -- local asset, fixed size, see above
      <img
        src={media.poster}
        alt={alt}
        width={media.width}
        height={media.height}
        style={{ aspectRatio: ratio }}
        className="w-full rounded-lg border border-theme object-cover"
      />
    );
  }

  if (media.type === 'video') {
    return (
      <video
        src={media.src}
        poster={media.poster}
        // Autoplay is only honoured when muted, and only inline on iOS. Both are set for that
        // reason, not as a preference.
        muted
        loop
        playsInline
        autoPlay={!prefersReducedMotion}
        controls={prefersReducedMotion}
        preload="metadata"
        aria-label={alt}
        width={media.width}
        height={media.height}
        style={{ aspectRatio: ratio }}
        className="w-full rounded-lg border border-theme object-cover"
      />
    );
  }

  return (
    // eslint-disable-next-line @next/next/no-img-element -- local asset, fixed size, see above
    <img
      src={media.src}
      alt={alt}
      width={media.width}
      height={media.height}
      style={{ aspectRatio: ratio }}
      className="w-full rounded-lg border border-theme object-cover"
    />
  );
}

const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';

/** Null when the browser has no matchMedia (jsdom, older embedded webviews). */
function motionQuery(): MediaQueryList | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null;
  return window.matchMedia(REDUCED_MOTION_QUERY);
}

function subscribe(onChange: () => void): () => void {
  const query = motionQuery();
  if (!query) return () => {};
  // addListener is the pre-Safari-14 spelling; both are handled because a self-hosted install is
  // opened with whatever browser its users have, not only the ones we test.
  if (typeof query.addEventListener === 'function') {
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }
  if (typeof query.addListener === 'function') {
    query.addListener(onChange);
    return () => query.removeListener(onChange);
  }
  return () => {};
}

/**
 * Tracks the OS "reduce motion" setting, including a change made while the panel is open.
 *
 * Subscribed rather than read into state in an effect: the value is external browser state, so
 * useSyncExternalStore reads it during render (no first paint with the wrong value, no cascading
 * re-render). The server snapshot is false, which is also what a browser without matchMedia gets.
 */
function usePrefersReducedMotion(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => motionQuery()?.matches ?? false,
    () => false,
  );
}
