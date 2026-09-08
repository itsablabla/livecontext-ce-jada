/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const createConversation = vi.fn();
const addMessage = vi.fn();
const execute = vi.fn();

vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: {
    createConversation: (...args: unknown[]) => createConversation(...args),
    addMessage: (...args: unknown[]) => addMessage(...args),
  },
}));

vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: { execute: (...args: unknown[]) => execute(...args) },
}));

import { ApiError } from '@/lib/api/api-client';
import { studioTitleFromPrompt, useStudioTurn } from '../useStudioTurn';
import { parseStudioEnvelope } from '@/lib/generation/studioMessage';

/**
 * A studio turn is a PURCHASE, and these tests are mostly about that.
 *
 * <p>Two invariants carry real money and are asserted here rather than left to review:
 * the request is written down before the provider is called, so a lost connection leaves a trace
 * instead of an invitation to send it again; and a turn is submitted exactly once, so a second
 * click on a slow button is not a second charge.
 */

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

const baseRequest = {
  prompt: 'a lighthouse at dusk',
  model: 'flux-1',
  kind: 'image',
  provider: 'flux',
  fallbackTitle: 'Untitled',
};

function successResult() {
  return {
    success: true,
    data: { model: 'flux-1', kind: 'image', provider: 'flux', file: { id: 'file-9' } },
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  createConversation.mockResolvedValue({ id: 'conv-new' });
  addMessage.mockResolvedValue({});
  execute.mockResolvedValue(successResult());
});

describe('useStudioTurn - creating the conversation', () => {
  it('creates a STUDIO conversation on the first turn and routes the caller to it', async () => {
    const onConversationCreated = vi.fn();
    const { result } = renderHook(
      () => useStudioTurn({ conversationId: null, onConversationCreated }),
      { wrapper },
    );

    await act(async () => { await result.current.run(baseRequest); });

    // Tagged at creation because the kind is immutable: a conversation created as a chat can never
    // become a studio one.
    expect(createConversation).toHaveBeenCalledWith(expect.objectContaining({ kind: 'studio' }));
    expect(onConversationCreated).toHaveBeenCalledWith({ id: 'conv-new' });
  });

  it('reuses the conversation it was given, instead of starting a new thread per turn', async () => {
    const { result } = renderHook(
      () => useStudioTurn({ conversationId: 'conv-existing' }), { wrapper },
    );

    await act(async () => { await result.current.run(baseRequest); });

    expect(createConversation).not.toHaveBeenCalled();
    expect(addMessage).toHaveBeenCalledWith('conv-existing', expect.anything(), expect.anything());
  });

  it('reports a failed creation without calling the provider', async () => {
    createConversation.mockResolvedValue({});
    const { result } = renderHook(() => useStudioTurn({ conversationId: null }), { wrapper });

    await act(async () => { await result.current.run(baseRequest); });

    expect(result.current.error).toEqual({ code: 'conversation_create_failed' });
    // Nothing was written and, above all, nothing was charged.
    expect(execute).not.toHaveBeenCalled();
    expect(addMessage).not.toHaveBeenCalled();
  });
});

describe('useStudioTurn - ordering', () => {
  it('writes the request BEFORE calling the provider', async () => {
    const order: string[] = [];
    addMessage.mockImplementation(async (_id: string, message: { role: string }) => {
      order.push(`message:${message.role}`);
      return {};
    });
    execute.mockImplementation(async () => { order.push('execute'); return successResult(); });

    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });
    await act(async () => { await result.current.run(baseRequest); });

    // The whole point: a generation can take minutes and the connection can drop while the server
    // finishes and charges. If the request were written after, that turn would leave no trace.
    expect(order).toEqual(['message:user', 'execute', 'message:assistant']);
  });

  it('sends the prompt as a parameter alongside the rest', async () => {
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    await act(async () => {
      await result.current.run({ ...baseRequest, params: { aspect_ratio: '16:9' } });
    });

    expect(execute).toHaveBeenCalledWith(expect.objectContaining({
      model: 'flux-1',
      params: { prompt: 'a lighthouse at dusk', aspect_ratio: '16:9' },
    }));
  });

  it('omits the prompt entirely when there is none, rather than sending an empty one', async () => {
    // `params` has already been narrowed to what the model declares; the prompt is the one field
    // that bypasses that narrowing, so it must not smuggle an empty string past it every turn. A
    // model driven purely by an input file - an upscaler, a transcriber - accepts no prompt at all,
    // and an undeclared parameter is a refusal the reader cannot act on.
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    await act(async () => {
      await result.current.run({ ...baseRequest, prompt: '   ', params: { scale: 2 } });
    });

    expect(execute).toHaveBeenCalledTimes(1);
    expect(execute.mock.calls[0][0].params).toEqual({ scale: 2 });
  });

  it('records the answer in the thread, asset included', async () => {
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });
    await act(async () => { await result.current.run(baseRequest); });

    const assistantCall = addMessage.mock.calls.find(([, m]) => m.role === 'assistant');
    expect(parseStudioEnvelope(assistantCall?.[1].content)).toMatchObject({
      role: 'result', success: true, file: { id: 'file-9' },
    });
  });

  it('records a refusal as a turn rather than dropping it', async () => {
    execute.mockResolvedValue({ success: false, error: 'Not enough credits.' });
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    await act(async () => { await result.current.run(baseRequest); });

    const assistantCall = addMessage.mock.calls.find(([, m]) => m.role === 'assistant');
    expect(parseStudioEnvelope(assistantCall?.[1].content)).toMatchObject({
      role: 'result', success: false, error: 'Not enough credits.',
    });
    // A recorded refusal is not a hook-level error: it is in the thread, where it can be read.
    expect(result.current.error).toBeNull();
  });
});

