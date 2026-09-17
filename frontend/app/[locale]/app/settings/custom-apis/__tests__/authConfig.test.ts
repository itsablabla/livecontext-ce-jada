import { describe, expect, it } from 'vitest';
import {
  buildCustomApiAuthPayload,
  normalizeCustomApiAuthConfig,
  normalizeCustomApiAuthType,
} from '../authConfig';

describe('custom API auth config helpers', () => {
  it('normalizes legacy basic auth values', () => {
    expect(normalizeCustomApiAuthType('basic')).toBe('basic_auth');
    expect(normalizeCustomApiAuthConfig('basic_auth')).toEqual({
      type: 'basic_auth',
      injectionType: 'basic_auth',
      key: 'Authorization',
    });
  });

  it('preserves explicit query auth wiring without a prefix', () => {
    expect(
      buildCustomApiAuthPayload('apikey', {
        type: 'apikey',
        injectionType: 'query',
        key: 'api_key',
        prefix: 'ignored',
      })
    ).toEqual({
      type: 'apikey',
      injectionType: 'query',
      key: 'api_key',
    });
  });

  it('defaults bearer auth to Authorization with ******', () => {
    expect(buildCustomApiAuthPayload('bearer')).toEqual({
      type: 'bearer',
      injectionType: 'header',
      key: 'Authorization',
      prefix: 'Bearer ',
    });
  });
});
