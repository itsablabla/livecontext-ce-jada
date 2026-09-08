/**
 * @vitest-environment jsdom
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { recipeToRequest, stashStudioRecipe, takeStudioRecipe } from '../studioHandoff';
import { STUDIO_MESSAGE_TYPE } from '../studioMessage';
import type { GenerationProvenance } from '@/lib/api/storage-api';

/**
 * Carrying a recipe from a file to the studio, across a navigation.
 *
 * <p>The rule with real consequences is "consumed exactly once". A recipe left behind would refill
 * the composer days later with a prompt the reader had forgotten writing, and pressing Create would
 * charge them for it.
 */

const recipe: GenerationProvenance = {
  model: 'flux-1',
  kind: 'image',
  provider: 'flux',
  prompt: 'a lighthouse at dusk',
  params: { aspect_ratio: '16:9' },
};

beforeEach(() => {
  window.sessionStorage.clear();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('stash / take', () => {
  it('carries the whole recipe across', () => {
    stashStudioRecipe(recipe);
    expect(takeStudioRecipe()).toEqual({
      type: STUDIO_MESSAGE_TYPE,
      role: 'request',
      prompt: 'a lighthouse at dusk',
      model: 'flux-1',
      kind: 'image',
      provider: 'flux',
      params: { aspect_ratio: '16:9' },
    });
  });

  it('is consumed exactly once', () => {
    stashStudioRecipe(recipe);
    expect(takeStudioRecipe()).not.toBeNull();
    // The second read is what a later visit to the studio does. It must find nothing.
    expect(takeStudioRecipe()).toBeNull();
  });

  it('returns null when nothing is in flight', () => {
    expect(takeStudioRecipe()).toBeNull();
  });

  it('clears a payload it cannot read, instead of retrying it for ever', () => {
    window.sessionStorage.setItem('lc.studio.pendingRecipe', '{not json');
    expect(takeStudioRecipe()).toBeNull();
    expect(window.sessionStorage.getItem('lc.studio.pendingRecipe')).toBeNull();
  });

  it('refuses to stash a recipe with no model - a replay has nothing to run', () => {
    stashStudioRecipe({ prompt: 'x' } as GenerationProvenance);
    expect(takeStudioRecipe()).toBeNull();
  });

  it('survives storage being unavailable, because the navigation must still happen', () => {
    // Private mode, a quota, a browser with site data blocked. The reader lands in the studio with
    // an empty composer, which is worse than the handoff and better than a dead button.
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    expect(() => stashStudioRecipe(recipe)).not.toThrow();
    setItem.mockRestore();
    expect(takeStudioRecipe()).toBeNull();
  });
});

describe('recipeToRequest', () => {
  it('refuses a recipe with no model', () => {
    // Without one, the composer would fill with words and submit them to whatever happened to be
    // selected, which is not the generation the reader asked to repeat.
    expect(recipeToRequest(null)).toBeNull();
    expect(recipeToRequest({} as GenerationProvenance)).toBeNull();
    expect(recipeToRequest({ model: '' } as GenerationProvenance)).toBeNull();
  });

  it('replays a recipe recorded before the kind was stored', () => {
    const request = recipeToRequest({ model: 'flux-1' } as GenerationProvenance);
    expect(request).toMatchObject({ model: 'flux-1', prompt: '', kind: '' });
  });

  it('omits params and provider rather than writing empty ones', () => {
    const request = recipeToRequest({ model: 'flux-1', prompt: 'x' } as GenerationProvenance);
    expect(request).not.toHaveProperty('params');
    expect(request).not.toHaveProperty('provider');
  });

  it('keeps the parameters, which is the point of repeating a recipe', () => {
    const request = recipeToRequest(recipe);
    expect(request?.params).toEqual({ aspect_ratio: '16:9' });
  });

  it('restores WHO PAID, so repeating a recipe does not move the charge', () => {
    // "Run this again with one thing changed" must not change a second thing, and the billing one
    // is the expensive one to change silently.
    const request = recipeToRequest({ ...recipe, credentialSource: 'user' });
    expect(request?.credentialSource).toBe('user');
  });

  it('ignores a payer the run never recorded', () => {
    // The stored value is what the run REPORTED. Anything else is not a payer this app can act on,
    // and guessing one would be the same silent change in the other direction.
    expect(recipeToRequest({ ...recipe, credentialSource: 'something-else' })).not.toHaveProperty('credentialSource');
    expect(recipeToRequest({ ...recipe, credentialSource: undefined })).not.toHaveProperty('credentialSource');
  });
});
