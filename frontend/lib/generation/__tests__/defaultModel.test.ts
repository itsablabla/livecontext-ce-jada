import { describe, expect, it } from 'vitest';

import { pickDefaultModel, PREFERRED_DEFAULT_MODEL } from '../defaultModel';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

/**
 * Which model the studio opens on.
 *
 * <p>It used to be `models[0]`, and that is a different model on two installs: the order is
 * whatever the seed and the sort produce, so the surface whose whole point is producing an asset
 * opened on whichever row happened to sort first - an image model, on a catalogue where images
 * come before video. The reader's first impression of what the studio is FOR came out of a sort.
 *
 * <p>The fallbacks step down rather than give up, and each step is asserted separately because each
 * covers a real install: the flagship missing, the provider missing, video missing entirely.
 */
function model(over: Partial<GenerationModel>): GenerationModel {
  return {
    model: 'x', kind: 'image', label: 'X', provider: 'Someone',
    iconSlug: null, apiToolId: 't', integrationName: 'someone',
    accepts: ['prompt'], required: [], limits: {},
    billedOn: null, measuredUnit: null, defaultQuantity: null,
    price: { unit: 'call', baseCredits: '0', unitCredits: '0' }, async: false,
    ...over,
  } as unknown as GenerationModel;
}

const FLAGSHIP = model({ model: PREFERRED_DEFAULT_MODEL, kind: 'video', integrationName: 'seedance', label: 'Seedance 2.5' });
const SIBLING = model({ model: 'seedance-2.0', kind: 'video', integrationName: 'seedance', label: 'Seedance 2.0' });
const OTHER_VIDEO = model({ model: 'runway-3', kind: 'video', integrationName: 'runway', label: 'Runway' });
const AN_IMAGE = model({ model: 'flux-1', kind: 'image', integrationName: 'flux', label: 'FLUX.1' });

describe('pickDefaultModel', () => {
  it('opens on the flagship even when it is not first in the catalogue', () => {
    // The defect this replaces: the first row won, whatever it was.
    expect(pickDefaultModel([AN_IMAGE, OTHER_VIDEO, FLAGSHIP])?.model).toBe(PREFERRED_DEFAULT_MODEL);
  });

  it('falls back to a video model of the same provider when the flagship is absent', () => {
    // An install that has not taken 2.5 yet, or carries only a resolution variant. Its reader
    // should still land on the provider the default names, not on someone else.
    expect(pickDefaultModel([AN_IMAGE, OTHER_VIDEO, SIBLING])?.model).toBe('seedance-2.0');
  });

  it('falls back to ANY video model when that provider is absent entirely', () => {
    // The format is the part of the intent that survives: the studio should open on something it
    // can show moving, whoever makes it.
    expect(pickDefaultModel([AN_IMAGE, OTHER_VIDEO])?.model).toBe('runway-3');
  });

  it('falls back to the first row when the catalogue has no video at all', () => {
    // An image-only install is a real one. Opening on nothing would be worse than opening on the
    // only thing there is.
    expect(pickDefaultModel([AN_IMAGE])?.model).toBe('flux-1');
  });

  it('answers null for an empty catalogue rather than inventing a model', () => {
    // The surface states this case in its own words; a fabricated selection would send a turn to
    // a model that does not exist.
    expect(pickDefaultModel([])).toBeNull();
  });

  it('does not mistake an IMAGE model of the same provider for the video default', () => {
    // The preference is a video one. A provider that also ships images would otherwise satisfy the
    // fallback with the wrong format, which is the defect the whole function exists to fix.
    const seedanceImage = model({ model: 'seedance-img', kind: 'image', integrationName: 'seedance' });

    expect(pickDefaultModel([seedanceImage, OTHER_VIDEO])?.model).toBe('runway-3');
  });

  it('matches the provider on its INTEGRATION, not its display name', () => {
    // Display names are not identifiers: an install can carry the same provider name under two
    // integrations, and they are two different accounts.
    const lookalike = model({ model: 'other', kind: 'video', integrationName: 'not-seedance', provider: 'Seedance' });

    expect(pickDefaultModel([lookalike])?.model).toBe('other');
    expect(pickDefaultModel([lookalike, SIBLING])?.model).toBe('seedance-2.0');
  });
});
