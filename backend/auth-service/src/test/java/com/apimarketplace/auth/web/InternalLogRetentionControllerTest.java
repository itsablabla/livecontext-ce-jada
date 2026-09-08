package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.LogRetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("InternalLogRetentionController")
class InternalLogRetentionControllerTest {

    private LogRetentionService service;
    private InternalLogRetentionController controller;

    @BeforeEach
    void setUp() {
        service = mock(LogRetentionService.class);
        controller = new InternalLogRetentionController(service);
    }

    @Test
    @DisplayName("Returns the finite windows the service resolved, unchanged")
    void returnsResolvedWindows() {
        when(service.retentionDaysFor(List.of("org-1", "org-2"))).thenReturn(Map.of("org-1", 90));

        ResponseEntity<Map<String, Integer>> response = controller.retentionDays(List.of("org-1", "org-2"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("org-1", 90), response.getBody());
    }

    /**
     * Truncating would be silently destructive in the caller's favour: the
     * workspaces dropped off the end are indistinguishable from workspaces that
     * retain, so the purge job would skip them believing it had asked. Refusing
     * makes it page.
     */
    @Test
    @DisplayName("Refuses an oversized batch instead of truncating it, and never asks the service")
    void refusesOversizedBatchRatherThanTruncating() {
        List<String> tooMany = IntStream
                .rangeClosed(0, InternalLogRetentionController.MAX_IDS_PER_REQUEST)
                .mapToObj(i -> "t" + i)
                .toList();

        ResponseEntity<Map<String, Integer>> response = controller.retentionDays(tooMany);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(service, never()).retentionDaysFor(anyCollection());
    }

    @Test
    @DisplayName("A batch exactly at the cap is accepted")
    void batchAtTheCapIsAccepted() {
        List<String> atCap = IntStream
                .range(0, InternalLogRetentionController.MAX_IDS_PER_REQUEST)
                .mapToObj(i -> "t" + i)
                .toList();
        when(service.retentionDaysFor(atCap)).thenReturn(Map.of());

        assertEquals(HttpStatus.OK, controller.retentionDays(atCap).getStatusCode());
    }

    @Test
    @DisplayName("A null body is an empty question, not an error")
    void nullBodyIsEmpty() {
        when(service.retentionDaysFor(List.of())).thenReturn(Map.of());

        ResponseEntity<Map<String, Integer>> response = controller.retentionDays(null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isEmpty());
    }
}
