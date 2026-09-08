import type { GenerationProvenance } from '@/lib/api/storage-api';
import { STUDIO_MESSAGE_TYPE, type StudioRequestEnvelope } from '@/lib/generation/studioMessage';

/**
 * Carrying a recipe from wherever an asset is shown to the studio that can run it again.
 *
 * <p><b>The problem.</b> "Run this again with one thing changed" starts on a file and has to arrive
 * at the studio composer, through a navigation. The recipe is a model, a prompt and a set of
 * parameters, which is far too much for a URL: a prompt is routinely hundreds of characters, and a
 * query string carrying one would be truncated by something long before it is read.
 *
 * <p><b>No surface stashes today.</b> The Files browser used to, and now opens the generation
 * dialog in place instead - the asset lands in the list it was started from, so there is nothing to
 * navigate to. The channel is kept for a surface that DOES have to travel to the studio, and the
 * studio still consumes it on mount, so such a surface is one call away rather than a redesign.
 *
 * <p><b>Why session storage and not a store.</b> The handoff has to survive a full navigation, and
 * a React store does not (the studio route is a different page, and a hard reload of the studio URL
 * mounts a fresh app). Session storage does, is scoped to the one tab that started the handoff, and
 * dies with it, which is exactly the lifetime this has: it is a click in flight, not state.
 *
 * <p><b>It is consumed exactly once.</b> Reading clears it. Left in place, opening the studio a
 * week later would refill the composer with a prompt the reader had forgotten writing, and pressing
 * Create would charge them for it.
 */

const STUDIO_RECIPE_KEY = 'lc.studio.pendingRecipe';

/**
 * Put a recipe in flight, to be picked up by the studio on its next mount.
 *
 * <p>Fails silently: private-mode and quota errors must not break the navigation that follows. The
 * studio then opens with an empty composer, which is a worse outcome than the handoff but not a
 * broken one.
 */
export function stashStudioRecipe(provenance: GenerationProvenance): void {
  if (typeof window === 'undefined') return;
  if (!provenance?.model) return;
  try {
    window.sessionStorage.setItem(STUDIO_RECIPE_KEY, JSON.stringify(provenance));
  } catch {
    // See the docblock: a failed handoff must not stop the reader getting to the studio.
  }
}

/**
 * Take the recipe in flight, if there is one, and clear it.
 *
 * <p>Returns the studio's own request shape rather than the provenance, so the caller has one thing
 * to hand the composer whether the turn came from a file or from a card in the thread.
 */
export function takeStudioRecipe(): StudioRequestEnvelope | null {
  if (typeof window === 'undefined') return null;
  let raw: string | null = null;
  try {
    raw = window.sessionStorage.getItem(STUDIO_RECIPE_KEY);
    // Cleared whether or not it parses. A payload this build cannot read would otherwise be retried
    // on every mount, for ever.
    if (raw !== null) window.sessionStorage.removeItem(STUDIO_RECIPE_KEY);
  } catch {
    return null;
  }
  if (!raw) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  return recipeToRequest(parsed as GenerationProvenance | null);
}

/**
 * A stored recipe, as a studio request.
 *
 * <p>Exported for the callers that already hold a provenance and do not need the storage hop, and
 * so the mapping is pinned by a test rather than restated at each call site.
 *
 * <p>Returns null without a model id: the model is what a replay RUNS, and a recipe that cannot
 * name one would fill the composer with words and then submit them to whatever happened to be
 * selected.
 */
export function recipeToRequest(
  provenance: GenerationProvenance | null | undefined,
): StudioRequestEnvelope | null {
  if (!provenance || typeof provenance.model !== 'string' || !provenance.model) return null;
  return {
    type: STUDIO_MESSAGE_TYPE,
    role: 'request',
    prompt: typeof provenance.prompt === 'string' ? provenance.prompt : '',
    model: provenance.model,
    // A recipe written before the kind was recorded still replays: the kind is used to draw the
    // turn before an answer arrives, and the model decides the real one anyway.
    kind: typeof provenance.kind === 'string' ? provenance.kind : '',
    ...(provenance.provider ? { provider: provenance.provider } : {}),
    ...(provenance.params ? { params: provenance.params } : {}),
    // Restored, so repeating a recipe does not quietly move the charge to another key. The stored
    // value is what the run REPORTED, so anything else is treated as unrecorded.
    ...(provenance.credentialSource === 'user' || provenance.credentialSource === 'platform'
      ? { credentialSource: provenance.credentialSource }
      : {}),
  };
}
