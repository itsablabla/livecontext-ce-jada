package com.apimarketplace.catalog.service;

import com.apimarketplace.auth.client.entitlement.PlanFeatureGate;
import com.apimarketplace.catalog.service.exception.PlanUpgradeRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CatalogPlanAccess")
class CatalogPlanAccessTest {

    private PlanFeatureGate planFeatureGate;
    private CatalogPlanAccess access;

    @BeforeEach
    void setUp() {
        planFeatureGate = mock(PlanFeatureGate.class);
        when(planFeatureGate.isEnabled()).thenReturn(true);
        access = new CatalogPlanAccess();
        access.setPlanFeatureGate(planFeatureGate);
    }

    @Test
    @DisplayName("The endpoint's key is asked BEFORE its API's, so one endpoint can be held back further")
    void toolKeyTakesPrecedenceOverApiKey() {
        assertEquals(List.of("tool:youtube-upload-video", "api:youtube-data-api"),
                CatalogPlanAccess.candidateKeys("youtube-data-api", "youtube-upload-video"));
    }

    @Test
    @DisplayName("Keys are lower-cased so a differently-cased slug still matches its gate")
    void normalisesSlugCase() {
        assertEquals(List.of("tool:youtube-upload-video", "api:youtube-data-api"),
                CatalogPlanAccess.candidateKeys(" YouTube-Data-Api ", " YouTube-Upload-Video "));
    }

    @Test
    @DisplayName("A missing slug contributes no key rather than a key like 'api:null'")
    void skipsMissingSlugs() {
        assertEquals(List.of("api:slack"), CatalogPlanAccess.candidateKeys("slack", null));
        assertEquals(List.of("tool:slack-post"), CatalogPlanAccess.candidateKeys(null, "slack-post"));
        assertTrue(CatalogPlanAccess.candidateKeys(null, null).isEmpty());
        assertTrue(CatalogPlanAccess.candidateKeys("  ", "  ").isEmpty());
    }

    @Test
    @DisplayName("Below the bar: refuses with the plan and a machine token, before anything is billed")
    void refusesBelowTheBar() {
        when(planFeatureGate.upgradeRequiredFor(eq("user-1"), any())).thenReturn("PRO");

        PlanUpgradeRequiredException e = assertThrows(PlanUpgradeRequiredException.class,
                () -> access.assertAllowed("user-1", "youtube-data-api", "youtube-upload-video", "Upload video"));

        assertEquals("PRO", e.getRequiredPlan());
        assertTrue(e.getMessage().contains("PRO"), e.getMessage());
        assertTrue(e.getMessage().contains("Upload video"), e.getMessage());
        assertEquals("PLAN_UPGRADE_REQUIRED", PlanUpgradeRequiredException.ERROR_CODE);
    }

    @Test
    @DisplayName("The refusal still reads as a sentence when the tool has no name to quote")
    void messageWithoutALabel() {
        when(planFeatureGate.upgradeRequiredFor(anyString(), any())).thenReturn("TEAM");

        PlanUpgradeRequiredException e = assertThrows(PlanUpgradeRequiredException.class,
                () -> access.assertAllowed("user-1", "youtube-data-api", "upload", null));

        assertTrue(e.getMessage().startsWith("This integration"), e.getMessage());
    }

    @Test
    @DisplayName("At or above the bar: the call proceeds")
    void allowsAtOrAboveTheBar() {
        when(planFeatureGate.upgradeRequiredFor(anyString(), any())).thenReturn(null);
        assertDoesNotThrow(() -> access.assertAllowed("user-1", "youtube-data-api", "upload", "Upload"));
    }

    @Test
    @DisplayName("A disabled gate never asks and never refuses")
    void disabledGateAllows() {
        when(planFeatureGate.isEnabled()).thenReturn(false);
        assertNull(access.upgradeRequiredFor("user-1", "youtube-data-api", "upload"));
        verify(planFeatureGate, never()).upgradeRequiredFor(anyString(), any());
    }

    @Test
    @DisplayName("No gate bean at all gates nothing")
    void missingGateAllows() {
        CatalogPlanAccess bare = new CatalogPlanAccess();
        assertNull(bare.upgradeRequiredFor("user-1", "youtube-data-api", "upload"));
    }

    @Test
    @DisplayName("A tool with neither slug resolved is never gated - there is nothing to key on")
    void unresolvedToolIsNotGated() {
        assertNull(access.upgradeRequiredFor("user-1", null, null));
        verify(planFeatureGate, never()).upgradeRequiredFor(anyString(), any());
    }
}
