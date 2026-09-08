package com.apimarketplace.auth.client.entitlement;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.AuthClient.PlanFeatureResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PlanFeatureGate")
class PlanFeatureGateTest {

    private static final Map<String, String> GATE = Map.of(
            "api:youtube-data-api", "PRO",
            "tool:youtube-data-api-upload-video", "TEAM",
            "node:browser_agent", "PRO");

    private AuthClient authClient;
    private PlanFeatureGate gate;

    @BeforeEach
    void setUp() {
        authClient = mock(AuthClient.class);
        when(authClient.getPlanFeatures(isNull())).thenReturn(new PlanFeatureResponse(GATE, null));
        gate = new PlanFeatureGate(authClient, true);
    }

    private void userIsOn(String planCode) {
        when(authClient.getPlanFeatures(eq("user-1"))).thenReturn(new PlanFeatureResponse(GATE, planCode));
    }

    @Test
    @DisplayName("A FREE account is refused a PRO integration and told which plan includes it")
    void refusesBelowTheBar() {
        userIsOn("FREE");
        assertEquals("PRO", gate.upgradeRequiredFor("user-1", List.of("api:youtube-data-api")));
    }

    @Test
    @DisplayName("An account at or above the bar is allowed")
    void allowsAtOrAboveTheBar() {
        userIsOn("PRO");
        assertNull(gate.upgradeRequiredFor("user-1", List.of("api:youtube-data-api")));
        assertTrue(gate.allows("user-1", List.of("api:youtube-data-api")));
    }

    @Test
    @DisplayName("The FIRST key with a requirement decides, so a tool can be held back further than its API")
    void firstMatchingKeyWins() {
        userIsOn("PRO");
        // tool: is listed first by its caller, and it requires more than the API does.
        assertEquals("TEAM", gate.upgradeRequiredFor("user-1",
                List.of("tool:youtube-data-api-upload-video", "api:youtube-data-api")));
        // Reversing the order gives the API's answer - precedence is the CALLER's
        // ordering, which is what makes this a general mechanism.
        assertNull(gate.upgradeRequiredFor("user-1",
                List.of("api:youtube-data-api", "tool:youtube-data-api-upload-video")));
    }

    @Test
    @DisplayName("A key with no requirement is not gated at all")
    void ungatedKeyIsAllowed() {
        userIsOn("FREE");
        assertNull(gate.upgradeRequiredFor("user-1", List.of("api:slack", "node:merge")));
    }

    @Test
    @DisplayName("A disabled gate answers 'allowed' without asking auth-service anything")
    void disabledGateNeverCalls() {
        PlanFeatureGate disabled = new PlanFeatureGate(authClient, false);
        assertNull(disabled.upgradeRequiredFor("user-1", List.of("api:youtube-data-api")));
        verify(authClient, times(0)).getPlanFeatures(any());
    }

    @Test
    @DisplayName("A blank user id is an internal execution and is never gated")
    void blankUserIsNeverGated() {
        assertNull(gate.upgradeRequiredFor(null, List.of("api:youtube-data-api")));
        assertNull(gate.upgradeRequiredFor("   ", List.of("api:youtube-data-api")));
    }

    @Test
    @DisplayName("An unreadable plan fails OPEN: a lookup failure must not stop a paid customer's run")
    void unknownPlanFailsOpen() {
        when(authClient.getPlanFeatures(eq("user-1"))).thenReturn(null);
        assertNull(gate.upgradeRequiredFor("user-1", List.of("api:youtube-data-api")));
    }

    @Test
    @DisplayName("An unreadable gate map gates nothing")
    void unreadableMapGatesNothing() {
        AuthClient broken = mock(AuthClient.class);
        when(broken.getPlanFeatures(any())).thenReturn(null);
        PlanFeatureGate brokenGate = new PlanFeatureGate(broken, true);
        assertNull(brokenGate.requiredPlanFor(List.of("api:youtube-data-api")));
    }

    @Test
    @DisplayName("The gate map is fetched once and reused, so a whole epoch does not re-ask per node")
    void cachesTheMap() {
        userIsOn("FREE");
        gate.upgradeRequiredFor("user-1", List.of("api:youtube-data-api"));
        gate.upgradeRequiredFor("user-1", List.of("api:youtube-data-api"));
        gate.upgradeRequiredFor("user-1", List.of("node:browser_agent"));
        verify(authClient, times(1)).getPlanFeatures(isNull());
        verify(authClient, times(1)).getPlanFeatures(eq("user-1"));
    }

    @Test
    @DisplayName("invalidate() drops both caches so the next question is asked again")
    void invalidateRefetches() {
        userIsOn("FREE");
        gate.upgradeRequiredFor("user-1", List.of("api:youtube-data-api"));
        gate.invalidate();
        gate.upgradeRequiredFor("user-1", List.of("api:youtube-data-api"));
        verify(authClient, times(2)).getPlanFeatures(isNull());
        verify(authClient, times(2)).getPlanFeatures(eq("user-1"));
    }

    @Test
    @DisplayName("A map that has never loaded is retried on a backoff, not on every single node")
    void neverLoadedMapBacksOff() {
        AuthClient broken = mock(AuthClient.class);
        when(broken.getPlanFeatures(any())).thenReturn(null);
        PlanFeatureGate brokenGate = new PlanFeatureGate(broken, true);

        for (int i = 0; i < 10; i++) {
            brokenGate.requiredPlanFor(List.of("api:youtube-data-api"));
        }

        // One attempt for the whole burst: an auth-service outage must not be turned
        // into one request per gated node.
        verify(broken, times(1)).getPlanFeatures(isNull());
    }

    @Test
    @DisplayName("A stored FREE requirement is not a requirement")
    void storedFreeIsNoRequirement() {
        AuthClient client = mock(AuthClient.class);
        when(client.getPlanFeatures(any()))
                .thenReturn(new PlanFeatureResponse(Map.of("node:merge", "FREE"), "FREE"));
        PlanFeatureGate freeGate = new PlanFeatureGate(client, true);
        assertNull(freeGate.requiredPlanFor(List.of("node:merge")));
        assertTrue(freeGate.allows("user-1", List.of("node:merge")));
    }
}
