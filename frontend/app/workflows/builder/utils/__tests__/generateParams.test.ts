import { describe, expect, it } from 'vitest';
import {
  buildGeneratePlanParams,
  DEFAULT_CREDENTIAL_SOURCE,
  extractGenerateDataFromPlanParams,
  isCredentialSource,
  platformQuantityFor,
} from '../generateParams';

/**
 * Plan round trip and price-size derivation for the `agent:generate` node.
 *
 * <p>The round trip matters because a stringified number would change what the
 * platform bills the run on, and a dropped parameter would silently change what
 * a saved workflow generates.
 */
describe('buildGeneratePlanParams', () => {
  it('always emits `model`, even empty, so validation can flag a node with none', () => {
    expect(buildGeneratePlanParams(undefined, undefined, undefined))
      .toEqual({ model: '', credential_source: DEFAULT_CREDENTIAL_SOURCE });
  });

  it('keeps numbers as numbers: a stringified duration would change the size billed', () => {
    const params = buildGeneratePlanParams('seedance-2.0-fast', undefined, {
      prompt: 'a boat',
      duration_seconds: 10,
      n: 2,
    });

    expect(params).toEqual({
      model: 'seedance-2.0-fast',
      prompt: 'a boat',
      duration_seconds: 10,
      n: 2,
      credential_source: DEFAULT_CREDENTIAL_SOURCE,
    });
    expect(typeof params.duration_seconds).toBe('number');
  });

  it('drops blank strings so an untouched control never reaches the provider as an empty value', () => {
    const params = buildGeneratePlanParams('m', undefined, {
      prompt: 'hello',
      voice: '',
      style: '   ',
      language: null,
    });

    expect(params).toEqual({ model: 'm', prompt: 'hello', credential_source: 'platform' });
  });

  it('emits a valid credential source, and falls back to the one the inspector shows', () => {
    expect(buildGeneratePlanParams('m', 'user', {}).credential_source).toBe('user');
    expect(buildGeneratePlanParams('m', 'platform', {}).credential_source).toBe('platform');
  });

  it('states the source even when the author never touched the control', () => {
    // The inspector defaults the pill to the platform key and quotes the
    // platform price next to it. Leaving the field out of the plan does not
    // mean "same thing by default": the backend reads an absent source as "try
    // the author's own key first", so the node would run on a provider account
    // the author never chose here and the platform would bill nothing, while
    // the builder had just shown them a price. Displayed and planned must be
    // the same value.
    expect(buildGeneratePlanParams('m', undefined, {}).credential_source)
      .toBe(DEFAULT_CREDENTIAL_SOURCE);
    expect(buildGeneratePlanParams('m', 'borrowed', {}).credential_source)
      .toBe(DEFAULT_CREDENTIAL_SOURCE);
  });

  it('never lets a stray control key inside generateParams shadow the node config', () => {
    const params = buildGeneratePlanParams('real-model', 'user', {
      model: 'sneaky-model',
      credential_source: 'platform',
    });

    expect(params.model).toBe('real-model');
    expect(params.credential_source).toBe('user');
  });

  it('carries WHICH own key the author pinned into the plan', () => {
    // The inspector has offered this choice since the node shipped, but nothing
    // wrote it into the plan, so the picked key was ignored at run time and the
    // account's default ran instead.
    const params = buildGeneratePlanParams('m', 'user', { prompt: 'a boat' }, 42);

    expect(params.credential_id).toBe(42);
  });

  it('omits the pinned key on the PLATFORM branch, which never consults one', () => {
    // The platform's own key answers the call there, so an id of the author's
    // states a choice no run can honour. Writing it anyway leaves the plan
    // naming a key nothing uses, which is the same "the picker chose something
    // the run ignores" the whole feature exists to remove.
    expect(buildGeneratePlanParams('m', 'platform', { prompt: 'x' }, 42))
      .not.toHaveProperty('credential_id');
    expect(buildGeneratePlanParams('m', undefined, { prompt: 'x' }, 42))
      .not.toHaveProperty('credential_id');
  });

  it('omits the pinned key when there is none, rather than writing a null to interpret', () => {
    expect(buildGeneratePlanParams('m', 'user', { prompt: 'x' })).not.toHaveProperty('credential_id');
    expect(buildGeneratePlanParams('m', 'user', { prompt: 'x' }, null)).not.toHaveProperty('credential_id');
    // Not an id: a stale zero or a negative would travel as a credential the
    // account does not own, and the run would be refused for something the
    // author never chose.
    expect(buildGeneratePlanParams('m', 'user', { prompt: 'x' }, 0)).not.toHaveProperty('credential_id');
  });

  it('carries a whole FileRef object through untouched', () => {
    const fileRef = { _type: 'file', path: 'tenant/a.png', name: 'a.png', mimeType: 'image/png' };
    const params = buildGeneratePlanParams('m', undefined, { input_image: fileRef });

    expect(params.input_image).toEqual(fileRef);
  });
});

