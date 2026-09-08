import { describe, expect, it } from 'vitest';
import { buildStudioTurns } from '../studioThread';
import { buildStudioRequest, buildStudioResult } from '../studioMessage';

/**
 * Reading a conversation as studio turns.
 *
 * <p>The cases that carry weight are the malformed ones: an answer that never arrived, a thread
 * holding messages from another feature, an answer with no request. Each has a right rendering, and
 * a loop that assumes messages arrive in pairs gets all three wrong.
 */

function request(prompt: string, model = 'flux-1') {
  return buildStudioRequest({ prompt, model, kind: 'image' });
}

function result(success = true) {
  return buildStudioResult(
    success
      ? { success: true, data: { model: 'flux-1', kind: 'image', provider: 'flux', file: { id: 'f1' } } }
      : { success: false, error: 'refused' },
    { model: 'flux-1', kind: 'image' },
  );
}

describe('buildStudioTurns', () => {
  it('pairs a request with the answer that follows it', () => {
    const turns = buildStudioTurns([
      { id: 'm1', content: request('a lighthouse') },
      { id: 'm2', content: result() },
    ]);
    expect(turns).toHaveLength(1);
    expect(turns[0]).toMatchObject({
      id: 'm1',
      request: { prompt: 'a lighthouse' },
      result: { success: true },
    });
  });

  it('keeps turns oldest first, the order a thread is read in', () => {
    const turns = buildStudioTurns([
      { id: 'm1', content: request('first') },
      { id: 'm2', content: result() },
      { id: 'm3', content: request('second') },
      { id: 'm4', content: result() },
    ]);
    expect(turns.map((turn) => turn.request.prompt)).toEqual(['first', 'second']);
  });

  it('keeps a request whose answer never arrived, rather than dropping it', () => {
    // This is the turn the reader most needs to see: the submission left, and the server may have
    // finished it and charged for it.
    const turns = buildStudioTurns([{ id: 'm1', content: request('a lighthouse') }]);
    expect(turns).toHaveLength(1);
    expect(turns[0].result).toBeUndefined();
  });

  it('closes an unanswered turn when the next request opens', () => {
    const turns = buildStudioTurns([
      { id: 'm1', content: request('lost one') },
      { id: 'm2', content: request('next one') },
      { id: 'm3', content: result() },
    ]);
    expect(turns).toHaveLength(2);
    expect(turns[0]).toMatchObject({ request: { prompt: 'lost one' } });
    expect(turns[0].result).toBeUndefined();
    expect(turns[1]).toMatchObject({ request: { prompt: 'next one' }, result: { success: true } });
  });

  it('keeps a refusal as a turn - it is history, not noise', () => {
    const turns = buildStudioTurns([
      { id: 'm1', content: request('a lighthouse') },
      { id: 'm2', content: result(false) },
    ]);
    expect(turns[0].result).toMatchObject({ success: false, error: 'refused' });
  });

  it('skips messages that are not studio turns', () => {
    // A studio conversation can still hold a stray message. There is nothing a studio card could
    // show for one, and it is skipped from this VIEW only.
    const turns = buildStudioTurns([
      { id: 'm0', content: 'just some prose' },
      { id: 'm1', content: request('a lighthouse') },
      { id: 'm2', content: '{"type":"__WORKFLOW__","nodes":[],"edges":[]}' },
      { id: 'm3', content: result() },
    ]);
    expect(turns).toHaveLength(1);
    expect(turns[0]).toMatchObject({ id: 'm1', result: { success: true } });
  });

  it('skips an answer with no request before it', () => {
    // A card with no prompt says nothing about what produced the asset, and inventing a request to
    // hang it on would claim a generation nobody asked for.
    const turns = buildStudioTurns([{ id: 'm1', content: result() }]);
    expect(turns).toEqual([]);
  });

  it('returns nothing for an empty or entirely non-studio conversation', () => {
    expect(buildStudioTurns([])).toEqual([]);
    expect(buildStudioTurns([{ id: 'm1', content: 'hello' }])).toEqual([]);
  });

  it('falls back to a positional id when a message has none', () => {
    // React needs a stable key even for a message the server has not assigned an id to yet.
    const turns = buildStudioTurns([{ content: request('a lighthouse') }]);
    expect(turns[0].id).toBe('studio-turn-0');
  });
});

