/**
 * The hold the backend may put on one tool call of an adapter's CLI, derived from the
 * per-call timeout that adapter configured (or knows) for it.
 *
 * The backend approval gate parks a call (an authorization card, an ask_user question)
 * no longer than the CLI at the other end keeps waiting for it. The bridge is the one
 * place that knows that wait, because it writes each CLI's MCP configuration; without
 * this the gate falls back to a floor sized for the shortest CLI (25 s), and a question
 * card with several questions expires while the person is still reading it.
 *
 * Half the CLI's per-call timeout: the CLI's timer covers the wait AND the tool run that
 * follows a released approval, so the wait takes the smaller share. Capped at
 * MAX_TOOL_HOLD_SECONDS, the most the backend reads (ParkRequests.MAX_CLI_MAX_PARK_MS), so
 * the number the session declares is the number the gate applies; claude-code's 30-minute
 * idle window lands on the cap. The gate's own budget (240 s by default) and half the run's
 * inactivity window bind before either.
 *
 * @param {object|null} adapter - a CLI adapter, optionally exposing getToolCallTimeoutSeconds()
 * @returns {string} whole seconds for the subprocess env, or '' when the adapter declares
 *   no timeout (the gate then applies its own floor)
 */
export const MAX_TOOL_HOLD_SECONDS = 600;

export function maxToolHoldSecondsFor(adapter) {
  const secs = typeof adapter?.getToolCallTimeoutSeconds === 'function' ? adapter.getToolCallTimeoutSeconds() : null;
  if (!Number.isFinite(secs) || secs <= 0) return '';
  return String(Math.min(MAX_TOOL_HOLD_SECONDS, Math.floor(secs / 2)));
}
