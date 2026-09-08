/**
 * Minimal PostHog loader via the official CDN `array.js` stub - NO npm
 * dependency (keeps the shared lockfile/node_modules untouched, can never break
 * a parallel `next dev`).
 *
 * Faithful, readable reproduction of PostHog's documented web snippet: build a
 * queue stub on `window.posthog`, and let `init()` inject `array.js`, which on
 * load replays the queued `_i` (init) + method calls. Calls made before the
 * script loads are queued, so callers never need to await.
 *
 * The whole thing is inert until {@link analytics.initAnalytics} calls
 * `init(...)` - which only happens behind the configured-key + consent gates.
 */

export interface PosthogClient {
  init: (token: string, config: Record<string, unknown>) => void;
  capture: (event: string, props?: Record<string, unknown>) => void;
  identify: (id: string, props?: Record<string, unknown>, propsOnce?: Record<string, unknown>) => void;
  register: (props: Record<string, unknown>) => void;
  register_once?: (props: Record<string, unknown>) => void;
  unregister?: (key: string) => void;
  get_property?: (key: string) => unknown;
  reset: () => void;
  opt_in_capturing: () => void;
  opt_out_capturing: () => void;
  __SV?: number;
}

/**
 * Returns whatever `window.posthog` is RIGHT NOW: the queue stub before
 * `array.js` has loaded, the real SDK instance afterwards. SSR-safe (null on
 * the server).
 *
 * Callers must never cache the stub returned by {@link loadPosthog}: once
 * `array.js` runs, PostHog's `init_from_snippet` replays the queued calls and
 * then REPLACES `window.posthog` with the materialised instance. Anything
 * pushed onto the old stub after that point is orphaned (never flushed). Every
 * capture / identify / register call therefore has to go through this getter.
 */
export function currentPosthog(): PosthogClient | null {
  if (typeof window === 'undefined') return null;
  const ph = (window as unknown as { posthog?: PosthogClient }).posthog;
  return ph && typeof ph.capture === 'function' ? ph : null;
}

/**
 * Installs the queue stub on `window.posthog` (if absent) and returns it.
 * Idempotent: a second call returns the same instance. SSR-safe: returns null
 * on the server. Use only to call `init()`; afterwards resolve the client via
 * {@link currentPosthog} (see its doc for why).
 */
export function loadPosthog(apiHost: string): PosthogClient | null {
  if (typeof window === 'undefined' || typeof document === 'undefined') return null;

  const w = window as unknown as { posthog?: PosthogClient & { _i?: unknown[]; push?: (a: unknown[]) => void; people?: unknown[] } };
  if (w.posthog && w.posthog.__SV) return w.posthog;

  // `posthog` starts life as an array; queued calls are pushed onto it and
  // `array.js` flushes them once loaded.
  const ph = (w.posthog || []) as unknown as PosthogClient & {
    _i: unknown[];
    push: (a: unknown[]) => void;
    people: unknown[];
    __SV?: number;
  };
  w.posthog = ph;
  ph._i = ph._i || [];

  const queueMethod = (target: { push: (a: unknown[]) => void; [k: string]: unknown }, name: string) => {
    target[name] = (...args: unknown[]) => target.push([name, ...args]);
  };

  // Only `init` triggers the network load (mirrors the official snippet).
  (ph as unknown as { init: (t: string, c: Record<string, unknown>) => void }).init = (token: string, config: Record<string, unknown>) => {
    const host = (config.api_host as string) || apiHost;
    const assetHost = host.replace('.i.posthog.com', '-assets.i.posthog.com');

    const script = document.createElement('script');
    script.type = 'text/javascript';
    script.crossOrigin = 'anonymous';
    script.async = true;
    script.src = `${assetHost}/static/array.js`;
    const first = document.getElementsByTagName('script')[0];
    if (first && first.parentNode) first.parentNode.insertBefore(script, first);
    else document.head.appendChild(script);

    ph.people = ph.people || [];
    const methods = (
      'capture register register_once unregister identify set_person_properties '
      + 'group reset set_config opt_in_capturing opt_out_capturing '
      + 'has_opted_in_capturing has_opted_out_capturing get_distinct_id '
      + 'reloadFeatureFlags onFeatureFlags getFeatureFlag isFeatureEnabled'
    ).split(' ');
    for (const m of methods) queueMethod(ph as unknown as { push: (a: unknown[]) => void; [k: string]: unknown }, m);

    ph._i.push([token, config, 'posthog']);
  };
  ph.__SV = 1;

  return w.posthog;
}
