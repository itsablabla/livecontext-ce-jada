import type { CustomApiAuthConfig } from '@/lib/api/orchestrator';

export type CustomApiAuthType = 'none' | 'bearer' | 'apikey' | 'oauth2' | 'basic_auth';
export type CustomApiAuthInjectionType = 'header' | 'query';

const DEFAULT_AUTH_CONFIG: Record<Exclude<CustomApiAuthType, 'none'>, CustomApiAuthConfig> = {
  bearer: { type: 'bearer', injectionType: 'header', key: 'Authorization', prefix: 'Bearer ' },
  apikey: { type: 'apikey', injectionType: 'header', key: 'X-API-Key' },
  oauth2: { type: 'oauth2', injectionType: 'header', key: 'Authorization', prefix: 'Bearer ' },
  basic_auth: { type: 'basic_auth', injectionType: 'basic_auth', key: 'Authorization' },
};

export function normalizeCustomApiAuthType(authType?: string | null): CustomApiAuthType {
  switch ((authType || 'none').toLowerCase()) {
    case 'basic':
    case 'basic_auth':
      return 'basic_auth';
    case 'api_key':
    case 'apikey':
      return 'apikey';
    case 'bearer_token':
    case 'bearer':
      return 'bearer';
    case 'oauth2':
      return 'oauth2';
    default:
      return 'none';
  }
}

export function getDefaultCustomApiAuthConfig(authType: CustomApiAuthType): CustomApiAuthConfig | undefined {
  return authType === 'none' ? undefined : { ...DEFAULT_AUTH_CONFIG[authType] };
}

export function normalizeCustomApiAuthConfig(
  authType: CustomApiAuthType,
  authConfig?: CustomApiAuthConfig
): CustomApiAuthConfig | undefined {
  const defaults = getDefaultCustomApiAuthConfig(authType);
  if (!defaults) return undefined;
  if (!authConfig) return defaults;

  return {
    ...defaults,
    ...authConfig,
    type: authType,
    injectionType:
      authType === 'basic_auth'
        ? 'basic_auth'
        : authConfig.injectionType === 'query'
          ? 'query'
          : 'header',
    key: authConfig.key?.trim() || defaults.key,
    prefix:
      authType === 'basic_auth' || authConfig.injectionType === 'query'
        ? undefined
        : authConfig.prefix ?? defaults.prefix,
  };
}

export function buildCustomApiAuthPayload(
  authType: CustomApiAuthType,
  authConfig?: CustomApiAuthConfig
): CustomApiAuthConfig | undefined {
  const normalized = normalizeCustomApiAuthConfig(authType, authConfig);
  if (!normalized) return undefined;
  if (normalized.type === 'basic_auth') {
    return { type: 'basic_auth', injectionType: 'basic_auth', key: 'Authorization' };
  }
  return {
    type: normalized.type,
    injectionType: normalized.injectionType as CustomApiAuthInjectionType,
    key: normalized.key,
    ...(normalized.injectionType === 'header' && normalized.prefix !== undefined
      ? { prefix: normalized.prefix }
      : {}),
  };
}
