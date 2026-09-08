import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

/**
 * The public id the studio opens on when the reader has not chosen anything.
 *
 * <p>A video model, and the current flagship of the provider: the studio's whole point is producing
 * an asset, and a clip is the format that shows what the surface is FOR at a glance. Named by its
 * public id rather than matched on the label, because the label is a display string that changes
 * with a catalogue edit while the id is what a turn records.
 */
export const PREFERRED_DEFAULT_MODEL = 'seedance-2.5';

/** The provider the fallbacks stay within, so a missing flagship lands on its sibling. */
const PREFERRED_PROVIDER = 'seedance';

/**
 * Which model the studio starts on.
 *
 * <p><b>Why a preference and not simply the first row.</b> The catalogue's order is whatever the
 * seed and the sort produce, so "the first model" is a different model on two installs and can
 * change under a reader on a catalogue edit. It opened the studio on an image model that happened
 * to sort first, which reads as though the surface is for images.
 *
 * <p>The fallbacks step DOWN rather than give up, and each step is deliberate: the exact flagship,
 * then any video model of the same provider (a resolution variant, or the previous generation on an
 * install that has not taken 2.5), then any video model at all, then the first row. An install with
 * a partial catalogue still opens on the closest thing to the intent rather than on nothing.
 *
 * <p>Returns null only for an empty catalogue, which is a state the surface states in its own words.
 */
export function pickDefaultModel(models: GenerationModel[]): GenerationModel | null {
  if (models.length === 0) return null;

  const exact = models.find((model) => model.model === PREFERRED_DEFAULT_MODEL);
  if (exact) return exact;

  // Matched on the INTEGRATION, which is the stable identifier; `provider` is a display name and
  // an install can carry the same provider under two integrations.
  const sameProviderVideo = models.find(
    (model) => model.kind === 'video' && model.integrationName === PREFERRED_PROVIDER,
  );
  if (sameProviderVideo) return sameProviderVideo;

  const anyVideo = models.find((model) => model.kind === 'video');
  if (anyVideo) return anyVideo;

  return models[0];
}
