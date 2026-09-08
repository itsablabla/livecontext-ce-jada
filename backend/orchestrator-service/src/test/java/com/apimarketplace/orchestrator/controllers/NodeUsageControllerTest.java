package com.apimarketplace.orchestrator.controllers;

import com.apimarketplace.orchestrator.services.usage.NodeUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the node-usage ranking endpoint (V461).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NodeUsageController.getRanking")
class NodeUsageControllerTest {

    @Mock private NodeUsageRepository repository;

    private NodeUsageController controller;

    @BeforeEach
    void setUp() {
        controller = new NodeUsageController(repository);
        when(repository.findRanking(anyInt())).thenReturn(List.of("agent", "mcp"));
    }

    private int capturedLimit() {
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(repository).findRanking(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("returns the ranked keys in the order the ledger gives them")
    void returnsTheRankingInOrder() {
        ResponseEntity<List<String>> response = controller.getRanking(50);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of("agent", "mcp"), response.getBody());
    }

    @Test
    @DisplayName("clamps a limit that is absurd in either direction")
    void clampsTheLimit() {
        controller.getRanking(100_000);
        assertEquals(200, capturedLimit(), "an unbounded limit would let one caller read the whole table");
    }

    @Test
    @DisplayName("clamps a zero or negative limit to one row rather than passing it to SQL")
    void clampsANonPositiveLimit() {
        controller.getRanking(0);
        assertEquals(1, capturedLimit());
    }

    @Test
    @DisplayName("answers with an empty ranking when the ledger cannot be read")
    void degradesToAnEmptyRanking() {
        // The caller renders a list it can already order itself. A 500 here would break
        // a palette section over a counter that is, by design, only decoration.
        when(repository.findRanking(anyInt())).thenThrow(new RuntimeException("relation does not exist"));

        ResponseEntity<List<String>> response = controller.getRanking(50);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(), response.getBody());
    }
}
