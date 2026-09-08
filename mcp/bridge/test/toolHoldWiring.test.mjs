/**
 * The backend approval gate holds a tool call (an authorization card, an ask_user question)
 * no longer than the CLI at the other end keeps waiting for it. The bridge is the one place
 * that knows that wait, because it writes each CLI's MCP configuration, so it has to
 * (1) write a per-call timeout where the CLI has one, (2) derive the hold from it, and
 * (3) forward the hold into the session it opens. Before this the gate fell back to a
 * 25 s floor on every CLI, and a question card with several questions expired while the
 * person was still reading it. Parse-time forwarding contracts, like
 * inactivityAndTimeoutWiring.test.mjs.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';
import { ClaudeAdapter } from '../adapters/claude-adapter.mjs';
import { CodexAdapter } from '../adapters/codex-adapter.mjs';
import { GeminiAdapter } from '../adapters/gemini-adapter.mjs';
import { MistralAdapter } from '../adapters/mistral-adapter.mjs';
import { maxToolHoldSecondsFor, MAX_TOOL_HOLD_SECONDS } from '../lib/toolHold.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const serverSource = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');
const cliSource = readFileSync(resolve(__dirname, '..', '..', 'agent-cli-server.mjs'), 'utf8');

test('codex: the generated config.toml carries the per-call timeout the adapter declares', () => {
  const adapter = new CodexAdapter();
  const tmp = mkdtempSync(join(tmpdir(), 'codexcfg-'));
  try {
    const p = adapter.writeMcpConfig(tmp, { serverName: 'agent-cli', command: 'node', args: ['x.mjs'], env: {} });
    const toml = readFileSync(p, 'utf8');
    assert.match(toml, /^tool_timeout_sec = 700$/m,
      'codex stops waiting after tool_timeout_sec and never cancels; the bridge must WRITE it rather than guess a version default');
    assert.equal(adapter.getToolCallTimeoutSeconds(), 700, 'the declared wait must be the written one');
    // Above the largest synchronous platform tool (web_search agent_browse 640 s, gateway 660 s):
    // a written timeout applies to EVERY call on this server, so it must not cut a real tool.
    assert.ok(adapter.getToolCallTimeoutSeconds() > 660, 'must exceed the gateway allowance for agent tool routes');
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
});

test('gemini: the generated settings.json carries the request timeout the adapter declares (ms)', () => {
  const adapter = new GeminiAdapter();
  const tmp = mkdtempSync(join(tmpdir(), 'geminicfg-'));
  try {
    const p = adapter.writeMcpConfig(tmp, { serverName: 'agent-cli', command: 'node', args: ['x.mjs'], env: {} });
    const server = JSON.parse(readFileSync(p, 'utf8')).mcpServers['agent-cli'];
    assert.equal(server.timeout, 700_000, 'gemini reads mcpServers.<name>.timeout in milliseconds');
    assert.equal(adapter.getToolCallTimeoutSeconds(), 700, 'the declared wait must match the written one, in seconds');
    assert.ok(adapter.getToolCallTimeoutSeconds() > 660, 'must exceed the gateway allowance for agent tool routes');
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
});

test('claude-code: declares its stdio idle window, writes it into the child env, and no per-server timeout below it', () => {
  const adapter = new ClaudeAdapter();
  assert.equal(adapter.getToolCallTimeoutSeconds(), 1800);
  // The declared window is the one the child runs with, whatever the bridge host exports.
  const saved = process.env.CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT;
  process.env.CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT = '5000';
  try {
    assert.equal(adapter.buildChildEnv('/tmp', undefined).CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT, '1800000',
      'the pinned idle window must win over an ambient shorter one, else the declaration lies');
  } finally {
    if (saved === undefined) delete process.env.CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT; else process.env.CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT = saved;
  }
  const tmp = mkdtempSync(join(tmpdir(), 'claudecfg-'));
  try {
    const p = adapter.writeMcpConfig(tmp, { serverName: 'agent-cli', command: 'node', args: ['x.mjs'], env: {} });
    const server = JSON.parse(readFileSync(p, 'utf8')).mcpServers['agent-cli'];
    assert.equal(server.timeout, undefined, 'a written timeout would only LOWER the default wall clock');
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
});

test('the hold is HALF the declared wait, capped at what the backend reads, and empty when the adapter declares none', () => {
  // The CLI timer covers the wait AND the tool run after a released approval; the wait
  // takes the smaller share (the rationale the backend's own 25 s floor was sized on).
  assert.equal(maxToolHoldSecondsFor(new CodexAdapter()), '350');
  assert.equal(maxToolHoldSecondsFor(new GeminiAdapter()), '350');
  // claude-code's 30-minute idle window halves to 900, above what the backend reads: the
  // bridge sends the cap itself, so the declared number is the applied number.
  assert.equal(MAX_TOOL_HOLD_SECONDS, 600, 'must match ParkRequests.MAX_CLI_MAX_PARK_MS (600 s)');
  assert.equal(maxToolHoldSecondsFor(new ClaudeAdapter()), '600');
  assert.equal(maxToolHoldSecondsFor({ getToolCallTimeoutSeconds: () => 1199 }), '599');
  assert.equal(maxToolHoldSecondsFor(new MistralAdapter()), '', 'vibe publishes no per-call timeout: the gate keeps its floor');
  assert.equal(maxToolHoldSecondsFor(null), '');
  assert.equal(maxToolHoldSecondsFor({ getToolCallTimeoutSeconds: () => 0 }), '');
  assert.equal(maxToolHoldSecondsFor({ getToolCallTimeoutSeconds: () => NaN }), '');
});

test('server.mjs hands the hold to the MCP subprocess as AGENT_CLI_MAX_TOOL_HOLD_SECONDS', () => {
  assert.match(serverSource, /AGENT_CLI_MAX_TOOL_HOLD_SECONDS:\s*maxToolHoldSecondsFor\(adapter\)/,
    'the env must be derived from the adapter that spawns the CLI, not a constant');
});

test('agent-cli-server.mjs forwards it as maxToolHoldSeconds on the session start body, absent when unset', () => {
  assert.match(cliSource, /process\.env\.AGENT_CLI_MAX_TOOL_HOLD_SECONDS/, 'must read the env');
  assert.match(cliSource, /body\.maxToolHoldSeconds\s*=/, 'must forward it on the CliSessionStartRequest body');
  const guard = cliSource.slice(cliSource.indexOf('const maxHoldSeconds'), cliSource.indexOf('body.maxToolHoldSeconds'));
  assert.match(guard, /!== ''/, "an empty env must NOT become maxToolHoldSeconds: 0 or NaN - absent means 'not known'");
  assert.match(guard, /> 0/, 'only a positive hold is forwarded');
});
