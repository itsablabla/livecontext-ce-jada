// @vitest-environment jsdom
/**
 * What the studio tells a reader a generation will cost, before they press the button that buys it.
 *
 * <p>Two properties are pinned here, and both are about a number being wrong in the direction that
 * costs the reader rather than the platform.
 *
 * <ul>
 *   <li><b>Rounding is UP.</b> A per-character model is quoted on a bucketed length, because the
 *       exact one changes on every keystroke and would mint a query key per key press. Rounding
 *       down would quote every prompt as if it were shorter than it is, on every model billed that
 *       way, silently and for ever. Up over-states by less than one bucket, which is the safe way
 *       to be imprecise about someone else's money.
 *   <li><b>A quote that no longer matches the request says so.</b> The quantity is debounced, so
 *       between a change and the answer the amount in hand belongs to the PREVIOUS request. Paste a
 *       long prompt, press send inside the window, and the figure beside the button is the one for
 *       the shorter prompt. `stale` is what stops it being presented as fact.
 * </ul>
 *
 * <p>Both were unguarded: mutating either left the whole frontend suite green, because the composer
 * tests mock this hook away entirely and nothing else asked it anything.
 */
import '@testing-library/jest-dom/vitest';
import * as React from 'react';
import { renderHook, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The query layer, reduced to what this hook reads back. `isFetching` is separate from `isFetched`
// on purpose: a refetch for a NEW quantity is exactly the window in which the old amount is stale.
const queryState = vi.hoisted(() => ({
  data: undefined as unknown,
  isFetched: true,
  isError: false,
  isFetching: false,
  lastKey: [] as readonly unknown[],
}));
// Typed on its arguments, so the third one - the options object that carries the quantity, the
// generation flag and the unit - is reachable in an assertion rather than a tuple index error.
const askPrice = vi.hoisted(() => vi.fn(
  async (_integration: string, _apiToolId: string | null, _opts: Record<string, unknown>) => ({}),
));
vi.mock('@tanstack/react-query', () => ({
  useQuery: ({ queryKey, queryFn, enabled }: {
    queryKey: readonly unknown[]; queryFn?: () => unknown; enabled?: boolean;
  }) => {
    queryState.lastKey = queryKey;
    // The queryFn is RUN, not merely held.
    //
    // A double that returns canned data and never calls it leaves the actual REQUEST untested,
    // and the request is a different expression from the key: quoting the debounced quantity in
    // the key while sending something else in the body would read correctly in every key-based
    // assertion and still put the wrong number in front of the reader. `enabled` is honoured so
    // the hook's own "nothing to quote" rule is exercised rather than bypassed.
    if (enabled !== false) { try { queryFn?.(); } catch { /* the canned answer is what renders */ } }
    return {
      data: queryState.data,
      isFetched: queryState.isFetched,
      isError: queryState.isError,
      isFetching: queryState.isFetching,
    };
  },
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getPlatformCredentialPublicInfo: askPrice } }));

import { useGenerationQuote } from '../useGenerationQuote';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

/** A model billed per CHARACTER, which is the only kind whose quantity is bucketed. */
function perCharacterModel(): GenerationModel {
  return {
    model: 'eleven-v3', kind: 'audio', label: 'Eleven v3', provider: 'elevenlabs',
    iconSlug: null, apiToolId: 't1', integrationName: 'elevenlabs',
    accepts: ['prompt'], required: [], limits: {},
    billedOn: 'prompt', measuredUnit: 'character', defaultQuantity: null,
    price: { unit: 'character', baseCredits: '0', unitCredits: '1' }, async: false,
  } as unknown as GenerationModel;
}

/** The quantity the hook actually asked about: the 5th segment of the query key. */
function quotedQuantity(): unknown {
  return queryState.lastKey[4];
}

beforeEach(() => {
  vi.useFakeTimers();
  queryState.data = undefined;
  queryState.isFetched = true;
  queryState.isError = false;
  queryState.isFetching = false;
  queryState.lastKey = [];
  askPrice.mockClear();
});
afterEach(() => {
  vi.useRealTimers();
});

