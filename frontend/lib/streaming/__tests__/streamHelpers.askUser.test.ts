/**
 * The question card is typed off the payload shape (`askUser`), like the two other cards.
 * A raw WS event and a mapped SSE event must both carry the payload through untouched.
 */
import { describe, expect, it } from 'vitest';
import { detectStreamEventType, mapV2EventToV1 } from '../streamHelpers';

const askUser = {
  toolCallId: 'call-7',
  questions: [{ header: 'Tone', question: 'Which?', options: [{ label: 'A' }, { label: 'B' }], multiSelect: false }],
  blocking: true,
  gateKey: 'call-7:ask',
};

describe('streamHelpers - ask_user_required', () => {
  it('detects an askUser payload as ask_user_required, ahead of the service-approval shape', () => {
    expect(detectStreamEventType({ streamId: 's1', askUser, timestamp: 'now' })).toBe('ask_user_required');
    // A question card never carries services/reason, but the order of checks is what keeps a
    // future field collision from being mistyped: askUser is decided before the pair check.
    expect(detectStreamEventType({ askUser, services: [], reason: 'x' })).toBe('ask_user_required');
  });

  it('maps the payload through with blocking and gateKey intact', () => {
    const mapped = mapV2EventToV1({ streamId: 's1', askUser, timestamp: 'now' }, 'ask_user_required', 's1');

    expect(mapped.type).toBe('ask_user_required');
    expect(mapped.askUser?.toolCallId).toBe('call-7');
    expect(mapped.askUser?.blocking).toBe(true);
    expect(mapped.askUser?.gateKey).toBe('call-7:ask');
    expect(mapped.askUser?.questions[0].header).toBe('Tone');
    expect(mapped.streamId).toBe('s1');
  });

  it('still types the authorization and service cards as before', () => {
    expect(detectStreamEventType({ toolAuthorization: { rule: 'x:y' } })).toBe('tool_authorization_required');
    expect(detectStreamEventType({ services: [], reason: 'r' })).toBe('service_approval_required');
  });
});
