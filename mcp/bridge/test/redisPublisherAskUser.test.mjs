/**
 * Bridge parity for the ask_user question card: the Node RedisPublisher must emit an
 * `ask_user_required` event (frontend discriminant: `askUser`) when a tool result carries
 * `userQuestionRequested` metadata and the gate did NOT already paint the card, so the chat
 * shows the question on the bridge (claude-code/codex) path exactly like the Java loop does.
 *
 * Run with: node --test mcp/bridge/test/redisPublisherAskUser.test.mjs
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RedisPublisher } from '../redis-publisher.mjs';

function makeRedis() {
  const published = [];
  return {
    published,
    async publish(channel, msg) { published.push({ channel, msg }); return 1; },
    async rpush() { return 1; },
    async ltrim() { return 'OK'; },
    async set() { return 'OK'; },
    async expire() { return 1; },
    async exists() { return 0; },
    async get() { return null; },
    async del() { return 1; },
  };
}

function parseEvents(redis) {
  return redis.published.map((p) => {
    try { return JSON.parse(p.msg); } catch { return {}; }
  });
}

const QUESTION = {
  toolCallId: 'call-ask-1',
  questions: [{ header: 'Tone', question: 'Which tone?', options: [{ label: 'Friendly' }, { label: 'Formal' }], multiSelect: false }],
};

test('emits an askUser card when the question is still open and no card was painted yet', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  await publisher.publishToolResult('call-ask-1', 'ask_user', true, 100, '{"status":"pending_user"}', {
    userQuestionRequested: true,
    userQuestion: QUESTION,
  });

  const card = parseEvents(redis).find((e) => e.askUser);
  assert.ok(card, 'an askUser event must be published');
  assert.equal(card.askUser.toolCallId, 'call-ask-1');
  assert.equal(card.askUser.questions.length, 1);
  assert.equal(card.askUser.questions[0].header, 'Tone');
  assert.equal(card.askUser.blocking, undefined, 'a consumer-painted card is never blocking');
});

test('does NOT emit a second card when the gate already painted it (approvalCardEmitted)', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  await publisher.publishToolResult('call-ask-2', 'ask_user', true, 25_000, '{"status":"pending_user"}', {
    userQuestionRequested: true,
    userQuestion: { ...QUESTION, toolCallId: 'call-ask-2' },
    approvalCardEmitted: true,
  });

  assert.equal(parseEvents(redis).find((e) => e.askUser), undefined,
    'the park painted the card before waiting; the result must not paint another');
});

test('does NOT emit a card for an answered or dismissed question (no userQuestionRequested)', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  await publisher.publishToolResult('call-ask-3', 'ask_user', true, 3_000,
    '{"status":"answered","answers":[{"header":"Tone","selected":["Friendly"]}]}', { approvalCardEmitted: true });
  await publisher.publishToolResult('call-ask-4', 'ask_user', true, 3_000,
    '{"status":"dismissed"}', { approvalCardEmitted: true, approvalGateDecision: 'denied' });

  assert.equal(parseEvents(redis).find((e) => e.askUser), undefined);
});

test('ignores a malformed payload with no toolCallId', async () => {
  const redis = makeRedis();
  const publisher = new RedisPublisher(redis, 'stream-1', 'conv-1', redis);

  await publisher.publishToolResult('call-ask-5', 'ask_user', true, 10, '{}', {
    userQuestionRequested: true,
    userQuestion: { questions: [] },
  });

  assert.equal(parseEvents(redis).find((e) => e.askUser), undefined);
});