/** Render the hook and let the 600 ms debounce settle, so the quote is for what was typed. */
function renderSettled(model: GenerationModel, source: Record<string, unknown>) {
  const view = renderHook(
    ({ s }: { s: Record<string, unknown> }) => useGenerationQuote(model, s),
    { initialProps: { s: source } },
  );
  act(() => { vi.advanceTimersByTime(700); });
  return view;
}

describe('useGenerationQuote - rounding a character count', () => {
  it('rounds UP, so the amount shown is never less than what will be charged', () => {
    // 51 characters must not be quoted as 50. Rounding down understates every prompt on every
    // per-character model, and understating is the only direction a reader can be hurt by.
    renderSettled(perCharacterModel(), { prompt: 'x'.repeat(51) });

    expect(quotedQuantity()).toBe(100);
  });

  it('never quotes below one bucket, even for a single character', () => {
    renderSettled(perCharacterModel(), { prompt: 'x' });

    expect(quotedQuantity()).toBe(50);
  });

  it('does not round a quantity the reader chose in whole steps', () => {
    // A duration in seconds or a count of images moves in deliberate increments and each one is
    // worth an exact quote. Bucketing those would misprice a call the reader configured precisely.
    const seconds = { ...perCharacterModel(), measuredUnit: 'second',
      price: { unit: 'second', baseCredits: '0', unitCredits: '1' } } as unknown as GenerationModel;

    renderSettled(seconds, { duration_seconds: 6 });

    expect(quotedQuantity()).toBe(6);
  });
});

describe('useGenerationQuote - whether the amount in hand is still the right one', () => {
  it('is not stale once the quantity has settled and nothing is in flight', () => {
    const { result } = renderSettled(perCharacterModel(), { prompt: 'x'.repeat(60) });

    expect(result.current.stale).toBe(false);
  });

  it('is stale while a newly typed quantity has not reached the query yet', () => {
    // The reachable-by-hand case: paste a long prompt and press send inside the debounce window.
    // The amount still on screen is the one for the previous, SHORTER prompt.
    const { result, rerender } = renderSettled(perCharacterModel(), { prompt: 'x'.repeat(10) });
    expect(result.current.stale).toBe(false);

    rerender({ s: { prompt: 'x'.repeat(2000) } });

    expect(result.current.stale).toBe(true);
  });

  it('stops being stale once the new quantity has been asked about', () => {
    const { result, rerender } = renderSettled(perCharacterModel(), { prompt: 'x'.repeat(10) });
    rerender({ s: { prompt: 'x'.repeat(2000) } });

    act(() => { vi.advanceTimersByTime(700); });

    expect(result.current.stale).toBe(false);
    expect(quotedQuantity()).toBe(2000);
  });

  it('is stale while the request for the current quantity is still in flight', () => {
    // Same reason, a step later: the quantity has arrived but the answer has not, so the amount
    // being displayed is still the previous one.
    queryState.isFetching = true;

    const { result } = renderSettled(perCharacterModel(), { prompt: 'x'.repeat(60) });

    expect(result.current.stale).toBe(true);
  });

  it('is never stale for a model with nothing to quote', () => {
    // No integration means no question is ever asked, so there is no answer to be waiting for.
    // Reporting stale here would dim a price that is as final as it will ever be.
    const noIntegration = { ...perCharacterModel(), integrationName: null } as unknown as GenerationModel;
    queryState.isFetching = true;

    const { result } = renderSettled(noIntegration, { prompt: 'x'.repeat(60) });

    expect(result.current.stale).toBe(false);
  });
});

describe('useGenerationQuote - settled, which is not the same as priced', () => {
  it('is settled for a model with no integration, because nothing will ever be asked', () => {
    const noIntegration = { ...perCharacterModel(), integrationName: null } as unknown as GenerationModel;
    queryState.isFetched = false;

    const { result } = renderSettled(noIntegration, { prompt: 'hi' });

    expect(result.current.settled).toBe(true);
  });

  it('is settled when the question was asked and ERRORED', () => {
    // "There is no published price" is an answer, and the composer states it. Leaving this
    // unsettled would show nothing at all beside a button that spends credits.
    queryState.isFetched = false;
    queryState.isError = true;

    const { result } = renderSettled(perCharacterModel(), { prompt: 'hi' });

    expect(result.current.settled).toBe(true);
  });

  it('is NOT settled while the first answer is still outstanding', () => {
    queryState.isFetched = false;
    queryState.isError = false;

    const { result } = renderSettled(perCharacterModel(), { prompt: 'hi' });

    expect(result.current.settled).toBe(false);
  });
});

