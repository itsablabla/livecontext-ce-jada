// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { WELL_KNOWN_MODELS } from '../wellKnownModels';

/**
 * The footer must not name a provider the platform cannot run.
 *
 * <p>This column is on every public page, so a provider dropped from the catalogue (CE
 * blocks some outright, and the seed is regenerated per release) would leave the site
 * advertising a model family that resolves to nothing in the app. The seed is the same file
 * `ModelSeedBootstrapService` applies at boot, so "present here" means "offered to users".
 *
 * <p>Enabled, not merely present: a disabled entry is a model the catalogue knows and does
 * not serve, which for a visitor is the same as absent.
 */

const SEED = join(
  process.cwd(),
  '..',
  'backend',
  'agent-service',
  'src',
  'main',
  'resources',
  'model-catalog',
  'models.json',
);

interface SeedModel {
  provider?: string;
  enabled?: boolean;
}

function enabledProviders(): Set<string> {
  const seed = JSON.parse(readFileSync(SEED, 'utf8')) as { models?: SeedModel[] };
  const providers = new Set<string>();
  for (const model of seed.models ?? []) {
    if (model.provider && model.enabled !== false) providers.add(model.provider);
  }
  return providers;
}

describe('well-known models in the footer', () => {
  const providers = enabledProviders();

  it('reads the model catalogue seed at all, so the cases below cannot pass vacuously', () => {
    expect(providers.size).toBeGreaterThan(5);
  });

  it('names eight families, the width of the integrations column beside it', () => {
    expect(WELL_KNOWN_MODELS).toHaveLength(8);
  });

  it.each(WELL_KNOWN_MODELS.map((m) => [m.label, m.provider]))(
    '%s is served by a provider the catalogue enables (%s)',
    (_label, provider) => {
      expect(providers.has(provider)).toBe(true);
    },
  );

  it('names each provider once, so no family is listed twice under two labels', () => {
    const keys = WELL_KNOWN_MODELS.map((m) => m.provider);
    expect(new Set(keys).size).toBe(keys.length);
  });
});
