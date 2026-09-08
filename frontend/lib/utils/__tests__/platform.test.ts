// @vitest-environment node
/**
 * The keyboard-spelling probe. Worth its own test because it is read by every
 * shortcut hint in the app, and because it must survive a server render, where
 * `navigator` does not exist at all.
 */
import React from 'react';
import { renderToString } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { isMacPlatform, useIsMacPlatform } from '../platform';

function withNavigator(value: unknown) {
  vi.stubGlobal('navigator', value);
}

afterEach(() => vi.unstubAllGlobals());

describe('isMacPlatform', () => {
  it('says no when there is no navigator at all, as on the server', () => {
    withNavigator(undefined);

    expect(isMacPlatform()).toBe(false);
  });

  it('recognises a Mac', () => {
    withNavigator({ platform: 'MacIntel' });

    expect(isMacPlatform()).toBe(true);
  });

  it('recognises an iPad, whose keyboard carries the same modifier keys', () => {
    withNavigator({ platform: 'iPad' });

    expect(isMacPlatform()).toBe(true);
  });

  it('prefers userAgentData, the replacement for the deprecated platform string', () => {
    withNavigator({ userAgentData: { platform: 'macOS' }, platform: 'Win32' });

    expect(isMacPlatform()).toBe(true);
  });

  it('says no on Windows and Linux', () => {
    withNavigator({ platform: 'Win32' });
    expect(isMacPlatform()).toBe(false);

    withNavigator({ platform: 'Linux x86_64' });
    expect(isMacPlatform()).toBe(false);
  });

  it('says no rather than throwing when the platform is missing entirely', () => {
    withNavigator({});

    expect(isMacPlatform()).toBe(false);
  });

  it('falls back to the user agent when the platform string is empty', () => {
    // Some browsers report `navigator.platform` as an empty string; the UA is
    // the last thing left that names the machine.
    withNavigator({ platform: '', userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)' });

    expect(isMacPlatform()).toBe(true);
  });
});

describe('useIsMacPlatform on the server', () => {
  it('renders the non-Mac answer even on a Mac, so hydration cannot mismatch', () => {
    // The server has no keyboard to look at. If the hook answered `true` here
    // and the client agreed, the markup would still differ from what the
    // SERVER sent, which is the mismatch this hook exists to avoid.
    withNavigator({ platform: 'MacIntel' });
    function Probe() {
      return React.createElement('span', null, String(useIsMacPlatform()));
    }

    expect(renderToString(React.createElement(Probe))).toContain('false');
  });
});
