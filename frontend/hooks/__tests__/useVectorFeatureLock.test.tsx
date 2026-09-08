// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { renderHook } from '@testing-library/react';

/**
 * The hook is a one-line adapter, and the only thing worth pinning is the thing that would break
 * silently: the KEY it asks about.
 *
 * Three sides spell that string separately, with no shared module between them, because the two
 * backends must not depend on each other and neither can be imported here. So each side pins it,
 * and a rename that misses one is caught by whichever test still expects the old value rather than
 * by a user discovering that a paid capability is free, or that a paid one is unreachable.
 */
const lockFor = vi.fn(() => ({ locked: true, requiredPlan: 'PRO' }));
vi.mock('@/hooks/usePlanFeatureGate', () => ({
  usePlanFeatureGate: () => ({ planCode: 'FREE', isLoading: false, requirements: {}, lockFor }),
}));

import { useVectorFeatureLock, VECTOR_FEATURE_KEY } from '../useVectorFeatureLock';

describe('useVectorFeatureLock', () => {
  it('asks the shared plan gate about exactly the vector key the backends gate on', () => {
    renderHook(() => useVectorFeatureLock());

    expect(lockFor).toHaveBeenCalledWith(['feature:vector_search']);
  });

  it('exports that key, so a surface never re-types it', () => {
    expect(VECTOR_FEATURE_KEY).toBe('feature:vector_search');
  });

  it('returns the gate verdict unchanged, including the plan to name', () => {
    const { result } = renderHook(() => useVectorFeatureLock());

    expect(result.current).toEqual({ locked: true, requiredPlan: 'PRO' });
  });
});
