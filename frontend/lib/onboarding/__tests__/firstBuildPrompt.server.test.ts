// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { consumeFirstBuildPrompt, storeFirstBuildPrompt } from '../firstBuildPrompt';
import { hasFreshPendingMessage } from '@/hooks/chat/useMessageHandlersV2';

/**
 * The storage helpers must be inert, not fatal, where there is no browser.
 *
 * <p><strong>Why a whole file for this.</strong> The environment IS the test.
 * `sessionStorage` is not merely unavailable on the server, it is an undeclared
 * identifier, so touching it throws a `ReferenceError` rather than a storage
 * error, and a try/catch around the access is not the same guarantee as not
 * reaching for it. This module is imported by a page that renders on the
 * server, and every other suite here runs under jsdom, where a missing guard is
 * invisible. Deleting the `typeof window` check makes these two cases fail.
 */
describe('first-build prompt storage with no browser', () => {
  it('does not throw when asked to store a proposal', () => {
    expect(typeof globalThis.sessionStorage).toBe('undefined');

    expect(() => storeFirstBuildPrompt('Build me a workflow.')).not.toThrow();
  });

  it('reports no proposal rather than throwing', () => {
    expect(() => consumeFirstBuildPrompt()).not.toThrow();
    expect(consumeFirstBuildPrompt()).toBeNull();
  });

  it('reports no pending pre-login message rather than throwing', () => {
    // The proposal asks this question on every home-view mount, so it carries
    // the same no-browser hazard as the storage helpers above.
    expect(() => hasFreshPendingMessage()).not.toThrow();
    expect(hasFreshPendingMessage()).toBe(false);
  });
});