describe('useStudioTurn - submitted exactly once', () => {
  it('ignores a second call while one is in flight', async () => {
    let release: (value: unknown) => void = () => {};
    execute.mockImplementation(() => new Promise((resolve) => { release = resolve; }));

    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    let second: unknown;
    await act(async () => {
      void result.current.run(baseRequest);
      // Same tick, before any re-render: a guard living in state would still read `false` here,
      // and the second click would be a second purchase.
      second = await result.current.run(baseRequest);
    });

    expect(second).toBeNull();
    expect(execute).toHaveBeenCalledTimes(1);

    await act(async () => { release(successResult()); });
  });

  it('accepts the next turn once the previous one has finished', async () => {
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    await act(async () => { await result.current.run(baseRequest); });
    await waitFor(() => expect(result.current.isRunning).toBe(false));
    await act(async () => { await result.current.run(baseRequest); });

    expect(execute).toHaveBeenCalledTimes(2);
  });
});

describe('useStudioTurn - a lost connection is not a failure', () => {
  it('reports connection_lost and does NOT resubmit', async () => {
    execute.mockRejectedValue(new Error('socket hang up'));
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    await act(async () => { await result.current.run(baseRequest); });

    // The submission left. The server may be finishing it and charging for it, so a retry would be
    // a second generation the customer pays for.
    expect(result.current.error).toEqual({ code: 'connection_lost', detail: 'socket hang up' });
    expect(execute).toHaveBeenCalledTimes(1);
  });

  it('answers with the LOST outcome, not with nothing', async () => {
    // The distinction that carries money. `null` means "nothing was sent, keep the draft"; a lost
    // connection means the opposite, and collapsing the two leaves the reader with an unchanged
    // thread and a primed composer holding the identical prompt.
    execute.mockRejectedValue(new Error('socket hang up'));
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    let outcome: unknown;
    await act(async () => { outcome = await result.current.run(baseRequest); });

    expect(outcome).toEqual({ status: 'lost', conversationId: 'conv-1' });
  });

  it('answers with NOTHING only when nothing was sent', async () => {
    createConversation.mockResolvedValue({});
    const { result } = renderHook(() => useStudioTurn({ conversationId: null }), { wrapper });

    let outcome: unknown;
    await act(async () => { outcome = await result.current.run(baseRequest); });

    expect(outcome).toBeNull();
    expect(execute).not.toHaveBeenCalled();
  });

  it('leaves the request in the thread when the answer never arrives', async () => {
    execute.mockRejectedValue(new Error('socket hang up'));
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    await act(async () => { await result.current.run(baseRequest); });

    const userCall = addMessage.mock.calls.find(([, m]) => m.role === 'user');
    expect(parseStudioEnvelope(userCall?.[1].content)).toMatchObject({ role: 'request' });
    expect(addMessage.mock.calls.some(([, m]) => m.role === 'assistant')).toBe(false);
  });

  it('clears the error so the next turn starts clean', async () => {
    execute.mockRejectedValueOnce(new Error('socket hang up'));
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    await act(async () => { await result.current.run(baseRequest); });
    expect(result.current.error).not.toBeNull();

    act(() => { result.current.clearError(); });
    expect(result.current.error).toBeNull();
  });
});

