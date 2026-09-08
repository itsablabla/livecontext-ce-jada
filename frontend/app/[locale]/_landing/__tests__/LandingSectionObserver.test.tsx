// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const analyticsMock = vi.hoisted(() => ({
  track: vi.fn((_event: string, _props?: Record<string, unknown>) => true),
}));
vi.mock('@/lib/analytics/analytics', () => analyticsMock);

import LandingSectionObserver from '../LandingSectionObserver';

type IOCallback = (entries: IntersectionObserverEntry[], observer: IntersectionObserver) => void;

/**
 * jsdom ships no IntersectionObserver. This fake records the callback, the
 * options and every observed element so a test can drive intersection by hand.
 */
class FakeIntersectionObserver {
  static instances: FakeIntersectionObserver[] = [];
  readonly callback: IOCallback;
  readonly options: IntersectionObserverInit | undefined;
  readonly observed: Element[] = [];
  readonly unobserve = vi.fn((el: Element) => {
    const idx = this.observed.indexOf(el);
    if (idx >= 0) this.observed.splice(idx, 1);
  });
  readonly disconnect = vi.fn();

  constructor(callback: IOCallback, options?: IntersectionObserverInit) {
    this.callback = callback;
    this.options = options;
    FakeIntersectionObserver.instances.push(this);
  }

  observe(el: Element) {
    this.observed.push(el);
  }

  /**
   * Simulates the browser reporting these targets as (not) intersecting. Like
   * the real API, an element that is no longer observed is never reported, so
   * the component's dedup (unobserve after the first view) is what is tested.
   */
  intersect(targets: Element[], isIntersecting = true) {
    const entries = targets
      .filter((target) => this.observed.includes(target))
      .map((target) => ({ target, isIntersecting })) as IntersectionObserverEntry[];
    act(() => this.callback(entries, this as unknown as IntersectionObserver));
  }
}

function renderLanding() {
  const utils = render(
    <>
      <main>
        <section id="hero">Hero</section>
        <section id="pricing">Pricing</section>
        <section>Anonymous section, no id</section>
      </main>
      <LandingSectionObserver />
    </>,
  );
  const observer = FakeIntersectionObserver.instances[0];
  const hero = document.getElementById('hero')!;
  const pricing = document.getElementById('pricing')!;
  return { ...utils, observer, hero, pricing };
}

describe('LandingSectionObserver', () => {
  beforeEach(() => {
    analyticsMock.track.mockClear();
    analyticsMock.track.mockImplementation(() => true);
    FakeIntersectionObserver.instances = [];
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('observes every main section with an id at 40% visibility', () => {
    const { observer, hero, pricing } = renderLanding();

    expect(FakeIntersectionObserver.instances).toHaveLength(1);
    expect(observer.options).toEqual({ threshold: 0.4 });
    expect(observer.observed).toEqual([hero, pricing]);
  });

  it('emits landing_section_viewed once per section even when a section intersects repeatedly', () => {
    const { observer, hero, pricing } = renderLanding();

    observer.intersect([hero]);
    observer.intersect([hero]);
    observer.intersect([pricing]);

    expect(analyticsMock.track).toHaveBeenCalledTimes(2);
    expect(analyticsMock.track).toHaveBeenNthCalledWith(1, 'landing_section_viewed', {
      section: 'hero',
      time_to_view_ms: expect.any(Number),
    });
    expect(analyticsMock.track).toHaveBeenNthCalledWith(2, 'landing_section_viewed', {
      section: 'pricing',
      time_to_view_ms: expect.any(Number),
    });
  });

  it('stops observing a section after its first view so the browser never reports it again', () => {
    const { observer, hero, pricing } = renderLanding();

    observer.intersect([hero]);

    expect(observer.unobserve).toHaveBeenCalledTimes(1);
    expect(observer.unobserve).toHaveBeenCalledWith(hero);
    expect(observer.observed).toEqual([pricing]);
  });

  it('reports time_to_view_ms as the rounded elapsed time since mount', () => {
    // A controlled clock: React's scheduler also reads performance.now(), so a
    // one-shot return value would be consumed before the effect runs.
    let now = 1000;
    const nowSpy = vi.spyOn(performance, 'now').mockImplementation(() => now);
    try {
      const { observer, hero } = renderLanding();
      now = 1234.6;

      observer.intersect([hero]);

      const props = analyticsMock.track.mock.calls[0][1] as { time_to_view_ms: number };
      expect(props.time_to_view_ms).toBe(235);
    } finally {
      nowSpy.mockRestore();
    }
  });

  it('ignores entries that are not intersecting', () => {
    const { observer, hero } = renderLanding();

    observer.intersect([hero], false);

    expect(analyticsMock.track).not.toHaveBeenCalled();
    expect(observer.unobserve).not.toHaveBeenCalled();
  });

  it('prefers data-landing-section over the element id as the section name', () => {
    render(
      <>
        <main>
          <section id="raw-id" data-landing-section="named-section">x</section>
        </main>
        <LandingSectionObserver />
      </>,
    );
    const observer = FakeIntersectionObserver.instances[0];
    const el = document.getElementById('raw-id')!;

    observer.intersect([el]);

    expect(analyticsMock.track).toHaveBeenCalledWith(
      'landing_section_viewed',
      expect.objectContaining({ section: 'named-section' }),
    );
  });

  it('disconnects the observer on unmount', () => {
    const { observer, unmount } = renderLanding();
    expect(observer.disconnect).not.toHaveBeenCalled();

    unmount();

    expect(observer.disconnect).toHaveBeenCalledTimes(1);
  });

  it('creates no observer when the page has no landing section', () => {
    render(<LandingSectionObserver />);

    expect(FakeIntersectionObserver.instances).toHaveLength(0);
  });

  it('does nothing when IntersectionObserver is unavailable', () => {
    vi.stubGlobal('IntersectionObserver', undefined);

    expect(() => renderLanding()).not.toThrow();
    expect(FakeIntersectionObserver.instances).toHaveLength(0);
    expect(analyticsMock.track).not.toHaveBeenCalled();
  });

  it('a section on screen BEFORE consent is emitted once consent is granted, and only once (regression)', () => {
    analyticsMock.track.mockImplementation(() => false); // banner not accepted yet
    render(
      <main>
        <section id="hero" />
        <LandingSectionObserver />
      </main>,
    );
    const observer = FakeIntersectionObserver.instances[0];
    const hero = document.getElementById('hero') as HTMLElement;
    hero.getBoundingClientRect = () =>
      ({ top: 0, bottom: 500, height: 500, left: 0, right: 0, width: 0, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
    Object.defineProperty(window, 'innerHeight', { value: 800, configurable: true });

    observer.intersect([hero]);
    expect(analyticsMock.track).toHaveBeenCalledTimes(1);
    expect(observer.unobserve).not.toHaveBeenCalled(); // not released: nothing was emitted

    analyticsMock.track.mockImplementation(() => true); // consent accepted
    window.dispatchEvent(new Event('lc:cookie-consent'));
    expect(analyticsMock.track).toHaveBeenCalledTimes(2);
    expect(analyticsMock.track).toHaveBeenLastCalledWith('landing_section_viewed', expect.objectContaining({ section: 'hero' }));
    expect(observer.unobserve).toHaveBeenCalledWith(hero);

    window.dispatchEvent(new Event('lc:cookie-consent'));
    expect(analyticsMock.track).toHaveBeenCalledTimes(2); // released: never again
  });
});
