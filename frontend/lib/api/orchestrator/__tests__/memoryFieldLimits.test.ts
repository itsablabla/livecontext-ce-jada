import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { join } from 'path';

import { MEMORY_FIELD_LIMITS } from '../memory.service';

/**
 * The editor's caps must match what the server actually enforces.
 *
 * <p><strong>Why this needs a test.</strong> `MEMORY_FIELD_LIMITS` exists so the
 * form can stop a person mid-keystroke instead of letting them write six hundred
 * characters and lose them to a round trip. That only works while the two agree,
 * and they drift in opposite, equally silent ways: raise the server's cap and the
 * browser clamps at the old figure with no error anywhere, lower it and the person
 * types happily up to a limit that is refused on submit.
 *
 * <p>The figures are read out of the Java rather than retyped here. A second copy
 * of a number is the thing being guarded against, so the guard cannot be a third.
 */

const BACKEND_MEMORY = [
  __dirname, '..', '..', '..', '..', '..',
  'backend', 'agent-service', 'src', 'main', 'java', 'com', 'apimarketplace',
  'agent', 'memory',
];

const LIMITS_CONFIG = join(...BACKEND_MEMORY, 'MemoryLimitsConfig.java');
const SERVICE = join(...BACKEND_MEMORY, 'MemoryService.java');

/** A `private int <name> = <n>;` field default, or a `public static final int <NAME> = <n>;`. */
function javaIntDefault(source: string, file: string, name: string): number {
  const declaration = String.raw`(?:private int|public static final int)\s+${name}\s*=\s*(\d+)\s*;`;
  const match = source.match(new RegExp(declaration));
  expect(match, `${file} no longer declares ${name}; this guard is now blind`).not.toBeNull();
  return Number(match![1]);
}

describe('MEMORY_FIELD_LIMITS mirrors the server defaults', () => {
  it('matches maxSummaryChars and maxContentChars from MemoryLimitsConfig', () => {
    const source = readFileSync(LIMITS_CONFIG, 'utf8');

    expect(MEMORY_FIELD_LIMITS.summary)
      .toBe(javaIntDefault(source, 'MemoryLimitsConfig', 'maxSummaryChars'));
    expect(MEMORY_FIELD_LIMITS.content)
      .toBe(javaIntDefault(source, 'MemoryLimitsConfig', 'maxContentChars'));
  });

  it('matches MAX_TITLE_CHARS from MemoryService, which is a constant rather than a setting', () => {
    // The title cap is NOT operator-tunable: it is a compile-time constant, so the
    // browser's copy can only ever be wrong by being stale.
    const source = readFileSync(SERVICE, 'utf8');

    expect(MEMORY_FIELD_LIMITS.title)
      .toBe(javaIntDefault(source, 'MemoryService', 'MAX_TITLE_CHARS'));
  });
});
