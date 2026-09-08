import { describe, it, expect } from 'vitest';
import { buildToolsConfigPayload, getMemoryAccessMode } from '../toolsConfigAccess';

/**
 * The read/write axis for long-term memory.
 *
 * It matters more than the other families' because a memory WRITE is not scoped
 * to the agent that made it: what one agent saves is injected into every agent in
 * the workspace. `'read'` is how an operator says "this agent may consult the
 * workspace's facts and may not change them".
 *
 * The payload half is the trap. `buildToolsConfigPayload` rebuilds the whole
 * `tools_config` from the form's state, so a mode it does not emit is DROPPED,
 * and a recall-only agent silently regains full write access the next time anyone
 * opens it and saves.
 */
describe('toolsConfigAccess - long-term memory read/write axis', () => {
  const base = {
    mode: 'all' as const,
    workflows: [],
    tables: [],
    interfaces: [],
    agents: [],
    applications: [],
  };

  it('reads back a stored recall-only mode, so opening an agent shows what it is set to', () => {
    expect(getMemoryAccessMode({ memoryAccessMode: 'read' })).toBe('read');
  });

  it('treats absent, null and anything unrecognized as full write, matching the backend default', () => {
    // ToolAccessControl allows the action when no mode is set, so the form has to
    // show R/W in that case or it would report a restriction that does not exist.
    expect(getMemoryAccessMode({})).toBe('write');
    expect(getMemoryAccessMode(null)).toBe('write');
    expect(getMemoryAccessMode(undefined)).toBe('write');
    expect(getMemoryAccessMode({ memoryAccessMode: 'readonly' })).toBe('write');
    expect(getMemoryAccessMode({ memoryAccessMode: 'write' })).toBe('write');
  });

  it('echoes the mode back into the payload, or an edit would quietly restore write access', () => {
    const payload = buildToolsConfigPayload({ ...base, memoryAccessMode: 'read' });
    expect(payload.memoryAccessMode).toBe('read');
  });

  it('echoes an explicit write too, rather than dropping it and relying on a merge', () => {
    // The same reason the other modes are always emitted: an omitted field lets a
    // backend merge keep the PREVIOUS value, so turning an agent back to R/W by
    // omission would leave it read-only.
    const payload = buildToolsConfigPayload({ ...base, memoryAccessMode: 'write' });
    expect(payload.memoryAccessMode).toBe('write');
  });

  it('does not invent a mode for a form that never touched it', () => {
    expect(buildToolsConfigPayload({ ...base }).memoryAccessMode).toBeUndefined();
  });

  it('is independent of the other families, which have their own axes', () => {
    const payload = buildToolsConfigPayload({
      ...base,
      memoryAccessMode: 'read',
      skillAccessMode: 'write',
      fileAccessMode: 'write',
    });
    expect(payload.memoryAccessMode).toBe('read');
    expect(payload.skillAccessMode).toBe('write');
    expect(payload.fileAccessMode).toBe('write');
  });
});
