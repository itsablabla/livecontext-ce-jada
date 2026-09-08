import { describe, expect, it } from 'vitest';
import {
  assetFields,
  buildParamSpec,
  buildSubmissionParams,
  hasSubmittableInput,
  MAX_ASSET_SLOTS,
  missingRequired,
  totalAssetSlots,
} from '../paramSpec';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

/**
 * The controls a model gets, derived from what the model itself declares.
 *
 * <p>The tests that matter here are the ones about ABSENCE: a control drawn for a parameter the
 * model does not accept can only produce a refusal, and it produces it after the reader has filled
 * it in. So most of these assert what is NOT offered.
 */

function model(overrides: Partial<GenerationModel> = {}): GenerationModel {
  return {
    model: 'test-model',
    kind: 'image',
    label: 'Test model',
    provider: 'test',
    iconSlug: null,
    apiToolId: null,
    integrationName: null,
    accepts: ['prompt'],
    required: [],
    limits: {},
    billedOn: null,
    measuredUnit: null,
    defaultQuantity: null,
    price: { unit: 'call', baseCredits: '0', unitCredits: '0' },
    async: false,
    ...overrides,
  };
}

describe('buildParamSpec', () => {
  it('offers nothing at all when there is no model yet', () => {
    expect(buildParamSpec(null)).toEqual([]);
    expect(buildParamSpec(undefined)).toEqual([]);
  });

  it('leaves the prompt out - every surface gives it its own place', () => {
    const spec = buildParamSpec(model({ accepts: ['prompt', 'seed'] }));
    expect(spec.map((f) => f.name)).toEqual(['seed']);
  });

  it('only offers what the model accepts, because anything else is refused', () => {
    // aspect_ratio is a real unified parameter, but not for THIS model.
    const spec = buildParamSpec(model({ accepts: ['prompt', 'seed'] }));
    expect(spec.map((f) => f.name)).not.toContain('aspect_ratio');
  });

  it('types a parameter with declared values as a closed choice', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'aspect_ratio'],
      limits: { aspect_ratio: { allowed: ['1:1', '16:9'] } },
    }));
    expect(spec[0]).toMatchObject({
      name: 'aspect_ratio',
      kind: 'choice',
      choices: ['1:1', '16:9'],
      choicesAreSuggestions: false,
    });
  });

  it('marks a suggested list as suggestions, so a surface does not forbid accepted values', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'style'],
      limits: { style: { allowed: ['anime'], allowedEnforced: false } },
    }));
    expect(spec[0].choicesAreSuggestions).toBe(true);
  });

  it('keeps a fetch-only parameter a choice with no values yet, not a dead empty dropdown', () => {
    // An ElevenLabs voice belongs to the account holding the key: the values exist, but only the
    // provider can name them. Typing it as free text would lose the fetch; shipping it as an empty
    // closed choice would be a control with nothing in it.
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'voice'],
      limits: { voice: { optionsAvailable: true } },
    }));
    expect(spec[0]).toMatchObject({ name: 'voice', kind: 'choice', optionsMustBeFetched: true });
    expect(spec[0].choices).toEqual([]);
  });

  it('types the numeric parameters as numbers and the rest as text', () => {
    const spec = buildParamSpec(model({ accepts: ['prompt', 'seed', 'negative_prompt'] }));
    expect(spec.find((f) => f.name === 'seed')?.kind).toBe('number');
    expect(spec.find((f) => f.name === 'negative_prompt')?.kind).toBe('text');
  });

  it('draws one picker per file the model takes, carrying what each file IS to it', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'input_image'],
      inputs: { input_image: { role: 'first_frame', maxItems: 3 } },
    }));
    expect(spec[0]).toMatchObject({
      name: 'input_image', kind: 'asset', slots: 3, role: 'first_frame', accept: 'image/*',
    });
  });

  it('gives an asset parameter one slot when the model does not say how many', () => {
    const spec = buildParamSpec(model({ accepts: ['prompt', 'input_audio'] }));
    expect(spec[0]).toMatchObject({ name: 'input_audio', slots: 1 });
  });

  it('caps the slots a descriptor can ask for', () => {
    // maxItems comes from a provider descriptor. Trusting it without bound draws whatever number
    // lands there, on screen, for as long as the descriptor says so.
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'input_image'],
      inputs: { input_image: { role: 'source_image', maxItems: 400 } },
    }));
    expect(spec[0].slots).toBe(MAX_ASSET_SLOTS);
  });

  it('never draws fewer than one slot, even on a descriptor claiming zero', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'input_image'],
      inputs: { input_image: { role: 'source_image', maxItems: 0 } },
    }));
    expect(spec[0].slots).toBe(1);
  });

  it('marks required parameters, which is what blocks a submit', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'input_image', 'seed'],
      required: ['input_image'],
    }));
    expect(spec.find((f) => f.name === 'input_image')?.required).toBe(true);
    expect(spec.find((f) => f.name === 'seed')?.required).toBe(false);
  });

  it('puts the files first: on a model whose subject is an image, the image leads', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'aspect_ratio', 'seed', 'negative_prompt', 'input_image'],
      limits: { aspect_ratio: { allowed: ['1:1'] } },
    }));
    expect(spec.map((f) => f.name)).toEqual([
      'input_image', 'aspect_ratio', 'seed', 'negative_prompt',
    ]);
  });
});