describe('useStudioTurn - the request could not be written down', () => {
  it('never reaches the provider when the REQUEST cannot be recorded', async () => {
    // The ordering exists so a lost answer leaves a trace. If the trace itself cannot be written,
    // calling the provider anyway produces the one state the design set out to prevent: a paid
    // generation with nothing on screen that it happened.
    addMessage.mockImplementation(async (_id: string, message: { role: string }) => {
      if (message.role === 'user') throw new Error('DB hiccup');
      return {};
    });
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    let outcome: unknown;
    await act(async () => { outcome = await result.current.run(baseRequest); });

    expect(execute).not.toHaveBeenCalled();
    // Nothing was sent, so the draft is worth keeping.
    expect(outcome).toBeNull();
    expect(result.current.error).toMatchObject({ code: 'unexpected' });
  });
});

describe('useStudioTurn - what the caller is told, and when', () => {
  it('reports the two phases in order, so a thread can refresh on the ANSWER only', async () => {
    // The surface draws the running turn itself and refreshes only on 'result'. Refreshing after
    // 'request' would replace that card with a turn that reads as abandoned - complete with its
    // "may still have been charged" warning, on a generation running normally.
    const onTurnRecorded = vi.fn();
    const { result } = renderHook(
      () => useStudioTurn({ conversationId: 'conv-1', onTurnRecorded }), { wrapper },
    );

    await act(async () => { await result.current.run(baseRequest); });

    expect(onTurnRecorded.mock.calls.map(([, phase]) => phase)).toEqual(['request', 'result']);
    expect(onTurnRecorded).toHaveBeenCalledWith('conv-1', 'request');
  });

  it('still reports the result phase for a REFUSAL, which is a turn like any other', async () => {
    execute.mockResolvedValue({ success: false, error: 'Not enough credits.' });
    const onTurnRecorded = vi.fn();
    const { result } = renderHook(
      () => useStudioTurn({ conversationId: 'conv-1', onTurnRecorded }), { wrapper },
    );

    await act(async () => { await result.current.run(baseRequest); });

    expect(onTurnRecorded.mock.calls.map(([, phase]) => phase)).toEqual(['request', 'result']);
  });
});

describe('useStudioTurn - a request the server refused', () => {
  it('keeps the draft and does NOT claim it may have been charged', async () => {
    // An expired session, a permission, a malformed call: the server ANSWERED, saying it did not
    // run. Folding this into connection_lost told the reader their generation might have been
    // billed and threw away their words and uploaded files, for a request that provably never ran.
    execute.mockRejectedValue(new ApiError('Unauthorized', 401));
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    let outcome: unknown;
    await act(async () => { outcome = await result.current.run(baseRequest); });

    expect(result.current.error).toMatchObject({ code: 'refused' });
    // null means "nothing was sent, keep the draft", which is exactly what happened.
    expect(outcome).toBeNull();
  });

  it('CLOSES the turn in the thread, so the refusal does not read as a possible charge', async () => {
    // The request was written down before the provider was called (that ordering is what protects a
    // lost turn). A refusal that returns without writing an answer therefore leaves that request
    // open FOR EVER - and an open request is exactly how this thread says "this may have been
    // charged", on the one outcome where nothing was. Every refusal appended another such card.
    execute.mockRejectedValue(new ApiError('Model unavailable in your region', 403));
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    await act(async () => { await result.current.run(baseRequest); });

    const written = addMessage.mock.calls.map(([, message]) => message);
    expect(written).toHaveLength(2);
    const answer = JSON.parse(written[1].content);
    expect(written[1].role).toBe('assistant');
    expect(answer).toMatchObject({ role: 'result', success: false });
    // The server's own sentence, kept: it is the only half that tells the reader what to do next.
    expect(answer.error).toContain('Model unavailable in your region');
  });

  it('still refuses - and keeps the draft - when the refusal cannot be written down', async () => {
    // Recording the refusal is best-effort. Nothing was charged either way, so a failed note must
    // not turn a diagnosed refusal into an `unexpected`, which is the code for "we do not know".
    execute.mockRejectedValue(new ApiError('Unauthorized', 401));
    addMessage.mockResolvedValueOnce({ id: 'm1' }).mockRejectedValueOnce(new Error('offline'));
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    let outcome: unknown;
    await act(async () => { outcome = await result.current.run(baseRequest); });

    expect(result.current.error).toMatchObject({ code: 'refused' });
    expect(outcome).toBeNull();
  });

  it('names a failed conversation creation as such, rather than as an unknown fault', async () => {
    // The distinction is not cosmetic: no conversation exists, so nothing was written and nothing
    // was sent. That is "safe to try again", where `unexpected` means "look at the thread first".
    createConversation.mockRejectedValue(new Error('network down'));
    const { result } = renderHook(() => useStudioTurn({ conversationId: null }), { wrapper });

    await act(async () => { await result.current.run(baseRequest); });

    expect(result.current.error).toMatchObject({ code: 'conversation_create_failed' });
    expect(execute).not.toHaveBeenCalled();
    expect(addMessage).not.toHaveBeenCalled();
  });

  it('still treats a timeout and a rate limit as possibly-charged', async () => {
    // 408 and 429 say "not now", which leaves the same doubt a dropped connection does.
    for (const status of [408, 429]) {
      execute.mockRejectedValueOnce(new ApiError('later', status));
      const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });
      let outcome: { status?: string } | null = null;
      await act(async () => { outcome = await result.current.run(baseRequest) as typeof outcome; });
      expect(outcome?.status).toBe('lost');
    }
  });

  it('treats a server fault as possibly-charged, because it may have dispatched', async () => {
    execute.mockRejectedValue(new ApiError('bad gateway', 502));
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    let outcome: { status?: string } | null = null;
    await act(async () => { outcome = await result.current.run(baseRequest) as typeof outcome; });

    expect(outcome?.status).toBe('lost');
  });
});

