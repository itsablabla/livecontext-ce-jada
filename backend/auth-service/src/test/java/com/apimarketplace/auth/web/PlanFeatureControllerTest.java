package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.PlanFeatureRequirement;
import com.apimarketplace.auth.service.PlanFeatureRequirementService;
import com.apimarketplace.auth.service.PlanLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PlanFeatureController")
class PlanFeatureControllerTest {

    private PlanFeatureRequirementService requirementService;
    private PlanLimitService planLimitService;
    private PlanFeatureController controller;

    @BeforeEach
    void setUp() {
        requirementService = mock(PlanFeatureRequirementService.class);
        planLimitService = mock(PlanLimitService.class);
        controller = new PlanFeatureController(requirementService, planLimitService);
    }

    @Test
    @DisplayName("The read is open to any signed-in user and answers the gate AND their plan in one call")
    void readIsOpenAndSelfContained() {
        when(requirementService.requirements()).thenReturn(Map.of("api:youtube-data-api", "PRO"));
        when(planLimitService.getPlanCode("user-1")).thenReturn("FREE");

        ResponseEntity<Map<String, Object>> response = controller.getForCaller("user-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(Map.of("api:youtube-data-api", "PRO"), body.get("requirements"));
        assertEquals("FREE", body.get("planCode"));
        // The builder needs the option list to render the admin control without a
        // second round-trip, and it must be the SAME list the writes validate against.
        assertEquals(List.of("FREE", "STARTER", "PRO", "TEAM", "ENTERPRISE"), body.get("selectablePlans"));
    }

    @Test
    @DisplayName("A non-admin cannot LIST the requirements")
    void listRefusesNonAdmin() {
        ResponseEntity<?> response = controller.listForAdmin("USER");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(requirementService, never()).list();
    }

    @Test
    @DisplayName("An admin gets every row with its label and who last changed it")
    void listReturnsRowsForAdmin() {
        when(requirementService.list()).thenReturn(List.of(
                new PlanFeatureRequirement("api:youtube-data-api", "PRO", "YouTube", "admin-1")));

        ResponseEntity<?> response = controller.listForAdmin("USER,ADMIN");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("requirements");
        assertEquals(1, rows.size());
        assertEquals("api:youtube-data-api", rows.get(0).get("featureKey"));
        assertEquals("PRO", rows.get(0).get("minPlan"));
        assertEquals("YouTube", rows.get(0).get("label"));
        assertEquals("admin-1", rows.get(0).get("updatedBy"));
    }

    @Test
    @DisplayName("A non-admin cannot WRITE a requirement - the gate would otherwise be self-service")
    void writeRefusesNonAdmin() {
        ResponseEntity<?> response = controller.upsert("USER", "user-1",
                Map.of("featureKey", "api:youtube-data-api", "minPlan", "PRO"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(requirementService, never()).set(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("An admin write stores the requirement and echoes what was stored")
    void writeStoresForAdmin() {
        when(requirementService.set("api:youtube-data-api", "PRO", "YouTube", "admin-1"))
                .thenReturn(new PlanFeatureRequirement("api:youtube-data-api", "PRO", "YouTube", "admin-1"));

        ResponseEntity<?> response = controller.upsert("ADMIN", "admin-1",
                Map.of("featureKey", "api:youtube-data-api", "minPlan", "PRO", "label", "YouTube"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(Boolean.TRUE, body.get("success"));
        assertEquals("PRO", body.get("minPlan"));
    }

    @Test
    @DisplayName("Clearing a requirement answers minPlan=null, so the caller drops the key rather than storing FREE")
    void clearingAnswersNull() {
        when(requirementService.set(anyString(), anyString(), any(), any())).thenReturn(null);

        ResponseEntity<?> response = controller.upsert("ADMIN", "admin-1",
                Map.of("featureKey", "node:media", "minPlan", "FREE"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertTrue(body.containsKey("minPlan"));
        assertNull(body.get("minPlan"));
    }

    @Test
    @DisplayName("A missing featureKey is a 400, not a stored row keyed on nothing")
    void missingKeyIsABadRequest() {
        ResponseEntity<?> response = controller.upsert("ADMIN", "admin-1", Map.of("minPlan", "PRO"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(requirementService, never()).set(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("A rejected key or plan comes back as a 400 carrying the service's own explanation")
    void rejectedInputIsABadRequest() {
        when(requirementService.set(anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unknown plan code 'GOLD'."));

        ResponseEntity<?> response = controller.upsert("ADMIN", "admin-1",
                Map.of("featureKey", "node:media", "minPlan", "GOLD"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertTrue(String.valueOf(body.get("error")).contains("GOLD"));
    }
}
