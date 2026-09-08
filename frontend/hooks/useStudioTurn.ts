'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/lib/api/api-client';
import { conversationApi, type Conversation } from '@/lib/api/conversationApi';
import {
  generationService,
  type GenerationResult,
} from '@/lib/api/orchestrator/generation.service';
import { buildStudioRequest, buildStudioResult } from '@/lib/generation/studioMessage';
import { GENERATION_HISTORY_QUERY_KEY } from '@/hooks/useGenerationHistory';

/**
 * One studio turn, from the words to the stored asset, written into a conversation.
 *
 * <p><b>What a studio conversation is.</b> Not a dialogue. Every turn is submitted to a generation
 * model on its own, with nothing carried over from the turn before it - the conversation exists so
 * the work can be found again, not so a model can remember it. That is why this hook takes the
 * model on every call instead of holding one: the reader may change model between turns, and each
 * turn records the model that actually ran it.
 *
 * <p><b>The ordering is the substance.</b> The request is written to the conversation BEFORE the
 * provider is called. A generation is a purchase that can take minutes, and the connection can be
 * lost while the server finishes, stores the asset and commits the charge. Writing the request
 * first means that history shows what was asked for even when the answer never came back - and the
 * reader is told to look rather than invited to send it again, which would be a second charge.
 *
 * <p><b>Nothing here retries.</b> A generation is not idempotent and must be submitted exactly
 * once. That is NOT the client default - `apiClient` retries once, and so does the query client -
 * so it is switched off explicitly at each call: `generationService.execute` passes `retries: 0`
 * on the purchase itself, and the three message writes below pass it too, because a retried write
 * duplicates the row it already created. Do not remove either override on the grounds that "the
 * client does not retry": it does. If a generation does not come back, this hook's job is to say
 * so, not to send it again.
 */

export interface StudioTurnRequest {
  prompt: string;
  /** Public model id. Per call, not per hook: the model can change between turns. */
  model: string;
  /** Format produced, used to render the turn before any answer arrives. */
  kind: string;
  provider?: string;
  /** Everything else the model accepts, file handles included, exactly as it will be sent. */
  params?: Record<string, unknown>;
  /** Which pool pays. Omitted leaves the decision to the server's own default. */
  credentialSource?: 'platform' | 'user';
  /** Which of the caller's own keys runs it, when they hold several for the provider. */
  credentialId?: number | null;
  /**
   * What to call the conversation when the prompt is empty (a turn that is entirely a file and a
   * set of parameters). Passed in, not defaulted here, because it is shown to a person and this
   * hook has no translations.
   */
  fallbackTitle: string;
}

/**
 * What became of a turn. Three states, not two, because the middle one carries money.
 *
 * <ul>
 *   <li>{@code recorded} - the turn ran and its answer is in the thread. A REFUSAL the reader can
 *       act on is recorded too: it is history, not a failure of this hook.</li>
 *   <li>{@code lost} - the submission LEFT and the connection dropped. The conversation exists and
 *       holds the request; the server may be finishing it and committing the charge. A caller must
 *       treat this like a turn that happened: show the request, and never re-offer it as a draft to
 *       send again.</li>
 *   <li>{@code null} - nothing was sent and nothing was charged. Only here is the draft still
 *       worth keeping.</li>
 * </ul>
 *
 * <p>Collapsing `lost` into `null` is the mistake this shape exists to prevent: it leaves the reader
 * looking at an unchanged thread and a primed composer holding the identical prompt, which is an
 * invitation to pay twice.
 */
export type StudioTurnOutcome =
  | { status: 'recorded'; conversationId: string; result: GenerationResult }
  | { status: 'lost'; conversationId: string };

/**
 * Why a turn never reached the thread.
 *
 * <p>A CODE rather than a sentence, because the sentence belongs to the surface that shows it and
 * has to exist in every language the app ships. `detail` carries the underlying technical message
 * where there is one - shown beside the translated sentence, never instead of it.
 *
 * <ul>
 *   <li>{@code conversation_create_failed} - nothing was written and nothing was charged.</li>
 *   <li>{@code connection_lost} - the submission LEFT and the answer never came back. The server
 *       may be finishing it and charging for it, so this must never be presented as a failure the
 *       reader should retry.</li>
 *   <li>{@code refused} - the request was ANSWERED, with a status saying it was not run: an expired
 *       session, a permission, a malformed call. Nothing was charged, so the draft is kept. Folding
 *       this into `connection_lost` told the reader their generation might have been billed and
 *       threw away their words and their uploaded files, for a request that provably never ran.</li>
 *   <li>{@code unexpected} - anything else, including a failure to write the answer down after a
 *       generation that did run.</li>
 * </ul>
 *
 * <p>Distinct from a turn that was refused and RECORDED: a refusal the reader can act on (no
 * credits, an unpublished price) comes back as a result envelope and is rendered in the thread.
 * These three never reach the thread, so they have nowhere else to be shown.
 */