describe('useStudioTurn - who pays', () => {
  it('forwards the payer and the chosen key', async () => {
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    await act(async () => {
      await result.current.run({ ...baseRequest, credentialSource: 'user', credentialId: 42 });
    });

    expect(execute).toHaveBeenCalledWith(expect.objectContaining({
      credential_source: 'user',
      credential_id: 42,
    }));
  });

  it('records the payer on the turn, so a replay can restore it', async () => {
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    await act(async () => {
      await result.current.run({ ...baseRequest, credentialSource: 'user' });
    });

    const userCall = addMessage.mock.calls.find(([, m]) => m.role === 'user');
    expect(parseStudioEnvelope(userCall?.[1].content)).toMatchObject({ credentialSource: 'user' });
  });

  it('sends no payer at all when the caller names none', async () => {
    // Sending a default would decide the account a generation is charged to on the reader's behalf.
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    await act(async () => { await result.current.run(baseRequest); });

    const sent = execute.mock.calls[0][0] as Record<string, unknown>;
    expect(sent).not.toHaveProperty('credential_source');
    expect(sent).not.toHaveProperty('credential_id');
  });
});

describe('useStudioTurn - the answer could not be written down', () => {
  it('still hands back the asset of a generation that ran and was charged', async () => {
    // By this point the provider has run and the charge is committed. Letting a failed note fail the
    // turn would discard the result - asset id included - and show a generic error for a generation
    // the reader has paid for and can see in their files.
    addMessage.mockImplementation(async (_id: string, message: { role: string }) => {
      if (message.role === 'assistant') throw new Error('DB hiccup');
      return {};
    });
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'conv-1' }), { wrapper });

    let outcome: { status?: string; result?: { success?: boolean } } | null = null;
    await act(async () => {
      outcome = await result.current.run(baseRequest) as typeof outcome;
    });

    expect(outcome?.status).toBe('recorded');
    expect(outcome?.result?.success).toBe(true);
    expect(result.current.error).toBeNull();
  });
});

describe('studioTitleFromPrompt', () => {
  it('names the thread after the words that started it', () => {
    expect(studioTitleFromPrompt('a lighthouse at dusk', 'Untitled')).toBe('a lighthouse at dusk');
  });

  it('falls back when there are no words - a turn can be all files and parameters', () => {
    expect(studioTitleFromPrompt('   ', 'Untitled')).toBe('Untitled');
  });

  it('collapses whitespace so a pasted prompt does not wrap the sidebar', () => {
    expect(studioTitleFromPrompt('a  lighthouse\n\nat dusk', 'Untitled')).toBe('a lighthouse at dusk');
  });

  it('truncates a long prompt to something a sidebar row can hold', () => {
    const title = studioTitleFromPrompt('x'.repeat(200), 'Untitled');
    expect(title).toHaveLength(60);
    expect(title.endsWith('...')).toBe(true);
  });
});

describe('useStudioTurn - a write that creates a row is never retried', () => {
  it('turns retries OFF on the request it writes before the provider is called', async () => {
    // apiClient retries once by DEFAULT, and this write creates a message row. A 5xx that arrives
    // after the row was persisted would therefore write it twice, and a studio thread holding two
    // copies of one request envelope renders the second as a turn whose answer never came - the
    // "this may still have been charged" warning, over a generation nobody paid for.
    //
    // Asserted on the options rather than on a behaviour because the retry lives inside the
    // client: there is no way to observe it from here except by what is asked for.
    const { result } = renderHook(() => useStudioTurn({ conversationId: 'c1' }), { wrapper });

    await act(async () => { await result.current.run(baseRequest); });

    for (const call of addMessage.mock.calls) {
      expect(call[2]).toMatchObject({ retries: 0 });
    }
    expect(addMessage.mock.calls.length).toBeGreaterThan(0);
  });
});
