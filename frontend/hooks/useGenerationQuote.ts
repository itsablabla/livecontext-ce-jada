'use client';

import * as React from 'react';
import { useQuery } from '@tanstack/react-query';
import { orchestratorApi } from '@/lib/api';
import { platformQuantityFor } from '@/app/workflows/builder/utils/generateParams';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';
import type { PlatformCredentialPublicInfo } from '@/lib/api/orchestrator/types';

/**
 * What the NEXT generation on this model will cost, from the published price.
 *
 * <p><b>Why the quote and not the model's own rate.</b> The catalogue ships a list rate with each
 * model; what is charged comes from the pricing version an administrator published. Showing the
 * first would have this screen state one number and the invoice state another.
 *
 * <p><b>Why only the selected model.</b> The dialog this replaces quoted every model in a format at
 * once, which is one request per row. On a composer the choice is already made when the price
 * matters, and the reader can change model and look again. One model, one request, and it is the
 * SAME request the payer control makes, so the two cost one between them.
 *
 * <p>The key deliberately matches the workflow inspector's, so a model already quoted there is
 * served from cache instead of re-asked - and the quantity it returns is the one that fed that key,
 * so any other surface quoting the same call lands on the same entry.
 */
export function useGenerationQuote(
  model: GenerationModel | null,
  /** What is currently typed, so the quote sizes THIS call rather than a default one. */
  quantitySource: Record<string, unknown>,
): {
  quote: PlatformCredentialPublicInfo | undefined;
  quantity: number | null;
  settled: boolean;
  /** True while the amount in hand belongs to a quantity the request has already moved past. */
  stale: boolean;
} {
  // `quantity` below is the one that feeds QUERY KEYS, and it is deliberately the debounced,
  // bucketed value rather than the exact one. Handing the exact value to another surface that
  // quotes the same call (the payer control, through CredentialSection) would key a SECOND cache
  // entry: two requests for one generation, and two amounts that can disagree on screen.
  // What the published rate multiplies. Not converted into the price's unit here: the published row
  // owns that conversion and answers in the unit it priced in.
  const exactQuantity = model
    ? platformQuantityFor(model.price?.unit, quantitySource, model.defaultQuantity)
    : null;

  // The quantity is part of the query key, and on a model billed per CHARACTER it is the prompt's
  // length - so every keystroke would mint a new key and fire a request that `staleTime` cannot
  // help. The key is therefore bucketed, and the bucket is coarse enough that ordinary typing does
  // not move it while staying fine enough that the amount on screen tracks what will be charged.
  const keyQuantity = React.useMemo(
    () => bucketQuantity(exactQuantity, model?.price?.unit),
    [exactQuantity, model?.price?.unit],
  );
  const debouncedQuantity = useDebouncedValue(keyQuantity, QUOTE_DEBOUNCE_MS);

  const { data, isFetched, isError, isFetching } = useQuery({
    queryKey: [
      'platform-credential-public-info',
      model?.integrationName?.toLowerCase() ?? '',
      model?.apiToolId ?? null,
      model?.model ?? null,
      debouncedQuantity,
      true,
      model?.measuredUnit ?? null,
    ] as const,
    queryFn: () => orchestratorApi.getPlatformCredentialPublicInfo(
      model!.integrationName as string,
      model!.apiToolId,
      // Every row of this catalogue is a generation. Stated rather than implied by the model id, so
      // the quote applies the same rule the billing path does: a generation is not sold on the
      // credential-wide default, and a rate of one dimension cannot price a call counted in another.
      {
        modelId: model!.model,
        quantity: debouncedQuantity,
        generation: true,
        quantityUnit: model!.measuredUnit,
      },
    ),
    // A model whose API has no platform credential has nothing to quote: asking would 404 on every
    // keystroke that changes the quantity.
    enabled: !!model?.integrationName,
    staleTime: 5 * 60_000,
  });

  // `settled` is the difference between "there is no published price" and "we have not asked yet",
  // and the caller needs it: silence in front of a button that spends credits is not an answer.
  // A model with no integration to quote is settled by definition - nothing will ever be asked.
  const settled = !model?.integrationName || isFetched || isError;

  // `stale` says the amount in hand was computed for a quantity the request no longer has.
  //
  // The debounce is what makes this possible: paste a long prompt into a per-character model and
  // press send inside the 600 ms, and the price still on screen is the one for the PREVIOUS bucket,
  // which is smaller. Nothing is double-charged and nothing is hidden, but the number beside the
  // button understates what the reader is about to spend, and a price is a statement about THIS
  // call. So the caller is told when it is not one yet, and stops presenting it as fact.
  //
  // A model with nothing to quote is never stale: no question is pending, so there is no answer to
  // wait for.
  const stale = !!model?.integrationName && (keyQuantity !== debouncedQuantity || isFetching);
  return { quote: data, quantity: debouncedQuantity, settled, stale };
}

/** How long the quantity must hold still before it is worth asking the server again. */
const QUOTE_DEBOUNCE_MS = 600;

/**
 * Round a character count to something that does not change on every keystroke.
 *
 * <p>Only characters are bucketed: a duration in seconds or a count of images changes in whole
 * steps a reader chose deliberately, and each of those is worth an exact quote. A prompt's length
 * changes continuously and nobody is watching the third digit of it.
 */
function bucketQuantity(quantity: number | null, unit: string | undefined): number | null {
  if (quantity == null || unit !== 'character') return quantity;
  const bucket = 50;
  return Math.max(bucket, Math.ceil(quantity / bucket) * bucket);
}

/** Hold a value still until it has stopped changing. */
function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [settled, setSettled] = React.useState(value);
  React.useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return settled;
}
