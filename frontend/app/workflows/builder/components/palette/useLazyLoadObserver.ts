import * as React from 'react';

type UseLazyLoadObserverParams = {
  /** Whether the observer should be active */
  enabled: boolean;
  /** Whether there's more data to load */
  hasMore: boolean;
  /** Whether currently loading */
  isLoading: boolean;
  /** Whether initial load is complete */
  isInitialLoading: boolean;
  /** Current data length (must be > 0 to trigger) */
  dataLength: number;
  /** Function to call when intersection is detected */
  onLoadMore: () => void;
};

/**
 * Hook for lazy loading with IntersectionObserver.
 * Returns a ref to attach to a sentinel element.
 */
export function useLazyLoadObserver({
  enabled,
  hasMore,
  isLoading,
  isInitialLoading,
  dataLength,
  onLoadMore,
}: UseLazyLoadObserverParams): React.RefObject<HTMLDivElement> {
  const sentinelRef = React.useRef<HTMLDivElement>(null);

  // Store current values in ref to avoid recreating observer
  const stateRef = React.useRef({
    hasMore,
    isLoading,
    isInitialLoading,
    dataLength,
    onLoadMore,
  });

  // Keep ref in sync
  React.useEffect(() => {
    stateRef.current = {
      hasMore,
      isLoading,
      isInitialLoading,
      dataLength,
      onLoadMore,
    };
  }, [hasMore, isLoading, isInitialLoading, dataLength, onLoadMore]);

  React.useEffect(() => {
    if (!enabled) return;
    // No IntersectionObserver (older embedded webviews, jsdom): the list stays on the
    // page it has instead of taking the whole palette down with a ReferenceError. It
    // only became reachable on the DEFAULT screen with the ranked integrations section,
    // so a gap that used to be unobservable is now a crash on open.
    if (typeof IntersectionObserver === 'undefined') return;

    const observer = new IntersectionObserver(
      (entries) => {
        const [entry] = entries;
        const state = stateRef.current;

        if (
          entry.isIntersecting &&
          state.hasMore &&
          !state.isLoading &&
          !state.isInitialLoading &&
          state.dataLength > 0
        ) {
          state.onLoadMore();
        }
      },
      { threshold: 0.1, rootMargin: '100px' }
    );

    const currentRef = sentinelRef.current;
    if (currentRef) {
      const timeoutId = setTimeout(() => {
        observer.observe(currentRef);
      }, 100);

      return () => {
        clearTimeout(timeoutId);
        if (currentRef) {
          observer.unobserve(currentRef);
        }
      };
    }

    return () => {
      if (currentRef) {
        observer.unobserve(currentRef);
      }
    };
  }, [enabled, hasMore, isLoading, isInitialLoading, dataLength]);

  return sentinelRef;
}

export default useLazyLoadObserver;