describe('useGenerationQuote - the quantity it hands back', () => {
  it('returns the quantity it QUOTED, not the one currently typed', () => {
    // The payer control prices the same call from this number. Handing back the live value would
    // key a second cache entry and put two different amounts for one generation on one screen.
    const { result, rerender } = renderSettled(perCharacterModel(), { prompt: 'x'.repeat(10) });
    rerender({ s: { prompt: 'x'.repeat(2000) } });

    expect(result.current.quantity).toBe(50);
  });
});

describe('useGenerationQuote - the request it actually sends', () => {
  /**
   * The query KEY and the request BODY are two separate expressions in the hook, so asserting the
   * key proves nothing about what the server is asked. Corrupting all three of the body's fields at
   * once - quantity, generation, quantityUnit - left the entire CI frontend selection green before
   * these tests existed, because no double ever ran the function that builds it.
   *
   * <p>Each field is worth its own assertion because each one is a different wrong price: the wrong
   * quantity misprices the size of the call, `generation: false` prices it at the credential-wide
   * default instead of the model's published generation rate, and the wrong unit lets a rate of one
   * dimension price a call counted in another.
   */
  const price = () => askPrice.mock.calls[0]?.[2] as Record<string, unknown> | undefined;

  it('asks about the quantity it QUOTED, not a default one', () => {
    // A per-second model: 10 seconds must be asked about as 10. Sending 1 would quote a
    // one-second clip beside a button that buys ten.
    const seconds = { ...perCharacterModel(), measuredUnit: 'second',
      price: { unit: 'second', baseCredits: '0', unitCredits: '1' } } as unknown as GenerationModel;

    renderSettled(seconds, { duration_seconds: 10 });

    expect(price()).toMatchObject({ quantity: 10 });
  });

  it('says the call is a GENERATION, or it is priced at the wrong rate entirely', () => {
    // Every row of this catalogue is a generation, and a generation is never sold on the
    // credential-wide default. Dropping this returns a rate for a different kind of call.
    renderSettled(perCharacterModel(), { prompt: 'hello' });

    expect(price()).toMatchObject({ generation: true });
  });

  it('names the unit the call is COUNTED in, so a mismatched rate can be refused', () => {
    // The published row can then refuse a rate that cannot price this call, instead of returning
    // an amount the run is then charged differently for.
    renderSettled(perCharacterModel(), { prompt: 'hello' });

    expect(price()).toMatchObject({ quantityUnit: 'character' });
  });

  it('asks about the model actually selected', () => {
    renderSettled(perCharacterModel(), { prompt: 'hello' });

    expect(askPrice).toHaveBeenCalledWith('elevenlabs', 't1', expect.objectContaining({
      modelId: 'eleven-v3',
    }));
  });

  it('sends the SETTLED quantity, never the one still being typed', () => {
    // The debounce exists so a per-character model is not re-quoted on every keystroke, and the
    // body must honour it as the key does. If the body read the LIVE value while the key read the
    // debounced one, the cache would be keyed on one number and filled with the price of another -
    // and the amount beside the button would belong to neither.
    const { rerender } = renderSettled(perCharacterModel(), { prompt: 'x'.repeat(10) });
    askPrice.mockClear();

    rerender({ s: { prompt: 'x'.repeat(2000) } });

    // Still inside the 600 ms window, so every request made in it is for the OLD bucket.
    for (const call of askPrice.mock.calls) {
      expect(call[2]).toMatchObject({ quantity: 50 });
    }
  });

  it('asks NOTHING for a model whose API has no platform credential', () => {
    // There is no platform rate to quote, and asking would 404 on every keystroke that moves the
    // quantity.
    const noIntegration = { ...perCharacterModel(), integrationName: null } as unknown as GenerationModel;

    renderSettled(noIntegration, { prompt: 'hello' });

    expect(askPrice).not.toHaveBeenCalled();
  });
});
