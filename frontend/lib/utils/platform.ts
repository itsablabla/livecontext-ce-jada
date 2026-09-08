'use client';

import { useSyncExternalStore } from 'react';

/**
 * Which physical keyboard the person in front of the app is using, for the one
 * thing it legitimately changes: how a shortcut is spelled (`Ctrl` vs `⌘`).
 *
 * `navigator.platform` is deprecated and some browsers report it empty, hence
 * the two fallbacks: `userAgentData.platform` first, the user-agent string last.
 *
 * Prefer `useIsMacPlatform` inside components: this reads `navigator`, which
 * does not exist on the server, so calling it during render would spell the
 * shortcut one way on the server and another after hydration.
 */
export function isMacPlatform(): boolean {
  if (typeof navigator === 'undefined') return false;
  const nav = navigator as Navigator & { userAgentData?: { platform?: string } };
  const platform = nav.userAgentData?.platform || nav.platform || nav.userAgent || '';
  return /mac|iphone|ipad|ipod/i.test(platform);
}

/** The platform never changes under a live page, so there is nothing to subscribe to. */
const noSubscription = () => () => {};

/**
 * `isMacPlatform` made safe to call during render: the server and the first
 * client render both see `false`, and React swaps the real answer in right
 * after hydration instead of warning about a mismatched tree.
 */
export function useIsMacPlatform(): boolean {
  return useSyncExternalStore(noSubscription, isMacPlatform, () => false);
}