describe('extractGenerateDataFromPlanParams', () => {
  it('splits the node config from the generation params', () => {
    const data = extractGenerateDataFromPlanParams({
      model: 'seedance-2.0-fast',
      credential_source: 'platform',
      prompt: 'a boat',
      duration_seconds: 10,
    });

    expect(data.generateModel).toBe('seedance-2.0-fast');
    expect(data.generateCredentialSource).toBe('platform');
    expect(data.generateParams).toEqual({ prompt: 'a boat', duration_seconds: 10 });
  });

  it('keeps a parameter it has no control for, instead of dropping it on the next save', () => {
    // The accepted vocabulary lives in the generation catalog. Pruning to a local
    // list would silently rewrite a workflow that used a newer dimension.
    const data = extractGenerateDataFromPlanParams({
      model: 'm',
      some_new_dimension: 'value',
    });

    expect(data.generateParams.some_new_dimension).toBe('value');
  });

  it('ignores an invalid credential source rather than importing it', () => {
    const data = extractGenerateDataFromPlanParams({ model: 'm', credential_source: 'borrowed' });

    expect(data.generateCredentialSource).toBeUndefined();
    expect(data.generateParams).not.toHaveProperty('credential_source');
  });

  it('round-trips build -> extract -> build without losing or retyping anything', () => {
    const original = buildGeneratePlanParams('seedance-2.0-fast', 'platform', {
      prompt: 'a boat',
      duration_seconds: 10,
      aspect_ratio: '16:9',
    }, 42);

    const data = extractGenerateDataFromPlanParams(original);
    const rebuilt = buildGeneratePlanParams(
      data.generateModel,
      data.generateCredentialSource,
      data.generateParams,
      data.selectedCredentialId,
    );

    expect(rebuilt).toEqual(original);
  });

  it('reads back WHICH key the node runs on, so reopening it shows the one it will use', () => {
    const data = extractGenerateDataFromPlanParams({
      model: 'm', credential_source: 'user', credential_id: 42, prompt: 'a boat',
    });

    expect(data.selectedCredentialId).toBe(42);
    // A control key, never a generation parameter: projected onto the
    // provider's request it would be refused as an unknown field.
    expect(data.generateParams).not.toHaveProperty('credential_id');
  });

  it('leaves an unusable pinned id out entirely, which is the account default on both sides', () => {
    // The picker then lands on the default, and so does the run: an id the
    // account no longer owns must not survive as a choice on either side.
    expect(extractGenerateDataFromPlanParams({ model: 'm', credential_id: 0 }))
      .not.toHaveProperty('selectedCredentialId');
    expect(extractGenerateDataFromPlanParams({ model: 'm', credential_id: 'abc' }))
      .not.toHaveProperty('selectedCredentialId');
  });

  it('tolerates an absent params map', () => {
    expect(extractGenerateDataFromPlanParams(undefined)).toEqual({ generateParams: {} });
  });
});

