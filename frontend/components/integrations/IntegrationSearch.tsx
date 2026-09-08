'use client';

import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { Search, X } from 'lucide-react';
import {
  mapIntegrations,
  PUBLIC_SITE_LOCALE,
  type PublicIntegration,
} from '@/lib/integrations/integrations';
import { IntegrationCard } from './IntegrationCard';

/**
 * The search box over the public integration catalog, used by both surfaces that
 * list integrations: the landing section and the `/integrations` directory.
 *
 * <h2>Why it wraps its results instead of owning them</h2>
 * <p>`children` is the server-rendered list, and it is what the page shows until
 * someone types. That ordering is the whole design: the catalog has to be in the
 * HTML a crawler receives, so the list cannot be something this component
 * fetches on mount. Typing then REPLACES it with matches from the server, which
 * is also why search is not a client-side filter over `children` - the landing
 * only renders the top few dozen, and filtering those would answer "no results"
 * for most of a catalog of several hundred.
 *
 * <p>The endpoint is anonymous at the gateway, so a raw fetch through the proxy
 * is correct here: these pages carry no auth token, and `apiClient` throws
 * without one.
 */

/** Long enough that a single keystroke is not a query, short enough to feel live. */
const DEBOUNCE_MS = 200;

/** One screen of matches. Anything past this is a sign to refine the term. */
const RESULT_LIMIT = 48;

/**
 * The last answer received, TAGGED with the term it answers.
 *
 * <p>Carrying the query is what lets "loading" be derived rather than stored: a
 * result whose query is not the current one is by definition stale, so there is
 * no state to set when the term changes, and no cascading render on every
 * keystroke. It also makes a late response for an abandoned term unable to paint
 * over the current one.
 */
type SearchResult =
  | { query: string; status: 'done'; results: PublicIntegration[]; total: number }
  | { query: string; status: 'error' };

export default function IntegrationSearch({
  children,
  totalCount,
  placeholder = 'Search integrations',
}: {
  /** The server-rendered list, shown whenever the box is empty. */
  children: React.ReactNode;
  /** Catalog size, for the input's hint. Omitted when the count is unknown. */
  totalCount?: number;
  placeholder?: string;
}) {
  const [query, setQuery] = useState('');
  const [result, setResult] = useState<SearchResult | null>(null);
  const inputId = useId();
  const statusId = useId();
  const abortRef = useRef<AbortController | null>(null);

  const trimmed = query.trim();
  const searching = trimmed.length > 0;

  /** The answer for the term currently in the box, or null while one is pending. */
  const current = result !== null && result.query === trimmed ? result : null;
  const loading = searching && current === null;

  useEffect(() => {
    if (!searching) {
      // Abort whatever is in flight: its answer would arrive after the visitor
      // already cleared the box.
      abortRef.current?.abort();
      abortRef.current = null;
      return;
    }

    const timer = setTimeout(async () => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      const params = new URLSearchParams({ q: trimmed, page: '0', size: String(RESULT_LIMIT) });
      try {
        const res = await fetch(`/api/proxy/public/integrations?${params.toString()}`, {
          headers: { Accept: 'application/json' },
          signal: controller.signal,
        });
        if (!res.ok) {
          setResult({ query: trimmed, status: 'error' });
          return;
        }
        const payload = await res.json();
        const total = typeof payload?.totalElements === 'number' ? payload.totalElements : 0;
        setResult({ query: trimmed, status: 'done', results: mapIntegrations(payload), total });
      } catch (error) {
        // An abort is this component cancelling itself, not a failure to report.
        if ((error as Error)?.name === 'AbortError') return;
        setResult({ query: trimmed, status: 'error' });
      }
    }, DEBOUNCE_MS);

    return () => clearTimeout(timer);
  }, [trimmed, searching]);

  useEffect(() => () => abortRef.current?.abort(), []);

  const hint = useMemo(() => {
    if (!searching) {
      return totalCount && totalCount > 0
        ? `${totalCount.toLocaleString(PUBLIC_SITE_LOCALE)} integrations, most-used first.`
        : null;
    }
    if (loading) return 'Searching…';
    if (current?.status === 'error') return 'Search is unavailable right now. The full list is below.';
    if (current?.status === 'done') {
      if (current.total === 0) return `No integration matches “${trimmed}”.`;
      const shown = current.results.length;
      return current.total > shown
        ? `${shown} of ${current.total} matches for “${trimmed}”.`
        : `${current.total} ${current.total === 1 ? 'match' : 'matches'} for “${trimmed}”.`;
    }
    return null;
  }, [current, loading, searching, totalCount, trimmed]);

  return (
    <div>
      <div className="relative">
        <label htmlFor={inputId} className="sr-only">
          Search integrations by name or by what they do
        </label>
        <Search
          className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2"
          style={{ color: 'var(--text-muted)' }}
          aria-hidden="true"
        />
        <input
          id={inputId}
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={placeholder}
          autoComplete="off"
          aria-describedby={statusId}
          className="h-11 w-full rounded-xl border pl-9 pr-9 text-sm outline-none transition-colors focus:border-[var(--text-muted)]"
          style={{
            borderColor: 'var(--border-color)',
            background: 'var(--bg-primary)',
            color: 'var(--text-primary)',
          }}
        />
        {searching && (
          <button
            type="button"
            onClick={() => setQuery('')}
            aria-label="Clear search"
            className="absolute right-2 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-lg transition-colors hover:bg-[var(--bg-tertiary)]"
            style={{ color: 'var(--text-muted)' }}
          >
            <X className="h-4 w-4" />
          </button>
        )}
      </div>

      {/* Polite, not assertive: results change on every keystroke and an assertive
          region would interrupt a screen-reader user mid-word. */}
      <p
        id={statusId}
        aria-live="polite"
        className="mt-2 min-h-[1.25rem] text-xs"
        style={{ color: 'var(--text-muted)' }}
      >
        {hint}
      </p>

      <div className="mt-5">
        {!searching && children}

        {current?.status === 'done' && current.results.length > 0 && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {current.results.map((integration) => (
              <IntegrationCard key={integration.slug} integration={integration} />
            ))}
          </div>
        )}

        {current?.status === 'done' && current.results.length === 0 && (
          <div
            className="rounded-xl border p-6 text-center text-sm"
            style={{ borderColor: 'var(--border-color)', color: 'var(--text-secondary)' }}
          >
            <p>
              Nothing matches “{trimmed}”. Every endpoint of every catalog integration is a tool, and
              you can also connect anything else with a custom API or a raw HTTP request.
            </p>
          </div>
        )}

        {/* A failed search falls back to the server-rendered list rather than to an
            empty screen: the visitor keeps something to browse. */}
        {current?.status === 'error' && children}
      </div>
    </div>
  );
}
