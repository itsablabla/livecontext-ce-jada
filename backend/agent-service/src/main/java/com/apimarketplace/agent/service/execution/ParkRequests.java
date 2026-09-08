package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.tools.authz.ToolAuthorizationScope;

import java.util.Map;

/**
 * Builds a {@link ToolApprovalGate.ParkRequest} from the credentials a tool call carries.
 *
 * <p>Every ceiling the gate applies is read off the credentials map: the caller's tool
 * deadline, the run's inactivity window, the execution reserve and whether a CLI is sitting
 * on the call. Two callers park (the authorization/credential gates in
 * {@link RemoteToolExecutionService}, and the {@code ask_user} tool), and they must read
 * those keys identically, so the reading lives here once.
 */
public final class ParkRequests {

    public static final String KEY_TOOL_DEADLINE = "__toolDeadlineEpochMs__";
    public static final String KEY_EXECUTION_RESERVE = "__toolExecutionReserveMs__";
    public static final String KEY_INACTIVITY_SECONDS = "__inactivityTimeoutSeconds__";
    /**
     * How long a call may be held on the CLI at the other end of a bridge session, in ms.
     * Set by {@code CliAgentService} from what the bridge declared for the CLI it spawned;
     * absent on older bridges and on the direct route. Read within
     * [{@link #MIN_CLI_MAX_PARK_MS}, {@link #MAX_CLI_MAX_PARK_MS}].
     */
    public static final String KEY_CLI_MAX_PARK_MS = "__cliMaxParkMs__";
    /**
     * A declared wait shorter than this is read as this. It still means "the CLI stops
     * waiting almost at once", so it must stay a SHORT ceiling rather than fall back to the
     * 25 s floor, which would hold the call longer than the CLI said it waits.
     */
    public static final long MIN_CLI_MAX_PARK_MS = 1_000L;
    /**
     * The most a declared wait is read as. The session endpoint is reachable from a browser,
     * so the value is trusted the way its sibling window is: within a documented range, never
     * as an open-ended timing. This is NOT the control on how long a park lasts: the gate's
     * own budget (240 s by default, {@code agent.tool.approval-gate.timeout-ms}) and half
     * the inactivity window bind first on every route. The bridge caps what it sends at the
     * same value, so the number a session declares is the number the gate reads.
     */
    public static final long MAX_CLI_MAX_PARK_MS = 600_000L;
    public static final String KEY_CONVERSATION_ID = "conversationId";
    public static final String KEY_STREAM_ID = "__streamId__";
    public static final String KEY_STREAM_ID_PLAIN = "streamId";

    private ParkRequests() {
    }

    /**
     * @param credentials        the tool call's credentials map
     * @param gateKey            identifies the parked call
     * @param callStartedEpochMs when the tool call began
     */
    public static ToolApprovalGate.ParkRequest of(Map<String, Object> credentials, String gateKey,
                                                  long callStartedEpochMs) {
        return new ToolApprovalGate.ParkRequest(
                conversationIdOf(credentials), gateKey, streamIdOf(credentials),
                deadlineOf(credentials), inactivityWindowMsOf(credentials), callStartedEpochMs,
                executionReserveMsOf(credentials), ToolAuthorizationScope.isCliBridgeSession(credentials),
                cliMaxParkMsOf(credentials));
    }

    /**
     * The hold the bridge granted on the CLI at the other end of this session, in ms, read
     * within [{@link #MIN_CLI_MAX_PARK_MS}, {@link #MAX_CLI_MAX_PARK_MS}], or {@code 0} when
     * nobody said (absent, blank, malformed, zero or negative: the gate then falls back to
     * its shortest-CLI floor).
     */
    public static long cliMaxParkMsOf(Map<String, Object> credentials) {
        long declared = longCredential(credentials, KEY_CLI_MAX_PARK_MS, true);
        if (declared <= 0L) {
            return 0L;
        }
        return Math.max(MIN_CLI_MAX_PARK_MS, Math.min(declared, MAX_CLI_MAX_PARK_MS));
    }

    public static String conversationIdOf(Map<String, Object> credentials) {
        return stringCredential(credentials, KEY_CONVERSATION_ID);
    }

    public static String streamIdOf(Map<String, Object> credentials) {
        String streamId = stringCredential(credentials, KEY_STREAM_ID);
        return streamId != null ? streamId : stringCredential(credentials, KEY_STREAM_ID_PLAIN);
    }

    /**
     * Absolute ceiling the caller's own tool timeout imposes on this call, or {@code 0} when
     * the caller sets none (the CLI-bridge path, whose HTTP read timeout is far longer than
     * any park). Injected by {@code AgentLoopExecutor} so the gate cannot park past the
     * moment its own result would be discarded as a timeout.
     */
    public static long deadlineOf(Map<String, Object> credentials) {
        return longCredential(credentials, KEY_TOOL_DEADLINE, false);
    }

    /**
     * How long the tool needs to RUN once a park releases it, held back from the caller's
     * deadline. Written by {@code AgentLoopExecutor} (the tool's own timeout); absent on
     * routes with no deadline at all, where there is nothing to reserve from.
     */
    public static long executionReserveMsOf(Map<String, Object> credentials) {
        return longCredential(credentials, KEY_EXECUTION_RESERVE, true);
    }

    /**
     * The run's inactivity watchdog window in ms, or {@code 0} when no watchdog applies.
     *
     * <p>A run that goes silent for this long is killed, and a parked call is silent, so
     * the park has to fit inside it WITH room for the tool that follows. The value is
     * per-agent ({@code __inactivityTimeoutSeconds__}, contract: 0 disables, 10 to 7200 sets
     * a window). It says nothing about the ROUTE: a direct chat sets it too, and a bridge run
     * omits it whenever its watchdog is disabled.
     */
    public static long inactivityWindowMsOf(Map<String, Object> credentials) {
        if (credentials == null) {
            return 0L;
        }
        Object raw = credentials.get(KEY_INACTIVITY_SECONDS);
        long seconds;
        if (raw instanceof Number num) {
            seconds = num.longValue();
        } else if (raw instanceof String str && !str.isBlank()) {
            try {
                seconds = Long.parseLong(str.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        } else {
            return 0L;
        }
        // 0 = the watchdog is off, so nothing constrains the park. Out-of-contract values
        // are ignored the same way the bridge ignores them, rather than inventing a window.
        return seconds >= 10 && seconds <= 7200 ? seconds * 1000L : 0L;
    }

    private static long longCredential(Map<String, Object> credentials, String key, boolean clampToZero) {
        if (credentials == null) {
            return 0L;
        }
        Object value = credentials.get(key);
        long parsed;
        if (value instanceof Number num) {
            parsed = num.longValue();
        } else if (value instanceof String str && !str.isBlank()) {
            try {
                parsed = Long.parseLong(str.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        } else {
            return 0L;
        }
        return clampToZero ? Math.max(0L, parsed) : parsed;
    }

    private static String stringCredential(Map<String, Object> credentials, String key) {
        if (credentials == null) {
            return null;
        }
        Object value = credentials.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
