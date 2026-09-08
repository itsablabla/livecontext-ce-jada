import { describe, it, expect } from 'vitest';
import { normalizeNodeId } from '../StepByStepContext';

/**
 * The prefix a canvas node id is turned into before it is sent as a step key.
 *
 * <p>Step-by-step execution addresses a node by `<prefix>:<normalized label>`,
 * and this function is the only place the prefix is derived from the canvas id.
 * Every way it can be wrong is silent: an unknown id falls to the `mcp:`
 * default, the engine finds no node under that key, and the step reads as one
 * that never ran rather than as an error.
 *
 * <p>Generate is the case that made this worth pinning. Its canvas ids are
 * minted `generate-<timestamp>-<random>`, so it matches none of the agent
 * prefixes by accident, and it joined the AI family without changing the shape
 * of its id.
 */
describe('normalizeNodeId', () => {
  it('gives a generate node the AI prefix, from the minted id shape it actually has', () => {
    expect(normalizeNodeId('generate-1717171717-a1b2c')).toBe('agent:generate_1717171717_a1b2c');
  });

  it('gives the bare palette id the same prefix, since a node can carry either', () => {
    expect(normalizeNodeId('generate')).toBe('agent:generate');
  });

  it('still gives an LLM agent the AI prefix', () => {
    expect(normalizeNodeId('ai-agent-42')).toBe('agent:ai_agent');
  });

  it('leaves an id that already carries a prefix alone', () => {
    expect(normalizeNodeId('agent:make_clip')).toBe('agent:make_clip');
    expect(normalizeNodeId('core:pause')).toBe('core:pause');
  });

  it('keeps a control node on the control prefix', () => {
    expect(normalizeNodeId('download_file-9')).toBe('core:download_file');
  });

  it('does not mistake an MCP tool that merely contains a core word for a control node', () => {
    // The reason `media` is a prefix test and not a substring one: a catalog
    // tool called create_media_container is an mcp: step.
    expect(normalizeNodeId('create_media_container-123')).toBe('mcp:create_media_container');
  });

  it('falls back to the tool prefix for an id it does not recognise', () => {
    // Stated on purpose: this is the branch a missing case lands in, and it is
    // why a missing case produces a key that addresses nothing instead of an
    // error anyone can see.
    expect(normalizeNodeId('gmail-send-77')).toBe('mcp:gmail_send');
  });
});