export type StudioTurnErrorCode =
  | 'conversation_create_failed'
  | 'connection_lost'
  | 'refused'
  | 'unexpected';

export interface StudioTurnError {
  code: StudioTurnErrorCode;
  /** The underlying message, when there is one worth showing. Never the whole explanation. */
  detail?: string;
}

export interface UseStudioTurnState {
  /** True from the moment a turn is submitted until its answer is written down. */
  isRunning: boolean;
  /** Why the LAST turn never reached the thread, or null. */
  error: StudioTurnError | null;
  run: (request: StudioTurnRequest) => Promise<StudioTurnOutcome | null>;
  clearError: () => void;
}

/**
 * What a studio conversation is called: the words that started it.
 *
 * <p>Exported so a test can pin the shape, and so the one caller that has no words yet can supply
 * its own fallback rather than have an English constant baked in here.
 */
export function studioTitleFromPrompt(prompt: string, fallback: string): string {
  const trimmed = prompt.trim().replace(/\s+/g, ' ');
  if (!trimmed) return fallback;
  // Long enough to recognise the thread in a sidebar, short enough not to wrap.
  return trimmed.length > 60 ? `${trimmed.slice(0, 57)}...` : trimmed;
}

export function useStudioTurn(options: {
  /** The conversation to write into, or null to create one on the first turn. */
  conversationId: string | null;
  /** Called with the id of a conversation this hook created, so the caller can route to it. */
  onConversationCreated?: (conversation: Conversation) => void;
  /**
   * Called once a half of the turn has been written down.
   *
   * <p>The phase matters to the caller: after `request` the thread holds a request with no answer,
   * which on screen is indistinguishable from a turn whose answer never arrived. A caller showing
   * the running turn itself should refresh on `result` only, and show its own in-flight card until
   * then.
   */
  onTurnRecorded?: (conversationId: string, phase: 'request' | 'result') => void;
}): UseStudioTurnState {
  const { conversationId, onConversationCreated, onTurnRecorded } = options;
  const [isRunning, setIsRunning] = useState(false);
  const [error, setError] = useState<StudioTurnError | null>(null);
  const queryClient = useQueryClient();

  // A generation must be submitted exactly once. Two clicks on a slow button are two purchases, so
  // the guard is a ref rather than the isRunning state: state is a render behind.
  const inFlightRef = useRef(false);

  // Callers pass inline closures; reading them through refs keeps `run` stable so the composer does
  // not lose its handler identity on every keystroke. Assigned in an effect rather than during
  // render: a ref written while rendering is unsafe under StrictMode's double render and is
  // rejected by the repo's react-hooks lint.
  const onConversationCreatedRef = useRef(onConversationCreated);
  const onTurnRecordedRef = useRef(onTurnRecorded);
  useEffect(() => {
    onConversationCreatedRef.current = onConversationCreated;
    onTurnRecordedRef.current = onTurnRecorded;
  }, [onConversationCreated, onTurnRecorded]);

  const clearError = useCallback(() => setError(null), []);

  const run = useCallback(async (request: StudioTurnRequest): Promise<StudioTurnOutcome | null> => {
    if (inFlightRef.current) return null;
    inFlightRef.current = true;
    setIsRunning(true);
    setError(null);

    try {
      // 1. The conversation. Created on the first turn only, and tagged studio at creation because
      //    the kind is immutable - a conversation created as a chat can never become one.
      let targetId = conversationId;
      if (!targetId) {
        // Its own try/catch so the failure keeps its own name. Letting it fall to the outer catch
        // would report `unexpected`, which is the code for "we do not know what happened" - here we
        // do: no conversation exists, so nothing was written down and nothing was sent. The
        // distinction is not cosmetic, it is the difference between "safe to try again" and "look
        // at the thread before you resend".
        let created: Conversation | null = null;
        try {
          created = await conversationApi.createConversation({
            title: studioTitleFromPrompt(request.prompt, request.fallbackTitle),
            model: request.model,
            provider: request.provider ?? '',
            kind: 'studio',
          }) as Conversation;
        } catch (createError) {
          const detail = createError instanceof Error ? createError.message : String(createError);
          setError({ code: 'conversation_create_failed', detail });
          return null;
        }
        if (!created?.id) {
          setError({ code: 'conversation_create_failed' });
          return null;
        }
        targetId = created.id;
        onConversationCreatedRef.current?.(created);
      }

      // 2. The request, written down BEFORE the provider is called - see the docblock.
      await conversationApi.addMessage(targetId, {
        role: 'user',
        content: buildStudioRequest({
          prompt: request.prompt,
          model: request.model,
          kind: request.kind,
          provider: request.provider,
          params: request.params,
          credentialSource: request.credentialSource,
        }),
        model: request.model,
      }, { retries: 0 });
      onTurnRecordedRef.current?.(targetId, 'request');

      // 3. The generation itself. Resolves with success:false and a sentence for a refusal the
      //    reader can act on; only a lost connection or a broken client throws.
      let result: GenerationResult;
      try {
        result = await generationService.execute({
          model: request.model,
          // The prompt goes in only when there is one. `params` has already been narrowed to what
          // the model declares (buildSubmissionParams), and sending it a parameter it does not
          // accept is a refusal the reader cannot act on - so the one field that bypasses that
          // narrowing must not smuggle an empty string past it every turn. A model driven purely by
          // an input file (an upscaler, a transcriber) accepts no prompt at all.
          params: {
            ...(request.prompt.trim() ? { prompt: request.prompt } : {}),
            ...(request.params ?? {}),
          },
          ...(request.credentialSource ? { credential_source: request.credentialSource } : {}),
          ...(request.credentialId != null ? { credential_id: request.credentialId } : {}),
        });
      } catch (submitError) {
        const message = submitError instanceof Error ? submitError.message : String(submitError);
        // A status the server CHOSE to send is an answer, not a lost submission: the call was
        // refused before anything ran, so nothing was charged and the draft is worth keeping.
        // 408 and 429 are excluded because they say "not now", which leaves the same doubt a
        // dropped connection does.
        const status = submitError instanceof ApiError ? submitError.status : undefined;
        const answeredAndRefused = status != null && status >= 400 && status < 500
          && status !== 408 && status !== 429;
        if (answeredAndRefused) {
          // The request is ALREADY in the thread (step 2), so returning here without an answer
          // would leave it open for ever - and an open request is how this thread says "this may
          // have been charged". It was not: the server refused before anything ran. So the turn is
          // CLOSED as a refusal, which is both true and the state the reader can act on.
          //
          // Same posture as step 4: writing the note must not itself fail the turn. Nothing was
          // charged either way, and a thrown write here would report `unexpected` for a refusal we
          // have already diagnosed.
          try {
            await conversationApi.addMessage(targetId, {
              role: 'assistant',
              content: buildStudioResult({ success: false, error: message }, {
                model: request.model,
                kind: request.kind,
                provider: request.provider,
              }),
              model: request.model,
            }, { retries: 0 });
            onTurnRecordedRef.current?.(targetId, 'result');
          } catch (recordError) {
            console.warn('[studio] the refusal could not be written down', recordError);
          }
          setError({ code: 'refused', detail: message });
          return null;
        }
        // Otherwise the submission left and the server may well be finishing it and charging for
        // it. Saying "failed" would be a guess, and the guess that costs money is the one that
        // invites a resend. The request is already in the thread, so the reader can look.
        setError({ code: 'connection_lost', detail: message });
        // NOT null: the request is in the thread and may have been paid for. See StudioTurnOutcome.
        return { status: 'lost', conversationId: targetId };
      }

      // 4. The answer, recorded whether or not it produced an asset. A refusal that leaves no trace
      //    invites the reader to send the same thing again.
      //
      //    Its own try/catch, and the failure does NOT fail the turn. By this point the provider has
      //    run and the charge is committed: letting a failed note throw would discard the result -
      //    asset id included - and show a generic "something went wrong" for a generation that
      //    succeeded and was paid for. The reader gets the asset; the thread is one line short, and
      //    the next read of the conversation heals it.
      try {
        await conversationApi.addMessage(targetId, {
          role: 'assistant',
          content: buildStudioResult(result, {
            model: request.model,
            kind: request.kind,
            provider: request.provider,
          }),
          model: request.model,
        }, { retries: 0 });
      } catch (recordError) {
        console.warn('[studio] the turn ran but its answer could not be written down', recordError);
      }
      // Everything from here on happens AFTER the provider ran and the charge was committed, so it
      // is kept out of the outer catch: that catch answers `null`, which this hook defines as
      // "nothing was sent and nothing was charged", and the composer honours it by keeping the
      // draft. A caller-supplied callback throwing here would therefore invite a second purchase.
      try {
        onTurnRecordedRef.current?.(targetId, 'result');
        // A new asset changes what the generation history holds, and the history is read from the
        // files themselves. Both surfaces share one cache, so this refreshes either of them.
        if (result.success) {
          void queryClient.invalidateQueries({ queryKey: [GENERATION_HISTORY_QUERY_KEY] });
        }
      } catch (afterError) {
        console.warn('[studio] the turn ran; refreshing what shows it did not', afterError);
      }

      return { status: 'recorded', conversationId: targetId, result };
    } catch (unexpected) {
      const message = unexpected instanceof Error ? unexpected.message : String(unexpected);
      setError({ code: 'unexpected', detail: message });
      return null;
    } finally {
      inFlightRef.current = false;
      setIsRunning(false);
    }
  }, [conversationId, queryClient]);

  return { isRunning, error, run, clearError };
}
