package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.tools.authz.ToolAuthorizationScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The park ceilings are read off the credentials map by every park site through this one
 * reader. The bridge-declared CLI wait is the newest of them: it lets a card on a
 * claude-code / codex / gemini run wait for the person instead of expiring at the
 * shortest-CLI floor, so a misread here silently brings that floor back.
 */
class ParkRequestsTest {

    @Test
    @DisplayName("reads the bridge-declared CLI wait in ms, from a number or a string")
    void readsTheDeclaredCliWait() {
        Map<String, Object> asNumber = Map.of(ParkRequests.KEY_CLI_MAX_PARK_MS, 300_000L);
        Map<String, Object> asString = Map.of(ParkRequests.KEY_CLI_MAX_PARK_MS, "300000");

        assertThat(ParkRequests.cliMaxParkMsOf(asNumber)).isEqualTo(300_000L);
        assertThat(ParkRequests.cliMaxParkMsOf(asString)).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("an absent, blank, malformed or negative wait is 0 - the gate then keeps its floor")
    void absentOrBadWaitIsZero() {
        Map<String, Object> negative = Map.of(ParkRequests.KEY_CLI_MAX_PARK_MS, -5L);
        Map<String, Object> malformed = Map.of(ParkRequests.KEY_CLI_MAX_PARK_MS, "soon");
        Map<String, Object> blank = Map.of(ParkRequests.KEY_CLI_MAX_PARK_MS, " ");

        assertThat(ParkRequests.cliMaxParkMsOf(null)).isZero();
        assertThat(ParkRequests.cliMaxParkMsOf(Map.of())).isZero();
        assertThat(ParkRequests.cliMaxParkMsOf(negative)).as("never negative: it would be a past deadline").isZero();
        assertThat(ParkRequests.cliMaxParkMsOf(malformed)).isZero();
        assertThat(ParkRequests.cliMaxParkMsOf(blank)).isZero();
    }

    @Test
    @DisplayName("the built ParkRequest carries the declared wait next to the bridge marker")
    void parkRequestCarriesTheDeclaredWait() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put(ParkRequests.KEY_CONVERSATION_ID, "conv-1");
        credentials.put(ParkRequests.KEY_STREAM_ID, "stream-1");
        credentials.put(ToolAuthorizationScope.KEY_CLI_BRIDGE_SESSION, true);
        credentials.put(ParkRequests.KEY_CLI_MAX_PARK_MS, 300_000L);

        ToolApprovalGate.ParkRequest park = ParkRequests.of(credentials, "call-1:ask", 1_000L);

        assertThat(park.cliBridgeSession()).isTrue();
        assertThat(park.cliMaxParkMs()).isEqualTo(300_000L);
        assertThat(park.conversationId()).isEqualTo("conv-1");
        assertThat(park.callStartedEpochMs()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("a declared wait is read within a documented range: under a second reads as a second, above ten minutes is capped")
    void declaredWaitIsClamped() {
        Map<String, Object> tooShort = Map.of(ParkRequests.KEY_CLI_MAX_PARK_MS, 5L);
        Map<String, Object> huge = Map.of(ParkRequests.KEY_CLI_MAX_PARK_MS, 3_600_000L);
        Map<String, Object> atMin = Map.of(ParkRequests.KEY_CLI_MAX_PARK_MS, ParkRequests.MIN_CLI_MAX_PARK_MS);
        Map<String, Object> zero = Map.of(ParkRequests.KEY_CLI_MAX_PARK_MS, 0L);

        // The session endpoint is reachable from a browser: a client-supplied timing is
        // trusted the way its sibling window is, inside a range, never open-ended. A tiny
        // declared wait stays a SHORT ceiling: dropping it to "not declared" would hold the
        // call for the 25 s floor, longer than the CLI said it waits.
        assertThat(ParkRequests.cliMaxParkMsOf(tooShort)).isEqualTo(ParkRequests.MIN_CLI_MAX_PARK_MS);
        assertThat(ParkRequests.cliMaxParkMsOf(huge)).isEqualTo(ParkRequests.MAX_CLI_MAX_PARK_MS);
        assertThat(ParkRequests.cliMaxParkMsOf(atMin)).isEqualTo(ParkRequests.MIN_CLI_MAX_PARK_MS);
        assertThat(ParkRequests.cliMaxParkMsOf(zero)).as("zero is 'not declared', never a ceiling").isZero();
    }

    @Test
    @DisplayName("the clamp bounds are the numbers the bridge is written against: 1 s and 600 s")
    void clampBoundsMatchTheBridge() {
        // mcp/bridge/lib/toolHold.mjs caps what it sends at MAX_TOOL_HOLD_SECONDS = 600 and
        // pins that number in its own test. Pinning the literal on THIS side too is what
        // keeps the two suites from both staying green while the numbers drift apart.
        assertThat(ParkRequests.MAX_CLI_MAX_PARK_MS).isEqualTo(600_000L);
        assertThat(ParkRequests.MIN_CLI_MAX_PARK_MS).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("a ParkRequest built without a declared wait reads as 0, so older call sites keep the floor")
    void legacyConstructorDeclaresNoWait() {
        ToolApprovalGate.ParkRequest park = new ToolApprovalGate.ParkRequest(
                "conv-1", "call-1", "stream-1", 0, 0, 0, 0, true);

        assertThat(park.cliMaxParkMs()).isZero();
    }
}
