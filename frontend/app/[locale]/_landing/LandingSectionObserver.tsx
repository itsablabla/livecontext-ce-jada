'use client';

import { useEffect } from 'react';
import { track } from '@/lib/analytics/analytics';
import { CONSENT_CHANGE_EVENT } from '@/lib/analytics/consent';

/**
 * Fires `landing_section_viewed` ONCE per landing section, the first time at
 * least 40% of it is on screen. One IntersectionObserver for every
 * `main section[id]` / `[data-landing-section]`, created on mount and
 * disconnected on unmount: no scroll listener, no work in render.
 *
 * A section is only released once its event was actually handed to the SDK.
 * Before the cookie banner is accepted `track()` is a no-op, and the hero is
 * on screen before anyone can accept, so the sections already visible are
 * re-checked when consent arrives instead of being silently lost.
 *
 * `time_to_view_ms` is measured from this component's mount, which is the
 * landing's hydration, so it reads as "how long into the visit".
 */
const VISIBLE_RATIO = 0.4;

export default function LandingSectionObserver() {
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return;
    const mountTime = performance.now();
    const targets = document.querySelectorAll<HTMLElement>('main section[id], [data-landing-section]');
    if (targets.length === 0) return;

    const pending = new Set<HTMLElement>(targets);

    const emit = (el: HTMLElement): boolean => {
      const section = el.dataset.landingSection || el.id;
      if (!section) return true; // nothing to say about an anonymous section
      return track('landing_section_viewed', {
        section,
        time_to_view_ms: Math.round(performance.now() - mountTime),
      });
    };

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          const el = entry.target as HTMLElement;
          if (!pending.has(el)) continue;
          if (emit(el)) {
            pending.delete(el);
            observer.unobserve(el);
          }
        }
      },
      { threshold: VISIBLE_RATIO },
    );
    pending.forEach((el) => observer.observe(el));

    // Consent granted mid-visit: the sections already on screen will not cross
    // the threshold again, so measure them once by hand.
    const onConsent = () => {
      const vh = window.innerHeight || document.documentElement.clientHeight || 0;
      for (const el of Array.from(pending)) {
        const r = el.getBoundingClientRect();
        const visible = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
        if (r.height > 0 && visible / r.height >= VISIBLE_RATIO && emit(el)) {
          pending.delete(el);
          observer.unobserve(el);
        }
      }
    };
    window.addEventListener(CONSENT_CHANGE_EVENT, onConsent);

    return () => {
      window.removeEventListener(CONSENT_CHANGE_EVENT, onConsent);
      observer.disconnect();
    };
  }, []);

  return null;
}
