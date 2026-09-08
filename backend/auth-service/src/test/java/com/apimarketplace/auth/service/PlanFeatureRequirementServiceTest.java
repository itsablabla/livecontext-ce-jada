package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PlanFeatureRequirement;
import com.apimarketplace.auth.repository.PlanFeatureRequirementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PlanFeatureRequirementService")
class PlanFeatureRequirementServiceTest {

    private PlanFeatureRequirementRepository repository;
    private PlanFeatureRequirementService service;
    private final Map<String, PlanFeatureRequirement> store = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        store.clear();
        repository = mock(PlanFeatureRequirementRepository.class);
        when(repository.findAll()).thenAnswer(inv -> new ArrayList<>(store.values()));
        when(repository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        when(repository.existsById(anyString()))
                .thenAnswer(inv -> store.containsKey(inv.getArgument(0, String.class)));
        when(repository.save(any(PlanFeatureRequirement.class))).thenAnswer(inv -> {
            PlanFeatureRequirement row = inv.getArgument(0);
            store.put(row.getFeatureKey(), row);
            return row;
        });
        // The repository is a mock, so deleteById does nothing unless taught to: without
        // this, "clearing removes the row" would pass against a store that never shrank.
        org.mockito.Mockito.doAnswer(inv -> {
            store.remove(inv.getArgument(0, String.class));
            return null;
        }).when(repository).deleteById(anyString());
        service = new PlanFeatureRequirementService(repository);
    }

    @Test
    @DisplayName("Stores a requirement and serves it in the map")
    void storesARequirement() {
        PlanFeatureRequirement saved = service.set("api:youtube-data-api", "PRO", "YouTube", "admin-1");

        assertEquals("PRO", saved.getMinPlan());
        assertEquals("YouTube", saved.getLabel());
        assertEquals("admin-1", saved.getUpdatedBy());
        assertEquals(Map.of("api:youtube-data-api", "PRO"), service.requirements());
    }

    @Test
    @DisplayName("Setting FREE DELETES the row: 'available to everyone' has exactly one spelling")
    void freeClearsTheRequirement() {
        service.set("node:media", "PRO", "Media", "admin-1");
        assertTrue(service.requirements().containsKey("node:media"));

        PlanFeatureRequirement cleared = service.set("node:media", "FREE", "Media", "admin-1");

        assertNull(cleared);
        verify(repository).deleteById("node:media");
        assertFalse(service.requirements().containsKey("node:media"));
    }

    @Test
    @DisplayName("Clearing a requirement that was never set touches nothing")
    void clearingAnAbsentRowIsANoOp() {
        assertNull(service.set("node:media", "FREE", null, "admin-1"));
        verify(repository, never()).deleteById(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("A blank plan clears, the same as FREE")
    void blankPlanClears() {
        service.set("node:media", "PRO", "Media", "admin-1");
        assertNull(service.set("node:media", "   ", null, "admin-1"));
        assertFalse(service.requirements().containsKey("node:media"));
    }

    @Test
    @DisplayName("The key is normalised, so one feature cannot end up gated under two spellings")
    void normalisesTheKey() {
        service.set("  API:YouTube-Data-Api  ", "pro", "YouTube", "admin-1");
        assertEquals(Map.of("api:youtube-data-api", "PRO"), service.requirements());
    }

    @Test
    @DisplayName("The feature: namespace is accepted, for a capability that is not a node or an endpoint")
    void acceptsTheFeatureNamespace() {
        // Added 2026-09-03 for vector search. It is not a node (gating node:find_rows would hold
        // back ordinary row lookups) and not a catalog endpoint, so it needed a namespace of its
        // own rather than a key bent to fit one of the others.
        service.set("feature:vector_search", "PRO", "Vector columns", "admin-1");

        verify(repository).save(any());
    }

    @Test
    @DisplayName("A key outside the four namespaces is refused rather than stored where nothing reads it")
    void rejectsAnUnknownNamespace() {
        assertThrows(IllegalArgumentException.class,
                () -> service.set("widget:media", "PRO", null, "admin-1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.set("media", "PRO", null, "admin-1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.set("node:", "PRO", null, "admin-1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.set(null, "PRO", null, "admin-1"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("An unknown plan code is refused: a stored typo would silently gate nothing")
    void rejectsAnUnknownPlan() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.set("node:media", "GOLD", null, "admin-1"));
        assertTrue(e.getMessage().contains("GOLD"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Updating keeps the existing label when the caller sends none")
    void keepsTheLabelWhenNoneIsSent() {
        service.set("api:youtube-data-api", "PRO", "YouTube", "admin-1");
        PlanFeatureRequirement updated = service.set("api:youtube-data-api", "TEAM", null, "admin-2");

        assertEquals("TEAM", updated.getMinPlan());
        assertEquals("YouTube", updated.getLabel());
        assertEquals("admin-2", updated.getUpdatedBy());
    }

    @Test
    @DisplayName("The map is cached, and an edit invalidates it immediately")
    void cachesAndInvalidatesOnWrite() {
        service.requirements();
        service.requirements();
        verify(repository, times(1)).findAll();

        service.set("node:media", "PRO", "Media", "admin-1");
        assertEquals(Map.of("node:media", "PRO"), service.requirements());
        // One more read after the write - the cached copy was dropped, not served stale.
        verify(repository, times(2)).findAll();
    }

    @Test
    @DisplayName("The admin list is ordered cheapest plan first, then by key")
    void listsCheapestFirst() {
        service.set("node:zebra", "TEAM", null, "admin-1");
        service.set("node:alpha", "TEAM", null, "admin-1");
        service.set("api:beta", "STARTER", null, "admin-1");

        List<String> keys = service.list().stream().map(PlanFeatureRequirement::getFeatureKey).toList();
        assertEquals(List.of("api:beta", "node:alpha", "node:zebra"), keys);
    }
}