describe('assetFields / totalAssetSlots', () => {
  it('reports no file slots for a model that takes no file', () => {
    // This is what removes the attachment control entirely: offering one here can only produce a
    // refusal.
    const spec = buildParamSpec(model({ accepts: ['prompt', 'seed'] }));
    expect(assetFields(spec)).toEqual([]);
    expect(totalAssetSlots(spec)).toBe(0);
  });

  it('counts every slot across every file parameter', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'input_image', 'input_audio'],
      inputs: {
        input_image: { role: 'first_frame', maxItems: 2 },
        input_audio: { role: 'source_audio', maxItems: 1 },
      },
    }));
    expect(assetFields(spec).map((f) => f.name)).toEqual(['input_image', 'input_audio']);
    expect(totalAssetSlots(spec)).toBe(3);
  });
});

describe('missingRequired', () => {
  const spec = buildParamSpec(model({
    accepts: ['prompt', 'input_image', 'voice', 'seed'],
    required: ['input_image', 'voice'],
    inputs: { input_image: { role: 'source_image', maxItems: 4 } },
    limits: { voice: { optionsAvailable: true } },
  }));

  it('names every required field still empty', () => {
    expect(missingRequired(spec, {}, {}).sort()).toEqual(['input_image', 'voice']);
  });

  it('is satisfied by the FIRST file only - a 4-file parameter requires one', () => {
    const missing = missingRequired(spec, { voice: 'aria' }, { input_image: [{ id: 'f1' }] });
    expect(missing).toEqual([]);
  });

  it('is not satisfied by a later slot when the first is empty', () => {
    const missing = missingRequired(spec, { voice: 'aria' }, { input_image: [undefined, { id: 'f2' }] });
    expect(missing).toEqual(['input_image']);
  });

  it('treats whitespace as empty - a space is not an answer', () => {
    const missing = missingRequired(spec, { voice: '   ' }, { input_image: [{ id: 'f1' }] });
    expect(missing).toEqual(['voice']);
  });

  it('ignores optional fields left empty', () => {
    const missing = missingRequired(spec, { voice: 'aria' }, { input_image: [{ id: 'f1' }] });
    expect(missing).not.toContain('seed');
  });
});

/**
 * What a turn actually SENDS.
 *
 * <p>These are the assertions that carry money, and they are made here rather than through the
 * composer for a reason found the hard way: the composer filters at three points (on reuse, on model
 * change, on submit) and any two of them MASK the third, so a component test could delete any one
 * filter and still pass. Handed the state directly, each rule is reachable.
 */
describe('buildSubmissionParams', () => {
  const spec = buildParamSpec(model({
    accepts: ['prompt', 'seed', 'aspect_ratio', 'input_image'],
    limits: { aspect_ratio: { allowed: ['1:1', '16:9'] } },
    inputs: { input_image: { role: 'source', maxItems: 2 } },
  }));

  it('drops a value the model does not declare', () => {
    // The platform REFUSES an undeclared parameter, and the refusal names nothing the reader can act
    // on: the turn fails with its cause nowhere on screen. A value can reach here from a reused
    // recipe made on another model, or from a model switch.
    const params = buildSubmissionParams(spec, { seed: '7', resolution: '4k' }, {});
    expect(params).toEqual({ seed: 7 });
  });

  it('casts a declared numeric parameter, and drops one that is not a number', () => {
    // Number('abc') is NaN, which JSON.stringify writes as null: the provider then refuses a value
    // the reader never typed.
    expect(buildSubmissionParams(spec, { seed: '7' }, {})).toEqual({ seed: 7 });
    expect(buildSubmissionParams(spec, { seed: 'abc' }, {})).toEqual({});
  });

  it('drops empty and whitespace values rather than sending them', () => {
    expect(buildSubmissionParams(spec, { aspect_ratio: '   ', seed: '' }, {})).toEqual({});
  });

  it('packs the files of a declared parameter, holes removed', () => {
    const a = { id: 'a' };
    const b = { id: 'b' };
    expect(buildSubmissionParams(spec, {}, { input_image: [undefined, a, b] }))
      .toEqual({ input_image: [a, b] });
  });

  it('caps the files at what the model takes', () => {
    // More handles than the parameter accepts is a refusal, and the reader paid nothing to learn it.
    const files = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
    expect(buildSubmissionParams(spec, {}, { input_image: files }))
      .toEqual({ input_image: [files[0], files[1]] });
  });

  it('drops files under a parameter the model does not declare', () => {
    // The state a model SWITCH leaves behind: a file attached on the previous model.
    expect(buildSubmissionParams(spec, {}, { input_audio: [{ id: 'a' }] })).toEqual({});
  });

  it('sends nothing for an empty composer', () => {
    expect(buildSubmissionParams(spec, {}, {})).toEqual({});
  });
});

describe('hasSubmittableInput', () => {
  const imageOnly = buildParamSpec(model({
    accepts: ['prompt', 'input_image'],
    inputs: { input_image: { role: 'source', maxItems: 1 } },
  }));
  const noFiles = buildParamSpec(model({ accepts: ['prompt', 'seed'] }));

  it('is true for a file the model takes', () => {
    expect(hasSubmittableInput(imageOnly, {}, { input_image: [{ id: 'a' }] })).toBe(true);
  });

  it('is FALSE for a file left over from another model', () => {
    // Counting raw state instead would let a file attached on the previous model unblock the send
    // button on one that takes none - and the turn would go out with an empty prompt.
    expect(hasSubmittableInput(noFiles, {}, { input_image: [{ id: 'a' }] })).toBe(false);
  });

  it('is false for an empty composer', () => {
    expect(hasSubmittableInput(imageOnly, {}, {})).toBe(false);
  });
});