describe('platformQuantityFor', () => {
  it('a per-second model bills on the duration', () => {
    expect(platformQuantityFor('second', { duration_seconds: 10 })).toBe(10);
  });

  it('a per-minute model reports SECONDS, because the server converts and the estimate must not', () => {
    // Converting here quoted 1.5 minutes while the billing path sent 90
    // seconds, so the estimate and the invoice were reached by two different
    // sums. The quote answers with the unit it priced in; this only measures.
    expect(platformQuantityFor('minute', { duration_seconds: 90 })).toBe(90);
  });

  it('a per-image model bills on how many were asked for', () => {
    expect(platformQuantityFor('image', { n: 4 })).toBe(4);
  });

  it('a per-character model bills on the length of the prompt', () => {
    expect(platformQuantityFor('character', { prompt: 'hello' })).toBe(5);
  });

  it('a per-call model is always one call', () => {
    expect(platformQuantityFor('call', {})).toBe(1);
  });

  it('returns null when the driving parameter is not set, so the surface shows the rate not a wrong total', () => {
    expect(platformQuantityFor('second', {})).toBeNull();
    // and with no default to fall back on either
    expect(platformQuantityFor('second', {}, null)).toBeNull();
    expect(platformQuantityFor('second', { duration_seconds: '' })).toBeNull();
    expect(platformQuantityFor('image', {})).toBeNull();
    expect(platformQuantityFor('character', { prompt: '' })).toBeNull();
  });

  it('returns null for an unknown unit rather than guessing a size', () => {
    expect(platformQuantityFor('parsec', { duration_seconds: 10 })).toBeNull();
    expect(platformQuantityFor(undefined, { duration_seconds: 10 })).toBeNull();
  });

  it('reads a numeric string, because a form control hands back text', () => {
    expect(platformQuantityFor('second', { duration_seconds: '10' })).toBe(10);
  });

  it('refuses a negative or non-numeric size instead of quoting a nonsense price', () => {
    expect(platformQuantityFor('second', { duration_seconds: -5 })).toBeNull();
    expect(platformQuantityFor('second', { duration_seconds: 'ten' })).toBeNull();
  });

  it('never prices a TEMPLATE, since the size is whatever it resolves to at run time', () => {
    // The prompt control is an expression editor, so a template is ordinary
    // input. Counting its characters quotes the placeholder rather than the
    // text, and it looks like a real answer: the numeric dimensions escape
    // this only because Number() of a template happens to be NaN.
    expect(platformQuantityFor('character', { prompt: '{{trigger:chat.output.message}}' })).toBeNull();
    expect(platformQuantityFor('character', { prompt: 'hi {{name}}' })).toBeNull();
    expect(platformQuantityFor('second', { duration_seconds: '{{x}}' })).toBeNull();
    // A literal prompt is still priced, so the guard is not a blanket silence.
    expect(platformQuantityFor('character', { prompt: 'hello' })).toBe(5);
  });

  it('falls back to the size the RUN would use, so the price is not silent on an empty form', () => {
    // Nothing typed is the state an author is in while deciding whether they
    // can afford the node, and the run does NOT treat it as zero: it bills the
    // model's default. Quoting nothing there hides a price that is perfectly
    // knowable, which was the whole reason the listing ships this number.
    expect(platformQuantityFor('second', {}, '5')).toBe(5);
    expect(platformQuantityFor('second', { duration_seconds: '' }, 5)).toBe(5);
    expect(platformQuantityFor('image', {}, '1')).toBe(1);
  });

  it('prefers what was typed over the default, and never rescues an INVALID value', () => {
    // A typed value wins: the author said how big it is.
    expect(platformQuantityFor('second', { duration_seconds: 10 }, '5')).toBe(10);
    // A typed but unusable value is a call that will not run, so quoting the
    // default price for it would describe a different call than the one on
    // screen.
    expect(platformQuantityFor('second', { duration_seconds: 'ten' }, '5')).toBeNull();
    expect(platformQuantityFor('second', { duration_seconds: -5 }, '5')).toBeNull();
    // A zero or unparseable default is not a size either.
    expect(platformQuantityFor('second', {}, '0')).toBeNull();
    expect(platformQuantityFor('second', {}, 'five')).toBeNull();
  });
});

describe('isCredentialSource', () => {
  it('accepts only the two real pools', () => {
    expect(isCredentialSource('user')).toBe(true);
    expect(isCredentialSource('platform')).toBe(true);
    expect(isCredentialSource('borrowed')).toBe(false);
    expect(isCredentialSource(undefined)).toBe(false);
  });
});
