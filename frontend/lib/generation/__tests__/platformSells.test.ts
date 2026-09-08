import { describe, expect, it } from 'vitest';

import { platformSellsThis } from '../platformSells';
import type { PlatformCredentialPublicInfo } from '@/lib/api/orchestrator';

/**
 * "Can the platform run this on its own key" is a THREE-part fact, and every part has been the
 * one missing in a real report. It is asserted here rather than inside the two components that
 * ask it, because the harm of the two drifting apart is asymmetric and silent: the credential
 * control decides whether to OFFER the platform, the studio decides whether to DEFAULT to it, and
 * a studio that defaults to a payer the control would never have shown lands the reader on a key
 * that cannot run the call - discovered at the refusal, after a model and a prompt.
 */
function info(over: Partial<PlatformCredentialPublicInfo>): PlatformCredentialPublicInfo {
  return {
    integrationName: 'seedance',
    available: true,
    hasPricing: true,
    platformCredentialId: 7,
    ...over,
  } as PlatformCredentialPublicInfo;
}

describe('platformSellsThis', () => {
  it('says yes only when the credential exists, is usable AND is priced', () => {
    expect(platformSellsThis(info({}))).toBe(true);
  });

  it('says no when no platform credential row exists for the integration', () => {
    // HeyGen, as reported: nothing to sell, so nothing to offer and nothing to default to.
    expect(platformSellsThis(info({ platformCredentialId: null, available: false, hasPricing: false }))).toBe(false);
  });

  it('says no when the row exists but is disabled or has no secret', () => {
    expect(platformSellsThis(info({ available: false }))).toBe(false);
  });

  it('says no when no rate is published, however available the key is', () => {
    // Without a rate the platform key would be a free ride on the platform's own account. An admin
    // opts an endpoint in by publishing a price; nothing else may stand in for that.
    expect(platformSellsThis(info({ hasPricing: false }))).toBe(false);
  });

  it('says no for an answer that has not arrived', () => {
    // The distinction the studio depends on: "we have not asked yet" must not read as "sold". The
    // caller waits for the quote to settle before acting on a false, so this only has to be safe.
    expect(platformSellsThis(undefined)).toBe(false);
    expect(platformSellsThis(null)).toBe(false);
  });

  it('does not accept a zero credential id as absent', () => {
    // Guards the `!= null` rather than a truthiness check: id 0 is a row, not a missing one.
    expect(platformSellsThis(info({ platformCredentialId: 0 }))).toBe(true);
  });
});
